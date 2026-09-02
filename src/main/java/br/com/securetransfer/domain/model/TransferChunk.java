package br.com.securetransfer.domain.model;

public record TransferChunk(
        int number,
        long originalSize,
        long encryptedSize,
        String sha256,
        String nonce,
        String fileName
) {
    public TransferChunk {
        if (number < 1) {
            throw new IllegalArgumentException("número do chunk deve ser positivo");
        }
        if (originalSize < 0 || encryptedSize < 0) {
            throw new IllegalArgumentException("tamanho de chunk não pode ser negativo");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SHA-256 do chunk inválido");
        }
        if (nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("nonce do chunk não pode ser vazio");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("nome interno do chunk não pode ser vazio");
        }
    }
}