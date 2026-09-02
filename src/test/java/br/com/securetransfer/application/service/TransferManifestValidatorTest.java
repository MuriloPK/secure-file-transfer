package br.com.securetransfer.application.service;

import br.com.securetransfer.configuration.TransferProperties;
import br.com.securetransfer.domain.exception.InvalidManifestException;
import br.com.securetransfer.domain.model.TransferChunk;
import br.com.securetransfer.domain.model.TransferManifest;
import br.com.securetransfer.domain.model.TransferStatus;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferManifestValidatorTest {
    @Test
    void rejectsTraversalInManifestFileName() {
        TransferProperties properties = new TransferProperties();
        properties.setMaxFileSize(DataSize.ofBytes(200));
        TransferManifestValidator validator = new TransferManifestValidator(properties);
        TransferManifest manifest = new TransferManifest(
                UUID.randomUUID(), "../arquivo.zip", 0, "e".repeat(64), 5, 0,
                new TransferManifest.EncryptionMetadata("AES/GCM/NoPadding"),
                List.of(), Instant.now(), TransferStatus.AVAILABLE);

        assertThatThrownBy(() -> validator.validate(manifest))
                .isInstanceOf(InvalidManifestException.class)
                .hasMessageContaining("inseguro");
    }

    @Test
    void rejectsDuplicateOrOutOfOrderChunks() {
        TransferProperties properties = new TransferProperties();
        properties.setMaxFileSize(DataSize.ofBytes(100));
        TransferManifestValidator validator = new TransferManifestValidator(properties);
        TransferChunk first = new TransferChunk(1, 5, 21, "a".repeat(64),
                "AAAAAAAAAAAAAAAA", "part-00001.bin");
        TransferChunk duplicate = new TransferChunk(1, 5, 21, "b".repeat(64),
                "AAAAAAAAAAAAAAAA", "part-00001.bin");
        TransferManifest manifest = new TransferManifest(
                UUID.randomUUID(), "arquivo.zip", 10, "e".repeat(64), 5, 2,
                new TransferManifest.EncryptionMetadata("AES/GCM/NoPadding"),
                List.of(first, duplicate), Instant.now(), TransferStatus.AVAILABLE);

        assertThatThrownBy(() -> validator.validate(manifest))
                .isInstanceOf(InvalidManifestException.class);
    }
}