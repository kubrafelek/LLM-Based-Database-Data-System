-- JWTs revoked by POST /auth/logout, kept until they would have expired anyway.
-- IF NOT EXISTS: the Python service creates the same table (Alembic 0004) when it shares the system DB.
CREATE TABLE IF NOT EXISTS revoked_tokens (
    jti VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_revoked_tokens_expires_at ON revoked_tokens (expires_at);
