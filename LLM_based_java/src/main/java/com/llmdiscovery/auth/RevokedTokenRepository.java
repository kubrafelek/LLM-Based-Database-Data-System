package com.llmdiscovery.auth;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Revoked token IDs (jti) in the system DB; same table and rules as the Python token_repository. */
@Repository
public class RevokedTokenRepository {

    private final JdbcTemplate jdbc;

    public RevokedTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isRevoked(String jti) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM revoked_tokens WHERE jti = ?)", Boolean.class, jti));
    }

    /** Record a revoked token and drop rows whose tokens have expired (they are rejected anyway). */
    @Transactional
    public void revoke(String jti, Instant expiresAt) {
        jdbc.update("DELETE FROM revoked_tokens WHERE expires_at < now()");
        jdbc.update("INSERT INTO revoked_tokens (jti, expires_at) VALUES (?, ?) ON CONFLICT (jti) DO NOTHING",
                jti, Timestamp.from(expiresAt));
    }
}
