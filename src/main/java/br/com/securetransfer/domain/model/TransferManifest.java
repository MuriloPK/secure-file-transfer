package br.com.securetransfer.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransferManifest(
        UUID transferId,
        String fileName,
        long originalSize,
        String originalSha256,
        long chunkSize,
        int totalChunks,
        EncryptionMetadata encryption,
        List<TransferChunk> chunks,
        Instant createdAt,
        TransferStatus status
) {
    public record EncryptionMetadata(String algorithm) {
    }

    public TransferManifest {
        if (transferId == null || fileName == null || originalSha256 == null ||
                encryption == null || chunks == null || createdAt == null || status == null) {
            throw new IllegalArgumentException("manifest possui campos obrigatórios ausentes");
        }
        chunks = List.copyOf(chunks);
    }
}