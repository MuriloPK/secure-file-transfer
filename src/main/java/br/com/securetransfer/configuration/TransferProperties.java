package br.com.securetransfer.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "transfer")
public class TransferProperties {
    private DataSize maxFileSize = DataSize.ofMegabytes(200);
    private DataSize chunkSize = DataSize.ofMegabytes(5);

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public DataSize getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(DataSize chunkSize) {
        this.chunkSize = chunkSize;
    }

    public long maxFileSizeBytes() {
        return maxFileSize.toBytes();
    }

    public long chunkSizeBytes() {
        return chunkSize.toBytes();
    }
}