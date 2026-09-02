package br.com.securetransfer.application.service;

public interface ProgressListener {
    void onProgress(String operation, long completed, long total, int item, int totalItems);
}