package br.com.securetransfer.domain.exception;

public class InvalidTransferStateException extends TransferException {
    public InvalidTransferStateException(String message) {
        super(message);
    }
}