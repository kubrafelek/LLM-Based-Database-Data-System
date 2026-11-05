package com.llmdiscovery.auth;

import com.llmdiscovery.common.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.MacAlgorithm;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** HS256/384/512 JWTs with "sub", "iat" and "exp", interchangeable with the Python service's tokens. */
@Component
public class JwtService {

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

    public Token create(String subject) {
        long expiresIn = properties.getJwtExpireMinutes() * 60L;
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expiresIn)))
                .signWith(key, algorithm)
                .compact();
        return new Token(token, expiresIn);
    }

    /** Returns the subject, or throws JwtException if the token is invalid, expired or lacks sub/exp. */
    public String decode(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        if (claims.getSubject() == null || claims.getExpiration() == null) {
            throw new JwtException("token must contain sub and exp");
        }
        return claims.getSubject();
    }
}
