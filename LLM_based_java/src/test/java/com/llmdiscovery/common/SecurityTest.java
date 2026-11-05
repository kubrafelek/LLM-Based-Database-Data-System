package com.llmdiscovery.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmdiscovery.auth.JwtService;
import com.llmdiscovery.auth.PasswordHasher;
import io.jsonwebtoken.JwtException;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecurityTest {

    private static final String KEY = Base64.getEncoder().encodeToString("k".repeat(32).getBytes());

    @Test
    void cipherRoundTripUsesARandomNonce() {
        SecretCipher cipher = new SecretCipher(KEY);
        String a = cipher.encrypt("secret");
        assertThat(a).isNotEqualTo(cipher.encrypt("secret"));
        assertThat(cipher.decrypt(a)).isEqualTo("secret");
    }

    @Test
    void cipherRejectsWrongKeyAndTamperedCiphertext() {
        String token = new SecretCipher(KEY).encrypt("secret");
        String otherKey = Base64.getEncoder().encodeToString("x".repeat(32).getBytes());
        assertThatThrownBy(() -> new SecretCipher(otherKey).decrypt(token)).isInstanceOf(AppException.Decryption.class);
        byte[] raw = Base64.getDecoder().decode(token);
        raw[raw.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> new SecretCipher(KEY).decrypt(tampered)).isInstanceOf(AppException.Decryption.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64!", "c2hvcnQ="})
    void cipherRejectsAnInvalidKey(String key) {
        assertThatThrownBy(() -> new SecretCipher(key)).isInstanceOf(IllegalArgumentException.class);
    }

    private static JwtService jwt(String secret, int expireMinutes) {
        AppProperties properties = new AppProperties();
        properties.setJwtSecretKey(secret);
        properties.setJwtExpireMinutes(expireMinutes);
        return new JwtService(properties);
    }

    @Test
    void tokenRoundTrip() {
        JwtService service = jwt("test-jwt-secret-with-enough-length-1234", 60);
        JwtService.Token token = service.create("admin");
        assertThat(token.expiresIn()).isEqualTo(3600);
        assertThat(service.decode(token.value())).isEqualTo("admin");
    }

    @Test
    void expiredAndForeignTokensAreRejected() {
        JwtService service = jwt("test-jwt-secret-with-enough-length-1234", -1);
        assertThatThrownBy(() -> service.decode(service.create("admin").value())).isInstanceOf(JwtException.class);
        String foreign = jwt("another-secret-with-enough-length-12345678", 60).create("admin").value();
        assertThatThrownBy(() -> jwt("test-jwt-secret-with-enough-length-1234", 60).decode(foreign))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void passwordHashIsArgon2idAndVerifies() {
        PasswordHasher hasher = new PasswordHasher();
        String hash = hasher.hash("pw");
        assertThat(hash).startsWith("$argon2id$v=19$m=65536,t=3,p=4$");
        assertThat(hasher.verify("pw", hash)).isTrue();
        assertThat(hasher.verify("other", hash)).isFalse();
        assertThat(hasher.verify("pw", "not-a-hash")).isFalse();
    }
}
