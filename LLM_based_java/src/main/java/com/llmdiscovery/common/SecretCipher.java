package com.llmdiscovery.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption for secrets stored in the system database.
 *
 * <p>Stored format: base64(nonce[12] || ciphertext || tag[16]), the same layout as the Python
 * service (cryptography's AESGCM), so both services can read the same stored connections.
 */
public class SecretCipher {

    private static final int NONCE_SIZE = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretCipher(String keyBase64) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(keyBase64 == null ? "" : keyBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ENCRYPTION_KEY must be valid base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalArgumentException("ENCRYPTION_KEY must decode to exactly 32 bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        byte[] nonce = new byte[NONCE_SIZE];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    public String decrypt(String token) {
        try {
            byte[] raw = Base64.getDecoder().decode(token);
            if (raw.length <= NONCE_SIZE) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Arrays.copyOf(raw, NONCE_SIZE)));
            byte[] plaintext = cipher.doFinal(raw, NONCE_SIZE, raw.length - NONCE_SIZE);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new AppException.Decryption(
                    "Stored credentials could not be decrypted (was ENCRYPTION_KEY changed?)", e);
        }
    }
}
