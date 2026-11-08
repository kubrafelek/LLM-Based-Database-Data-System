"""users.token_version: revoke all of a user's tokens at once

Revision ID: 0005
Revises: 0004
Create Date: 2026-11-08

IF NOT EXISTS: the Java service adds the same column (Flyway V3) when it shares the system DB.
"""

from alembic import op

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS token_version INTEGER NOT NULL DEFAULT 0")


def downgrade() -> None:
    op.execute("ALTER TABLE users DROP COLUMN IF EXISTS token_version")
