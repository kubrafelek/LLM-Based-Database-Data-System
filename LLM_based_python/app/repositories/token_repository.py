from datetime import UTC, datetime

from sqlalchemy import delete, exists, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.domain.models import RevokedToken


async def is_revoked(session: AsyncSession, jti: str) -> bool:
    return bool(await session.scalar(select(exists().where(RevokedToken.jti == jti))))


async def revoke(session: AsyncSession, jti: str, expires_at: datetime) -> None:
    """Record a revoked token and drop rows whose tokens have expired (they are rejected anyway)."""
    await session.execute(delete(RevokedToken).where(RevokedToken.expires_at < datetime.now(UTC)))
    await session.execute(
        insert(RevokedToken).values(jti=jti, expires_at=expires_at).on_conflict_do_nothing(index_elements=["jti"])
    )
