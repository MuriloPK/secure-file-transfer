package br.com.securetransfer.presentation.cli;

import br.com.securetransfer.application.service.DownloadFileService;
import br.com.securetransfer.application.service.ListTransfersService;
import br.com.securetransfer.application.service.PublishFileService;
import br.com.securetransfer.application.service.ValidateTransferService;
import br.com.securetransfer.domain.model.TransferId;
import br.com.securetransfer.domain.model.TransferManifest;
import br.com.securetransfer.infrastructure.repository.S3TransferRepositoryAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

@Component
public class TransferCli implements CommandLineRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(TransferCli.class);
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());
    private final PublishFileService publisher;
    private final DownloadFileService downloader;
    private final ListTransfersService lister;
    private final ValidateTransferService validator;
    private final Optional<S3TransferRepositoryAdapter> orphanedBlobCleaner;

    public TransferCli(PublishFileService publisher, DownloadFileService downloader,
                       ListTransfersService lister, ValidateTransferService validator) {
        this(publisher, downloader, lister, validator, Optional.empty());
    }

    @Autowired
    public TransferCli(PublishFileService publisher, DownloadFileService downloader,
                       ListTransfersService lister, ValidateTransferService validator,
                       ObjectProvider<S3TransferRepositoryAdapter> orphanedBlobCleanerProvider) {
        this(publisher, downloader, lister, validator,
                Optional.ofNullable(orphanedBlobCleanerProvider.getIfAvailable()));
    }

    TransferCli(PublishFileService publisher, DownloadFileService downloader,
                ListTransfersService lister, ValidateTransferService validator,
                Optional<S3TransferRepositoryAdapter> orphanedBlobCleaner) {
        this.publisher = publisher;
        this.downloader = downloader;
        this.lister = lister;
        this.validator = validator;
        this.orphanedBlobCleaner = orphanedBlobCleaner;
    }

    @Override
    public void run(String... args) {
        if (args.length > 0) {
            runCommand(args);
            return;
        }
        try (Scanner scanner = new Scanner(System.in)) {
            boolean running = true;
            while (running) {
                printMenu();
                String option = scanner.nextLine().trim();
                try {
                    running = execute(option, scanner);
                } catch (RuntimeException | java.io.IOException exception) {
                    System.out.println("Erro: " + exception.getMessage());
                    LOGGER.warn("operação da CLI falhou", exception);
                }
            }
        }
    }

    private void runCommand(String[] args) {
        try {
            String[] commandArgs = Arrays.stream(args)
                    .filter(argument -> !argument.startsWith("--"))
                    .toArray(String[]::new);
            if (commandArgs.length == 0) {
                printUsage();
                return;
            }
            switch (commandArgs[0].toLowerCase()) {
                case "publish" -> {
                    requireArgs(commandArgs, 2);
                    TransferManifest manifest = publisher.publish(Path.of(commandArgs[1]), new CliProgressListener());
                    printPublished(manifest);
                }
                case "list" -> printTransfers(lister.list());
                case "download" -> {
                    requireArgs(commandArgs, 3);
                    Path result = downloader.download(TransferId.parse(commandArgs[1]), Path.of(commandArgs[2]),
                            new CliProgressListener());
                    System.out.println("Arquivo entregue: " + result);
                }
                case "verify" -> {
                    requireArgs(commandArgs, 2);
                    printManifest(validator.validate(TransferId.parse(commandArgs[1])));
                    System.out.println("Manifesto válido.");
                }
                case "cleanup-orphaned-blobs", "cleanup-orphans", "cleanup" -> {
                    if (orphanedBlobCleaner.isEmpty()) {
                        printUsage();
                        return;
                    }
                    printCleanupReport(orphanedBlobCleaner.get().cleanupOrphanedBlobs());
                }
                default -> printUsage();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private boolean execute(String option, Scanner scanner) throws java.io.IOException {
        switch (option) {
            case "1" -> {
                System.out.print("Arquivo ZIP: ");
                TransferManifest manifest = publisher.publish(Path.of(scanner.nextLine().trim()),
                        new CliProgressListener());
                printPublished(manifest);
            }
            case "2" -> printTransfers(lister.list());
            case "3" -> {
                System.out.print("Transfer ID: ");
                TransferId id = TransferId.parse(scanner.nextLine().trim());
                System.out.print("Diretório de destino: ");
                Path result = downloader.download(id, Path.of(scanner.nextLine().trim()),
                        new CliProgressListener());
                System.out.println("Arquivo entregue: " + result);
            }
            case "4" -> {
                System.out.print("Transfer ID: ");
                printManifest(validator.validate(TransferId.parse(scanner.nextLine().trim())));
                System.out.println("Manifesto válido.");
            }
            case "5" -> {
                if (orphanedBlobCleaner.isPresent()) {
                    printCleanupReport(orphanedBlobCleaner.get().cleanupOrphanedBlobs());
                    break;
                }
                return false;
            }
            case "6" -> {
                if (orphanedBlobCleaner.isPresent()) {
                    return false;
                }
                System.out.println("Opção inválida.");
            }
            default -> System.out.println("Opção inválida.");
        }
        return true;
    }

    private void printMenu() {
        String cleanupOption = orphanedBlobCleaner.isPresent()
                ? "5 - Limpar blobs órfãos\n                6 - Sair"
                : "5 - Sair";
        System.out.println("""

                ======================================
                SECURE FILE TRANSFER
                ======================================
                1 - Publicar arquivo
                2 - Transferências disponíveis
                3 - Baixar transferência
                4 - Verificar transferência
                """ + cleanupOption + "\n");
        System.out.print("Escolha: ");
    }

    private static void printCleanupReport(S3TransferRepositoryAdapter.OrphanedBlobCleanupReport report) {
        System.out.println("Limpeza de blobs órfãos concluída.");
        System.out.println("Candidatos: " + report.candidates());
        System.out.println("Removidos: " + report.removed());
        System.out.println("Preservados (manifest presente): " + report.preserved());
        System.out.println("Falhas: " + report.failures());
    }

    private static void printPublished(TransferManifest manifest) {
        System.out.println("Publicação concluída.");
        System.out.println("Transfer ID: " + manifest.transferId());
        System.out.println("SHA-256: " + manifest.originalSha256());
        System.out.println("Chunks: " + manifest.totalChunks());
    }

    private static void printTransfers(List<TransferManifest> manifests) {
        if (manifests.isEmpty()) {
            System.out.println("Nenhuma transferência disponível.");
            return;
        }
        System.out.println("Transferências disponíveis:");
        for (int index = 0; index < manifests.size(); index++) {
            TransferManifest manifest = manifests.get(index);
            System.out.printf("%d. %s | %s | %d chunks | %s | %s%n",
                    index + 1, manifest.transferId(), manifest.fileName(),
                    manifest.totalChunks(), DATE_FORMAT.format(manifest.createdAt()), manifest.status());
        }
    }

    private static void printManifest(TransferManifest manifest) {
        System.out.printf("ID: %s%nNome: %s%nTamanho: %d bytes%nSHA-256: %s%nChunks: %d%nStatus: %s%n",
                manifest.transferId(), manifest.fileName(), manifest.originalSize(),
                manifest.originalSha256(), manifest.totalChunks(), manifest.status());
    }

    private static void requireArgs(String[] args, int count) {
        if (args.length < count) {
            throw new IllegalArgumentException("argumentos insuficientes");
        }
    }

    private void printUsage() {
        String cleanupCommand = orphanedBlobCleaner.isPresent()
                ? " | cleanup-orphaned-blobs"
                : "";
        System.out.println("Uso: java -jar secure-file-transfer.jar [publish <arquivo.zip> | list"
                + " | download <id> <diretório> | verify <id>" + cleanupCommand + "]");
    }
}
