package br.com.securetransfer.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "storage")
public class StorageProperties {
    private Path path = Path.of("./storage");

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path;
    }
}