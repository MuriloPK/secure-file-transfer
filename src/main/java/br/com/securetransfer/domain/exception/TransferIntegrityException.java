package br.com.securetransfer.domain.exception;

public class TransferIntegrityException extends TransferException {
    public TransferIntegrityException(String message) {
        super(message);
    }
}