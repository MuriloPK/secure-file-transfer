package br.com.securetransfer.application.service;

import br.com.securetransfer.configuration.WorkProperties;
import br.com.securetransfer.domain.model.TransferId;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

@Component
public class TemporaryFileManager {
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

    private static final class CleanupException extends RuntimeException {
        private final IOException ioException;

        private CleanupException(IOException ioException) {
            this.ioException = ioException;
        }
    }
}