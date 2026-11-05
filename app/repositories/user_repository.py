from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.domain.models import User


async def get_by_username(session: AsyncSession, username: str) -> User | None:
    return await session.scalar(select(User).where(User.username == username))


def add(session: AsyncSession, user: User) -> None:
    session.add(user)
