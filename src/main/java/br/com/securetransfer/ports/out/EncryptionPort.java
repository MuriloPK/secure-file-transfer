package br.com.securetransfer.ports.out;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface EncryptionPort {
    EncryptionResult encrypt(InputStream input, OutputStream output, SecretKey key) throws IOException;

    void decrypt(InputStream input, OutputStream output, byte[] nonce, SecretKey key) throws IOException;

    record EncryptionResult(long encryptedSize, String sha256, byte[] nonce) {
    }
}