package br.com.securetransfer;

import br.com.securetransfer.application.service.ChunkService;
import br.com.securetransfer.application.service.DownloadFileService;
import br.com.securetransfer.application.service.NoOpProgressListener;
import br.com.securetransfer.application.service.ProgressListener;
import br.com.securetransfer.application.service.PublishFileService;
import br.com.securetransfer.application.service.TemporaryFileManager;
import br.com.securetransfer.application.service.TransferManifestValidator;
import br.com.securetransfer.configuration.CryptoProperties;
import br.com.securetransfer.configuration.StorageProperties;
import br.com.securetransfer.configuration.TransferProperties;
import br.com.securetransfer.configuration.WorkProperties;
import br.com.securetransfer.domain.model.TransferChunk;
import br.com.securetransfer.domain.model.TransferId;
import br.com.securetransfer.domain.model.TransferManifest;
import br.com.securetransfer.domain.exception.FileTooLargeException;
import br.com.securetransfer.infrastructure.crypto.AesGcmEncryptionAdapter;
import br.com.securetransfer.infrastructure.crypto.SecretKeyProvider;
import br.com.securetransfer.infrastructure.hash.Sha256HashAdapter;
import br.com.securetransfer.infrastructure.repository.LocalDirectoryTransferRepositoryAdapter;
import br.com.securetransfer.ports.out.TransferRepositoryPort;
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
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

    @Test
    void resumesInterruptedMultiEntryZipWithoutRedownloadingValidatedChunks(@TempDir Path temp) throws Exception {
        TransferFixture fixture = fixture(temp);
        Path original = temp.resolve("arquivo-com-varios-arquivos-e-retomada.zip");
        writeDeterministicMultiEntryZip(original, CHUNK_SIZE);
        Path destination = Files.createDirectory(temp.resolve("destination"));

        long originalSize = Files.size(original);
        String originalHash = sha256(fixture.hash, original);
        var manifest = fixture.publisher.publish(original, new NoOpProgressListener());
        TransferId transferId = new TransferId(manifest.transferId());

        assertThat(manifest.totalChunks()).isGreaterThan(1);
        assertThat(manifest.originalSize()).isEqualTo(originalSize);
        assertThat(manifest.originalSha256()).isEqualTo(originalHash);

        ProgressListener interruptAfterFirstChunk = (operation, completed, total, item, totalItems) -> {
            if ("Baixando".equals(operation) && item == 1) {
                throw new DownloadInterruptedException();
            }
        };
        assertThatThrownBy(() -> fixture.downloader.download(
                transferId, destination, interruptAfterFirstChunk))
                .isInstanceOf(DownloadInterruptedException.class);
        assertThat(fixture.repository.downloadedChunkNumbers()).containsExactly(1);

        Path downloaded = fixture.downloader.download(
                transferId, destination, new NoOpProgressListener());

        assertThat(fixture.repository.downloadedChunkNumbers())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, manifest.totalChunks())
                        .boxed().toList());
        assertThat(downloaded.getFileName().toString()).isEqualTo(original.getFileName().toString());
        assertThat(Files.size(downloaded)).isEqualTo(originalSize);
        assertThat(sha256(fixture.hash, downloaded)).isEqualTo(originalHash);
        assertThat(Files.mismatch(original, downloaded)).isEqualTo(-1L);
        assertThat(zipEntryNames(downloaded)).containsExactlyElementsOf(MULTI_ENTRY_NAMES);
    }

    @Test
    void removesPartialArchiveWhenAssemblyIsInterruptedAndCanRetry(@TempDir Path temp) throws Exception {
        TransferFixture fixture = fixture(temp);
        Path original = temp.resolve("arquivo-com-montagem-interrompida.zip");
        writeDeterministicMultiEntryZip(original, CHUNK_SIZE);
        Path destination = Files.createDirectory(temp.resolve("destination"));

        long originalSize = Files.size(original);
        String originalHash = sha256(fixture.hash, original);
        var manifest = fixture.publisher.publish(original, new NoOpProgressListener());
        TransferId transferId = new TransferId(manifest.transferId());
        List<Integer> chunkNumbers = IntStream.rangeClosed(1, manifest.totalChunks()).boxed().toList();

        ProgressListener interruptDuringAssembly = (operation, completed, total, item, totalItems) -> {
            if ("Montando".equals(operation) && item == 2) {
                throw new AssemblyInterruptedException();
            }
        };
        assertThatThrownBy(() -> fixture.downloader.download(
                transferId, destination, interruptDuringAssembly))
                .isInstanceOf(AssemblyInterruptedException.class);
        assertThat(fixture.repository.downloadedChunkNumbers()).containsExactlyElementsOf(chunkNumbers);
        try (var files = Files.list(destination)) {
            assertThat(files.toList()).isEmpty();
        }

        Path downloaded = fixture.downloader.download(
                transferId, destination, new NoOpProgressListener());

        assertThat(fixture.repository.downloadedChunkNumbers()).containsExactlyElementsOf(chunkNumbers);
        assertThat(downloaded.getFileName().toString()).isEqualTo(original.getFileName().toString());
        assertThat(Files.size(downloaded)).isEqualTo(originalSize);
        assertThat(sha256(fixture.hash, downloaded)).isEqualTo(originalHash);
        assertThat(Files.mismatch(original, downloaded)).isEqualTo(-1L);
        assertThat(zipEntryNames(downloaded)).containsExactlyElementsOf(MULTI_ENTRY_NAMES);
    }

    @Test
    void cleansAbandonedAssemblyOnRestartWithoutTouchingUserFilesAndResumes(@TempDir Path temp) throws Exception {
        TransferFixture fixture = fixture(temp);
        Path original = temp.resolve("arquivo-com-residuo-de-montagem.zip");
        writeDeterministicMultiEntryZip(original, CHUNK_SIZE);
        Path destination = Files.createDirectory(temp.resolve("destination"));

        long originalSize = Files.size(original);
        String originalHash = sha256(fixture.hash, original);
        var manifest = fixture.publisher.publish(original, new NoOpProgressListener());
        TransferId transferId = new TransferId(manifest.transferId());
        List<Integer> chunkNumbers = IntStream.rangeClosed(1, manifest.totalChunks()).boxed().toList();

        ProgressListener interruptDuringAssembly = (operation, completed, total, item, totalItems) -> {
            if ("Montando".equals(operation) && item == 2) {
                throw new AssemblyInterruptedError();
            }
        };
        assertThatThrownBy(() -> fixture.downloader.download(
                transferId, destination, interruptDuringAssembly))
                .isInstanceOf(AssemblyInterruptedError.class);
        assertThat(fixture.repository.downloadedChunkNumbers()).containsExactlyElementsOf(chunkNumbers);

        Path abandonedAssembly;
        try (var files = Files.list(destination)) {
            abandonedAssembly = files
                    .filter(path -> path.getFileName().toString().startsWith(".secure-transfer-assembly-"))
                    .findFirst()
                    .orElseThrow();
        }
        Path userFile = destination.resolve("keep-me.download");
        Files.writeString(userFile, "arquivo do usuário");
        assertThat(abandonedAssembly).isRegularFile();

        WorkProperties restartedWorkProperties = new WorkProperties();
        restartedWorkProperties.setPath(temp.resolve("work"));
        TemporaryFileManager restartedTemporaryFiles = new TemporaryFileManager(restartedWorkProperties);
        assertThat(restartedTemporaryFiles.cleanupAbandonedDestinationFiles(destination)).isEqualTo(1);
        assertThat(abandonedAssembly).doesNotExist();
        assertThat(userFile).hasContent("arquivo do usuário");

        Path downloaded = fixture.downloader.download(
                transferId, destination, new NoOpProgressListener());

        assertThat(fixture.repository.downloadedChunkNumbers()).containsExactlyElementsOf(chunkNumbers);
        assertThat(downloaded.getFileName().toString()).isEqualTo(original.getFileName().toString());
        assertThat(Files.size(downloaded)).isEqualTo(originalSize);
        assertThat(sha256(fixture.hash, downloaded)).isEqualTo(originalHash);
        assertThat(Files.mismatch(original, downloaded)).isEqualTo(-1L);
        assertThat(userFile).hasContent("arquivo do usuário");
    }

    @Test
    void cleansAbandonedIncomingChunkAndPreservesValidatedChunksForResume(@TempDir Path temp) throws Exception {
        TransferFixture fixture = fixture(temp);
        Path original = temp.resolve("arquivo-com-residuo-de-download.zip");
        writeDeterministicMultiEntryZip(original, CHUNK_SIZE);
        Path destination = Files.createDirectory(temp.resolve("destination"));

        var manifest = fixture.publisher.publish(original, new NoOpProgressListener());
        TransferId transferId = new TransferId(manifest.transferId());
        ProgressListener stopAfterFirstChunk = (operation, completed, total, item, totalItems) -> {
            if ("Baixando".equals(operation) && item == 1) {
                throw new DownloadInterruptedException();
            }
        };
        assertThatThrownBy(() -> fixture.downloader.download(
                transferId, destination, stopAfterFirstChunk))
                .isInstanceOf(DownloadInterruptedException.class);

        Path downloadDirectory = temp.resolve("work/download").resolve(transferId.toString());
        Path validatedChunk = downloadDirectory.resolve(manifest.chunks().get(0).fileName());
        Path abandonedIncoming = Files.createTempFile(downloadDirectory, "chunk-", ".download");
        Path unrelatedFile = downloadDirectory.resolve("keep-me.download");
        Files.writeString(unrelatedFile, "arquivo fora do protocolo de temporários");
        assertThat(validatedChunk).isRegularFile();
        assertThat(abandonedIncoming).isRegularFile();

        try (var activeDownload = fixture.temporaryFiles.openDownloadSession(transferId)) {
            assertThat(fixture.temporaryFiles.cleanupAbandonedDownloads()).isZero();
            assertThat(abandonedIncoming).isRegularFile();
        }
        WorkProperties restartedWorkProperties = new WorkProperties();
        restartedWorkProperties.setPath(temp.resolve("work"));
        TemporaryFileManager restartedTemporaryFiles = new TemporaryFileManager(restartedWorkProperties);
        assertThat(restartedTemporaryFiles.cleanupAbandonedDownloads()).isEqualTo(1);
        assertThat(validatedChunk).isRegularFile();
        assertThat(abandonedIncoming).doesNotExist();
        assertThat(unrelatedFile).isRegularFile();

        Path downloaded = fixture.downloader.download(
                transferId, destination, new NoOpProgressListener());

        assertThat(fixture.repository.downloadedChunkNumbers()).containsExactlyElementsOf(
                IntStream.rangeClosed(1, manifest.totalChunks()).boxed().toList());
        assertThat(downloaded.getFileName().toString()).isEqualTo(original.getFileName().toString());
        assertThat(Files.mismatch(original, downloaded)).isEqualTo(-1L);
    }

    @Test
    void redownloadsChunkAfterInterruptedRemoteRead(@TempDir Path temp) throws Exception {
        TransferFixture fixture = fixture(temp);
        Path original = temp.resolve("arquivo-com-leitura-remota-interrompida.zip");
        writeDeterministicMultiEntryZip(original, CHUNK_SIZE);
        Path destination = Files.createDirectory(temp.resolve("destination"));

        long originalSize = Files.size(original);
        String originalHash = sha256(fixture.hash, original);
        var manifest = fixture.publisher.publish(original, new NoOpProgressListener());
        TransferId transferId = new TransferId(manifest.transferId());
        TransferChunk interruptedChunk = manifest.chunks().get(1);
        fixture.repository.interruptNextDownloadAfterBytes(
                interruptedChunk.number(), interruptedChunk.encryptedSize() / 2);

        assertThatThrownBy(() -> fixture.downloader.download(
                transferId, destination, new NoOpProgressListener()))
                .isInstanceOf(IOException.class);

        Path downloadDirectory = temp.resolve("work/download").resolve(transferId.toString());
        Path partialChunk = downloadDirectory.resolve(interruptedChunk.fileName());
        assertThat(partialChunk).doesNotExist();
        try (var files = Files.list(downloadDirectory)) {
            assertThat(files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".download"))
                    .toList()).isEmpty();
        }

        Path downloaded = fixture.downloader.download(
                transferId, destination, new NoOpProgressListener());

        assertThat(fixture.repository.downloadedChunkNumbers()).containsExactly(1, 2, 2);
        assertThat(downloaded.getFileName().toString()).isEqualTo(original.getFileName().toString());
        assertThat(Files.size(downloaded)).isEqualTo(originalSize);
        assertThat(sha256(fixture.hash, downloaded)).isEqualTo(originalHash);
        assertThat(Files.mismatch(original, downloaded)).isEqualTo(-1L);
        assertThat(zipEntryNames(downloaded)).containsExactlyElementsOf(MULTI_ENTRY_NAMES);
    }

    @Test
    void redownloadsChunkWithInvalidSha256BeforeResuming(@TempDir Path temp) throws Exception {
        TransferFixture fixture = fixture(temp);
        Path original = temp.resolve("arquivo-com-chunk-corrompido.zip");
        writeDeterministicMultiEntryZip(original, CHUNK_SIZE);
        Path destination = Files.createDirectory(temp.resolve("destination"));

        long originalSize = Files.size(original);
        String originalHash = sha256(fixture.hash, original);
        var manifest = fixture.publisher.publish(original, new NoOpProgressListener());
        TransferId transferId = new TransferId(manifest.transferId());
        TransferChunk corruptedChunk = manifest.chunks().get(1);
        fixture.repository.corruptNextDownload(corruptedChunk.number());

        assertThatThrownBy(() -> fixture.downloader.download(
                transferId, destination, new NoOpProgressListener()))
                .isInstanceOf(br.com.securetransfer.domain.exception.ChunkCorruptedException.class);
        assertThat(fixture.repository.downloadedChunkNumbers()).containsExactly(1, 2);
        Path corruptedCache = temp.resolve("work/download")
                .resolve(transferId.toString())
                .resolve(corruptedChunk.fileName());
        assertThat(corruptedCache).doesNotExist();

        Path downloaded = fixture.downloader.download(
                transferId, destination, new NoOpProgressListener());

        assertThat(fixture.repository.downloadedChunkNumbers()).containsExactly(1, 2, 2);
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
        var localRepository = new LocalDirectoryTransferRepositoryAdapter(storageProperties, mapper);
        var repository = new CountingTransferRepository(localRepository);
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
        return new TransferFixture(publisher, downloader, repository, hash, temporaryFiles);
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

    private static void writeDeterministicMultiEntryZip(Path path, long payloadSize) throws Exception {
        byte[] metadata = "name=secure-transfer\nversion=1\n".getBytes(StandardCharsets.UTF_8);

        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(path));
             ZipOutputStream zip = new ZipOutputStream(file)) {
            writeStoredEntry(zip, MULTI_ENTRY_METADATA_NAME, metadata);
            writeStoredDeterministicEntry(zip, MULTI_ENTRY_PAYLOAD_NAME, payloadSize);
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

    private static void writeStoredDeterministicEntry(ZipOutputStream zip, String name, long size) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setTime(0L);
        entry.setSize(size);
        entry.setCompressedSize(size);
        entry.setCrc(crc32OfDeterministicPayload(size));
        zip.putNextEntry(entry);
        writeDeterministicPayload(zip, size);
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
                                   CountingTransferRepository repository, Sha256HashAdapter hash,
                                   TemporaryFileManager temporaryFiles) {
    }

    private static final class CountingTransferRepository implements TransferRepositoryPort {
        private final TransferRepositoryPort delegate;
        private final List<Integer> downloadedChunkNumbers = new ArrayList<>();

        private CountingTransferRepository(TransferRepositoryPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public void synchronize() throws IOException {
            delegate.synchronize();
        }

        @Override
        public void publishChunk(TransferId transferId, TransferChunk chunk, Path source) throws IOException {
            delegate.publishChunk(transferId, chunk, source);
        }

        @Override
        public void publishManifest(TransferManifest manifest) throws IOException {
            delegate.publishManifest(manifest);
        }

        @Override
        public InputStream downloadChunk(TransferId transferId, TransferChunk chunk) throws IOException {
            downloadedChunkNumbers.add(chunk.number());
            InputStream downloaded = delegate.downloadChunk(transferId, chunk);
            if (corruptNextChunkNumber != null && corruptNextChunkNumber == chunk.number()) {
                corruptNextChunkNumber = null;
                byte[] content;
                try (downloaded) {
                    content = downloaded.readAllBytes();
                }
                content[0] ^= 1;
                return new ByteArrayInputStream(content);
            }
            if (interruptNextChunkNumber != null && interruptNextChunkNumber == chunk.number()) {
                interruptNextChunkNumber = null;
                return new InterruptedInputStream(downloaded, interruptAfterBytes);
            }
            return downloaded;
        }

        @Override
        public TransferManifest downloadManifest(TransferId transferId) throws IOException {
            return delegate.downloadManifest(transferId);
        }

        @Override
        public List<TransferManifest> listTransfers() throws IOException {
            return delegate.listTransfers();
        }

        @Override
        public boolean exists(TransferId transferId) {
            return delegate.exists(transferId);
        }

        private List<Integer> downloadedChunkNumbers() {
            return List.copyOf(downloadedChunkNumbers);
        }

        private Integer corruptNextChunkNumber;
        private Integer interruptNextChunkNumber;
        private long interruptAfterBytes;

        private void corruptNextDownload(int chunkNumber) {
            corruptNextChunkNumber = chunkNumber;
        }

        private void interruptNextDownloadAfterBytes(int chunkNumber, long bytes) {
            interruptNextChunkNumber = chunkNumber;
            interruptAfterBytes = bytes;
        }
    }

    private static final class InterruptedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        private InterruptedInputStream(InputStream delegate, long bytesBeforeFailure) {
            this.delegate = delegate;
            this.remaining = bytesBeforeFailure;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                throw new IOException("leitura remota interrompida");
            }
            int value = delegate.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining == 0) {
                throw new IOException("leitura remota interrompida");
            }
            int requested = (int) Math.min((long) length, remaining);
            int read = delegate.read(bytes, offset, requested);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class DownloadInterruptedException extends RuntimeException {
    }

    private static final class AssemblyInterruptedException extends RuntimeException {
    }

    private static final class AssemblyInterruptedError extends Error {
    }
}