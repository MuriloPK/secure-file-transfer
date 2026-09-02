package br.com.securetransfer.domain.exception;

public class InvalidFileTypeException extends TransferException {
    public InvalidFileTypeException(String fileName) {
        super("somente arquivos .zip são aceitos: " + fileName);
    }
}