package br.com.securetransfer.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "storage")
public class StorageProperties {
    private StorageType type = StorageType.LOCAL;
    private Path path = Path.of("./storage");
    private GitProperties git = new GitProperties();

    public StorageType getType() {
        return type;
    }

    public void setType(StorageType type) {
        this.type = type;
    }

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path;
    }

    public GitProperties getGit() {
        return git;
    }

    public void setGit(GitProperties git) {
        this.git = git;
    }

    public enum StorageType {
        LOCAL,
        GIT
    }

    public static class GitProperties {
        private String remote;
        private String branch = "main";
        private DataSize maxBlobSize = DataSize.ofMegabytes(100);

        public String getRemote() {
            return remote;
        }

        public void setRemote(String remote) {
            this.remote = remote;
        }

        public String getBranch() {
            return branch;
        }

        public void setBranch(String branch) {
            this.branch = branch;
        }

        public DataSize getMaxBlobSize() {
            return maxBlobSize;
        }

        public void setMaxBlobSize(DataSize maxBlobSize) {
            this.maxBlobSize = maxBlobSize;
        }

        public long maxBlobSizeBytes() {
            return maxBlobSize.toBytes();
        }
    }
}