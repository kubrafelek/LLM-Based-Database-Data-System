package com.llmdiscovery.auth;

import com.llmdiscovery.common.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.MacAlgorithm;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * HS256/384/512 JWTs with "sub", "iat", "exp" and a random "jti" (for POST /auth/logout),
 * interchangeable with the Python service's tokens.
 */
@Component
public class JwtService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppProperties properties;
    private final MacAlgorithm algorithm;
    private final SecretKey key;

    public JwtService(AppProperties properties) {
        this.properties = properties;
        this.algorithm = switch (properties.getJwtAlgorithm()) {
            case "HS256" -> Jwts.SIG.HS256;
            case "HS384" -> Jwts.SIG.HS384;
            case "HS512" -> Jwts.SIG.HS512;
            default -> throw new IllegalArgumentException("Unsupported JWT_ALGORITHM " + properties.getJwtAlgorithm());
        };
        // The key's JCA name is HmacSHA256/384/512, not the JWT name HS256.
        String jcaName = "HmacSHA" + properties.getJwtAlgorithm().substring(2);
        this.key = new SecretKeySpec(properties.getJwtSecretKey().getBytes(StandardCharsets.UTF_8), jcaName);
    }

    public record Token(String value, long expiresIn) {}

    /** tokenId is null for tokens issued before revocation existed (valid until they expire). */
    public record TokenClaims(String subject, String tokenId, Instant expiresAt) {}

    public Token create(String subject) {
        long expiresIn = properties.getJwtExpireMinutes() * 60L;
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expiresIn)))
                .id(UUID.randomUUID().toString().replace("-", ""))
                .signWith(key, algorithm)
                .compact();
        return new Token(token, expiresIn);
    }

    /** Returns the subject, or throws JwtException if the token is invalid, expired or lacks sub/exp. */
    public String decode(String token) {
        return decodeClaims(token).subject();
    }

    /** Verifies the token and returns its claims; throws JwtException if invalid, expired or malformed. */
    public TokenClaims decodeClaims(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        if (claims.getSubject() == null || claims.getExpiration() == null) {
            throw new JwtException("token must contain sub and exp");
        }
        String jti = rawJti(token);
        if (jti != null && (jti.isEmpty() || jti.length() > 64)) {
            throw new JwtException("invalid jti");
        }
        return new TokenClaims(claims.getSubject(), jti, claims.getExpiration().toInstant());
    }

    /**
     * The jti exactly as sent. jjwt turns a numeric jti into a string; Python's PyJWT rejects it, so read
     * the (already verified) payload JSON and accept only a JSON string.
     */
    private static String rawJti(String token) {
        try {
            String payload = token.split("\\.")[1];
            JsonNode jti = MAPPER.readTree(Base64.getUrlDecoder().decode(payload)).get("jti");
            if (jti == null || jti.isNull()) {
                return null;
            }
            if (!jti.isTextual()) {
                throw new JwtException("invalid jti");
            }
            return jti.asText();
        } catch (IOException | IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            throw new JwtException("invalid payload", e);
        }
    }
}
