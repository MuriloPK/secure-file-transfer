package br.com.securetransfer.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "work")
public class WorkProperties {
    private Path path = Path.of("./work");

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path;
    }
}