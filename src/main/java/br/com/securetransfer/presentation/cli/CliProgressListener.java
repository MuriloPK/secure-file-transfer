package br.com.securetransfer.presentation.cli;

import br.com.securetransfer.application.service.ProgressListener;

public class CliProgressListener implements ProgressListener {
    private String lastOperation = "";

    @Override
    public void onProgress(String operation, long completed, long total, int item, int totalItems) {
        if (!operation.equals(lastOperation) || item == totalItems || item % 5 == 0) {
            int percent = total == 0 ? 100 : (int) Math.min(100, completed * 100 / total);
            System.out.printf("%s [%s] %d%% — %d/%d partes%n",
                    operation, bar(percent), percent, item, totalItems);
            lastOperation = operation;
        }
    }

    private static String bar(int percent) {
        int filled = percent / 5;
        return "█".repeat(filled) + "░".repeat(20 - filled);
    }
}