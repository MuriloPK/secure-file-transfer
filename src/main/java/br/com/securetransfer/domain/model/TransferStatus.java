package br.com.securetransfer.domain.model;

public enum TransferStatus {
    PREPARING,
    ENCRYPTING,
    UPLOADING,
    AVAILABLE,
    DOWNLOADING,
    VALIDATING,
    ASSEMBLING,
    COMPLETED,
    FAILED
}