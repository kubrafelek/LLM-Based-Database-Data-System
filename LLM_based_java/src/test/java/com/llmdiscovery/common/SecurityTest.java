package com.llmdiscovery.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmdiscovery.auth.JwtService;
import com.llmdiscovery.auth.PasswordHasher;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.spec.SecretKeySpec;
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

    private static final String JWT_SECRET = "test-jwt-secret-with-enough-length-1234";

    private static String signed(Object jti) {
        var builder = Jwts.builder().subject("admin").expiration(Date.from(Instant.now().plusSeconds(300)));
        if (jti != null) {
            builder.claim("jti", jti);
        }
        return builder.signWith(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"),
                Jwts.SIG.HS256).compact();
    }

    /** A token with exactly this JSON payload (jjwt's builder would coerce a numeric jti to a string). */
    private static String rawSigned(String payloadJson) throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String input = b64.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + b64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void tokensCarryAUniqueIdAndExpiry() {
        JwtService service = jwt(JWT_SECRET, 60);
        JwtService.TokenClaims first = service.decodeClaims(service.create("admin").value());
        JwtService.TokenClaims second = service.decodeClaims(service.create("admin").value());
        assertThat(first.subject()).isEqualTo("admin");
        assertThat(first.tokenId()).hasSize(32).isNotEqualTo(second.tokenId());
        assertThat(first.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void tokenWithoutIdIsStillAccepted() {
        assertThat(jwt(JWT_SECRET, 60).decodeClaims(signed(null)).tokenId()).isNull();
    }

    @Test
    void tokenWithMalformedIdIsRejected() throws Exception {
        JwtService service = jwt(JWT_SECRET, 60);
        long exp = Instant.now().getEpochSecond() + 300;
        for (String jti : new String[] {"123", "\"\"", "\"" + "x".repeat(65) + "\""}) {
            String token = rawSigned("{\"sub\":\"admin\",\"exp\":" + exp + ",\"jti\":" + jti + "}");
            assertThatThrownBy(() -> service.decodeClaims(token)).as(jti).isInstanceOf(JwtException.class);
        }
    }
}
