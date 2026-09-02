package br.com.securetransfer.domain.model;

public record FileMetadata(String fileName, long size, String sha256) {
    public FileMetadata {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("nome do arquivo não pode ser vazio");
        }
        if (size < 0) {
            throw new IllegalArgumentException("tamanho do arquivo não pode ser negativo");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SHA-256 do arquivo inválido");
        }
    }
}