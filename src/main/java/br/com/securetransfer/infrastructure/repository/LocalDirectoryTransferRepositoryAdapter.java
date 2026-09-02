package br.com.securetransfer.infrastructure.repository;

import br.com.securetransfer.domain.exception.StorageException;
import br.com.securetransfer.domain.exception.TransferNotFoundException;
import br.com.securetransfer.domain.model.TransferChunk;
import br.com.securetransfer.domain.model.TransferId;
import br.com.securetransfer.domain.model.TransferManifest;
import br.com.securetransfer.configuration.StorageProperties;
import br.com.securetransfer.ports.out.TransferRepositoryPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class LocalDirectoryTransferRepositoryAdapter implements TransferRepositoryPort {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalDirectoryTransferRepositoryAdapter.class);
    private static final String TRANSFERS_DIR = "transfers";
    private static final String MANIFEST = "manifest.json";
    private final Path transfersRoot;
    private final ObjectMapper objectMapper;

    public LocalDirectoryTransferRepositoryAdapter(StorageProperties properties, ObjectMapper objectMapper) {
        this.transfersRoot = properties.getPath().toAbsolutePath().normalize().resolve(TRANSFERS_DIR).normalize();
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishChunk(TransferId transferId, TransferChunk chunk, Path source) throws IOException {
        Path target = chunkPath(transferId, chunk);
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void publishManifest(TransferManifest manifest) throws IOException {
        Path transferDir = transferPath(new TransferId(manifest.transferId()));
        Files.createDirectories(transferDir);
        Path temporary = Files.createTempFile(transferDir, "manifest-", ".tmp");
        try {
            objectMapper.writeValue(temporary.toFile(), manifest);
            Files.move(temporary, transferDir.resolve(MANIFEST), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public InputStream downloadChunk(TransferId transferId, TransferChunk chunk) throws IOException {
        Path path = chunkPath(transferId, chunk);
        if (!Files.isRegularFile(path)) {
            throw new TransferNotFoundException(transferId + " / chunk " + chunk.number());
        }
        return Files.newInputStream(path);
    }

    @Override
    public TransferManifest downloadManifest(TransferId transferId) throws IOException {
        Path path = transferPath(transferId).resolve(MANIFEST);
        if (!Files.isRegularFile(path)) {
            throw new TransferNotFoundException(transferId.toString());
        }
        try {
            return objectMapper.readValue(path.toFile(), TransferManifest.class);
        } catch (JsonProcessingException exception) {
            throw new StorageException("não foi possível ler " + MANIFEST, exception);
        }
    }

    @Override
    public List<TransferManifest> listTransfers() throws IOException {
        if (!Files.isDirectory(transfersRoot)) {
            return List.of();
        }
        List<TransferManifest> manifests = new ArrayList<>();
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(transfersRoot)) {
            for (Path directory : directories) {
                Path manifest = directory.resolve(MANIFEST);
                if (!Files.isRegularFile(manifest)) {
                    continue;
                }
                try {
                    manifests.add(objectMapper.readValue(manifest.toFile(), TransferManifest.class));
                } catch (JsonProcessingException exception) {
                    LOGGER.warn("manifest ignorado por estar inválido path={}", manifest);
                }
            }
        }
        return manifests.stream()
                .sorted(Comparator.comparing(TransferManifest::createdAt).reversed())
                .toList();
    }

    @Override
    public boolean exists(TransferId transferId) {
        return Files.isRegularFile(transferPath(transferId).resolve(MANIFEST));
    }

    private Path transferPath(TransferId transferId) {
        Path path = transfersRoot.resolve(transferId.toString()).normalize();
        if (!path.startsWith(transfersRoot)) {
            throw new IllegalArgumentException("caminho de transferência inválido");
        }
        return path;
    }

    private Path chunkPath(TransferId transferId, TransferChunk chunk) {
        Path path = transferPath(transferId).resolve("chunks").resolve(chunk.fileName()).normalize();
        if (!path.startsWith(transferPath(transferId).resolve("chunks"))) {
            throw new IllegalArgumentException("nome de chunk inválido");
        }
        return path;
    }
}