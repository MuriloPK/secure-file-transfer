package br.com.securetransfer.application.service;

import br.com.securetransfer.configuration.WorkProperties;
import br.com.securetransfer.domain.model.TransferId;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;

@Component
public class TemporaryFileManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(TemporaryFileManager.class);
    private static final String DOWNLOAD_LOCK_FILE = ".download.lock";
    private static final String INCOMING_CHUNK_PREFIX = "chunk-";
    private static final String INCOMING_CHUNK_SUFFIX = ".download";
    private static final String ASSEMBLED_FILE_PREFIX = ".secure-transfer-assembly-";
    private static final String ASSEMBLED_FILE_SUFFIX = ".download";
    private static final String DESTINATION_LOCK_PREFIX = ".secure-transfer-destination-";
    private static final String DESTINATION_LOCK_SUFFIX = ".lock";
    private final Path uploadRoot;
    private final Path downloadRoot;

    public TemporaryFileManager(WorkProperties properties) {
        Path root = properties.getPath().toAbsolutePath().normalize();
        this.uploadRoot = root.resolve("upload").normalize();
        this.downloadRoot = root.resolve("download").normalize();
    }

    public Path createUploadDirectory(TransferId transferId) throws IOException {
        return Files.createDirectories(uploadRoot.resolve(transferId.toString()).normalize());
    }

    public Path createDownloadDirectory(TransferId transferId) throws IOException {
        return Files.createDirectories(downloadRoot.resolve(transferId.toString()).normalize());
    }

    public Path createAssemblyFile(Path destination) throws IOException {
        return Files.createTempFile(destination, ASSEMBLED_FILE_PREFIX, ASSEMBLED_FILE_SUFFIX);
    }

    /**
     * Removes download files that cannot be resumed after an abrupt shutdown.
     *
     * <p>Validated chunks are deliberately not touched: they are the cache used
     * to resume a later download. A lock prevents this recovery pass from
     * changing a directory that another process is currently using.</p>
     */
    @PostConstruct
    public void cleanupAbandonedDownloadsOnStartup() {
        try {
            int removedFiles = cleanupAbandonedDownloads();
            if (removedFiles > 0) {
                LOGGER.info("abandoned download cleanup removed files={}", removedFiles);
            }
        } catch (IOException exception) {
            LOGGER.warn("abandoned download cleanup failed; resumable chunks were preserved", exception);
        }
    }

    public int cleanupAbandonedDownloads() throws IOException {
        if (!Files.isDirectory(downloadRoot)) {
            return 0;
        }

        int removedFiles = 0;
        try (var directories = Files.list(downloadRoot)) {
            for (Path directory : directories.toList()) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(directory)) {
                    continue;
                }
                removedFiles += cleanupAbandonedDownloadDirectory(directory);
            }
        }
        return removedFiles;
    }

    public int cleanupAbandonedDestinationFiles(Path destination) throws IOException {
        if (!Files.isDirectory(destination)) {
            return 0;
        }

        Path lockPath = destinationLockPath(destination);
        try (FileChannel channel = openLockChannel(lockPath)) {
            FileLock lock = tryLock(channel);
            if (lock == null) {
                return 0;
            }
            try {
                return cleanupAbandonedDestinationFilesLocked(destination);
            } finally {
                lock.release();
            }
        }
    }

    int cleanupAbandonedDestinationFiles(DestinationSession session) throws IOException {
        return cleanupAbandonedDestinationFilesLocked(session.destination);
    }

    public DestinationSession openDestinationSession(Path destination) throws IOException {
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        Path lockPath = destinationLockPath(normalizedDestination);
        FileChannel channel = openLockChannel(lockPath);
        try {
            FileLock lock = acquireLock(channel);
            return new DestinationSession(normalizedDestination, channel, lock);
        } catch (IOException | RuntimeException exception) {
            channel.close();
            throw exception;
        }
    }

    private int cleanupAbandonedDestinationFilesLocked(Path destination) throws IOException {
        int removedFiles = 0;
        try (var files = Files.list(destination)) {
            for (Path file : files.toList()) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        && isAbandonedAssemblyFile(file)
                        && Files.deleteIfExists(file)) {
                    removedFiles++;
                }
            }
        }
        return removedFiles;
    }

    public DownloadSession openDownloadSession(TransferId transferId) throws IOException {
        Path directory = createDownloadDirectory(transferId);
        Path lockPath = directory.resolve(DOWNLOAD_LOCK_FILE);
        rejectSymbolicLink(lockPath);
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = tryLock(channel);
            if (lock == null) {
                throw new IOException("download já está em andamento: " + transferId);
            }
            return new DownloadSession(directory, lockPath, channel, lock);
        } catch (IOException | RuntimeException exception) {
            channel.close();
            throw exception;
        }
    }

    public void cleanup(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new CleanupException(exception);
                }
            });
        } catch (CleanupException exception) {
            throw exception.ioException;
        }
    }

    private int cleanupAbandonedDownloadDirectory(Path directory) throws IOException {
        Path lockPath = directory.resolve(DOWNLOAD_LOCK_FILE);
        rejectSymbolicLink(lockPath);
        try (FileChannel channel = FileChannel.open(
                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = tryLock(channel);
            if (lock == null) {
                return 0;
            }
            int removedFiles = 0;
            try {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.toList()) {
                        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                && isAbandonedIncomingChunk(path)) {
                            if (Files.deleteIfExists(path)) {
                                removedFiles++;
                            }
                        }
                    }
                }
            } finally {
                lock.release();
            }
            Files.deleteIfExists(lockPath);
            return removedFiles;
        }
    }

    private static boolean isAbandonedIncomingChunk(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.startsWith(INCOMING_CHUNK_PREFIX)
                && fileName.endsWith(INCOMING_CHUNK_SUFFIX);
    }

    private static boolean isAbandonedAssemblyFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.startsWith(ASSEMBLED_FILE_PREFIX)
                && fileName.endsWith(ASSEMBLED_FILE_SUFFIX);
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException exception) {
            return null;
        }
    }

    private static FileLock acquireLock(FileChannel channel) throws IOException {
        while (true) {
            FileLock lock = tryLock(channel);
            if (lock != null) {
                return lock;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("espera pelo bloqueio de destino interrompida", exception);
            }
        }
    }

    private FileChannel openLockChannel(Path lockPath) throws IOException {
        Files.createDirectories(lockPath.getParent());
        rejectSymbolicLink(lockPath);
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    private Path destinationLockPath(Path destination) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(destination.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível", exception);
        }
        return downloadRoot.resolve(DESTINATION_LOCK_PREFIX
                + HexFormat.of().formatHex(digest) + DESTINATION_LOCK_SUFFIX);
    }

    private static void rejectSymbolicLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("arquivo de bloqueio simbólico não é permitido: " + path);
        }
    }

    public static final class DestinationSession implements AutoCloseable {
        private final Path destination;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        private DestinationSession(Path destination, FileChannel channel, FileLock lock) {
            this.destination = destination;
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                lock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    public static final class DownloadSession implements AutoCloseable {
        private final Path directory;
        private final Path lockPath;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        private DownloadSession(Path directory, Path lockPath, FileChannel channel, FileLock lock) {
            this.directory = directory;
            this.lockPath = lockPath;
            this.channel = channel;
            this.lock = lock;
        }

        public Path directory() {
            return directory;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                lock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            try {
                Files.deleteIfExists(lockPath);
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class CleanupException extends RuntimeException {
        private final IOException ioException;

        private CleanupException(IOException ioException) {
            this.ioException = ioException;
        }
    }
}