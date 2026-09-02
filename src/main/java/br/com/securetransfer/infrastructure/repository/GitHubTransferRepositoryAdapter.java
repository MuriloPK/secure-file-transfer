package br.com.securetransfer.infrastructure.repository;

import br.com.securetransfer.configuration.StorageProperties;
import br.com.securetransfer.domain.exception.StorageException;
import br.com.securetransfer.domain.exception.TransferNotFoundException;
import br.com.securetransfer.domain.model.TransferChunk;
import br.com.securetransfer.domain.model.TransferId;
import br.com.securetransfer.domain.model.TransferManifest;
import br.com.securetransfer.ports.out.TransferRepositoryPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "storage.type", havingValue = "git")
public class GitHubTransferRepositoryAdapter implements TransferRepositoryPort {
    private static final String TRANSFERS_DIR = "transfers";
    private static final String MANIFEST = "manifest.json";
    private static final String GIT_ATTRIBUTES = ".gitattributes";
    private static final String LFS_CHUNK_PATTERN = "transfers/**/*.bin";
    private static final String LFS_CHUNK_ATTRIBUTES =
            LFS_CHUNK_PATTERN + " filter=lfs diff=lfs merge=lfs -text";
    private static final String ORIGIN = "origin";
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);

    private final Path repositoryRoot;
    private final ObjectMapper objectMapper;
    private final StorageProperties.GitProperties properties;
    private final Set<TransferId> synchronizedPublishingTransfers = ConcurrentHashMap.newKeySet();

    public GitHubTransferRepositoryAdapter(StorageProperties storageProperties, ObjectMapper objectMapper) {
        this.repositoryRoot = storageProperties.getPath().toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.properties = storageProperties.getGit();
        validateConfiguration();
    }

    @Override
    public void synchronize() throws IOException {
        ensureRepository();
        ensureLfsAvailable();
        runGit("sincronizar o repositório Git", "pull", "--ff-only", ORIGIN, properties.getBranch());
    }

    @Override
    public void publishChunk(TransferId transferId, TransferChunk chunk, Path source) throws IOException {
        Path input = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(input)) {
            throw new StorageException("chunk criptografado não encontrado para publicação");
        }
        if (Files.size(input) != chunk.encryptedSize()) {
            throw new StorageException("tamanho do chunk criptografado não corresponde ao manifest");
        }
        boolean usesLfs = properties.getLargeBlobStrategy() == StorageProperties.LargeBlobStrategy.LFS;
        if (!usesLfs && Files.size(input) > properties.maxBlobSizeBytes()) {
            throw new StorageException("o remoto Git rejeita arquivos maiores que o limite configurado de "
                    + properties.getMaxBlobSize());
        }

        boolean firstChunk = synchronizedPublishingTransfers.add(transferId);
        try {
            prepareForPublication(firstChunk);
            boolean lfsAttributesChanged = usesLfs && ensureLfsTracking();
            Path target = chunkPath(transferId, chunk);
            Files.createDirectories(target.getParent());
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            if (lfsAttributesChanged) {
                commitAndPush("configurar Git LFS e publicar chunk " + chunk.number()
                                + " da transferência " + transferId,
                        relativePath(target), GIT_ATTRIBUTES);
            } else {
                commitAndPush("publicar chunk " + chunk.number() + " da transferência " + transferId,
                        relativePath(target));
            }
        } catch (IOException | RuntimeException exception) {
            if (firstChunk) {
                synchronizedPublishingTransfers.remove(transferId);
            }
            throw exception;
        }
    }

    @Override
    public void publishManifest(TransferManifest manifest) throws IOException {
        TransferId transferId = new TransferId(manifest.transferId());
        boolean wasPublishing = synchronizedPublishingTransfers.contains(transferId);
        try {
            prepareForPublication(!wasPublishing);
            Path transferDir = transferPath(transferId);
            Files.createDirectories(transferDir);
            Path temporary = Files.createTempFile(transferDir, "manifest-", ".tmp");
            try {
                objectMapper.writeValue(temporary.toFile(), manifest);
                Path target = transferDir.resolve(MANIFEST);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                commitAndPush("publicar manifest da transferência " + transferId, relativePath(target));
            } finally {
                Files.deleteIfExists(temporary);
            }
        } finally {
            synchronizedPublishingTransfers.remove(transferId);
        }
    }

    /**
     * Removes one already published transfer from the Git working tree and
     * publishes that deletion. This intentionally is not part of the
     * repository port: it is a provider-specific maintenance operation for
     * disposable contract repositories.
     */
    void cleanupPublishedTransfer(TransferId transferId) throws IOException {
        prepareForPublication(true);
        Path transferDir = transferPath(transferId);
        if (!Files.isDirectory(transferDir) || Files.isSymbolicLink(transferDir)) {
            return;
        }

        Path manifestPath = transferDir.resolve(MANIFEST);
        if (!Files.isRegularFile(manifestPath) || Files.isSymbolicLink(manifestPath)) {
            throw new StorageException("não foi possível confirmar o manifest da transferência de contrato");
        }
        TransferManifest manifest;
        try {
            manifest = objectMapper.readValue(manifestPath.toFile(), TransferManifest.class);
        } catch (JsonProcessingException exception) {
            throw new StorageException("não foi possível confirmar o manifest da transferência de contrato",
                    exception);
        }
        if (!transferId.value().equals(manifest.transferId())) {
            throw new StorageException("o manifest não corresponde à transferência de contrato solicitada");
        }

        List<Path> pathsToDelete;
        try (var paths = Files.walk(transferDir)) {
            pathsToDelete = paths.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : pathsToDelete) {
            Files.deleteIfExists(path);
        }
        commitAndPush("remover transferência de contrato " + transferId,
                relativePath(transferDir));
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
        Path transfersRoot = repositoryRoot.resolve(TRANSFERS_DIR).normalize();
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
                } catch (JsonProcessingException ignored) {
                    // A manifest inválido não deve tornar as outras transferências indisponíveis.
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

    private void validateConfiguration() {
        if (properties == null) {
            throw new IllegalArgumentException("configuração storage.git ausente");
        }
        if (properties.getBranch() == null || properties.getBranch().isBlank()
                || properties.getBranch().contains("..")
                || properties.getBranch().startsWith("-")) {
            throw new IllegalArgumentException("storage.git.branch inválida");
        }
        if (properties.getMaxBlobSize() == null || properties.maxBlobSizeBytes() <= 0) {
            throw new IllegalArgumentException("storage.git.max-blob-size deve ser positivo");
        }
        if (properties.getLargeBlobStrategy() == null) {
            throw new IllegalArgumentException("storage.git.large-blob-strategy é obrigatória");
        }
        if (properties.getRemote() != null && !properties.getRemote().isBlank()) {
            rejectEmbeddedCredentials(properties.getRemote());
        }
    }

    private void ensureLfsAvailable() throws IOException {
        if (properties.getLargeBlobStrategy() == StorageProperties.LargeBlobStrategy.LFS) {
            runGit("inicializar Git LFS no clone", "lfs", "install", "--local");
        }
    }

    private boolean ensureLfsTracking() throws IOException {
        Path attributes = repositoryRoot.resolve(GIT_ATTRIBUTES).normalize();
        if (!attributes.startsWith(repositoryRoot)) {
            throw new IllegalArgumentException("caminho de atributos Git inválido");
        }
        if (Files.isRegularFile(attributes)
                && Files.readAllLines(attributes).stream().anyMatch(LFS_CHUNK_ATTRIBUTES::equals)) {
            return false;
        }
        runGit("configurar chunks para Git LFS", "lfs", "track", LFS_CHUNK_PATTERN);
        return true;
    }

    private void ensureRepository() throws IOException {
        if (!Files.exists(repositoryRoot)) {
            String remote = configuredRemote();
            Files.createDirectories(repositoryRoot.getParent());
            runGitOutsideRepository("clonar o repositório Git", "clone", "--branch",
                    properties.getBranch(), "--single-branch", remote, repositoryRoot.toString());
        }
        if (!Files.isDirectory(repositoryRoot) || !Files.isDirectory(repositoryRoot.resolve(".git"))) {
            throw new StorageException("storage.path não aponta para um clone Git válido");
        }
        String configuredRemote = properties.getRemote();
        if (configuredRemote != null && !configuredRemote.isBlank()) {
            String actualRemote = runGitCapture("ler o remoto do repositório Git", "remote", "get-url", ORIGIN);
            if (!sameRemote(configuredRemote, actualRemote.trim())) {
                throw new StorageException("o remoto Git configurado não corresponde ao clone em storage.path");
            }
        }
        String currentBranch = runGitCapture("verificar a branch do repositório Git",
                "branch", "--show-current").trim();
        if (!properties.getBranch().equals(currentBranch)) {
            throw new StorageException("o clone Git deve estar na branch configurada: " + properties.getBranch());
        }
    }

    private String configuredRemote() {
        String remote = properties.getRemote();
        if (remote == null || remote.isBlank()) {
            throw new StorageException("storage.git.remote é obrigatório quando storage.path ainda não é um clone");
        }
        return remote;
    }

    private void prepareForPublication(boolean synchronizeFirst) throws IOException {
        ensureRepository();
        ensureWorkingTreeClean();
        if (synchronizeFirst) {
            ensureLfsAvailable();
            runGit("sincronizar o repositório Git", "pull", "--ff-only", ORIGIN, properties.getBranch());
        }
    }

    private void ensureWorkingTreeClean() throws IOException {
        String status = runGitCapture("verificar alterações locais antes da publicação",
                "status", "--porcelain", "--untracked-files=all");
        if (!status.isBlank()) {
            throw new StorageException("o clone Git possui alterações locais; faça commit ou descarte-as antes de publicar");
        }
    }

    private void commitAndPush(String message, String... paths) throws IOException {
        String previousHead = runGitCapture("identificar o commit local antes da publicação", "rev-parse", "HEAD")
                .trim();
        try {
            List<String> addCommand = new ArrayList<>();
            addCommand.add("add");
            addCommand.add("--");
            addCommand.addAll(List.of(paths));
            runGit("adicionar arquivos ao commit Git", addCommand.toArray(String[]::new));
            runGitWithConfiguration("criar commit Git", "commit", "-m", message);
            runGit("enviar alterações ao repositório Git", "push", ORIGIN, properties.getBranch());
        } catch (IOException | RuntimeException exception) {
            rollbackFailedPublication(previousHead, exception, paths);
            throw exception;
        }
    }

    private void rollbackFailedPublication(String previousHead, Exception publicationFailure, String... generatedPaths) {
        Set<String> locallyChangedGeneratedPaths;
        try {
            locallyChangedGeneratedPaths = locallyChangedPaths(generatedPaths);
        } catch (IOException statusFailure) {
            throw rollbackFailure(publicationFailure, statusFailure);
        }

        try {
            runGit("remover publicação Git local rejeitada preservando alterações locais",
                    "reset", "--keep", previousHead);
        } catch (IOException | RuntimeException rollbackFailure) {
            if (!locallyChangedGeneratedPaths.isEmpty()) {
                try {
                    rollbackWithConcurrentGeneratedEdits(previousHead, generatedPaths, locallyChangedGeneratedPaths);
                } catch (IOException | RuntimeException safeRollbackFailure) {
                    throw rollbackFailure(publicationFailure, safeRollbackFailure);
                }
            } else {
                throw rollbackFailure(publicationFailure, rollbackFailure);
            }
        }

        verifyRollbackState(publicationFailure);
    }

    private Set<String> locallyChangedPaths(String... paths) throws IOException {
        Set<String> changedPaths = new HashSet<>();
        for (String path : paths) {
            String status = runGitCapture("verificar alterações concorrentes no arquivo publicado",
                    "status", "--porcelain", "--untracked-files=all", "--", path);
            if (!status.isBlank()) {
                changedPaths.add(path);
            }
        }
        return changedPaths;
    }

    private void rollbackWithConcurrentGeneratedEdits(String previousHead, String[] generatedPaths,
                                                       Set<String> locallyChangedGeneratedPaths) throws IOException {
        // A mixed reset changes only the index, so an edit made over a generated file
        // remains in the working tree while the rejected publication leaves HEAD.
        runGit("remover publicação Git local rejeitada preservando edição no mesmo arquivo",
                "reset", "--mixed", previousHead);
        for (String path : generatedPaths) {
            if (locallyChangedGeneratedPaths.contains(path)) {
                continue;
            }
            restoreOrRemoveGeneratedPath(previousHead, path);
        }
    }

    private void restoreOrRemoveGeneratedPath(String previousHead, String path) throws IOException {
        String previousPath = runGitCapture("verificar o arquivo anterior à publicação",
                "ls-tree", "-r", "--name-only", previousHead, "--", path);
        if (!previousPath.isBlank()) {
            runGit("restaurar o arquivo anterior à publicação",
                    "restore", "--source", previousHead, "--worktree", "--", path);
            return;
        }
        Path generatedPath = repositoryRoot.resolve(path).normalize();
        if (!generatedPath.startsWith(repositoryRoot)) {
            throw new IllegalArgumentException("caminho gerado fora do clone Git");
        }
        Files.deleteIfExists(generatedPath);
    }

    private void verifyRollbackState(Exception publicationFailure) {
        String status;
        try {
            status = runGitCapture("verificar o estado do clone após o rollback",
                    "status", "--porcelain", "--untracked-files=all");
        } catch (IOException statusFailure) {
            StorageException safeFailure = new StorageException(
                    "falha ao publicar no repositório Git; o rollback foi concluído, mas não foi possível"
                            + " verificar o estado do clone; preserve as alterações locais e sincronize o clone"
                            + " manualmente antes de tentar novamente",
                    publicationFailure);
            safeFailure.addSuppressed(statusFailure);
            throw safeFailure;
        }
        if (!status.isBlank()) {
            throw new StorageException(
                    failureMessage(publicationFailure)
                            + "; rollback concluído e alterações locais detectadas durante a publicação"
                            + " foram preservadas no clone; faça commit ou descarte-as antes de tentar novamente",
                    publicationFailure);
        }
    }

    private static StorageException rollbackFailure(Exception publicationFailure, Exception rollbackFailure) {
        StorageException safeFailure = new StorageException(
                "falha ao publicar no repositório Git; não foi possível desfazer automaticamente a publicação"
                        + " rejeitada com segurança; preserve as alterações locais, sincronize o clone"
                        + " manualmente e remova o commit rejeitado antes de tentar novamente",
                publicationFailure);
        safeFailure.addSuppressed(rollbackFailure);
        return safeFailure;
    }

    private static String failureMessage(Exception failure) {
        return failure.getMessage() == null ? "falha ao publicar no repositório Git" : failure.getMessage();
    }

    private String relativePath(Path path) {
        Path relative = repositoryRoot.relativize(path.normalize());
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IllegalArgumentException("caminho de storage inválido");
        }
        return relative.toString();
    }

    private Path transferPath(TransferId transferId) {
        Path transfersRoot = repositoryRoot.resolve(TRANSFERS_DIR).normalize();
        Path path = transfersRoot.resolve(transferId.toString()).normalize();
        if (!path.startsWith(transfersRoot)) {
            throw new IllegalArgumentException("caminho de transferência inválido");
        }
        return path;
    }

    private Path chunkPath(TransferId transferId, TransferChunk chunk) {
        Path chunksRoot = transferPath(transferId).resolve("chunks").normalize();
        Path path = chunksRoot.resolve(chunk.fileName()).normalize();
        if (!path.startsWith(chunksRoot)) {
            throw new IllegalArgumentException("nome de chunk inválido");
        }
        return path;
    }

    private void runGit(String operation, String... arguments) throws IOException {
        runGitResult(operation, true, arguments);
    }

    private String runGitCapture(String operation, String... arguments) throws IOException {
        return runGitResult(operation, true, arguments).output();
    }

    private void runGitWithConfiguration(String operation, String... arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("-c");
        command.add("user.name=Secure File Transfer");
        command.add("-c");
        command.add("user.email=secure-file-transfer@localhost");
        command.addAll(List.of(arguments));
        runGitResult(operation, true, command.toArray(String[]::new));
    }

    private void runGitOutsideRepository(String operation, String... arguments) throws IOException {
        runGitResult(operation, false, arguments);
    }

    private GitResult runGitResult(String operation, boolean insideRepository, String... arguments)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        if (insideRepository) {
            command.add("-C");
            command.add(repositoryRoot.toString());
        }
        command.addAll(List.of(arguments));

        Process process;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectErrorStream(true);
            processBuilder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = processBuilder.start();
        } catch (IOException exception) {
            throw new StorageException("não foi possível executar Git; verifique se o Git está instalado", exception);
        }

        String output;
        try (InputStream input = process.getInputStream()) {
            output = readLimited(input);
            if (!process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new StorageException("o Git excedeu o tempo limite ao tentar " + operation);
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new StorageException("operação Git interrompida ao tentar " + operation, exception);
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new StorageException(classifyFailure(operation, output),
                    new IOException("git exit code " + exitCode));
        }
        return new GitResult(exitCode, output);
    }

    private static String readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total < 8192) {
                int accepted = Math.min(read, 8192 - total);
                output.write(buffer, 0, accepted);
                total += accepted;
            }
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    static String classifyFailure(String operation, String output) {
        String lower = output.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "authentication failed", "authentication required", "could not read username",
                "permission denied", "repository not found", "http 401", "http 403", "unauthorized",
                "publickey", "access denied", "invalid username or password")) {
            return "falha de autenticação/autorização do Git ao tentar " + operation
                    + "; configure a credencial do Git/SSH para o repositório privado";
        }
        if (containsAny(lower, "could not resolve host", "failed to connect", "connection refused",
                "connection timed out", "network is unreachable", "couldn't connect", "unable to access",
                "service unavailable", "http 500", "http 502", "http 503", "http 504", "timed out")) {
            return "o remoto Git está indisponível ao tentar " + operation
                    + "; verifique a rede e tente novamente";
        }
        if (containsAny(lower, "non-fast-forward", "fetch first", "rejected", "merge conflict",
                "divergent branches", "would be overwritten", "not possible to fast-forward",
                "cannot fast-forward", "failed to push some refs")) {
            return "conflito no repositório Git ao tentar " + operation
                    + "; sincronize o clone e resolva o conflito antes de tentar novamente";
        }
        if (containsAny(lower, "file size", "file exceeds", "file is too large", "large files",
                "maximum file size", "exceeds the", "gh001", "gh013")) {
            return "o remoto Git rejeitou a operação por limite de tamanho; reduza storage.git.max-blob-size"
                    + " ou o tamanho dos chunks";
        }
        return "falha ao tentar " + operation + " no repositório Git; verifique o remoto e as permissões";
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static void rejectEmbeddedCredentials(String remote) {
        if (!remote.regionMatches(true, 0, "http://", 0, 7)
                && !remote.regionMatches(true, 0, "https://", 0, 8)) {
            return;
        }
        try {
            URI uri = new URI(remote);
            if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
                throw new IllegalArgumentException(
                        "storage.git.remote não pode conter usuário, token ou senha embutidos");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("storage.git.remote inválido", exception);
        }
    }

    private static boolean sameRemote(String configured, String actual) {
        return configured.equals(actual)
                || configured.endsWith(".git") && configured.substring(0, configured.length() - 4).equals(actual)
                || actual.endsWith(".git") && actual.substring(0, actual.length() - 4).equals(configured);
    }

    private record GitResult(int exitCode, String output) {
    }
}