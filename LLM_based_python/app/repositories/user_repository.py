from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.domain.models import User


async def get_by_username(session: AsyncSession, username: str) -> User | None:
    return await session.scalar(select(User).where(User.username == username))


def add(session: AsyncSession, user: User) -> None:
    session.add(user)


async def get_token_version(session: AsyncSession, username: str) -> int | None:
    """The user's current token_version; None when the user does not exist (any token is then invalid)."""
    return await session.scalar(select(User.token_version).where(User.username == username))


async def bump_token_version(session: AsyncSession, username: str) -> None:
    """Invalidate every token issued to this user so far."""
    await session.execute(update(User).where(User.username == username).values(token_version=User.token_version + 1))
