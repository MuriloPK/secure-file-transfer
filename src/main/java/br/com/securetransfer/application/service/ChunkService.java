package br.com.securetransfer.application.service;

import br.com.securetransfer.domain.model.TransferChunk;
import br.com.securetransfer.domain.model.TransferId;
import br.com.securetransfer.ports.out.EncryptionPort;
import br.com.securetransfer.configuration.TransferProperties;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class ChunkService {
    private final TransferProperties properties;
    private final EncryptionPort encryption;

    public ChunkService(TransferProperties properties, EncryptionPort encryption) {
        this.properties = properties;
        this.encryption = encryption;
    }

    public List<PreparedChunk> splitAndEncrypt(Path source, Path uploadDirectory,
                                                TransferId transferId, SecretKey key,
                                                ProgressListener progress) throws IOException {
        List<PreparedChunk> chunks = new ArrayList<>();
        long fileSize = Files.size(source);
        long totalChunks = fileSize == 0 ? 0 : (fileSize + properties.getChunkSize() - 1) / properties.getChunkSize();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
            for (int number = 1; number <= totalChunks; number++) {
                Path encryptedPath = uploadDirectory.resolve(String.format("part-%05d.bin", number)).normalize();
                long limit = Math.min(properties.getChunkSize(),
                        fileSize - ((long) (number - 1) * properties.getChunkSize()));
                CountingLimitedInputStream chunkInput = new CountingLimitedInputStream(input, limit);
                EncryptionPort.EncryptionResult result;
                try (var output = new BufferedOutputStream(Files.newOutputStream(encryptedPath))) {
                    result = encryption.encrypt(chunkInput, output, key);
                }
                if (chunkInput.count() != limit) {
                    throw new IOException("arquivo mudou durante a leitura do chunk " + number);
                }
                TransferChunk chunk = new TransferChunk(number, chunkInput.count(), result.encryptedSize(),
                        result.sha256(), Base64.getEncoder().encodeToString(result.nonce()), encryptedPath.getFileName().toString());
                chunks.add(new PreparedChunk(chunk, encryptedPath));
                progress.onProgress("Criptografando", chunkInput.count(), fileSize, number, (int) totalChunks);
            }
        }
        return List.copyOf(chunks);
    }

    public record PreparedChunk(TransferChunk metadata, Path encryptedPath) {
    }

    private static final class CountingLimitedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;
        private long count;

        private CountingLimitedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int value = delegate.read();
            if (value == -1) {
                remaining = 0;
                return -1;
            }
            remaining--;
            count++;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int requested = (int) Math.min(length, remaining);
            int read = delegate.read(buffer, offset, requested);
            if (read == -1) {
                remaining = 0;
                return -1;
            }
            remaining -= read;
            count += read;
            return read;
        }

        private long count() {
            return count;
        }
    }
}