package com.llmdiscovery.classification;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Cached /classify responses (table classification_results, JSONB). */
@Repository
public class ClassificationResultRepository {

    private final JdbcTemplate jdbc;

    public ClassificationResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> getFresh(UUID columnId, String cacheKey, OffsetDateTime notBefore) {
        return jdbc.query(
                "SELECT result::text FROM classification_results WHERE column_id = ? AND cache_key = ? AND created_at >= ?",
                (rs, i) -> rs.getString(1), columnId, cacheKey, Timestamp.from(notBefore.toInstant()))
                .stream().findFirst();
    }

    /** Insert or replace the cached result for this column and key. */
    public void save(UUID columnId, String cacheKey, String resultJson, OffsetDateTime createdAt) {
        jdbc.update(
                """
                INSERT INTO classification_results (id, column_id, cache_key, result, created_at)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT ON CONSTRAINT uq_classification_results_key
                DO UPDATE SET result = EXCLUDED.result, created_at = EXCLUDED.created_at
                """,
                UUID.randomUUID(), columnId, cacheKey, resultJson, Timestamp.from(createdAt.toInstant()));
    }
}
