"""initial schema

Revision ID: 0001
Revises:
Create Date: 2026-11-05
"""

import sqlalchemy as sa

from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "db_metadata",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("database_name", sa.String(255), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_db_metadata_created_at", "db_metadata", ["created_at"])

    op.create_table(
        "db_connections",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "metadata_id",
            sa.Uuid(),
            sa.ForeignKey("db_metadata.id", ondelete="CASCADE"),
            nullable=False,
            unique=True,
        ),
        sa.Column("host", sa.String(255), nullable=False),
        sa.Column("port", sa.Integer(), nullable=False),
        sa.Column("database", sa.String(255), nullable=False),
        sa.Column("username", sa.String(255), nullable=False),
        sa.Column("password_encrypted", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )

    op.create_table(
        "db_tables",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "metadata_id",
            sa.Uuid(),
            sa.ForeignKey("db_metadata.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("schema_name", sa.String(255), nullable=False),
        sa.Column("table_name", sa.String(255), nullable=False),
        sa.Column("table_type", sa.String(32), nullable=False),
        sa.UniqueConstraint("metadata_id", "schema_name", "table_name"),
    )
    op.create_index("ix_db_tables_metadata_id", "db_tables", ["metadata_id"])

    op.create_table(
        "db_columns",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "table_id",
            sa.Uuid(),
            sa.ForeignKey("db_tables.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("column_name", sa.String(255), nullable=False),
        sa.Column("data_type", sa.String(255), nullable=False),
        sa.Column("is_nullable", sa.Boolean(), nullable=False),
        sa.Column("ordinal_position", sa.Integer(), nullable=False),
    )
    op.create_index("ix_db_columns_table_id", "db_columns", ["table_id"])


def downgrade() -> None:
    op.drop_table("db_columns")
    op.drop_table("db_tables")
    op.drop_table("db_connections")
    op.drop_table("db_metadata")
