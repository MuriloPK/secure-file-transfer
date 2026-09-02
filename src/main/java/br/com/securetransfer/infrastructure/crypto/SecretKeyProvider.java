package br.com.securetransfer.infrastructure.crypto;

import br.com.securetransfer.configuration.CryptoProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class SecretKeyProvider {
    private final CryptoProperties properties;

    public SecretKeyProvider(CryptoProperties properties) {
        this.properties = properties;
    }

    public SecretKey key() {
        String secret = System.getenv(properties.getSecretEnv());
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("variável de ambiente " + properties.getSecretEnv() + " não configurada");
        }
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM não oferece SHA-256", exception);
        }
    }
}