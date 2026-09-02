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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final long MIN_MULTIPART_PART_SIZE = 5L * 1024 * 1024;
    private static final long MAX_MULTIPART_PART_SIZE = 5L * 1024 * 1024 * 1024;
    private static final int MAX_MULTIPART_PARTS = 10_000;
    private static final Logger LOGGER = LoggerFactory.getLogger(S3TransferRepositoryAdapter.class);
    private final S3Client client;
    private final ObjectMapper objectMapper;
    private final String bucket;
    private final String metadataPrefix;
    private final String blobPrefix;
    private final long multipartThresholdBytes;
    private final long multipartPartSizeBytes;
    private final Duration orphanRetention;

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
        this.multipartThresholdBytes = requirePositiveSize(
                properties.getMultipartThreshold(), "storage.s3.multipart-threshold");
        this.multipartPartSizeBytes = requireMultipartPartSize(properties.getMultipartPartSize());
        this.orphanRetention = requirePositiveDuration(
                properties.getOrphanRetention(), "storage.s3.orphan-retention");
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
        if (size > multipartThresholdBytes) {
            publishMultipartChunk(transferId, chunk, input, size);
            return;
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

    private void publishMultipartChunk(TransferId transferId, TransferChunk chunk, Path input, long size)
            throws IOException {
        String key = blobKey(transferId, chunk);
        int expectedParts = numberOfParts(size);
        String uploadId = null;
        try {
            CreateMultipartUploadResponse created = client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType("application/octet-stream")
                    .build());
            uploadId = created.uploadId();
            if (uploadId == null || uploadId.isBlank()) {
                throw new StorageException("armazenamento de objetos não retornou o identificador do upload multipart");
            }

            List<CompletedPart> completedParts = new ArrayList<>(expectedParts);
            long remaining = size;
            long offset = 0;
            for (int partNumber = 1; remaining > 0; partNumber++) {
                long partSize = Math.min(multipartPartSizeBytes, remaining);
                long partOffset = offset;
                UploadPartResponse uploaded = client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .contentLength(partSize)
                                .build(),
                        RequestBody.fromContentProvider(
                                () -> openPart(input, partOffset, partSize),
                                partSize,
                                "application/octet-stream"));
                if (uploaded == null || uploaded.eTag() == null || uploaded.eTag().isBlank()) {
                    throw new StorageException("armazenamento de objetos não retornou o ETag da parte "
                            + partNumber);
                }
                completedParts.add(CompletedPart.builder()
                        .partNumber(partNumber)
                        .eTag(uploaded.eTag())
                        .build());
                remaining -= partSize;
                offset += partSize;
            }

            client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder()
                            .parts(completedParts)
                            .build())
                    .build());
        } catch (S3Exception | SdkClientException exception) {
            StorageException failure = storageFailure("publicar chunk multipart " + chunk.number(), exception);
            abortMultipartUpload(bucket, key, uploadId, failure);
            throw failure;
        } catch (UncheckedIOException exception) {
            IOException failure = exception.getCause();
            abortMultipartUpload(bucket, key, uploadId, failure);
            throw failure;
        } catch (RuntimeException exception) {
            abortMultipartUpload(bucket, key, uploadId, exception);
            throw exception;
        }
    }

    private int numberOfParts(long size) {
        long parts = size / multipartPartSizeBytes
                + (size % multipartPartSizeBytes == 0 ? 0 : 1);
        if (parts > MAX_MULTIPART_PARTS) {
            throw new StorageException("chunk excede o limite de 10.000 partes do upload multipart");
        }
        return Math.toIntExact(parts);
    }

    private void abortMultipartUpload(String bucket, String key, String uploadId, Throwable failure) {
        if (uploadId == null || uploadId.isBlank()) {
            return;
        }
        try {
            client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .build());
            LOGGER.info("upload multipart abortado key={}", key);
        } catch (S3Exception | SdkClientException abortFailure) {
            failure.addSuppressed(storageFailure("abortar upload multipart", abortFailure));
        }
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

    /**
     * Removes staged chunks after a publication fails before manifest
     * publication starts. A manifest check is deliberately repeated before
     * every deletion so this safety boundary is also enforced when callers
     * invoke the cleanup operation directly.
     */
    @Override
    public void cleanupUnpublishedTransfer(TransferId transferId) throws IOException {
        if (manifestExistsForCleanup(transferId)) {
            LOGGER.info("unpublished transfer cleanup skipped transferId={} reason=manifest-present", transferId);
            return;
        }

        String prefix = chunkPrefix(transferId);
        String continuationToken = null;
        int deleted = 0;
        do {
            ListObjectsV2Response page;
            try {
                ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix);
                if (continuationToken != null) {
                    request.continuationToken(continuationToken);
                }
                page = client.listObjectsV2(request.build());
            } catch (S3Exception | SdkClientException exception) {
                throw storageFailure("listar chunks órfãos da transferência " + transferId, exception);
            }

            for (S3Object object : page.contents()) {
                if (object.key() == null || !object.key().startsWith(prefix)) {
                    continue;
                }
                if (manifestExistsForCleanup(transferId)) {
                    LOGGER.info("unpublished transfer cleanup stopped transferId={} deleted={} reason=manifest-present",
                            transferId, deleted);
                    return;
                }
                try {
                    client.deleteObject(DeleteObjectRequest.builder()
                            .bucket(bucket)
                            .key(object.key())
                            .build());
                    deleted++;
                } catch (S3Exception | SdkClientException exception) {
                    throw storageFailure("remover chunk órfão da transferência " + transferId, exception);
                }
            }
            continuationToken = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (continuationToken != null && !continuationToken.isBlank());

        LOGGER.info("unpublished transfer cleanup finished transferId={} deleted={}", transferId, deleted);
    }

    /**
     * Finds old chunk objects whose transfer never acquired a manifest and
     * removes them. The manifest is checked immediately before every delete,
     * so a transfer that becomes available while this sweep is running is
     * preserved. Missing objects are harmless on a later run because object
     * deletion is idempotent in S3.
     *
     * @return counts of candidates, removed objects, preserved objects and
     *         failures observed during the sweep
     */
    public OrphanedBlobCleanupReport cleanupOrphanedBlobs() throws IOException {
        return cleanupOrphanedBlobs(Instant.now());
    }

    OrphanedBlobCleanupReport cleanupOrphanedBlobs(Instant now) throws IOException {
        if (now == null) {
            throw new IllegalArgumentException("o instante da limpeza não pode ser nulo");
        }
        Instant cutoff = now.minus(orphanRetention);
        Map<TransferId, List<S3Object>> candidatesByTransfer = new LinkedHashMap<>();
        int candidateCount = 0;
        String continuationToken = null;

        do {
            ListObjectsV2Response page;
            try {
                ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(blobPrefix + "/");
                if (continuationToken != null) {
                    request.continuationToken(continuationToken);
                }
                page = client.listObjectsV2(request.build());
            } catch (S3Exception | SdkClientException exception) {
                LOGGER.warn("orphan blob cleanup failed while listing objects", exception);
                throw storageFailure("listar blobs órfãos", exception);
            }

            for (S3Object object : page.contents()) {
                TransferId transferId = transferIdFromChunkKey(object.key());
                Instant lastModified = object.lastModified();
                if (transferId == null || lastModified == null || !lastModified.isBefore(cutoff)) {
                    continue;
                }
                candidateCount++;
                candidatesByTransfer.computeIfAbsent(transferId, ignored -> new ArrayList<>()).add(object);
                LOGGER.info("orphan blob cleanup candidate transferId={} key={} lastModified={}",
                        transferId, object.key(), lastModified);
            }
            continuationToken = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (continuationToken != null && !continuationToken.isBlank());

        int removedCount = 0;
        int preservedCount = 0;
        int failureCount = 0;
        for (Map.Entry<TransferId, List<S3Object>> entry : candidatesByTransfer.entrySet()) {
            TransferId transferId = entry.getKey();
            List<S3Object> transferCandidates = entry.getValue();
            for (int index = 0; index < transferCandidates.size(); index++) {
                boolean manifestPresent;
                try {
                    manifestPresent = manifestExistsForCleanup(transferId);
                } catch (IOException | RuntimeException exception) {
                    failureCount++;
                    LOGGER.warn("orphan blob cleanup failed transferId={} while checking manifest; "
                            + "objects preserved", transferId, exception);
                    break;
                }
                if (manifestPresent) {
                    int preservedForTransfer = transferCandidates.size() - index;
                    preservedCount += preservedForTransfer;
                    LOGGER.info("orphan blob cleanup preserved transferId={} objects={} reason=manifest-present",
                            transferId, preservedForTransfer);
                    break;
                }

                S3Object object = transferCandidates.get(index);
                try {
                    client.deleteObject(DeleteObjectRequest.builder()
                            .bucket(bucket)
                            .key(object.key())
                            .build());
                    removedCount++;
                    LOGGER.info("orphan blob cleanup removed transferId={} key={}", transferId, object.key());
                } catch (RuntimeException exception) {
                    failureCount++;
                    LOGGER.warn("orphan blob cleanup failed transferId={} key={}",
                            transferId, object.key(), exception);
                }
            }
        }

        OrphanedBlobCleanupReport report = new OrphanedBlobCleanupReport(
                candidateCount, removedCount, preservedCount, failureCount);
        LOGGER.info("orphan blob cleanup finished candidates={} removed={} preserved={} failures={} cutoff={}",
                report.candidates(), report.removed(), report.preserved(), report.failures(), cutoff);
        return report;
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

    private String chunkPrefix(TransferId transferId) {
        return blobPrefix + "/" + transferId + "/chunks/";
    }

    private TransferId transferIdFromChunkKey(String key) {
        String prefix = blobPrefix + "/";
        if (key == null || !key.startsWith(prefix)) {
            return null;
        }
        String remainder = key.substring(prefix.length());
        int transferSeparator = remainder.indexOf('/');
        if (transferSeparator <= 0) {
            return null;
        }
        String transferValue = remainder.substring(0, transferSeparator);
        String chunkPrefix = transferValue + "/chunks/";
        if (!remainder.startsWith(chunkPrefix)
                || remainder.length() == chunkPrefix.length()
                || remainder.substring(chunkPrefix.length()).contains("/")) {
            return null;
        }
        try {
            return TransferId.parse(transferValue);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean manifestExistsForCleanup(TransferId transferId) throws IOException {
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(manifestKey(transferId))
                    .build());
            return true;
        } catch (S3Exception exception) {
            if (isNotFound(exception)) {
                return false;
            }
            throw storageFailure("verificar manifest da transferência " + transferId, exception);
        } catch (SdkClientException exception) {
            throw storageFailure("verificar manifest da transferência " + transferId, exception);
        }
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

    private static long requirePositiveSize(DataSize value, String propertyName) {
        if (value == null || value.toBytes() <= 0) {
            throw new IllegalArgumentException(propertyName + " deve ser maior que zero");
        }
        return value.toBytes();
    }

    private static Duration requirePositiveDuration(Duration value, String propertyName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(propertyName + " deve ser maior que zero");
        }
        return value;
    }

    private static long requireMultipartPartSize(DataSize value) {
        long bytes = requirePositiveSize(value, "storage.s3.multipart-part-size");
        if (bytes < MIN_MULTIPART_PART_SIZE) {
            throw new IllegalArgumentException("storage.s3.multipart-part-size deve ser de pelo menos 5MB");
        }
        if (bytes > MAX_MULTIPART_PART_SIZE) {
            throw new IllegalArgumentException("storage.s3.multipart-part-size não pode exceder 5GB");
        }
        return bytes;
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

    private static InputStream openPart(Path input, long offset, long length) {
        SeekableByteChannel source = null;
        try {
            source = Files.newByteChannel(input, StandardOpenOption.READ);
            source.position(offset);
            return new PartInputStream(Channels.newInputStream(source), length);
        } catch (IOException exception) {
            if (source != null) {
                try {
                    source.close();
                } catch (IOException closeException) {
                    exception.addSuppressed(closeException);
                }
            }
            throw new UncheckedIOException("não foi possível abrir parte do chunk para upload", exception);
        }
    }

    public record OrphanedBlobCleanupReport(int candidates, int removed, int preserved, int failures) {
    }

    /**
     * Presents only one multipart part to the SDK. A new instance is opened
     * for each request (including SDK retries), so no part is held in memory
     * and a consumed stream is never reused.
     */
    private static final class PartInputStream extends InputStream {
        private final InputStream source;
        private long remaining;

        private PartInputStream(InputStream source, long length) {
            this.source = source;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int value = source.read();
            if (value < 0) {
                throw new IOException("fonte do chunk terminou durante o upload multipart");
            }
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int requested = (int) Math.min(length, remaining);
            int count = source.read(bytes, offset, requested);
            if (count < 0) {
                throw new IOException("fonte do chunk terminou durante o upload multipart");
            }
            if (count == 0) {
                return 0;
            }
            remaining -= count;
            return count;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }
}