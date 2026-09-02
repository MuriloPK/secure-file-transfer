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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferFlowIntegrationTest {
    private static final long MEBIBYTE = 1024L * 1024L;
    private static final long MAX_FILE_SIZE = 200 * MEBIBYTE;
    private static final long CHUNK_SIZE = 5 * MEBIBYTE;
    private static final int GENERATION_BUFFER_SIZE = 64 * 1024;
    private static final String ZIP_ENTRY_NAME = "payload.bin";
    private static final String MULTI_ENTRY_METADATA_NAME = "metadata.txt";
    private static final String MULTI_ENTRY_PAYLOAD_NAME = "data/payload.txt";
    private static final List<String> MULTI_ENTRY_NAMES = List.of(
            MULTI_ENTRY_METADATA_NAME, MULTI_ENTRY_PAYLOAD_NAME);
    private static final long ZIP_ARCHIVE_OVERHEAD = 30L + ZIP_ENTRY_NAME.length()
            + 46L + ZIP_ENTRY_NAME.length() + 22L;

    @ParameterizedTest(name = "{0} MiB publica e baixa sem carregar o arquivo inteiro")
    @ValueSource(ints = {50, 199, 200})
    void publishesAndDownloadsBoundarySizes(int sizeInMebibytes, @TempDir Path temp) throws Exception {
        TransferFixture fixture = fixture(temp);
        Path original = temp.resolve("arquivo-" + sizeInMebibytes + "-mebibytes.zip");
        long originalSize = sizeInMebibytes * MEBIBYTE;
        writeDeterministicZip(original, originalSize);
        assertValidZip(original, originalSize);
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

    @Test
    void publishesAndDownloadsZipWithMultipleEntries(@TempDir Path temp) throws Exception {
        TransferFixture fixture = fixture(temp);
        Path original = temp.resolve("arquivo-com-varios-arquivos.zip");
        writeDeterministicMultiEntryZip(original);
        Path destination = Files.createDirectory(temp.resolve("destination"));

        long originalSize = Files.size(original);
        String originalHash = sha256(fixture.hash, original);
        var manifest = fixture.publisher.publish(original, new NoOpProgressListener());

        assertThat(manifest.originalSize()).isEqualTo(originalSize);
        assertThat(manifest.originalSha256()).isEqualTo(originalHash);

        Path downloaded = fixture.downloader.download(
                new br.com.securetransfer.domain.model.TransferId(manifest.transferId()),
                destination, new NoOpProgressListener());

        assertThat(downloaded.getFileName().toString()).isEqualTo(original.getFileName().toString());
        assertThat(Files.size(downloaded)).isEqualTo(originalSize);
        assertThat(sha256(fixture.hash, downloaded)).isEqualTo(originalHash);
        assertThat(Files.mismatch(original, downloaded)).isEqualTo(-1L);
        assertThat(zipEntryNames(downloaded)).containsExactlyElementsOf(MULTI_ENTRY_NAMES);
    }

    @ParameterizedTest(name = "{0} bytes acima do limite é rejeitado")
    @ValueSource(longs = {MAX_FILE_SIZE + 1})
    void rejectsFilesLargerThanMaximum(long size, @TempDir Path temp) throws Exception {
        TransferFixture fixture = fixture(temp);
        Path original = temp.resolve("arquivo-acima-do-limite.zip");
        writeDeterministicZip(original, size);
        assertValidZip(original, size);

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

    private static void writeDeterministicZip(Path path, long size) throws Exception {
        long payloadSize = size - ZIP_ARCHIVE_OVERHEAD;
        if (payloadSize <= 0) {
            throw new IllegalArgumentException("tamanho insuficiente para um arquivo ZIP");
        }

        ZipEntry entry = new ZipEntry(ZIP_ENTRY_NAME);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(payloadSize);
        entry.setCompressedSize(payloadSize);
        entry.setCrc(crc32OfDeterministicPayload(payloadSize));

        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(path));
             ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(entry);
            writeDeterministicPayload(zip, payloadSize);
            zip.closeEntry();
        }
        if (Files.size(path) != size) {
            throw new AssertionError("o ZIP gerado não tem o tamanho solicitado");
        }
    }

    private static void writeDeterministicMultiEntryZip(Path path) throws Exception {
        byte[] metadata = "name=secure-transfer\nversion=1\n".getBytes(StandardCharsets.UTF_8);
        byte[] payload = "payload-entry-with-deterministic-content\n".getBytes(StandardCharsets.UTF_8);

        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(path));
             ZipOutputStream zip = new ZipOutputStream(file)) {
            writeStoredEntry(zip, MULTI_ENTRY_METADATA_NAME, metadata);
            writeStoredEntry(zip, MULTI_ENTRY_PAYLOAD_NAME, payload);
        }
    }

    private static void writeStoredEntry(ZipOutputStream zip, String name, byte[] content) throws Exception {
        CRC32 crc = new CRC32();
        crc.update(content);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setTime(0L);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static long crc32OfDeterministicPayload(long size) {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[GENERATION_BUFFER_SIZE];
        long offset = 0;
        while (offset < size) {
            int length = (int) Math.min(buffer.length, size - offset);
            fillDeterministicBuffer(buffer, length, offset);
            crc.update(buffer, 0, length);
            offset += length;
        }
        return crc.getValue();
    }

    private static void writeDeterministicPayload(OutputStream output, long size) throws Exception {
        byte[] buffer = new byte[GENERATION_BUFFER_SIZE];
        long offset = 0;
        while (offset < size) {
            int length = (int) Math.min(buffer.length, size - offset);
            fillDeterministicBuffer(buffer, length, offset);
            output.write(buffer, 0, length);
            offset += length;
        }
    }

    private static void fillDeterministicBuffer(byte[] buffer, int length, long offset) {
        for (int index = 0; index < length; index++) {
            buffer[index] = (byte) ((offset + index) * 31);
        }
    }

    private static void assertValidZip(Path path, long archiveSize) throws Exception {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = zip.getEntry(ZIP_ENTRY_NAME);
            assertThat(entry).isNotNull();
            assertThat(entry.getMethod()).isEqualTo(ZipEntry.STORED);
            assertThat(entry.getSize()).isEqualTo(archiveSize - ZIP_ARCHIVE_OVERHEAD);
            assertThat(entry.getCompressedSize()).isEqualTo(archiveSize - ZIP_ARCHIVE_OVERHEAD);
        }
    }

    private static List<String> zipEntryNames(Path path) throws Exception {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            return zip.stream().map(ZipEntry::getName).toList();
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