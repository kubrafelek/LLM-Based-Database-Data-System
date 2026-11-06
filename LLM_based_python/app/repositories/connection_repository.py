import uuid

from sqlalchemy import func
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.domain.models import DbConnection


async def upsert(
    session: AsyncSession,
    host: str,
    port: int,
    database_name: str,
    username: str,
    encrypted_password: str,
) -> uuid.UUID:
    """Return the connection for host/port/database/username, creating it if needed.

    An existing row gets the new encrypted password, so a rotated password takes effect.
    """
    insert_stmt = insert(DbConnection).values(
        id=uuid.uuid4(),
        host=host,
        port=port,
        database_name=database_name,
        username=username,
        encrypted_password=encrypted_password,
    )
    upsert_stmt = insert_stmt.on_conflict_do_update(
        constraint="uq_db_connections_target",
        set_={"encrypted_password": insert_stmt.excluded.encrypted_password, "updated_at": func.now()},
    ).returning(DbConnection.id)
    return (await session.execute(upsert_stmt)).scalar_one()
