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
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3TransferRepositoryAdapterTest {
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

    private S3TransferRepositoryAdapter adapter(S3Client client) {
        return new S3TransferRepositoryAdapter(properties(), mapper, client);
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