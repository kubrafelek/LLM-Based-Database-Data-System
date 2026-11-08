"""Revoked tokens in the system DB (POST /auth/logout)."""

from datetime import UTC, datetime, timedelta

from sqlalchemy import func, select

from app.domain.models import RevokedToken
from app.repositories import token_repository


async def test_revoke_and_check(session):
    expires = datetime.now(UTC) + timedelta(minutes=30)
    assert not await token_repository.is_revoked(session, "a" * 32)

    await token_repository.revoke(session, "a" * 32, expires)
    await token_repository.revoke(session, "a" * 32, expires)  # idempotent
    await session.commit()

    assert await token_repository.is_revoked(session, "a" * 32)
    assert not await token_repository.is_revoked(session, "b" * 32)


async def test_expired_rows_are_purged_on_the_next_revoke(session):
    await token_repository.revoke(session, "old", datetime.now(UTC) - timedelta(seconds=1))
    await session.commit()
    await token_repository.revoke(session, "new", datetime.now(UTC) + timedelta(minutes=30))
    await session.commit()

    assert await session.scalar(select(func.count()).select_from(RevokedToken)) == 1
    assert await token_repository.is_revoked(session, "new")
