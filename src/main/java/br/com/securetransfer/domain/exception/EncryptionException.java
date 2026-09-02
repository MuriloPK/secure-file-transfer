package br.com.securetransfer.domain.exception;

public class EncryptionException extends TransferException {
    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}