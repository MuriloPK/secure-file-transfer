package br.com.securetransfer.application.service;

public final class NoOpProgressListener implements ProgressListener {
    @Override
    public void onProgress(String operation, long completed, long total, int item, int totalItems) {
    }
}