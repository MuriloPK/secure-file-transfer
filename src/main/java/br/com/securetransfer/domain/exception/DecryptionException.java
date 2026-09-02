package br.com.securetransfer.domain.exception;

public class DecryptionException extends TransferException {
    public DecryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}