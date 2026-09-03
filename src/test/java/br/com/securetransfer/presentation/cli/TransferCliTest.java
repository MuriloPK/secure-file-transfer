package br.com.securetransfer.presentation.cli;

import br.com.securetransfer.application.service.DownloadFileService;
import br.com.securetransfer.application.service.ListTransfersService;
import br.com.securetransfer.application.service.PublishFileService;
import br.com.securetransfer.application.service.ValidateTransferService;
import br.com.securetransfer.infrastructure.repository.S3TransferRepositoryAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferCliTest {
    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream output;

    @BeforeEach
    void captureOutput() {
        output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output));
    }

    @AfterEach
    void restoreOutput() {
        System.setOut(originalOut);
    }

    @Test
    void cleanupCommandDisplaysAllCountsAndFailsWhenReportContainsFailures() throws Exception {
        S3TransferRepositoryAdapter cleaner = mock(S3TransferRepositoryAdapter.class);
        when(cleaner.cleanupOrphanedBlobs())
                .thenReturn(new S3TransferRepositoryAdapter.OrphanedBlobCleanupReport(7, 3, 2, 2));
        TransferCli cli = newCli(Optional.of(cleaner));

        assertThatThrownBy(() -> cli.run("cleanup-orphaned-blobs"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 falha(s)");

        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("Candidatos: 7")
                .contains("Removidos: 3")
                .contains("Preservados (manifest presente): 2")
                .contains("Falhas: 2");
        verify(cleaner).cleanupOrphanedBlobs();
    }

    @Test
    void cleanupCommandCompletesWhenReportHasNoFailures() throws Exception {
        S3TransferRepositoryAdapter cleaner = mock(S3TransferRepositoryAdapter.class);
        when(cleaner.cleanupOrphanedBlobs())
                .thenReturn(new S3TransferRepositoryAdapter.OrphanedBlobCleanupReport(4, 3, 1, 0));
        TransferCli cli = newCli(Optional.of(cleaner));

        cli.run("cleanup-orphaned-blobs");

        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("Candidatos: 4")
                .contains("Removidos: 3")
                .contains("Preservados (manifest presente): 1")
                .contains("Falhas: 0");
        verify(cleaner).cleanupOrphanedBlobs();
    }

    @Test
    void cleanupCommandIsNotOfferedForNonS3Storage() {
        TransferCli cli = newCli(Optional.empty());

        cli.run("cleanup-orphaned-blobs");

        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("Uso:")
                .doesNotContain("cleanup-orphaned-blobs");
    }

    private static TransferCli newCli(Optional<S3TransferRepositoryAdapter> cleaner) {
        return new TransferCli(
                mock(PublishFileService.class),
                mock(DownloadFileService.class),
                mock(ListTransfersService.class),
                mock(ValidateTransferService.class),
                cleaner);
    }
}