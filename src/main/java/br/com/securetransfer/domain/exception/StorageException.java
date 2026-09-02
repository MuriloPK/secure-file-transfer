package br.com.securetransfer.domain.exception;

public class StorageException extends TransferException {
    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}