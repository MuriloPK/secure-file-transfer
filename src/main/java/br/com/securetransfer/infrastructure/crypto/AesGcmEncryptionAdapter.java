package br.com.securetransfer.infrastructure.crypto;

import br.com.securetransfer.domain.exception.DecryptionException;
import br.com.securetransfer.domain.exception.EncryptionException;
import br.com.securetransfer.ports.out.EncryptionPort;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class AesGcmEncryptionAdapter implements EncryptionPort {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_SIZE = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int BUFFER_SIZE = 8192;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public EncryptionResult encrypt(InputStream input, OutputStream output, SecretKey key) throws IOException {
        byte[] nonce = new byte[NONCE_SIZE];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce);
            MessageDigest digest = sha256();
            byte[] buffer = new byte[BUFFER_SIZE];
            long encryptedSize = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                byte[] encrypted = cipher.update(buffer, 0, read);
                encryptedSize += writeAndDigest(encrypted, output, digest);
            }
            encryptedSize += writeAndDigest(cipher.doFinal(), output, digest);
            return new EncryptionResult(encryptedSize, HexFormat.of().formatHex(digest.digest()), nonce);
        } catch (GeneralSecurityException exception) {
            throw new EncryptionException("não foi possível criptografar o chunk", exception);
        }
    }

    @Override
    public void decrypt(InputStream input, OutputStream output, byte[] nonce, SecretKey key) throws IOException {
        if (nonce == null || nonce.length != NONCE_SIZE) {
            throw new DecryptionException("nonce inválido", new IllegalArgumentException());
        }
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, nonce);
            byte[] buffer = new byte[BUFFER_SIZE];
            byte[] tail = new byte[TAG_BYTES];
            int tailSize = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                byte[] combined = new byte[tailSize + read];
                System.arraycopy(tail, 0, combined, 0, tailSize);
                System.arraycopy(buffer, 0, combined, tailSize, read);
                int processLength = Math.max(0, combined.length - TAG_BYTES);
                if (processLength > 0) {
                    byte[] plain = cipher.update(combined, 0, processLength);
                    if (plain != null) {
                        output.write(plain);
                    }
                }
                tailSize = combined.length - processLength;
                System.arraycopy(combined, processLength, tail, 0, tailSize);
            }
            if (tailSize != TAG_BYTES) {
                throw new DecryptionException("chunk criptografado sem tag GCM completa",
                        new IllegalArgumentException());
            }
            byte[] finalPlain = cipher.doFinal(tail, 0, tailSize);
            if (finalPlain != null) {
                output.write(finalPlain);
            }
        } catch (GeneralSecurityException exception) {
            throw new DecryptionException("falha de autenticação ou senha incorreta", exception);
        }
    }

    private Cipher cipher(int mode, SecretKey key, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, new SecretKeySpec(key.getEncoded(), "AES"),
                new GCMParameterSpec(TAG_BITS, nonce));
        return cipher;
    }

    private static long writeAndDigest(byte[] bytes, OutputStream output, MessageDigest digest) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return 0;
        }
        output.write(bytes);
        digest.update(bytes);
        return bytes.length;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("JVM não oferece SHA-256", exception);
        }
    }
}