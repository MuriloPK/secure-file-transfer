package br.com.securetransfer.application.service;

import br.com.securetransfer.domain.exception.InvalidManifestException;

import java.nio.file.Files;
import java.nio.file.Path;

public final class PathSafety {
    private PathSafety() {
    }

    public static String requireSafeFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.length() > 255 ||
                fileName.equals(".") || fileName.equals("..") ||
                fileName.contains("/") || fileName.contains("\\") ||
                fileName.contains("\0") || Path.of(fileName).isAbsolute()) {
            throw new InvalidManifestException("nome de arquivo inseguro: " + fileName);
        }
        return fileName;
    }

    public static Path requireExistingDirectory(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("diretório de destino inexistente: " + directory);
        }
        return normalized;
    }
}