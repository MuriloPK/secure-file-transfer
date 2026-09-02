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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

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