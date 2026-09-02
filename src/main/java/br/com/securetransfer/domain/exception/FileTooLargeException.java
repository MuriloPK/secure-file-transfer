package br.com.securetransfer.domain.exception;

public class FileTooLargeException extends TransferException {
    public FileTooLargeException(long actual, long maximum) {
        super("arquivo excede o limite de " + maximum + " bytes (recebido: " + actual + ")");
    }
}