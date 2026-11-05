import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.domain.models import ClassificationResult


async def get_fresh(
    session: AsyncSession, column_id: uuid.UUID, cache_key: str, not_before: datetime
) -> ClassificationResult | None:
    stmt = select(ClassificationResult).where(
        ClassificationResult.column_id == column_id,
        ClassificationResult.cache_key == cache_key,
        ClassificationResult.created_at >= not_before,
    )
    return await session.scalar(stmt)


async def save(
    session: AsyncSession, column_id: uuid.UUID, cache_key: str, result: dict[str, Any], created_at: datetime
) -> None:
    """Insert or replace the cached result for this column and key."""
    stmt = insert(ClassificationResult).values(
        id=uuid.uuid4(), column_id=column_id, cache_key=cache_key, result=result, created_at=created_at
    )
    stmt = stmt.on_conflict_do_update(
        constraint="uq_classification_results_key",
        set_={"result": stmt.excluded.result, "created_at": stmt.excluded.created_at},
    )
    await session.execute(stmt)
