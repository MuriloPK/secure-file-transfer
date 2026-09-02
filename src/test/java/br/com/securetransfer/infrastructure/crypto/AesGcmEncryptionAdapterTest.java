package br.com.securetransfer.infrastructure.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmEncryptionAdapterTest {
    @Test
    void encryptsAndDecryptsStreamingPayload() throws Exception {
        AesGcmEncryptionAdapter adapter = new AesGcmEncryptionAdapter();
        SecretKey key = key();
        byte[] original = "conteúdo protegido".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();

        var result = adapter.encrypt(new ByteArrayInputStream(original), encrypted, key);
        ByteArrayOutputStream decrypted = new ByteArrayOutputStream();
        adapter.decrypt(new ByteArrayInputStream(encrypted.toByteArray()), decrypted, result.nonce(), key);

        assertThat(decrypted.toByteArray()).isEqualTo(original);
        assertThat(result.encryptedSize()).isEqualTo(original.length + 16);
        assertThat(result.nonce()).hasSize(12);
    }

    @Test
    void rejectsTamperedCiphertext() throws Exception {
        AesGcmEncryptionAdapter adapter = new AesGcmEncryptionAdapter();
        SecretKey key = key();
        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        var result = adapter.encrypt(new ByteArrayInputStream(new byte[]{1, 2, 3}), encrypted, key);
        byte[] tampered = encrypted.toByteArray();
        tampered[0] ^= 1;

        assertThatThrownBy(() -> adapter.decrypt(new ByteArrayInputStream(tampered),
                new ByteArrayOutputStream(), result.nonce(), key))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("autenticação");
    }

    private static SecretKey key() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return generator.generateKey();
    }
}