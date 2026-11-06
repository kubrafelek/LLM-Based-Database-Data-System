package com.llmdiscovery.discovery;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConnectionRepository {

    private final JdbcTemplate jdbc;

    public ConnectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Delete the connection (and its encrypted password) unless a snapshot still uses it. */
    public void deleteIfUnused(UUID connectionId) {
        jdbc.update("DELETE FROM db_connections c WHERE c.id = ? "
                + "AND NOT EXISTS (SELECT 1 FROM db_metadata m WHERE m.connection_id = c.id)", connectionId);
    }

    /**
     * Return the connection for host/port/database/username, creating it if needed.
     * An existing row gets the new encrypted password, so a rotated password takes effect.
     */
    public UUID upsert(String host, int port, String databaseName, String username, String encryptedPassword) {
        return jdbc.queryForObject(
                """
                INSERT INTO db_connections (id, host, port, database_name, username, encrypted_password)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT uq_db_connections_target
                DO UPDATE SET encrypted_password = EXCLUDED.encrypted_password, updated_at = now()
                RETURNING id
                """,
                UUID.class, UUID.randomUUID(), host, port, databaseName, username, encryptedPassword);
    }
}
