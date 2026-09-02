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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitHubTransferRepositoryAdapterTest {
    private static final String LFS_CONTRACT_ENABLED = "SECURE_TRANSFER_GIT_LFS_CONTRACT_TEST";
    private static final String LFS_CONTRACT_REMOTE = "SECURE_TRANSFER_GIT_LFS_TEST_REMOTE";
    private static final String LFS_CONTRACT_BRANCH = "SECURE_TRANSFER_GIT_LFS_TEST_BRANCH";
    private static final String LFS_CONTRACT_CHUNK_BYTES = "SECURE_TRANSFER_GIT_LFS_TEST_CHUNK_BYTES";
    private static final String LFS_CONTRACT_MIN_CHUNK_BYTES =
            "SECURE_TRANSFER_GIT_LFS_TEST_MIN_CHUNK_BYTES";
    private static final String LFS_CONTRACT_RETAIN_TRANSFER =
            "SECURE_TRANSFER_GIT_LFS_TEST_RETAIN_TRANSFER";
    private static final long DEFAULT_MIN_CHUNK_BYTES = 100L * 1024 * 1024 + 1;

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
    void cleansOnlyTheRequestedPublishedTransfer(@TempDir Path temp) throws Exception {
        Path remote = createRemoteRepository(temp);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var adapter = new GitHubTransferRepositoryAdapter(properties(remote, temp.resolve("clone")), mapper);
        adapter.synchronize();

        TransferId retainedTransfer = TransferId.newId();
        TransferId transferToClean = TransferId.newId();
        byte[] retainedContent = "retain-me".getBytes();
        byte[] cleanupContent = "clean-me".getBytes();
        TransferChunk retainedChunk = chunk(retainedContent, "part-00001.bin");
        TransferChunk cleanupChunk = chunk(cleanupContent, "part-00001.bin");
        Path retainedSource = temp.resolve("retained.bin");
        Path cleanupSource = temp.resolve("cleanup.bin");
        Files.write(retainedSource, retainedContent);
        Files.write(cleanupSource, cleanupContent);

        adapter.publishChunk(retainedTransfer, retainedChunk, retainedSource);
        adapter.publishManifest(manifest(retainedTransfer, "retained.zip", retainedContent, retainedChunk));
        adapter.publishChunk(transferToClean, cleanupChunk, cleanupSource);
        adapter.publishManifest(manifest(transferToClean, "cleanup.zip", cleanupContent, cleanupChunk));

        adapter.cleanupPublishedTransfer(transferToClean);

        assertThat(adapter.exists(transferToClean)).isFalse();
        assertThat(adapter.exists(retainedTransfer)).isTrue();
        assertThat(adapter.listTransfers()).extracting(TransferManifest::transferId)
                .containsExactly(retainedTransfer.value());
    }

    @Test
    void concurrentClonesDoNotExposeManifestFromRejectedPushAndCanRetry(@TempDir Path temp) throws Exception {
        Path remote = createRemoteRepository(temp);
        Path cloneA = temp.resolve("clone-a");
        Path cloneB = temp.resolve("clone-b");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var first = new GitHubTransferRepositoryAdapter(properties(remote, cloneA), mapper);
        var second = new GitHubTransferRepositoryAdapter(properties(remote, cloneB), mapper);
        first.synchronize();
        second.synchronize();

        TransferId firstTransfer = TransferId.newId();
        TransferId secondTransfer = TransferId.newId();
        byte[] firstContent = "first-clone".getBytes();
        byte[] secondContent = "second-clone".getBytes();
        Path firstSource = temp.resolve("first.bin");
        Path secondSource = temp.resolve("second.bin");
        Files.write(firstSource, firstContent);
        Files.write(secondSource, secondContent);
        TransferChunk firstChunk = chunk(firstContent, "part-00001.bin");
        TransferChunk secondChunk = chunk(secondContent, "part-00001.bin");
        TransferManifest firstManifest = manifest(firstTransfer, "first.zip", firstContent, firstChunk);
        TransferManifest secondManifest = manifest(secondTransfer, "second.zip", secondContent, secondChunk);

        first.publishChunk(firstTransfer, firstChunk, firstSource);
        second.synchronize();
        second.publishChunk(secondTransfer, secondChunk, secondSource);

        Path pushBarrier = temp.resolve("push-barrier");
        Files.createDirectory(pushBarrier);
        installFirstPushBarrier(cloneA, pushBarrier, "clone-a", "clone-b");
        installFirstPushBarrier(cloneB, pushBarrier, "clone-b", "clone-a");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PublicationResult> firstResult = executor.submit(
                    () -> publishManifest(first, firstTransfer, firstManifest));
            Future<PublicationResult> secondResult = executor.submit(
                    () -> publishManifest(second, secondTransfer, secondManifest));
            PublicationResult firstPublication = firstResult.get();
            PublicationResult secondPublication = secondResult.get();

            assertThat(List.of(firstPublication.success(), secondPublication.success()))
                    .containsExactlyInAnyOrder(true, false);
            PublicationResult rejected = firstPublication.success() ? secondPublication : firstPublication;
            assertThat(rejected.failure())
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("conflito no repositório Git")
                    .hasMessageContaining("sincronize o clone")
                    .hasMessageContaining("antes de tentar novamente");
            assertThat((rejected.adapter()).listTransfers()).isEmpty();

            rejected.adapter().synchronize();
            rejected.adapter().publishManifest(rejected.manifest());
        } finally {
            executor.shutdownNow();
        }

        var observer = new GitHubTransferRepositoryAdapter(properties(remote, temp.resolve("observer")), mapper);
        observer.synchronize();
        assertThat(observer.listTransfers()).extracting(TransferManifest::transferId)
                .containsExactlyInAnyOrder(firstTransfer.value(), secondTransfer.value());
    }

    @Test
    void rejectsPublicationFromCloneWithLocalChangesWithoutDiscardingThem(@TempDir Path temp) throws Exception {
        Path remote = createRemoteRepository(temp);
        Path clone = temp.resolve("clone");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var adapter = new GitHubTransferRepositoryAdapter(properties(remote, clone), mapper);
        adapter.synchronize();

        String localChange = "manual change that must survive a rejected publication";
        Files.writeString(clone.resolve("README"), localChange);
        TransferId transferId = TransferId.newId();
        byte[] content = "chunk-data".getBytes();
        TransferChunk chunk = chunk(content, "part-00001.bin");
        TransferManifest manifest = manifest(transferId, "arquivo.zip", content, chunk);

        assertThatThrownBy(() -> adapter.publishManifest(manifest))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("alterações locais")
                .hasMessageContaining("antes de publicar");
        assertThat(Files.readString(clone.resolve("README"))).isEqualTo(localChange);
        assertThat(adapter.listTransfers()).isEmpty();
        assertThat(runCapture(clone, "git", "status", "--porcelain"))
                .contains(" M README");
    }

    @Test
    void preservesLocalChangeCreatedDuringRejectedPublicationAndRemovesGeneratedFiles(@TempDir Path temp)
            throws Exception {
        Path remote = createRemoteRepository(temp);
        Path clone = temp.resolve("clone");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var adapter = new GitHubTransferRepositoryAdapter(properties(remote, clone), mapper);
        adapter.synchronize();

        String localChange = "manual change created while publication was in progress";
        installPushFailureWithConcurrentEdit(clone, clone.resolve("README"), localChange);
        TransferId transferId = TransferId.newId();
        byte[] content = "chunk-data".getBytes();
        TransferChunk chunk = chunk(content, "part-00001.bin");
        TransferManifest manifest = manifest(transferId, "arquivo.zip", content, chunk);

        assertThatThrownBy(() -> adapter.publishManifest(manifest))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("conflito no repositório Git ao tentar enviar alterações ao repositório Git")
                .hasMessageContaining("rollback concluído")
                .hasMessageContaining("alterações locais detectadas durante a publicação")
                .hasMessageContaining("preservadas no clone");
        assertThat(Files.readString(clone.resolve("README"))).isEqualTo(localChange);
        assertThat(clone.resolve("transfers").resolve(transferId.toString()).resolve("manifest.json"))
                .doesNotExist();
        assertThat(adapter.listTransfers()).isEmpty();
        assertThat(runCapture(clone, "git", "status", "--porcelain"))
                .contains(" M README")
                .doesNotContain(transferId.toString());
    }

    @Test
    void preservesLocalChangeToGeneratedManifestAndRemovesRejectedCommit(@TempDir Path temp) throws Exception {
        Path remote = createRemoteRepository(temp);
        Path clone = temp.resolve("clone");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var adapter = new GitHubTransferRepositoryAdapter(properties(remote, clone), mapper);
        adapter.synchronize();

        TransferId transferId = TransferId.newId();
        byte[] content = "chunk-data".getBytes();
        TransferChunk chunk = chunk(content, "part-00001.bin");
        TransferManifest manifest = manifest(transferId, "arquivo.zip", content, chunk);
        Path manifestPath = clone.resolve("transfers").resolve(transferId.toString()).resolve("manifest.json");
        String localChange = "manual manifest edit that must survive the rejected publication";
        installPushFailureWithConcurrentEdit(clone, manifestPath, localChange);

        assertThatThrownBy(() -> adapter.publishManifest(manifest))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("rollback concluído")
                .hasMessageContaining("alterações locais")
                .hasMessageContaining("preservadas no clone")
                .hasMessageContaining("faça commit ou descarte-as");
        assertThat(Files.readString(manifestPath)).isEqualTo(localChange);
        assertThat(adapter.listTransfers()).isEmpty();
        assertThat(runCapture(clone, "git", "log", "-1", "--pretty=%s")).isEqualTo("seed\n");
        assertThat(runCapture(clone, "git", "status", "--porcelain", "--untracked-files=all"))
                .contains("?? transfers/" + transferId + "/manifest.json");
        assertThat(runCapture(remote, "git", "log", "-1", "--pretty=%s", "main")).isEqualTo("seed\n");
    }

    @Test
    void preservesLocalChangeToGeneratedLfsChunkAndRemovesRejectedCommit(@TempDir Path temp) throws Exception {
        Path remote = createRemoteRepository(temp);
        Path clone = temp.resolve("clone");
        StorageProperties properties = propertiesWithLfs(remote, clone);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var adapter = new GitHubTransferRepositoryAdapter(properties, mapper);
        adapter.synchronize();

        TransferId transferId = TransferId.newId();
        byte[] content = "chunk-data".getBytes();
        TransferChunk chunk = chunk(content, "part-00001.bin");
        Path source = temp.resolve("chunk.bin");
        Files.write(source, content);
        Path chunkPath = clone.resolve("transfers").resolve(transferId.toString())
                .resolve("chunks").resolve(chunk.fileName());
        String localChange = "manual chunk edit that must survive the rejected publication";
        installRemotePushFailureWithConcurrentEdit(remote, chunkPath, localChange);

        assertThatThrownBy(() -> adapter.publishChunk(transferId, chunk, source))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("rollback concluído")
                .hasMessageContaining("alterações locais")
                .hasMessageContaining("preservadas no clone")
                .hasMessageContaining("faça commit ou descarte-as");
        assertThat(Files.readString(chunkPath)).isEqualTo(localChange);
        assertThat(clone.resolve(".gitattributes")).doesNotExist();
        assertThat(adapter.listTransfers()).isEmpty();
        assertThat(runCapture(clone, "git", "log", "-1", "--pretty=%s")).isEqualTo("seed\n");
        assertThat(runCapture(clone, "git", "status", "--porcelain", "--untracked-files=all"))
                .contains("?? transfers/" + transferId + "/chunks/" + chunk.fileName())
                .doesNotContain(".gitattributes");
        assertThat(runCapture(remote, "git", "log", "-1", "--pretty=%s", "main")).isEqualTo("seed\n");
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
    void publishesLargeChunksAsLfsPointersAndDownloadsThemFromAnotherClone(@TempDir Path temp) throws Exception {
        Path remote = createRemoteRepository(temp);
        StorageProperties firstProperties = properties(remote, temp.resolve("clone-a"));
        firstProperties.getGit().setMaxBlobSize(DataSize.ofBytes(3));
        firstProperties.getGit().setLargeBlobStrategy(StorageProperties.LargeBlobStrategy.LFS);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var first = new GitHubTransferRepositoryAdapter(firstProperties, mapper);
        TransferId transferId = TransferId.newId();
        byte[] content = "large".getBytes();
        Path source = temp.resolve("chunk.bin");
        Files.write(source, content);
        TransferChunk chunk = new TransferChunk(1, content.length, content.length,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bm9uY2U=", "part-00001.bin");

        first.publishChunk(transferId, chunk, source);

        Path checkedInChunk = firstProperties.getPath().resolve("transfers")
                .resolve(transferId.toString()).resolve("chunks").resolve(chunk.fileName());
        assertThat(Files.readAllBytes(checkedInChunk)).containsExactly(content);
        assertThat(runCapture(firstProperties.getPath(), "git", "show", "HEAD:"
                + "transfers/" + transferId + "/chunks/" + chunk.fileName()))
                .startsWith("version https://git-lfs.github.com/spec/v1");

        TransferManifest manifest = new TransferManifest(transferId.value(), "arquivo.zip", content.length,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                content.length, 1, new TransferManifest.EncryptionMetadata("AES/GCM/NoPadding"),
                List.of(chunk), Instant.now(), TransferStatus.AVAILABLE);
        first.publishManifest(manifest);

        var second = new GitHubTransferRepositoryAdapter(
                propertiesWithLfs(remote, temp.resolve("clone-b")), mapper);
        second.synchronize();
        try (InputStream downloaded = second.downloadChunk(transferId, chunk)) {
            assertThat(downloaded.readAllBytes()).containsExactly(content);
        }
    }

    @Test
    void verifiesLargeLfsTransferAgainstHostedRemote(@TempDir Path temp) throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getenv(LFS_CONTRACT_ENABLED)),
                "contrato Git LFS hospedado desabilitado");
        String remote = requiredEnvironment(LFS_CONTRACT_REMOTE);
        String branch = environmentOrDefault(LFS_CONTRACT_BRANCH, "main");
        long minimumChunkBytes = configuredMinimumChunkBytes();
        long chunkBytes = configuredChunkBytes();
        assumeTrue(chunkBytes >= minimumChunkBytes,
                "o tamanho do chunk do contrato deve ser pelo menos o mínimo configurado");

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Path source = temp.resolve("hosted-lfs-chunk.bin");
        writeDeterministicContent(source, chunkBytes);
        TransferId transferId = TransferId.newId();
        TransferChunk chunk = new TransferChunk(1, chunkBytes, chunkBytes,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bm9uY2U=", "part-00001.bin");
        StorageProperties firstProperties = hostedLfsProperties(remote, branch, temp.resolve("clone-a"));
        var first = new GitHubTransferRepositoryAdapter(firstProperties, mapper);

        boolean retainTransfer = configuredRetention();
        try {
            runContractStage("authentication and repository access", first::synchronize);
            runContractStage("LFS pointer publication", () -> {
                first.publishChunk(transferId, chunk, source);
                Path checkedInChunk = firstProperties.getPath().resolve("transfers")
                        .resolve(transferId.toString()).resolve("chunks").resolve(chunk.fileName());
                assertThat(Files.size(checkedInChunk)).isEqualTo(chunkBytes);
                assertThat(runCapture(firstProperties.getPath(), "git", "show", "HEAD:"
                        + "transfers/" + transferId + "/chunks/" + chunk.fileName()))
                        .startsWith("version https://git-lfs.github.com/spec/v1");
            });

            TransferManifest manifest = new TransferManifest(transferId.value(), "arquivo.zip", chunkBytes,
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    chunkBytes, 1, new TransferManifest.EncryptionMetadata("AES/GCM/NoPadding"),
                    List.of(chunk), Instant.now(), TransferStatus.AVAILABLE);
            runContractStage("manifest publication", () -> first.publishManifest(manifest));

            var second = new GitHubTransferRepositoryAdapter(
                    hostedLfsProperties(remote, branch, temp.resolve("clone-b")), mapper);
            runContractStage("listing", () -> {
                second.synchronize();
                assertThat(second.listTransfers()).extracting(TransferManifest::transferId)
                        .contains(transferId.value());
            });
            runContractStage("download", () -> {
                Path downloaded = temp.resolve("downloaded.bin");
                try (InputStream input = second.downloadChunk(transferId, chunk);
                     OutputStream output = Files.newOutputStream(downloaded)) {
                    input.transferTo(output);
                }
                assertThat(Files.size(downloaded)).isEqualTo(chunkBytes);
                assertThat(Files.mismatch(source, downloaded)).isEqualTo(-1L);
            });
            System.out.printf("Git LFS contract passed: stages=authentication, pointer-publication, "
                    + "manifest-publication, listing, download; chunkBytes=%d; minimumChunkBytes=%d; "
                    + "transferId=%s%n", chunkBytes, minimumChunkBytes, transferId);
        } finally {
            cleanupContractTransfer(first, transferId, retainTransfer);
        }
    }

    private static boolean configuredRetention() {
        String value = System.getenv(LFS_CONTRACT_RETAIN_TRANSFER);
        if (value == null || value.isBlank()) {
            return false;
        }
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(LFS_CONTRACT_RETAIN_TRANSFER + " deve ser true ou false");
        }
        return Boolean.parseBoolean(value);
    }

    private static void cleanupContractTransfer(GitHubTransferRepositoryAdapter adapter, TransferId transferId,
                                                boolean retainTransfer) {
        if (retainTransfer) {
            System.out.printf("Git LFS contract transfer retained for inspection: transferId=%s%n", transferId);
            return;
        }
        try {
            adapter.cleanupPublishedTransfer(transferId);
            System.out.printf("Git LFS contract transfer cleaned: transferId=%s%n", transferId);
        } catch (Exception | AssertionError cleanupFailure) {
            System.err.printf("WARNING: Git LFS contract cleanup failed; transferId=%s; "
                    + "remove only transfers/%s after inspection: %s%n",
                    transferId, transferId, cleanupFailure.getMessage());
        }
    }

    private static void runContractStage(String stage, ContractOperation operation) throws Exception {
        try {
            operation.run();
        } catch (Exception | AssertionError failure) {
            AssertionError stageFailure = new AssertionError("Git LFS contract stage failed: " + stage);
            stageFailure.initCause(failure);
            throw stageFailure;
        }
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
    void reportsHostedAuthenticationAndAvailabilityFailuresWithoutProviderResponse() {
        String providerResponse = "batch response: Authentication required: "
                + "https://github.example.invalid/org/repo.git (provider-secret)";
        String authenticationMessage = GitHubTransferRepositoryAdapter.classifyFailure(
                "enviar objetos LFS", providerResponse);
        assertThat(authenticationMessage)
                .contains("autenticação")
                .contains("Git")
                .doesNotContain("provider-secret")
                .doesNotContain("github.example.invalid");

        String unavailableResponse = "fatal: unable to access remote: HTTP 503 provider-internal-details";
        String availabilityMessage = GitHubTransferRepositoryAdapter.classifyFailure(
                "sincronizar o repositório Git", unavailableResponse);
        assertThat(availabilityMessage)
                .contains("indisponível")
                .doesNotContain("provider-internal-details")
                .doesNotContain("HTTP 503");

        String gitLabAuthenticationResponse = "remote: HTTP Basic: Access denied for "
                + "https://gitlab.example.invalid/group/repo.git (provider-secret)";
        String gitLabAuthenticationMessage = GitHubTransferRepositoryAdapter.classifyFailure(
                "enviar objetos LFS", gitLabAuthenticationResponse);
        assertThat(gitLabAuthenticationMessage)
                .contains("autenticação")
                .doesNotContain("provider-secret")
                .doesNotContain("gitlab.example.invalid");
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

    private static StorageProperties propertiesWithLfs(Path remote, Path clone) {
        StorageProperties properties = properties(remote, clone);
        properties.getGit().setLargeBlobStrategy(StorageProperties.LargeBlobStrategy.LFS);
        properties.getGit().setMaxBlobSize(DataSize.ofBytes(3));
        return properties;
    }

    private static StorageProperties hostedLfsProperties(String remote, String branch, Path clone) {
        StorageProperties properties = new StorageProperties();
        properties.setType(StorageProperties.StorageType.GIT);
        properties.setPath(clone);
        properties.getGit().setRemote(remote);
        properties.getGit().setBranch(branch);
        properties.getGit().setMaxBlobSize(DataSize.ofBytes(DEFAULT_MIN_CHUNK_BYTES - 1));
        properties.getGit().setLargeBlobStrategy(StorageProperties.LargeBlobStrategy.LFS);
        return properties;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " deve apontar para um remoto Git LFS dedicado");
        }
        return value;
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static long configuredChunkBytes() {
        String value = System.getenv(LFS_CONTRACT_CHUNK_BYTES);
        if (value == null || value.isBlank()) {
            return DEFAULT_MIN_CHUNK_BYTES;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(LFS_CONTRACT_CHUNK_BYTES + " deve ser um número inteiro", exception);
        }
    }

    private static long configuredMinimumChunkBytes() {
        String value = System.getenv(LFS_CONTRACT_MIN_CHUNK_BYTES);
        if (value == null || value.isBlank()) {
            return DEFAULT_MIN_CHUNK_BYTES;
        }
        try {
            long minimum = Long.parseLong(value);
            if (minimum <= 0) {
                throw new IllegalArgumentException(LFS_CONTRACT_MIN_CHUNK_BYTES + " deve ser positivo");
            }
            return minimum;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    LFS_CONTRACT_MIN_CHUNK_BYTES + " deve ser um número inteiro", exception);
        }
    }

    private static void writeDeterministicContent(Path target, long size) throws Exception {
        byte[] buffer = new byte[1024 * 1024];
        for (int index = 0; index < buffer.length; index++) {
            buffer[index] = (byte) (index * 31);
        }
        try (OutputStream output = Files.newOutputStream(target)) {
            long remaining = size;
            while (remaining > 0) {
                int length = (int) Math.min(remaining, buffer.length);
                output.write(buffer, 0, length);
                remaining -= length;
            }
        }
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

    private static TransferChunk chunk(byte[] content, String fileName) {
        return new TransferChunk(1, content.length, content.length,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bm9uY2U=", fileName);
    }

    private static TransferManifest manifest(TransferId transferId, String fileName, byte[] content,
                                             TransferChunk chunk) {
        return new TransferManifest(transferId.value(), fileName, content.length,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                content.length, 1, new TransferManifest.EncryptionMetadata("AES/GCM/NoPadding"),
                List.of(chunk), Instant.now(), TransferStatus.AVAILABLE);
    }

    private static PublicationResult publishManifest(GitHubTransferRepositoryAdapter adapter, TransferId transferId,
                                                     TransferManifest manifest) {
        try {
            adapter.publishManifest(manifest);
            return new PublicationResult(true, null, adapter, transferId, manifest);
        } catch (Exception exception) {
            return new PublicationResult(false, exception, adapter, transferId, manifest);
        }
    }

    private static void installFirstPushBarrier(Path clone, Path barrier, String name, String otherName)
            throws Exception {
        Path hook = clone.resolve(".git/hooks/pre-push");
        String marker = shellQuote(barrier.toAbsolutePath().toString());
        String script = "#!/bin/sh\n"
                + "set -eu\n"
                + "marker=" + marker + "\n"
                + "if [ ! -f \"$marker/" + name + "-first\" ]; then\n"
                + "  touch \"$marker/" + name + "-first\"\n"
                + "  while [ ! -f \"$marker/" + otherName + "-first\" ]; do sleep 0.01; done\n"
                + "fi\n";
        Files.writeString(hook, script);
        Files.setPosixFilePermissions(hook, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
    }

    private static void installPushFailureWithConcurrentEdit(Path clone, Path editedFile, String content)
            throws Exception {
        Path hook = clone.resolve(".git/hooks/pre-push");
        String script = "#!/bin/sh\n"
                + "set -eu\n"
                + "printf '%s' " + shellQuote(content) + " > " + shellQuote(editedFile.toAbsolutePath().toString()) + "\n"
                + "printf '%s\\n' 'simulated concurrent publication failure' >&2\n"
                + "exit 1\n";
        Files.writeString(hook, script);
        Files.setPosixFilePermissions(hook, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
    }

    private static void installRemotePushFailureWithConcurrentEdit(Path remote, Path editedFile, String content)
            throws Exception {
        Path hook = remote.resolve("hooks/pre-receive");
        String script = "#!/bin/sh\n"
                + "set -eu\n"
                + "printf '%s' " + shellQuote(content) + " > " + shellQuote(editedFile.toAbsolutePath().toString()) + "\n"
                + "printf '%s\\n' 'simulated concurrent publication failure' >&2\n"
                + "exit 1\n";
        Files.writeString(hook, script);
        Files.setPosixFilePermissions(hook, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @FunctionalInterface
    private interface ContractOperation {
        void run() throws Exception;
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

    private static String runCapture(Path directory, String... command) throws Exception {
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
        return output;
    }

    private record PublicationResult(boolean success, Exception failure, GitHubTransferRepositoryAdapter adapter,
                                     TransferId transferId, TransferManifest manifest) {
    }
}