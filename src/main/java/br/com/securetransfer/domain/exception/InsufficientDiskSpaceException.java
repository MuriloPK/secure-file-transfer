package br.com.securetransfer.domain.exception;

public class InsufficientDiskSpaceException extends TransferException {
    public InsufficientDiskSpaceException(String message) {
        super(message);
    }
}