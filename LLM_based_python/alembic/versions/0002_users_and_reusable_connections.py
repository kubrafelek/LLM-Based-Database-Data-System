"""users table, reusable connections (1:N metadata), metadata schema_name/table_count

Revision ID: 0002
Revises: 0001
Create Date: 2026-11-05
"""

import sqlalchemy as sa

from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column("username", sa.String(150), nullable=False, unique=True),
        sa.Column("password_hash", sa.String(255), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )

    # db_connections: spec column names + updated_at
    op.alter_column("db_connections", "database", new_column_name="database_name")
    op.alter_column("db_connections", "password_encrypted", new_column_name="encrypted_password")
    op.add_column(
        "db_connections",
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )

    # db_metadata: point at the connection instead of the other way around
    op.add_column("db_metadata", sa.Column("connection_id", sa.Uuid(), nullable=True))
    op.add_column("db_metadata", sa.Column("schema_name", sa.String(255), nullable=True))
    op.add_column("db_metadata", sa.Column("table_count", sa.Integer(), server_default="0", nullable=False))
    op.execute("UPDATE db_metadata m SET connection_id = c.id FROM db_connections c WHERE c.metadata_id = m.id")
    op.execute(
        "UPDATE db_metadata m SET table_count = " "(SELECT count(*) FROM db_tables t WHERE t.metadata_id = m.id)"
    )

    # Collapse duplicate connections to the most recent row (latest password) before
    # adding the uniqueness constraint.
    ranked = (
        "SELECT id, first_value(id) OVER ("
        "PARTITION BY host, port, database_name, username ORDER BY created_at DESC, id"
        ") AS keep_id FROM db_connections"
    )
    op.execute(
        f"UPDATE db_metadata m SET connection_id = r.keep_id FROM ({ranked}) r "
        "WHERE m.connection_id = r.id AND r.id <> r.keep_id"
    )
    op.execute(f"DELETE FROM db_connections c USING ({ranked}) r WHERE c.id = r.id AND r.id <> r.keep_id")

    op.drop_column("db_connections", "metadata_id")
    op.alter_column("db_metadata", "connection_id", nullable=False)
    op.alter_column("db_metadata", "table_count", server_default=None)
    op.create_foreign_key(
        "fk_db_metadata_connection_id",
        "db_metadata",
        "db_connections",
        ["connection_id"],
        ["id"],
        ondelete="RESTRICT",
    )
    op.create_index("ix_db_metadata_connection_id", "db_metadata", ["connection_id"])
    op.create_unique_constraint(
        "uq_db_connections_target", "db_connections", ["host", "port", "database_name", "username"]
    )


def downgrade() -> None:
    # Going back to one connection per snapshot would need to duplicate connection rows
    # and cannot restore the pre-merge duplicates, so this migration is irreversible.
    raise NotImplementedError("0002 is irreversible; restore from a backup instead")
