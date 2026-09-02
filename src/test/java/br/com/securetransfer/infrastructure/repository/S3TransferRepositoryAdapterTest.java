package br.com.securetransfer.infrastructure.repository;

import br.com.securetransfer.configuration.StorageProperties;
import br.com.securetransfer.domain.exception.StorageException;
import br.com.securetransfer.domain.exception.TransferNotFoundException;
import br.com.securetransfer.domain.model.TransferChunk;
import br.com.securetransfer.domain.model.TransferId;
import br.com.securetransfer.domain.model.TransferManifest;
import br.com.securetransfer.domain.model.TransferStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3TransferRepositoryAdapterTest {
    private static final String CONTRACT_TEST_ENABLED = "SECURE_TRANSFER_S3_CONTRACT_TEST";
    private static final String CONTRACT_TEST_BUCKET = "SECURE_TRANSFER_S3_TEST_BUCKET";
    private static final String CONTRACT_TEST_ENDPOINT = "SECURE_TRANSFER_S3_TEST_ENDPOINT";
    private static final String CONTRACT_TEST_REGION = "SECURE_TRANSFER_S3_TEST_REGION";
    private static final String CONTRACT_TEST_METADATA_PREFIX = "SECURE_TRANSFER_S3_TEST_METADATA_PREFIX";
    private static final String CONTRACT_TEST_BLOB_PREFIX = "SECURE_TRANSFER_S3_TEST_BLOB_PREFIX";
    private static final String CONTRACT_TEST_PATH_STYLE = "SECURE_TRANSFER_S3_TEST_PATH_STYLE_ACCESS";
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void publishesChunksInBlobPrefixAndManifestInMetadataPrefix(@TempDir Path temp) throws Exception {
        S3Client client = mock(S3Client.class);
        S3TransferRepositoryAdapter adapter = adapter(client);
        TransferId transferId = TransferId.newId();
        byte[] content = "encrypted-chunk".getBytes();
        Path source = temp.resolve("chunk.bin");
        Files.write(source, content);
        TransferChunk chunk = chunk(content, "part-00001.bin");

        adapter.publishChunk(transferId, chunk, source);
        TransferManifest manifest = manifest(transferId, content, chunk);
        adapter.publishManifest(manifest);

        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client, org.mockito.Mockito.times(2)).putObject(requests.capture(),
                any(software.amazon.awssdk.core.sync.RequestBody.class));
        assertThat(requests.getAllValues()).extracting(PutObjectRequest::key)
                .containsExactly(
                        "blobs/" + transferId + "/chunks/part-00001.bin",
                        "metadata/" + transferId + "/manifest.json");
        assertThat(requests.getAllValues()).extracting(PutObjectRequest::contentLength)
                .containsExactly((long) content.length, requests.getAllValues().get(1).contentLength());
    }

    @Test
    void publishesLargeChunksAsStreamingMultipartUpload(@TempDir Path temp) throws Exception {
        S3Client client = mock(S3Client.class);
        StorageProperties properties = properties();
        properties.getS3().setMultipartThreshold(DataSize.ofMegabytes(5));
        properties.getS3().setMultipartPartSize(DataSize.ofMegabytes(5));
        S3TransferRepositoryAdapter adapter = new S3TransferRepositoryAdapter(properties, mapper, client);
        TransferId transferId = TransferId.newId();
        long size = 11L * 1024 * 1024;
        Path source = temp.resolve("large-chunk.bin");
        Files.write(source, new byte[(int) size]);
        TransferChunk chunk = new TransferChunk(1, size, size,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bm9uY2U=", source.getFileName().toString());

        when(client.createMultipartUpload(any(software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest.class)))
                .thenReturn(software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse.builder()
                        .uploadId("upload-1").build());
        List<Long> uploadedSizes = new ArrayList<>();
        when(client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            UploadPartRequest request = invocation.getArgument(0);
            RequestBody body = invocation.getArgument(1);
            uploadedSizes.add(body.contentLength());
            try (InputStream part = body.contentStreamProvider().newStream()) {
                part.transferTo(OutputStream.nullOutputStream());
            }
            return software.amazon.awssdk.services.s3.model.UploadPartResponse.builder()
                    .eTag("etag-" + request.partNumber()).build();
        });

        adapter.publishChunk(transferId, chunk, source);

        assertThat(uploadedSizes).containsExactly(5L * 1024 * 1024, 5L * 1024 * 1024, 1L * 1024 * 1024);
        verify(client, org.mockito.Mockito.times(3)).uploadPart(any(UploadPartRequest.class),
                any(RequestBody.class));
        verify(client).completeMultipartUpload(any(
                software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest.class));
        verify(client, org.mockito.Mockito.never()).putObject(any(PutObjectRequest.class),
                any(RequestBody.class));
        verify(client, org.mockito.Mockito.never()).abortMultipartUpload(
                any(software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest.class));
    }

    @Test
    void retainsMultipartUploadWhenAPartFails(@TempDir Path temp) throws Exception {
        S3Client client = mock(S3Client.class);
        StorageProperties properties = properties();
        properties.getS3().setMultipartThreshold(DataSize.ofMegabytes(5));
        properties.getS3().setMultipartPartSize(DataSize.ofMegabytes(5));
        S3TransferRepositoryAdapter adapter = new S3TransferRepositoryAdapter(properties, mapper, client);
        TransferId transferId = TransferId.newId();
        long size = 11L * 1024 * 1024;
        Path source = temp.resolve("large-chunk.bin");
        Files.write(source, new byte[(int) size]);
        TransferChunk chunk = new TransferChunk(1, size, size,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bm9uY2U=", source.getFileName().toString());

        when(client.createMultipartUpload(any(software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest.class)))
                .thenReturn(software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse.builder()
                        .uploadId("upload-1").build());
        AtomicInteger attempts = new AtomicInteger();
        when(client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() == 2) {
                throw software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(503).build();
            }
            RequestBody body = invocation.getArgument(1);
            try (InputStream part = body.contentStreamProvider().newStream()) {
                part.transferTo(OutputStream.nullOutputStream());
            }
            return software.amazon.awssdk.services.s3.model.UploadPartResponse.builder().eTag("etag-1").build();
        });

        assertThatThrownBy(() -> adapter.publishChunk(transferId, chunk, source))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("multipart");

        verify(client, org.mockito.Mockito.never()).abortMultipartUpload(
                any(software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest.class));
        verify(client, org.mockito.Mockito.never()).completeMultipartUpload(any(
                software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest.class));
    }

    @Test
    void resumesMultipartUploadUsingOnlyMissingParts(@TempDir Path temp) throws Exception {
        S3Client client = mock(S3Client.class);
        StorageProperties properties = properties();
        properties.getS3().setMultipartThreshold(DataSize.ofMegabytes(5));
        properties.getS3().setMultipartPartSize(DataSize.ofMegabytes(5));
        S3TransferRepositoryAdapter adapter = new S3TransferRepositoryAdapter(properties, mapper, client);
        TransferId transferId = TransferId.newId();
        long partSize = 5L * 1024 * 1024;
        long size = 16L * 1024 * 1024;
        Path source = temp.resolve("large-chunk.bin");
        Files.write(source, new byte[(int) size]);
        TransferChunk chunk = new TransferChunk(1, size, size,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bm9uY2U=", source.getFileName().toString());
        String key = "blobs/" + transferId + "/chunks/large-chunk.bin";

        when(client.listMultipartUploads(any(
                software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest.class)))
                .thenReturn(ListMultipartUploadsResponse.builder()
                        .uploads(MultipartUpload.builder().key(key).uploadId("upload-resume").build())
                        .build());
        when(client.listParts(any(software.amazon.awssdk.services.s3.model.ListPartsRequest.class)))
                .thenReturn(ListPartsResponse.builder()
                        .parts(
                                Part.builder().partNumber(1).size(partSize).eTag("etag-1").build(),
                                Part.builder().partNumber(2).size(partSize).eTag("").build(),
                                Part.builder().partNumber(4).size(size - (3 * partSize)).eTag("etag-4").build())
                        .build());
        when(client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class))).thenAnswer(invocation -> {
            UploadPartRequest request = invocation.getArgument(0);
            assertThat(request.partNumber()).isIn(2, 3);
            return software.amazon.awssdk.services.s3.model.UploadPartResponse.builder()
                    .eTag("etag-" + request.partNumber()).build();
        });

        adapter.publishChunk(transferId, chunk, source);

        verify(client, org.mockito.Mockito.never()).createMultipartUpload(any(
                software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest.class));
        ArgumentCaptor<UploadPartRequest> uploads = ArgumentCaptor.forClass(UploadPartRequest.class);
        verify(client, org.mockito.Mockito.times(2)).uploadPart(uploads.capture(), any(RequestBody.class));
        assertThat(uploads.getAllValues()).extracting(UploadPartRequest::partNumber)
                .containsExactly(2, 3);
        ArgumentCaptor<software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest> complete =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest.class);
        verify(client).completeMultipartUpload(complete.capture());
        assertThat(complete.getValue().uploadId()).isEqualTo("upload-resume");
        assertThat(complete.getValue().multipartUpload().parts())
                .extracting(software.amazon.awssdk.services.s3.model.CompletedPart::partNumber)
                .containsExactly(1, 2, 3, 4);
        assertThat(complete.getValue().multipartUpload().parts())
                .extracting(software.amazon.awssdk.services.s3.model.CompletedPart::eTag)
                .containsExactly("etag-1", "etag-2", "etag-3", "etag-4");
    }

    @Test
    void abortsIncompatibleMultipartSessionAndStartsOver(@TempDir Path temp) throws Exception {
        S3Client client = mock(S3Client.class);
        StorageProperties properties = properties();
        properties.getS3().setMultipartThreshold(DataSize.ofMegabytes(5));
        properties.getS3().setMultipartPartSize(DataSize.ofMegabytes(5));
        S3TransferRepositoryAdapter adapter = new S3TransferRepositoryAdapter(properties, mapper, client);
        TransferId transferId = TransferId.newId();
        long size = 11L * 1024 * 1024;
        Path source = temp.resolve("large-chunk.bin");
        Files.write(source, new byte[(int) size]);
        TransferChunk chunk = new TransferChunk(1, size, size,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bm9uY2U=", source.getFileName().toString());
        String key = "blobs/" + transferId + "/chunks/large-chunk.bin";

        when(client.listMultipartUploads(any(
                software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest.class)))
                .thenReturn(ListMultipartUploadsResponse.builder()
                        .uploads(MultipartUpload.builder().key(key).uploadId("incompatible").build())
                        .build());
        when(client.listParts(any(software.amazon.awssdk.services.s3.model.ListPartsRequest.class)))
                .thenReturn(ListPartsResponse.builder()
                        .parts(Part.builder().partNumber(4).size(1L).eTag("etag-extra").build())
                        .build());
        when(client.createMultipartUpload(any(
                software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest.class)))
                .thenReturn(software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse.builder()
                        .uploadId("upload-new").build());
        when(client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    UploadPartRequest request = invocation.getArgument(0);
                    return software.amazon.awssdk.services.s3.model.UploadPartResponse.builder()
                            .eTag("etag-" + request.partNumber()).build();
                });

        adapter.publishChunk(transferId, chunk, source);

        ArgumentCaptor<software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest> abort =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest.class);
        verify(client).abortMultipartUpload(abort.capture());
        assertThat(abort.getValue().uploadId()).isEqualTo("incompatible");
        verify(client).createMultipartUpload(any(
                software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest.class));
        ArgumentCaptor<UploadPartRequest> uploads = ArgumentCaptor.forClass(UploadPartRequest.class);
        verify(client, org.mockito.Mockito.times(3)).uploadPart(uploads.capture(), any(RequestBody.class));
        assertThat(uploads.getAllValues()).extracting(UploadPartRequest::uploadId)
                .containsOnly("upload-new");
    }

    @Test
    void removesStagedChunksWhenManifestIsAbsent() throws Exception {
        S3Client client = mock(S3Client.class);
        S3TransferRepositoryAdapter adapter = adapter(client);
        TransferId transferId = TransferId.newId();
        String prefix = "blobs/" + transferId + "/chunks/";

        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(
                software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(404).build());
        when(client.listObjectsV2(any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(
                                S3Object.builder().key(prefix + "part-00001.bin").build(),
                                S3Object.builder().key(prefix + "part-00002.bin").build())
                        .build());

        adapter.cleanupUnpublishedTransfer(transferId);

        ArgumentCaptor<software.amazon.awssdk.services.s3.model.DeleteObjectRequest> deletes =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class);
        verify(client, org.mockito.Mockito.times(2)).deleteObject(deletes.capture());
        assertThat(deletes.getAllValues()).extracting(
                        software.amazon.awssdk.services.s3.model.DeleteObjectRequest::key)
                .containsExactly(prefix + "part-00001.bin", prefix + "part-00002.bin");
        ArgumentCaptor<software.amazon.awssdk.services.s3.model.ListObjectsV2Request> lists =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class);
        verify(client).listObjectsV2(lists.capture());
        assertThat(lists.getValue().prefix()).isEqualTo(prefix);
    }

    @Test
    void neverRemovesChunksWhenManifestIsPresent() throws Exception {
        S3Client client = mock(S3Client.class);
        S3TransferRepositoryAdapter adapter = adapter(client);
        TransferId transferId = TransferId.newId();
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());

        adapter.cleanupUnpublishedTransfer(transferId);

        verify(client).headObject(any(HeadObjectRequest.class));
        verify(client, org.mockito.Mockito.never()).listObjectsV2(
                any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class));
        verify(client, org.mockito.Mockito.never()).deleteObject(
                any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }

    @Test
    void removesOnlyOldValidChunkObjects() throws Exception {
        S3Client client = mock(S3Client.class);
        StorageProperties properties = properties();
        properties.getS3().setOrphanRetention(Duration.ofHours(1));
        S3TransferRepositoryAdapter adapter = new S3TransferRepositoryAdapter(properties, mapper, client);
        TransferId oldTransfer = TransferId.newId();
        TransferId recentTransfer = TransferId.newId();
        Instant now = Instant.parse("2026-09-02T12:00:00Z");

        when(client.listObjectsV2(any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(
                                S3Object.builder()
                                        .key("blobs/" + oldTransfer + "/chunks/part-00001.bin")
                                        .lastModified(now.minus(Duration.ofHours(2)))
                                        .build(),
                                S3Object.builder()
                                        .key("blobs/" + recentTransfer + "/chunks/part-00001.bin")
                                        .lastModified(now.minus(Duration.ofMinutes(30)))
                                        .build(),
                                S3Object.builder()
                                        .key("blobs/not-a-transfer/chunks/part-00001.bin")
                                        .lastModified(now.minus(Duration.ofHours(2)))
                                        .build(),
                                S3Object.builder()
                                        .key("blobs/" + oldTransfer + "/chunks/nested/part.bin")
                                        .lastModified(now.minus(Duration.ofHours(2)))
                                        .build())
                        .build());
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(
                software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(404).build());

        S3TransferRepositoryAdapter.OrphanedBlobCleanupReport report =
                adapter.cleanupOrphanedBlobs(now);

        assertThat(report.candidates()).isEqualTo(1);
        assertThat(report.removed()).isEqualTo(1);
        assertThat(report.preserved()).isZero();
        assertThat(report.failures()).isZero();
        ArgumentCaptor<software.amazon.awssdk.services.s3.model.DeleteObjectRequest> delete =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class);
        verify(client).deleteObject(delete.capture());
        assertThat(delete.getValue().key())
                .isEqualTo("blobs/" + oldTransfer + "/chunks/part-00001.bin");
    }

    @Test
    void preservesOldChunksWhenManifestExists() throws Exception {
        S3Client client = mock(S3Client.class);
        StorageProperties properties = properties();
        properties.getS3().setOrphanRetention(Duration.ofHours(1));
        S3TransferRepositoryAdapter adapter = new S3TransferRepositoryAdapter(properties, mapper, client);
        TransferId transferId = TransferId.newId();
        Instant now = Instant.parse("2026-09-02T12:00:00Z");

        when(client.listObjectsV2(any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(
                                S3Object.builder()
                                        .key("blobs/" + transferId + "/chunks/part-00001.bin")
                                        .lastModified(now.minus(Duration.ofHours(2)))
                                        .build(),
                                S3Object.builder()
                                        .key("blobs/" + transferId + "/chunks/part-00002.bin")
                                        .lastModified(now.minus(Duration.ofHours(2)))
                                        .build())
                        .build());
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());

        S3TransferRepositoryAdapter.OrphanedBlobCleanupReport report =
                adapter.cleanupOrphanedBlobs(now);

        assertThat(report.candidates()).isEqualTo(2);
        assertThat(report.removed()).isZero();
        assertThat(report.preserved()).isEqualTo(2);
        assertThat(report.failures()).isZero();
        verify(client, org.mockito.Mockito.never()).deleteObject(
                any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }

    @Test
    void rechecksManifestBeforeEachDeleteAndStopsWhenTransferBecomesAvailable() throws Exception {
        S3Client client = mock(S3Client.class);
        StorageProperties properties = properties();
        properties.getS3().setOrphanRetention(Duration.ofHours(1));
        S3TransferRepositoryAdapter adapter = new S3TransferRepositoryAdapter(properties, mapper, client);
        TransferId transferId = TransferId.newId();
        Instant now = Instant.parse("2026-09-02T12:00:00Z");

        when(client.listObjectsV2(any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(
                                S3Object.builder()
                                        .key("blobs/" + transferId + "/chunks/part-00001.bin")
                                        .lastModified(now.minus(Duration.ofHours(2)))
                                        .build(),
                                S3Object.builder()
                                        .key("blobs/" + transferId + "/chunks/part-00002.bin")
                                        .lastModified(now.minus(Duration.ofHours(2)))
                                        .build())
                        .build());
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(404).build())
                .thenReturn(HeadObjectResponse.builder().build());

        S3TransferRepositoryAdapter.OrphanedBlobCleanupReport report =
                adapter.cleanupOrphanedBlobs(now);

        assertThat(report.candidates()).isEqualTo(2);
        assertThat(report.removed()).isEqualTo(1);
        assertThat(report.preserved()).isEqualTo(1);
        assertThat(report.failures()).isZero();
        verify(client).deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
        verify(client, org.mockito.Mockito.times(2)).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void recordsDeleteFailuresAndContinuesWithOtherOrphans() throws Exception {
        S3Client client = mock(S3Client.class);
        StorageProperties properties = properties();
        properties.getS3().setOrphanRetention(Duration.ofHours(1));
        S3TransferRepositoryAdapter adapter = new S3TransferRepositoryAdapter(properties, mapper, client);
        TransferId firstTransfer = TransferId.newId();
        TransferId secondTransfer = TransferId.newId();
        Instant now = Instant.parse("2026-09-02T12:00:00Z");

        when(client.listObjectsV2(any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(
                                S3Object.builder()
                                        .key("blobs/" + firstTransfer + "/chunks/part-00001.bin")
                                        .lastModified(now.minus(Duration.ofHours(2)))
                                        .build(),
                                S3Object.builder()
                                        .key("blobs/" + secondTransfer + "/chunks/part-00001.bin")
                                        .lastModified(now.minus(Duration.ofHours(2)))
                                        .build())
                        .build());
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(
                software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(404).build());
        when(client.deleteObject(any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class)))
                .thenThrow(software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(503).build())
                .thenReturn(null);

        S3TransferRepositoryAdapter.OrphanedBlobCleanupReport report =
                adapter.cleanupOrphanedBlobs(now);

        assertThat(report.candidates()).isEqualTo(2);
        assertThat(report.removed()).isEqualTo(1);
        assertThat(report.preserved()).isZero();
        assertThat(report.failures()).isEqualTo(1);
        verify(client, org.mockito.Mockito.times(2)).deleteObject(
                any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }

    @Test
    void listsOnlyReadableManifestsAndDownloadsChunkAsStream() throws Exception {
        S3Client client = mock(S3Client.class);
        S3TransferRepositoryAdapter adapter = adapter(client);
        TransferId transferId = TransferId.newId();
        byte[] content = "encrypted-chunk".getBytes();
        TransferChunk chunk = chunk(content, "part-00001.bin");
        TransferManifest manifest = manifest(transferId, content, chunk);
        byte[] serializedManifest = mapper.writeValueAsBytes(manifest);

        when(client.listObjectsV2(any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                .contents(
                        S3Object.builder().key("metadata/" + transferId + "/manifest.json").build(),
                        S3Object.builder().key("metadata/not-a-transfer/manifest.json").build(),
                        S3Object.builder().key("metadata/" + transferId + "/other.json").build())
                .build());
        when(client.getObjectAsBytes(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(
                GetObjectResponse.builder().contentLength((long) serializedManifest.length).build(),
                serializedManifest));
        ResponseInputStream<GetObjectResponse> chunkResponse = new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) content.length).build(),
                AbortableInputStream.create(new ByteArrayInputStream(content)));
        when(client.getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
                .thenReturn(chunkResponse);

        assertThat(adapter.listTransfers()).extracting(TransferManifest::transferId)
                .containsExactly(transferId.value());
        try (InputStream downloaded = adapter.downloadChunk(transferId, chunk)) {
            assertThat(downloaded.readAllBytes()).containsExactly(content);
        }
        ArgumentCaptor<software.amazon.awssdk.services.s3.model.ListObjectsV2Request> request =
                ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class);
        verify(client).listObjectsV2(request.capture());
        assertThat(request.getValue().prefix()).isEqualTo("metadata/");
    }

    @Test
    void translatesMissingManifestAndRejectsEmbeddedEndpointCredentials() {
        S3Client client = mock(S3Client.class);
        StorageProperties properties = properties();
        when(client.getObjectAsBytes(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class))).thenThrow(
                software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(404).build());
        S3TransferRepositoryAdapter adapter = new S3TransferRepositoryAdapter(properties, mapper, client);

        assertThatThrownBy(() -> adapter.downloadManifest(TransferId.newId()))
                .isInstanceOf(TransferNotFoundException.class);

        properties.getS3().setEndpoint("https://user@example.invalid");
        assertThatThrownBy(() -> new S3TransferRepositoryAdapter(properties, mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credenciais");
    }

    @Test
    void rejectsRemoteChunkWithUnexpectedLength() {
        S3Client client = mock(S3Client.class);
        S3TransferRepositoryAdapter adapter = adapter(client);
        byte[] content = "encrypted-chunk".getBytes();
        TransferChunk chunk = chunk(content, "part-00001.bin");
        when(client.getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) content.length + 1).build(),
                AbortableInputStream.create(new ByteArrayInputStream(content))));

        assertThatThrownBy(() -> adapter.downloadChunk(TransferId.newId(), chunk))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("tamanho");
    }

    @Test
    void transfersChunkAndManifestAgainstARealS3CompatibleBucket(@TempDir Path temp) throws Throwable {
        assumeTrue(Boolean.parseBoolean(System.getenv(CONTRACT_TEST_ENABLED)),
                "contrato S3-compatible desabilitado");

        String bucket = requiredEnvironment(CONTRACT_TEST_BUCKET);
        String endpoint = optionalEnvironment(CONTRACT_TEST_ENDPOINT);
        String region = environmentOrDefault(CONTRACT_TEST_REGION, "us-east-1");
        boolean pathStyleAccess = Boolean.parseBoolean(
                environmentOrDefault(CONTRACT_TEST_PATH_STYLE, "true"));
        TransferId transferId = TransferId.newId();
        String metadataPrefix = isolatedPrefix(
                environmentOrDefault(CONTRACT_TEST_METADATA_PREFIX, "metadata"), transferId);
        String blobPrefix = isolatedPrefix(
                environmentOrDefault(CONTRACT_TEST_BLOB_PREFIX, "blobs"), transferId);

        StorageProperties properties = new StorageProperties();
        properties.setType(StorageProperties.StorageType.OBJECT);
        properties.getS3().setBucket(bucket);
        properties.getS3().setEndpoint(endpoint);
        properties.getS3().setRegion(region);
        properties.getS3().setMetadataPrefix(metadataPrefix);
        properties.getS3().setBlobPrefix(blobPrefix);
        properties.getS3().setPathStyleAccess(pathStyleAccess);

        String fileName = "part-00001.bin";
        String metadataKey = metadataPrefix + "/" + transferId + "/manifest.json";
        String blobKey = blobPrefix + "/" + transferId + "/chunks/" + fileName;
        byte[] content = "real-s3-compatible-streaming-contract".getBytes(StandardCharsets.UTF_8);
        Path source = temp.resolve(fileName);
        Files.write(source, content);
        TransferChunk chunk = new TransferChunk(1, content.length, content.length,
                sha256(content), "bm9uY2U=", fileName);
        TransferManifest manifest = new TransferManifest(transferId.value(), "arquivo.zip", content.length,
                sha256(content), content.length, 1,
                new TransferManifest.EncryptionMetadata("AES/GCM/NoPadding"),
                List.of(chunk), Instant.now(), TransferStatus.AVAILABLE);

        S3Client client = createClient(properties.getS3());
        Throwable failure = null;
        try {
            S3TransferRepositoryAdapter adapter = new S3TransferRepositoryAdapter(properties, mapper, client);

            assertThatThrownBy(() -> adapter.downloadManifest(TransferId.newId()))
                    .isInstanceOf(TransferNotFoundException.class);
            assertThatThrownBy(() -> adapter.downloadChunk(transferId, chunk))
                    .isInstanceOf(TransferNotFoundException.class);
            adapter.publishChunk(transferId, chunk, source);
            assertThat(adapter.listTransfers()).isEmpty();

            adapter.publishManifest(manifest);
            assertThat(adapter.exists(transferId)).isTrue();
            assertThat(adapter.listTransfers()).extracting(TransferManifest::transferId)
                    .containsExactly(transferId.value());

            Path downloaded = temp.resolve("downloaded.bin");
            try (InputStream input = adapter.downloadChunk(transferId, chunk);
                 OutputStream output = Files.newOutputStream(downloaded)) {
                input.transferTo(output);
            }
            assertThat(Files.size(downloaded)).isEqualTo(content.length);
            assertThat(Files.mismatch(source, downloaded)).isEqualTo(-1L);

            assertThat(client.listObjectsV2(request -> request.bucket(bucket)
                    .prefix(metadataPrefix + "/").build()).contents())
                    .extracting(S3Object::key)
                    .containsExactly(metadataKey)
                    .doesNotContain(blobKey);
            assertThat(client.listObjectsV2(request -> request.bucket(bucket)
                    .prefix(blobPrefix + "/").build()).contents())
                    .extracting(S3Object::key)
                    .containsExactly(blobKey)
                    .doesNotContain(metadataKey);
        } catch (Throwable exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                client.deleteObject(request -> request.bucket(bucket).key(metadataKey).build());
                client.deleteObject(request -> request.bucket(bucket).key(blobKey).build());
            } catch (RuntimeException cleanupException) {
                if (failure != null) {
                    failure.addSuppressed(cleanupException);
                } else {
                    throw cleanupException;
                }
            } finally {
                client.close();
            }
        }
    }

    private S3TransferRepositoryAdapter adapter(S3Client client) {
        return new S3TransferRepositoryAdapter(properties(), mapper, client);
    }

    private static S3Client createClient(StorageProperties.S3Properties properties) {
        var builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .forcePathStyle(properties.isPathStyleAccess());
        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " deve apontar para um bucket de teste dedicado");
        }
        return value;
    }

    private static String optionalEnvironment(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String isolatedPrefix(String basePrefix, TransferId transferId) {
        return basePrefix.replaceAll("/+$", "") + "/contract-" + transferId;
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private StorageProperties properties() {
        StorageProperties properties = new StorageProperties();
        properties.setType(StorageProperties.StorageType.OBJECT);
        properties.getS3().setBucket("transfer-bucket");
        properties.getS3().setEndpoint("https://s3.example.invalid");
        return properties;
    }

    private static TransferChunk chunk(byte[] content, String fileName) {
        return new TransferChunk(1, content.length, content.length,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bm9uY2U=", fileName);
    }

    private static TransferManifest manifest(TransferId transferId, byte[] content, TransferChunk chunk) {
        return new TransferManifest(transferId.value(), "arquivo.zip", content.length,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                content.length, 1, new TransferManifest.EncryptionMetadata("AES/GCM/NoPadding"),
                List.of(chunk), Instant.now(), TransferStatus.AVAILABLE);
    }
}