package br.com.securetransfer.infrastructure.repository;

import br.com.securetransfer.application.service.PathSafety;
import br.com.securetransfer.configuration.StorageProperties;
import br.com.securetransfer.domain.exception.StorageException;
import br.com.securetransfer.domain.exception.TransferNotFoundException;
import br.com.securetransfer.domain.model.TransferChunk;
import br.com.securetransfer.domain.model.TransferId;
import br.com.securetransfer.domain.model.TransferManifest;
import br.com.securetransfer.ports.out.TransferRepositoryPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * S3-compatible repository that keeps transfer metadata and encrypted blobs in
 * separate object prefixes. The manifest is written only after all chunks have
 * been uploaded by the application service.
 *
 * <p>Authentication is intentionally delegated to the AWS SDK default
 * credentials provider chain. Access keys are therefore supplied by the
 * environment, workload identity, or the machine's standard credentials
 * provider and never become application configuration or manifest data.</p>
 */
@Component
@ConditionalOnExpression("'${storage.type:local}'.toLowerCase() matches 'object|s3'")
public class S3TransferRepositoryAdapter implements TransferRepositoryPort {
    private static final String MANIFEST = "manifest.json";
    private final S3Client client;
    private final ObjectMapper objectMapper;
    private final String bucket;
    private final String metadataPrefix;
    private final String blobPrefix;

    public S3TransferRepositoryAdapter(StorageProperties storageProperties, ObjectMapper objectMapper) {
        this(storageProperties, objectMapper, createClient(s3Properties(storageProperties)));
    }

    S3TransferRepositoryAdapter(StorageProperties storageProperties, ObjectMapper objectMapper, S3Client client) {
        if (storageProperties == null || storageProperties.getS3() == null) {
            throw new IllegalArgumentException("configuração storage.s3 ausente");
        }
        this.client = client;
        this.objectMapper = objectMapper;
        StorageProperties.S3Properties properties = storageProperties.getS3();
        this.bucket = requireBucket(properties.getBucket());
        this.metadataPrefix = requirePrefix(properties.getMetadataPrefix(), "storage.s3.metadata-prefix");
        this.blobPrefix = requirePrefix(properties.getBlobPrefix(), "storage.s3.blob-prefix");
        if (metadataPrefix.equals(blobPrefix)) {
            throw new IllegalArgumentException("storage.s3.metadata-prefix e blob-prefix devem ser diferentes");
        }
    }

