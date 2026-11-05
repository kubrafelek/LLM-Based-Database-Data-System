"""classification_results: cached /classify responses

Revision ID: 0003
Revises: 0002
Create Date: 2026-11-05
"""
import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "classification_results",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "column_id",
            sa.Uuid(),
            sa.ForeignKey("db_columns.id", ondelete="CASCADE"),
            nullable=False,
            index=True,
        ),
        sa.Column("cache_key", sa.String(64), nullable=False),
        sa.Column("result", postgresql.JSONB(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint("column_id", "cache_key", name="uq_classification_results_key"),
    )


def downgrade() -> None:
    op.drop_table("classification_results")
