package br.com.securetransfer.application.service;

import br.com.securetransfer.domain.exception.InvalidManifestException;
import br.com.securetransfer.domain.model.TransferChunk;
import br.com.securetransfer.domain.model.TransferManifest;
import br.com.securetransfer.configuration.TransferProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Component
public class TransferManifestValidator {
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final long MAX_CHUNKS_SAFETY_LIMIT = 100_000;
    private final TransferProperties properties;

    public TransferManifestValidator(TransferProperties properties) {
        this.properties = properties;
    }

    public void validate(TransferManifest manifest) {
        if (manifest == null || manifest.transferId() == null ||
                manifest.transferId().equals(new UUID(0, 0)) ||
                manifest.originalSize() < 0 || manifest.chunkSize() <= 0 ||
                manifest.chunkSize() > properties.getMaxFileSize() ||
                manifest.totalChunks() < 0 || manifest.totalChunks() > MAX_CHUNKS_SAFETY_LIMIT ||
                !isSha256(manifest.originalSha256()) ||
                manifest.encryption() == null ||
                !AES_GCM.equals(manifest.encryption().algorithm())) {
            throw new InvalidManifestException("campos básicos inválidos");
        }
        PathSafety.requireSafeFileName(manifest.fileName());
        long expectedChunks = manifest.originalSize() == 0
                ? 0 : (manifest.originalSize() + manifest.chunkSize() - 1) / manifest.chunkSize();
        if (manifest.totalChunks() != expectedChunks || manifest.chunks().size() != manifest.totalChunks()) {
            throw new InvalidManifestException("quantidade de chunks não corresponde ao tamanho original");
        }
        Set<Integer> numbers = new HashSet<>();
        long totalOriginalBytes = 0;
        for (int index = 0; index < manifest.chunks().size(); index++) {
            TransferChunk chunk = manifest.chunks().get(index);
            if (chunk == null || chunk.number() != index + 1 || !numbers.add(chunk.number()) ||
                    chunk.originalSize() < 0 || chunk.originalSize() > manifest.chunkSize() ||
                    chunk.encryptedSize() < 16 ||
                    !isSha256(chunk.sha256()) ||
                    !isNonce(chunk.nonce()) ||
                    !chunk.fileName().equals(String.format("part-%05d.bin", chunk.number()))) {
                throw new InvalidManifestException("chunk inválido na posição " + (index + 1));
            }
            totalOriginalBytes += chunk.originalSize();
        }
        if (totalOriginalBytes != manifest.originalSize()) {
            throw new InvalidManifestException("soma dos chunks não corresponde ao arquivo original");
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean isNonce(String value) {
        try {
            return value != null && Base64.getDecoder().decode(value).length == 12;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}