    @Override
    public void publishChunk(TransferId transferId, TransferChunk chunk, Path source) throws IOException {
        Path input = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(input)) {
            throw new StorageException("chunk criptografado não encontrado para publicação");
        }
        long size = Files.size(input);
        if (size != chunk.encryptedSize()) {
            throw new StorageException("tamanho do chunk criptografado não corresponde ao manifest");
        }
        putObject("publicar chunk " + chunk.number(),
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(blobKey(transferId, chunk))
                        .contentLength(size)
                        .contentType("application/octet-stream")
                        .build(),
                RequestBody.fromFile(input));
    }

    @Override
    public void publishManifest(TransferManifest manifest) throws IOException {
        byte[] content;
        try {
            content = objectMapper.writeValueAsBytes(manifest);
        } catch (JsonProcessingException exception) {
            throw new StorageException("não foi possível serializar " + MANIFEST, exception);
        }
        putObject("publicar manifest da transferência " + manifest.transferId(),
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(manifestKey(new TransferId(manifest.transferId())))
                        .contentLength((long) content.length)
                        .contentType("application/json")
                        .build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public InputStream downloadChunk(TransferId transferId, TransferChunk chunk) throws IOException {
        ResponseInputStream<GetObjectResponse> response;
        try {
            response = client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(blobKey(transferId, chunk))
                    .build());
        } catch (S3Exception exception) {
            if (isNotFound(exception)) {
                throw new TransferNotFoundException(transferId + " / chunk " + chunk.number());
            }
            throw storageFailure("baixar chunk " + chunk.number(), exception);
        } catch (SdkClientException exception) {
            throw storageFailure("baixar chunk " + chunk.number(), exception);
        }

        Long contentLength = response.response().contentLength();
        if (contentLength != null && contentLength != chunk.encryptedSize()) {
            response.close();
            throw new StorageException("tamanho do chunk remoto não corresponde ao manifest");
        }
        return response;
    }

    @Override
    public TransferManifest downloadManifest(TransferId transferId) throws IOException {
        try {
            ResponseBytes<GetObjectResponse> response = client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(manifestKey(transferId))
                    .build());
            return parseManifest(response.asByteArray());
        } catch (S3Exception exception) {
            if (isNotFound(exception)) {
                throw new TransferNotFoundException(transferId.toString());
            }
            throw storageFailure("baixar manifest da transferência " + transferId, exception);
        } catch (SdkClientException exception) {
            throw storageFailure("baixar manifest da transferência " + transferId, exception);
        }
    }

    @Override
    public List<TransferManifest> listTransfers() throws IOException {
        List<TransferManifest> manifests = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Response page;
            try {
                ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(metadataPrefix + "/");
                if (continuationToken != null) {
                    request.continuationToken(continuationToken);
                }
                page = client.listObjectsV2(request.build());
            } catch (S3Exception | SdkClientException exception) {
                throw storageFailure("listar manifests", exception);
            }
            for (S3Object object : page.contents()) {
                TransferId transferId = transferIdFromManifestKey(object.key());
                if (transferId == null) {
                    continue;
                }
                try {
                    manifests.add(downloadManifest(transferId));
                } catch (TransferNotFoundException ignored) {
                    // The object may disappear between LIST and GET.
                } catch (StorageException exception) {
                    if (!exception.getMessage().contains("não foi possível ler")) {
                        throw exception;
                    }
                }
            }
            continuationToken = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (continuationToken != null && !continuationToken.isBlank());

        return manifests.stream()
                .sorted(Comparator.comparing(TransferManifest::createdAt).reversed())
                .toList();
    }

    @Override
    public boolean exists(TransferId transferId) {
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(manifestKey(transferId))
                    .build());
            return true;
        } catch (S3Exception | SdkClientException exception) {
            return false;
        }
    }

    /**
     * Object stores do not require a local clone or a pull. Each operation
     * addresses the current object directly, so the port's synchronization
     * hook remains a deliberate no-op.
     */
    @Override
    public void synchronize() {
    }

    private void putObject(String operation, PutObjectRequest request, RequestBody body) throws IOException {
        try {
            client.putObject(request, body);
        } catch (S3Exception | SdkClientException exception) {
            throw storageFailure(operation, exception);
        }
    }

    private TransferManifest parseManifest(byte[] content) throws IOException {
        try {
            return objectMapper.readValue(content, TransferManifest.class);
        } catch (JsonProcessingException exception) {
            throw new StorageException("não foi possível ler " + MANIFEST, exception);
        }
    }

    private String manifestKey(TransferId transferId) {
        return metadataPrefix + "/" + transferId + "/" + MANIFEST;
    }

    private String blobKey(TransferId transferId, TransferChunk chunk) {
        return blobPrefix + "/" + transferId + "/chunks/" + PathSafety.requireSafeFileName(chunk.fileName());
    }

    private TransferId transferIdFromManifestKey(String key) {
        String expectedPrefix = metadataPrefix + "/";
        if (key == null || !key.startsWith(expectedPrefix) || !key.endsWith("/" + MANIFEST)) {
            return null;
        }
        String value = key.substring(expectedPrefix.length(), key.length() - ("/" + MANIFEST).length());
        if (value.isBlank() || value.contains("/")) {
            return null;
        }
        try {
            return TransferId.parse(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static S3Client createClient(StorageProperties.S3Properties properties) {
        validateEndpoint(properties.getEndpoint());
        if (properties.getRegion() == null || properties.getRegion().isBlank()) {
            throw new IllegalArgumentException("storage.s3.region é obrigatória");
        }
        var builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .forcePathStyle(properties.isPathStyleAccess());
        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }

    private static StorageProperties.S3Properties s3Properties(StorageProperties properties) {
        if (properties == null || properties.getS3() == null) {
            throw new IllegalArgumentException("configuração storage.s3 ausente");
        }
        return properties.getS3();
    }

    private static String requireBucket(String value) {
        if (value == null || value.isBlank() || value.length() > 255
                || value.contains("/") || value.contains("\\")
                || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("storage.s3.bucket é inválido");
        }
        return value;
    }

    private static String requirePrefix(String value, String propertyName) {
        if (value == null || value.isBlank() || value.contains("..")
                || value.contains("\\") || value.startsWith("/")) {
            throw new IllegalArgumentException(propertyName + " é inválido");
        }
        String normalized = value.replaceAll("/+$", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(propertyName + " é inválido");
        }
        return normalized;
    }

    private static void validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return;
        }
        try {
            URI uri = new URI(endpoint);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("storage.s3.endpoint deve usar HTTP ou HTTPS");
            }
            if (uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("storage.s3.endpoint não pode conter credenciais");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("storage.s3.endpoint inválido", exception);
        }
    }

    private static boolean isNotFound(S3Exception exception) {
        String errorCode = exception.awsErrorDetails() == null
                ? null : exception.awsErrorDetails().errorCode();
        return exception.statusCode() == 404
                || "NoSuchKey".equalsIgnoreCase(errorCode)
                || "NotFound".equalsIgnoreCase(errorCode);
    }

    private static StorageException storageFailure(String operation, RuntimeException exception) {
        if (exception instanceof S3Exception s3Exception && s3Exception.statusCode() > 0) {
            return new StorageException("falha ao " + operation + " no armazenamento de objetos (status "
                    + s3Exception.statusCode() + ")", exception);
        }
        return new StorageException("falha ao " + operation + " no armazenamento de objetos", exception);
    }
}