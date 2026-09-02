package br.com.securetransfer.application.service;

import br.com.securetransfer.configuration.TransferProperties;
import br.com.securetransfer.domain.exception.FileTooLargeException;
import br.com.securetransfer.domain.exception.InvalidFileTypeException;
import br.com.securetransfer.domain.model.TransferChunk;
import br.com.securetransfer.domain.model.TransferId;
import br.com.securetransfer.domain.model.TransferManifest;
import br.com.securetransfer.domain.model.TransferStatus;
import br.com.securetransfer.infrastructure.crypto.SecretKeyProvider;
import br.com.securetransfer.ports.out.HashPort;
import br.com.securetransfer.ports.out.TransferRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@Service
public class PublishFileService {
    private static final Logger LOGGER = LoggerFactory.getLogger(PublishFileService.class);
    private final TransferProperties properties;
    private final HashPort hash;
    private final ChunkService chunks;
    private final TransferRepositoryPort repository;
    private final TemporaryFileManager temporaryFiles;
    private final SecretKeyProvider keyProvider;

    public PublishFileService(TransferProperties properties, HashPort hash, ChunkService chunks,
                              TransferRepositoryPort repository, TemporaryFileManager temporaryFiles,
                              SecretKeyProvider keyProvider) {
        this.properties = properties;
        this.hash = hash;
        this.chunks = chunks;
        this.repository = repository;
        this.temporaryFiles = temporaryFiles;
        this.keyProvider = keyProvider;
    }

    public TransferManifest publish(Path source, ProgressListener progress) throws IOException {
        Path input = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("arquivo inexistente: " + source);
        }
        String fileName = PathSafety.requireSafeFileName(input.getFileName().toString());
        if (!fileName.toLowerCase().endsWith(".zip")) {
            throw new InvalidFileTypeException(fileName);
        }
        long size = Files.size(input);
        if (size > properties.maxFileSizeBytes()) {
            throw new FileTooLargeException(size, properties.maxFileSizeBytes());
        }
        TransferId transferId = TransferId.newId();
        Path uploadDirectory = temporaryFiles.createUploadDirectory(transferId);
        LOGGER.info("transfer started transferId={} size={}", transferId, size);
        try {
            String originalHash;
            try (var inputStream = new BufferedInputStream(Files.newInputStream(input))) {
                originalHash = hash.sha256(inputStream);
            }
            SecretKey key = keyProvider.key();
            List<ChunkService.PreparedChunk> prepared = chunks.splitAndEncrypt(
                    input, uploadDirectory, transferId, key, progress);
            for (ChunkService.PreparedChunk preparedChunk : prepared) {
                repository.publishChunk(transferId, preparedChunk.metadata(), preparedChunk.encryptedPath());
                progress.onProgress("Publicando", preparedChunk.metadata().encryptedSize(),
                        prepared.stream().mapToLong(item -> item.metadata().encryptedSize()).sum(),
                        preparedChunk.metadata().number(), prepared.size());
            }
            List<TransferChunk> metadata = prepared.stream().map(ChunkService.PreparedChunk::metadata).toList();
            TransferManifest manifest = new TransferManifest(
                    transferId.value(), fileName, size, originalHash, properties.chunkSizeBytes(),
                    metadata.size(), new TransferManifest.EncryptionMetadata("AES/GCM/NoPadding"),
                    metadata, Instant.now(), TransferStatus.AVAILABLE);
            repository.publishManifest(manifest);
            LOGGER.info("transfer completed transferId={} size={}", transferId, size);
            return manifest;
        } finally {
            temporaryFiles.cleanup(uploadDirectory);
        }
    }
}