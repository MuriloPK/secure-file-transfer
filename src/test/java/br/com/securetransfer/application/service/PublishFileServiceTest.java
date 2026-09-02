package br.com.securetransfer.application.service;

import br.com.securetransfer.configuration.TransferProperties;
import br.com.securetransfer.domain.model.TransferChunk;
import br.com.securetransfer.infrastructure.crypto.SecretKeyProvider;
import br.com.securetransfer.ports.out.HashPort;
import br.com.securetransfer.ports.out.TransferRepositoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublishFileServiceTest {
    @Mock
    private HashPort hash;
    @Mock
    private ChunkService chunks;
    @Mock
    private TransferRepositoryPort repository;
    @Mock
    private TemporaryFileManager temporaryFiles;
    @Mock
    private SecretKeyProvider keyProvider;

    @Test
    void cleansUnpublishedTransferWhenChunkPublicationFails(@TempDir Path temp) throws Exception {
        Path source = sourceFile(temp);
        Path uploadDirectory = temp.resolve("upload");
        TransferChunk chunk = chunk();
        when(temporaryFiles.createUploadDirectory(any())).thenReturn(uploadDirectory);
        when(hash.sha256(any(InputStream.class))).thenReturn("a".repeat(64));
        when(keyProvider.key()).thenReturn(new SecretKeySpec(new byte[16], "AES"));
        when(chunks.splitAndEncrypt(any(), any(), any(), any(), any()))
                .thenReturn(List.of(new ChunkService.PreparedChunk(chunk, uploadDirectory.resolve(chunk.fileName()))));
        IOException failure = new IOException("falha simulada no upload do chunk");
        doThrow(failure).when(repository).publishChunk(any(), any(), any());

        PublishFileService publisher = publisher();

        assertThatThrownBy(() -> publisher.publish(source, (operation, completed, total, item, totalItems) -> {
        })).isSameAs(failure);

        verify(repository).cleanupUnpublishedTransfer(any());
        verify(repository, never()).publishManifest(any());
    }

    @Test
    void doesNotCleanUpWhenManifestPublicationHasStarted(@TempDir Path temp) throws Exception {
        Path source = sourceFile(temp);
        Path uploadDirectory = temp.resolve("upload");
        when(temporaryFiles.createUploadDirectory(any())).thenReturn(uploadDirectory);
        when(hash.sha256(any(InputStream.class))).thenReturn("a".repeat(64));
        when(keyProvider.key()).thenReturn(new SecretKeySpec(new byte[16], "AES"));
        when(chunks.splitAndEncrypt(any(), any(), any(), any(), any()))
                .thenReturn(List.of(new ChunkService.PreparedChunk(chunk(), uploadDirectory.resolve("part-00001.bin"))));
        IOException failure = new IOException("resultado remoto desconhecido");
        doThrow(failure).when(repository).publishManifest(any());

        PublishFileService publisher = publisher();

        assertThatThrownBy(() -> publisher.publish(source, (operation, completed, total, item, totalItems) -> {
        })).isSameAs(failure);

        verify(repository, never()).cleanupUnpublishedTransfer(any());
    }

    private PublishFileService publisher() {
        TransferProperties properties = new TransferProperties();
        return new PublishFileService(properties, hash, chunks, repository, temporaryFiles, keyProvider);
    }

    private static Path sourceFile(Path temp) throws IOException {
        Path source = temp.resolve("arquivo.zip");
        Files.write(source, new byte[]{1});
        return source;
    }

    private static TransferChunk chunk() {
        return new TransferChunk(1, 1, 1, "a".repeat(64), "bm9uY2U=", "part-00001.bin");
    }
}