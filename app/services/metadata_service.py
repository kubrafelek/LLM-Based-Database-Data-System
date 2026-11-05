from uuid import UUID

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.exceptions import InvalidConnectionError, InvalidRequestError, NotFoundError, TargetDatabaseError
from app.core.security import SecretCipher
from app.domain.models import DbColumn, DbMetadata, DbTable
from app.domain.schemas import (
    ColumnOut,
    DbConnectionRequest,
    MetadataDetail,
    MetadataList,
    MetadataSummary,
    TableOut,
)
from app.infrastructure.database.target_database import ConnectionInfo, connect_target, extract_tables
from app.repositories import connection_repository, metadata_repository


def _to_detail(metadata: DbMetadata, host: str, port: int) -> MetadataDetail:
    tables = sorted(metadata.tables, key=lambda t: (t.schema_name, t.table_name))
    return MetadataDetail(
        metadata_id=metadata.id,
        connection_id=metadata.connection_id,
        database_name=metadata.database_name,
        schema_name=metadata.schema_name,
        host=host,
        port=port,
        created_at=metadata.created_at,
        table_count=metadata.table_count,
        tables=[
            TableOut(
                table_id=table.id,
                schema_name=table.schema_name,
                table_name=table.table_name,
                table_type=table.table_type,
                columns=[
                    ColumnOut(
                        column_id=column.id,
                        column_name=column.column_name,
                        data_type=column.data_type,
                        is_nullable=column.is_nullable,
                        ordinal_position=column.ordinal_position,
                    )
                    for column in sorted(table.columns, key=lambda c: c.ordinal_position)
                ],
            )
            for table in tables
        ],
    )


async def create_metadata(
    session: AsyncSession, request: DbConnectionRequest, settings: Settings, cipher: SecretCipher
) -> MetadataDetail:
    password = request.password.get_secret_value()
    info = ConnectionInfo(request.host, request.port, request.database, request.username, password)
    try:
        async with connect_target(info, settings) as conn:
            extracted = await extract_tables(conn, request.schema_name)
    except TargetDatabaseError as exc:
        # The caller supplied these details, so this is a client error rather than a 502.
        raise InvalidConnectionError(exc.detail) from exc
    if request.schema_name is not None and not extracted:
        raise InvalidRequestError(f"Schema '{request.schema_name}' does not exist or has no tables")

    connection_id = await connection_repository.upsert(
        session,
        host=request.host,
        port=request.port,
        database_name=request.database,
        username=request.username,
        encrypted_password=cipher.encrypt(password),
    )
    metadata = DbMetadata(
        connection_id=connection_id,
        database_name=request.database,
        schema_name=request.schema_name,
        table_count=len(extracted),
        tables=[
            DbTable(
                schema_name=table.schema_name,
                table_name=table.table_name,
                table_type=table.table_type,
                columns=[
                    DbColumn(
                        column_name=column.column_name,
                        data_type=column.data_type,
                        is_nullable=column.is_nullable,
                        ordinal_position=column.ordinal_position,
                    )
                    for column in table.columns
                ],
            )
            for table in extracted
        ],
    )
    metadata_repository.add(session, metadata)
    await session.commit()
    return _to_detail(metadata, request.host, request.port)


async def list_metadata(session: AsyncSession, limit: int, offset: int) -> MetadataList:
    rows, total = await metadata_repository.list_page(session, limit, offset)
    return MetadataList(
        total=total,
        items=[
            MetadataSummary(
                metadata_id=metadata.id,
                connection_id=metadata.connection_id,
                database_name=metadata.database_name,
                schema_name=metadata.schema_name,
                host=connection.host,
                port=connection.port,
                created_at=metadata.created_at,
                table_count=metadata.table_count,
            )
            for metadata, connection in rows
        ],
    )


async def get_metadata(session: AsyncSession, metadata_id: UUID) -> MetadataDetail:
    metadata = await metadata_repository.get_with_tables(session, metadata_id)
    if metadata is None:
        raise NotFoundError(f"Metadata {metadata_id} not found")
    return _to_detail(metadata, metadata.connection.host, metadata.connection.port)


async def delete_metadata(session: AsyncSession, metadata_id: UUID) -> None:
    if not await metadata_repository.delete_by_id(session, metadata_id):
        raise NotFoundError(f"Metadata {metadata_id} not found")
    await session.commit()
