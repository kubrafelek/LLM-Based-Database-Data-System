from uuid import UUID

from sqlalchemy import Row, delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.domain.models import DbColumn, DbConnection, DbMetadata, DbTable


def add(session: AsyncSession, metadata: DbMetadata) -> None:
    session.add(metadata)


async def list_page(
    session: AsyncSession, limit: int, offset: int
) -> tuple[list[Row[tuple[DbMetadata, DbConnection]]], int]:
    stmt = (
        select(DbMetadata, DbConnection)
        .join(DbConnection, DbConnection.id == DbMetadata.connection_id)
        .order_by(DbMetadata.created_at.desc(), DbMetadata.id)
        .limit(limit)
        .offset(offset)
    )
    rows = list((await session.execute(stmt)).all())
    total = await session.scalar(select(func.count()).select_from(DbMetadata))
    return rows, total or 0


async def get_with_tables(session: AsyncSession, metadata_id: UUID) -> DbMetadata | None:
    stmt = (
        select(DbMetadata)
        .where(DbMetadata.id == metadata_id)
        .options(
            selectinload(DbMetadata.connection),
            selectinload(DbMetadata.tables).selectinload(DbTable.columns),
        )
    )
    return await session.scalar(stmt)


async def delete_by_id(session: AsyncSession, metadata_id: UUID) -> bool:
    """Delete a snapshot. Tables and columns go via ON DELETE CASCADE; the connection is kept."""
    result = await session.execute(delete(DbMetadata).where(DbMetadata.id == metadata_id))
    return result.rowcount > 0


async def get_column_context(
    session: AsyncSession, column_id: UUID
) -> Row[tuple[DbColumn, DbTable, DbConnection]] | None:
    stmt = (
        select(DbColumn, DbTable, DbConnection)
        .join(DbTable, DbTable.id == DbColumn.table_id)
        .join(DbMetadata, DbMetadata.id == DbTable.metadata_id)
        .join(DbConnection, DbConnection.id == DbMetadata.connection_id)
        .where(DbColumn.id == column_id)
    )
    return (await session.execute(stmt)).one_or_none()
