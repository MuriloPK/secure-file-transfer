package br.com.securetransfer.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "storage")
public class StorageProperties {
    private StorageType type = StorageType.LOCAL;
    private Path path = Path.of("./storage");
    private GitProperties git = new GitProperties();
    private S3Properties object = new S3Properties();

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

    public S3Properties getS3() {
        return object;
    }

    public void setS3(S3Properties s3) {
        this.object = s3;
    }

    public S3Properties getObject() {
        return object;
    }

    public void setObject(S3Properties object) {
        this.object = object;
    }

    public enum StorageType {
        LOCAL,
        GIT,
        OBJECT,
        S3
    }

    public static class GitProperties {
        private String remote;
        private String branch = "main";
        private DataSize maxBlobSize = DataSize.ofMegabytes(100);
        private LargeBlobStrategy largeBlobStrategy = LargeBlobStrategy.REJECT;

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

        public LargeBlobStrategy getLargeBlobStrategy() {
            return largeBlobStrategy;
        }

        public void setLargeBlobStrategy(LargeBlobStrategy largeBlobStrategy) {
            this.largeBlobStrategy = largeBlobStrategy;
        }

        public long maxBlobSizeBytes() {
            return maxBlobSize.toBytes();
        }
    }

    public enum LargeBlobStrategy {
        REJECT,
        LFS
    }

    public static class S3Properties {
        private String endpoint;
        private String bucket;
        private String region = "us-east-1";
        private String metadataPrefix = "metadata";
        private String blobPrefix = "blobs";
        private boolean pathStyleAccess = true;
        private DataSize multipartThreshold = DataSize.ofMegabytes(100);
        private DataSize multipartPartSize = DataSize.ofMegabytes(8);
        private boolean multipartResumeEnabled = true;
        private Duration orphanRetention = Duration.ofHours(24);

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getMetadataPrefix() {
            return metadataPrefix;
        }

        public void setMetadataPrefix(String metadataPrefix) {
            this.metadataPrefix = metadataPrefix;
        }

        public String getBlobPrefix() {
            return blobPrefix;
        }

        public void setBlobPrefix(String blobPrefix) {
            this.blobPrefix = blobPrefix;
        }

        public boolean isPathStyleAccess() {
            return pathStyleAccess;
        }

        public void setPathStyleAccess(boolean pathStyleAccess) {
            this.pathStyleAccess = pathStyleAccess;
        }

        public DataSize getMultipartThreshold() {
            return multipartThreshold;
        }

        public void setMultipartThreshold(DataSize multipartThreshold) {
            this.multipartThreshold = multipartThreshold;
        }

        public DataSize getMultipartPartSize() {
            return multipartPartSize;
        }

        public void setMultipartPartSize(DataSize multipartPartSize) {
            this.multipartPartSize = multipartPartSize;
        }

        public Duration getOrphanRetention() {
            return orphanRetention;
        }

        public void setOrphanRetention(Duration orphanRetention) {
            this.orphanRetention = orphanRetention;
        }

        public long multipartThresholdBytes() {
            return multipartThreshold.toBytes();
        }

        public long multipartPartSizeBytes() {
            return multipartPartSize.toBytes();
        }

        public boolean isMultipartResumeEnabled() {
            return multipartResumeEnabled;
        }

        public void setMultipartResumeEnabled(boolean multipartResumeEnabled) {
            this.multipartResumeEnabled = multipartResumeEnabled;
        }
    }
}