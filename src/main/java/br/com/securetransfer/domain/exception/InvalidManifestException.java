package br.com.securetransfer.domain.exception;

public class InvalidManifestException extends TransferException {
    public InvalidManifestException(String message) {
        super("manifest inválido: " + message);
    }

    public InvalidManifestException(String message, Throwable cause) {
        super("manifest inválido: " + message, cause);
    }
}