package br.com.securetransfer.domain.exception;

public class TransferNotFoundException extends TransferException {
    public TransferNotFoundException(String transferId) {
        super("transferência não encontrada: " + transferId);
    }
}