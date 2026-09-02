package br.com.securetransfer;

import br.com.securetransfer.application.service.ChunkService;
import br.com.securetransfer.application.service.DownloadFileService;
import br.com.securetransfer.application.service.NoOpProgressListener;
import br.com.securetransfer.application.service.PublishFileService;
import br.com.securetransfer.application.service.TemporaryFileManager;
import br.com.securetransfer.application.service.TransferManifestValidator;
import br.com.securetransfer.configuration.CryptoProperties;
import br.com.securetransfer.configuration.StorageProperties;
import br.com.securetransfer.configuration.TransferProperties;
import br.com.securetransfer.configuration.WorkProperties;
import br.com.securetransfer.infrastructure.crypto.AesGcmEncryptionAdapter;
import br.com.securetransfer.infrastructure.crypto.SecretKeyProvider;
import br.com.securetransfer.infrastructure.hash.Sha256HashAdapter;
import br.com.securetransfer.infrastructure.repository.LocalDirectoryTransferRepositoryAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TransferFlowIntegrationTest {
    @Test
    void publishesAndDownloadsIdenticalZip(@TempDir Path temp) throws Exception {
        TransferProperties transferProperties = new TransferProperties();
        transferProperties.setMaxFileSize(DataSize.ofKilobytes(200));
        transferProperties.setChunkSize(DataSize.ofKilobytes(5));
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setPath(temp.resolve("storage"));
        WorkProperties workProperties = new WorkProperties();
        workProperties.setPath(temp.resolve("work"));

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var repository = new LocalDirectoryTransferRepositoryAdapter(storageProperties, mapper);
        var hash = new Sha256HashAdapter();
        var encryption = new AesGcmEncryptionAdapter();
        var chunkService = new ChunkService(transferProperties, encryption);
        var temporaryFiles = new TemporaryFileManager(workProperties);
        SecretKey key = key();
        SecretKeyProvider keyProvider = new SecretKeyProvider(new CryptoProperties()) {
            @Override
            public SecretKey key() {
                return key;
            }
        };
        var publisher = new PublishFileService(transferProperties, hash, chunkService,
                repository, temporaryFiles, keyProvider);
        var validator = new TransferManifestValidator(transferProperties);
        var downloader = new DownloadFileService(repository, validator, temporaryFiles,
                hash, encryption, keyProvider);

        Path original = temp.resolve("arquivo com acento-ção.zip");
        byte[] bytes = new byte[12 * 1024 + 7];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 31);
        }
        Files.write(original, bytes);
        Path destination = Files.createDirectory(temp.resolve("destination"));

        var manifest = publisher.publish(original, new NoOpProgressListener());
        assertThat(manifest.totalChunks()).isEqualTo(3);
        assertThat(repository.listTransfers()).hasSize(1);
        Path downloaded = downloader.download(new br.com.securetransfer.domain.model.TransferId(manifest.transferId()),
                destination, new NoOpProgressListener());

        assertThat(downloaded.getFileName().toString()).isEqualTo(original.getFileName().toString());
        assertThat(Files.readAllBytes(downloaded)).isEqualTo(Files.readAllBytes(original));
        assertThat(hash.sha256(Files.newInputStream(downloaded)))
                .isEqualTo(hash.sha256(Files.newInputStream(original)));
    }

    private static SecretKey key() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return generator.generateKey();
    }
}