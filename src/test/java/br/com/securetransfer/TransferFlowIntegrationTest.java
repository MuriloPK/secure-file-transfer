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
import br.com.securetransfer.domain.exception.FileTooLargeException;
import br.com.securetransfer.infrastructure.crypto.AesGcmEncryptionAdapter;
import br.com.securetransfer.infrastructure.crypto.SecretKeyProvider;
import br.com.securetransfer.infrastructure.hash.Sha256HashAdapter;
import br.com.securetransfer.infrastructure.repository.LocalDirectoryTransferRepositoryAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferFlowIntegrationTest {
    private static final long MEBIBYTE = 1024L * 1024L;
    private static final long MAX_FILE_SIZE = 200 * MEBIBYTE;
    private static final long CHUNK_SIZE = 5 * MEBIBYTE;
    private static final int GENERATION_BUFFER_SIZE = 64 * 1024;

    @ParameterizedTest(name = "{0} MiB publica e baixa sem carregar o arquivo inteiro")
    @ValueSource(ints = {50, 199, 200})
    void publishesAndDownloadsBoundarySizes(int sizeInMebibytes, @TempDir Path temp) throws Exception {
        TransferFixture fixture = fixture(temp);
        Path original = temp.resolve("arquivo-" + sizeInMebibytes + "-mebibytes.zip");
        long originalSize = sizeInMebibytes * MEBIBYTE;
        writeDeterministicFile(original, originalSize);
        Path destination = Files.createDirectory(temp.resolve("destination"));

        String originalHash = sha256(fixture.hash, original);
        var manifest = fixture.publisher.publish(original, new NoOpProgressListener());

        assertThat(manifest.originalSize()).isEqualTo(originalSize);
        assertThat(manifest.originalSha256()).isEqualTo(originalHash);
        int expectedChunks = (int) ((originalSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
        assertThat(manifest.totalChunks()).isEqualTo(expectedChunks);
        assertThat(manifest.chunks().subList(0, expectedChunks - 1))
                .allSatisfy(chunk -> assertThat(chunk.originalSize()).isEqualTo(CHUNK_SIZE));
        assertThat(manifest.chunks().get(expectedChunks - 1).originalSize())
                .isEqualTo(originalSize - (long) (expectedChunks - 1) * CHUNK_SIZE);

        Path downloaded = fixture.downloader.download(
                new br.com.securetransfer.domain.model.TransferId(manifest.transferId()),
                destination, new NoOpProgressListener());

        assertThat(downloaded.getFileName().toString()).isEqualTo(original.getFileName().toString());
        assertThat(Files.size(downloaded)).isEqualTo(originalSize);
        assertThat(sha256(fixture.hash, downloaded)).isEqualTo(originalHash);
        assertThat(Files.mismatch(original, downloaded)).isEqualTo(-1L);
    }

    @ParameterizedTest(name = "{0} bytes acima do limite é rejeitado")
    @ValueSource(longs = {MAX_FILE_SIZE + 1})
    void rejectsFilesLargerThanMaximum(long size, @TempDir Path temp) throws Exception {
        TransferFixture fixture = fixture(temp);
        Path original = temp.resolve("arquivo-acima-do-limite.zip");
        createSparseFile(original, size);

        assertThatThrownBy(() -> fixture.publisher.publish(original, new NoOpProgressListener()))
                .isInstanceOf(FileTooLargeException.class);
        assertThat(fixture.repository.listTransfers()).isEmpty();
    }

    private static TransferFixture fixture(Path temp) throws Exception {
        TransferProperties transferProperties = new TransferProperties();
        transferProperties.setMaxFileSize(DataSize.ofMegabytes(200));
        transferProperties.setChunkSize(DataSize.ofMegabytes(5));
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
        var publisher = new PublishFileService(transferProperties, hash, chunkService, repository, temporaryFiles,
                keyProvider);
        var validator = new TransferManifestValidator(transferProperties);
        var downloader = new DownloadFileService(repository, validator, temporaryFiles,
                hash, encryption, keyProvider);
        return new TransferFixture(publisher, downloader, repository, hash);
    }

    private static void writeDeterministicFile(Path path, long size) throws Exception {
        byte[] buffer = new byte[GENERATION_BUFFER_SIZE];
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(path))) {
            long offset = 0;
            while (offset < size) {
                int length = (int) Math.min(buffer.length, size - offset);
                fillDeterministicBuffer(buffer, length, offset);
                output.write(buffer, 0, length);
                offset += length;
            }
        }
    }

    private static void fillDeterministicBuffer(byte[] buffer, int length, long offset) {
        for (int index = 0; index < length; index++) {
            buffer[index] = (byte) ((offset + index) * 31);
        }
    }

    private static void createSparseFile(Path path, long size) throws Exception {
        try (SeekableByteChannel channel = Files.newByteChannel(path,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(size - 1);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
    }

    private static String sha256(Sha256HashAdapter hash, Path path) throws Exception {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            return hash.sha256(input);
        }
    }

    private static SecretKey key() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return generator.generateKey();
    }

    private record TransferFixture(PublishFileService publisher, DownloadFileService downloader,
                                   LocalDirectoryTransferRepositoryAdapter repository, Sha256HashAdapter hash) {
    }
}