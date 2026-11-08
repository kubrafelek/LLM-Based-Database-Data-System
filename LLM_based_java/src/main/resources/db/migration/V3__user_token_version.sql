-- users.token_version: raised on a password change or POST /auth/logout-all to revoke all of a user's tokens.
-- IF NOT EXISTS: the Python service adds the same column (Alembic 0005) when it shares the system DB.
ALTER TABLE users ADD COLUMN IF NOT EXISTS token_version INTEGER NOT NULL DEFAULT 0;
