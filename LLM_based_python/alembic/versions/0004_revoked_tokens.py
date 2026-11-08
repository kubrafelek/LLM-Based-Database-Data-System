"""revoked_tokens: JWTs revoked by POST /auth/logout

Revision ID: 0004
Revises: 0003
Create Date: 2026-11-08

IF NOT EXISTS: the Java service creates the same table (Flyway V2) when it shares the system DB.
"""

from alembic import op

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS revoked_tokens (
            jti VARCHAR(64) PRIMARY KEY,
            expires_at TIMESTAMP WITH TIME ZONE NOT NULL
        )
        """
    )
    op.execute("CREATE INDEX IF NOT EXISTS ix_revoked_tokens_expires_at ON revoked_tokens (expires_at)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS revoked_tokens")
