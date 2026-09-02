package br.com.securetransfer.domain.exception;

public class ChunkCorruptedException extends TransferException {
    public ChunkCorruptedException(int chunk) {
        super("chunk corrompido: " + chunk);
    }
}