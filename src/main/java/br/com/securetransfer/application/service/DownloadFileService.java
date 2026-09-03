package br.com.securetransfer.application.service;

import br.com.securetransfer.domain.exception.ChunkCorruptedException;
import br.com.securetransfer.domain.exception.InsufficientDiskSpaceException;
import br.com.securetransfer.domain.exception.TransferIntegrityException;
import br.com.securetransfer.domain.model.TransferChunk;
import br.com.securetransfer.domain.model.TransferId;
import br.com.securetransfer.domain.model.TransferManifest;
import br.com.securetransfer.infrastructure.crypto.SecretKeyProvider;
import br.com.securetransfer.ports.out.EncryptionPort;
import br.com.securetransfer.ports.out.HashPort;
import br.com.securetransfer.ports.out.TransferRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

@Service
public class DownloadFileService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadFileService.class);
    private final TransferRepositoryPort repository;
    private final TransferManifestValidator validator;
    private final TemporaryFileManager temporaryFiles;
    private final HashPort hash;
    private final EncryptionPort encryption;
    private final SecretKeyProvider keyProvider;

    public DownloadFileService(TransferRepositoryPort repository, TransferManifestValidator validator,
                               TemporaryFileManager temporaryFiles, HashPort hash, EncryptionPort encryption,
                               SecretKeyProvider keyProvider) {
        this.repository = repository;
        this.validator = validator;
        this.temporaryFiles = temporaryFiles;
        this.hash = hash;
        this.encryption = encryption;
        this.keyProvider = keyProvider;
    }

    public Path download(TransferId transferId, Path destinationDirectory, ProgressListener progress) throws IOException {
        repository.synchronize();
        TransferManifest manifest = repository.downloadManifest(transferId);
        validator.validate(manifest);
        Path destination = PathSafety.requireExistingDirectory(destinationDirectory);
        if (Files.getFileStore(destination).getUsableSpace() < manifest.originalSize()) {
            throw new InsufficientDiskSpaceException("espaço insuficiente no destino");
        }
        temporaryFiles.cleanupAbandonedDestinationFiles(destination);
        try (TemporaryFileManager.DownloadSession downloadSession =
                     temporaryFiles.openDownloadSession(transferId)) {
            Path downloadDirectory = downloadSession.directory();
            LOGGER.info("transfer download started transferId={}", transferId);
            for (TransferChunk chunk : manifest.chunks()) {
                Path cached = downloadDirectory.resolve(chunk.fileName()).normalize();
                boolean cachedChunkIsValid;
                try {
                    cachedChunkIsValid = isValidCachedChunk(cached, chunk);
                } catch (IOException exception) {
                    Files.deleteIfExists(cached);
                    throw exception;
                }
                if (!cachedChunkIsValid) {
                    Files.deleteIfExists(cached);
                    Path incoming = Files.createTempFile(downloadDirectory, "chunk-", ".download");
                    boolean promoted = false;
                    try {
                        try (InputStream remote = repository.downloadChunk(transferId, chunk);
                             var output = Files.newOutputStream(incoming)) {
                            remote.transferTo(output);
                        }
                        if (!isValidCachedChunk(incoming, chunk)) {
                            throw new ChunkCorruptedException(chunk.number());
                        }
                        moveAtomically(incoming, cached);
                        promoted = true;
                    } finally {
                        if (!promoted) {
                            Files.deleteIfExists(incoming);
                        }
                    }
                }
                progress.onProgress("Baixando", chunk.encryptedSize(),
                        manifest.chunks().stream().mapToLong(TransferChunk::encryptedSize).sum(),
                        chunk.number(), manifest.totalChunks());
            }
            Path assembled = temporaryFiles.createAssemblyFile(destination);
            try {
                assemble(manifest, downloadDirectory, assembled, progress);
                String finalHash;
                try (var input = new BufferedInputStream(Files.newInputStream(assembled))) {
                    finalHash = hash.sha256(input);
                }
                if (!manifest.originalSha256().equals(finalHash) || Files.size(assembled) != manifest.originalSize()) {
                    throw new TransferIntegrityException("SHA-256 final não corresponde ao SHA-256 original");
                }
                Path finalPath = destination.resolve(PathSafety.requireSafeFileName(manifest.fileName())).normalize();
                if (!finalPath.startsWith(destination)) {
                    throw new TransferIntegrityException("destino final fora do diretório autorizado");
                }
                try {
                    moveAtomicallyWithoutReplace(assembled, finalPath);
                } catch (FileAlreadyExistsException exception) {
                    throw new TransferIntegrityException("arquivo de destino já existe: " + finalPath);
                }
                LOGGER.info("transfer download completed transferId={} path={}", transferId, finalPath);
                downloadSession.close();
                temporaryFiles.cleanup(downloadDirectory);
                return finalPath;
            } catch (RuntimeException | IOException exception) {
                Files.deleteIfExists(assembled);
                throw exception;
            }
        } catch (RuntimeException | IOException exception) {
            LOGGER.error("transfer failed transferId={}", transferId, exception);
            throw exception;
        }
    }

    private void assemble(TransferManifest manifest, Path downloadDirectory, Path assembled,
                          ProgressListener progress) throws IOException {
        SecretKey key = keyProvider.key();
        try (var output = new BufferedOutputStream(Files.newOutputStream(assembled))) {
            for (TransferChunk chunk : manifest.chunks()) {
                Path cached = downloadDirectory.resolve(chunk.fileName()).normalize();
                long before = Files.size(assembled);
                try (var input = new BufferedInputStream(Files.newInputStream(cached))) {
                    encryption.decrypt(input, output, Base64.getDecoder().decode(chunk.nonce()), key);
                }
                output.flush();
                long written = Files.size(assembled) - before;
                if (written != chunk.originalSize()) {
                    throw new TransferIntegrityException("tamanho descriptografado inválido no chunk " + chunk.number());
                }
                progress.onProgress("Montando", written, manifest.originalSize(), chunk.number(), manifest.totalChunks());
            }
        }
    }

    private boolean isValidCachedChunk(Path path, TransferChunk chunk) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) != chunk.encryptedSize()) {
            return false;
        }
        try (var input = new BufferedInputStream(Files.newInputStream(path))) {
            return chunk.sha256().equals(hash.sha256(input));
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void moveAtomicallyWithoutReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }
}