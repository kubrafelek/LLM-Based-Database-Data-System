import uuid
from datetime import datetime, timezone

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint, Uuid, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    username: Mapped[str] = mapped_column(String(150), unique=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, server_default=func.now()
    )


class DbConnection(Base):
    """A target database. The password is stored encrypted; one row per host/port/db/user."""

    __tablename__ = "db_connections"
    __table_args__ = (
        UniqueConstraint("host", "port", "database_name", "username", name="uq_db_connections_target"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    host: Mapped[str] = mapped_column(String(255))
    port: Mapped[int] = mapped_column(Integer)
    database_name: Mapped[str] = mapped_column(String(255))
    username: Mapped[str] = mapped_column(String(255))
    encrypted_password: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, server_default=func.now()
    )

    metadata_records: Mapped[list["DbMetadata"]] = relationship(back_populates="connection")


class DbMetadata(Base):
    """One metadata extraction snapshot of a target database."""

    __tablename__ = "db_metadata"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    # RESTRICT: a connection can only go away once none of its snapshots reference it.
    connection_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("db_connections.id", ondelete="RESTRICT"), index=True
    )
    database_name: Mapped[str] = mapped_column(String(255))
    schema_name: Mapped[str | None] = mapped_column(String(255))
    table_count: Mapped[int] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_utcnow, server_default=func.now(), index=True
    )

    connection: Mapped[DbConnection] = relationship(back_populates="metadata_records")
    tables: Mapped[list["DbTable"]] = relationship(
        back_populates="metadata_record", cascade="all, delete-orphan", passive_deletes=True
    )


class DbTable(Base):
    __tablename__ = "db_tables"
    __table_args__ = (UniqueConstraint("metadata_id", "schema_name", "table_name"),)

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    metadata_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("db_metadata.id", ondelete="CASCADE"), index=True
    )
    schema_name: Mapped[str] = mapped_column(String(255))
    table_name: Mapped[str] = mapped_column(String(255))
    table_type: Mapped[str] = mapped_column(String(32))

    metadata_record: Mapped[DbMetadata] = relationship(back_populates="tables")
    columns: Mapped[list["DbColumn"]] = relationship(
        back_populates="table", cascade="all, delete-orphan", passive_deletes=True
    )


class DbColumn(Base):
    __tablename__ = "db_columns"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
    table_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("db_tables.id", ondelete="CASCADE"), index=True
    )
    column_name: Mapped[str] = mapped_column(String(255))
    data_type: Mapped[str] = mapped_column(String(255))
    is_nullable: Mapped[bool] = mapped_column(Boolean)
    ordinal_position: Mapped[int] = mapped_column(Integer)

    table: Mapped[DbTable] = relationship(back_populates="columns")
