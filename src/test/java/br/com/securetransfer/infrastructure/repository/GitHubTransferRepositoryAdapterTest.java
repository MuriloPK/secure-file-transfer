package br.com.securetransfer.infrastructure.repository;

import br.com.securetransfer.configuration.StorageProperties;
import br.com.securetransfer.domain.exception.StorageException;
import br.com.securetransfer.domain.model.TransferChunk;
import br.com.securetransfer.domain.model.TransferId;
import br.com.securetransfer.domain.model.TransferManifest;
import br.com.securetransfer.domain.model.TransferStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubTransferRepositoryAdapterTest {
    @Test
    void publishesChunksBeforeManifestAndSynchronizesASecondClone(@TempDir Path temp) throws Exception {
        Path remote = createRemoteRepository(temp);
        StorageProperties firstProperties = properties(remote, temp.resolve("clone-a"));
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var first = new GitHubTransferRepositoryAdapter(firstProperties, mapper);
        TransferId transferId = TransferId.newId();
        byte[] content = "chunk-data".getBytes();
        Path source = temp.resolve("chunk.bin");
        Files.write(source, content);
        TransferChunk chunk = new TransferChunk(1, content.length, content.length,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bm9uY2U=", "part-00001.bin");

        first.publishChunk(transferId, chunk, source);
        assertThat(first.listTransfers()).isEmpty();

        TransferManifest manifest = new TransferManifest(transferId.value(), "arquivo.zip", content.length,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                content.length, 1, new TransferManifest.EncryptionMetadata("AES/GCM/NoPadding"),
                List.of(chunk), Instant.now(), TransferStatus.AVAILABLE);
        first.publishManifest(manifest);

        StorageProperties secondProperties = properties(remote, temp.resolve("clone-b"));
        var second = new GitHubTransferRepositoryAdapter(secondProperties, mapper);
        second.synchronize();
        assertThat(second.listTransfers()).extracting(TransferManifest::transferId)
                .containsExactly(transferId.value());
        try (InputStream downloaded = second.downloadChunk(transferId, chunk)) {
            assertThat(downloaded.readAllBytes()).containsExactly(content);
        }
    }

    @Test
    void rejectsBlobAboveConfiguredLimitWithoutExposingRemoteDetails(@TempDir Path temp) throws Exception {
        StorageProperties properties = properties(temp.resolve("remote.git"), temp.resolve("clone"));
        properties.getGit().setMaxBlobSize(DataSize.ofBytes(3));
        var adapter = new GitHubTransferRepositoryAdapter(properties,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        Path source = temp.resolve("chunk.bin");
        Files.write(source, new byte[]{1, 2, 3, 4});
        TransferChunk chunk = new TransferChunk(1, 4, 4,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bm9uY2U=", "part-00001.bin");

        assertThatThrownBy(() -> adapter.publishChunk(TransferId.newId(), chunk, source))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("limite configurado")
                .hasMessageNotContaining("remote.git");
    }

    @Test
    void rejectsCredentialsEmbeddedInRemoteUrl(@TempDir Path temp) {
        StorageProperties properties = properties(temp.resolve("clone"), temp.resolve("clone"));
        properties.getGit().setRemote("https://user:secret@github.com/org/private.git");

        assertThatThrownBy(() -> new GitHubTransferRepositoryAdapter(properties,
                new ObjectMapper().registerModule(new JavaTimeModule())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("não pode conter");
    }

    @Test
    void acceptsStandardSshRemoteSyntax(@TempDir Path temp) {
        StorageProperties properties = properties(temp.resolve("clone"), temp.resolve("clone"));
        properties.getGit().setRemote("git@github.com:org/private.git");

        assertThat(new GitHubTransferRepositoryAdapter(properties,
                new ObjectMapper().registerModule(new JavaTimeModule()))).isNotNull();
    }

    private static StorageProperties properties(Path remote, Path clone) {
        StorageProperties properties = new StorageProperties();
        properties.setType(StorageProperties.StorageType.GIT);
        properties.setPath(clone);
        properties.getGit().setRemote(remote.toAbsolutePath().toString());
        properties.getGit().setBranch("main");
        return properties;
    }

    private static Path createRemoteRepository(Path temp) throws Exception {
        Path remote = temp.resolve("remote.git");
        Path seed = temp.resolve("seed");
        run(temp, "git", "init", "--bare", remote.toString());
        run(temp, "git", "init", seed.toString());
        run(seed, "git", "checkout", "-b", "main");
        run(seed, "git", "config", "user.name", "test");
        run(seed, "git", "config", "user.email", "test@example.invalid");
        Files.writeString(seed.resolve("README"), "seed");
        run(seed, "git", "add", "README");
        run(seed, "git", "commit", "-m", "seed");
        run(seed, "git", "remote", "add", "origin", remote.toString());
        run(seed, "git", "push", "-u", "origin", "main");
        return remote;
    }

    private static void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readAllBytes());
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("command failed: " + String.join(" ", command) + "\n" + output);
        }
    }
}