"""Fixtures for tests that run against real PostgreSQL databases (integration + e2e).

The system DB is migrated with Alembic once per session and truncated before each test.
The target DB is a dedicated database seeded with the demo script (see docker-compose
"test" profile).
"""

import os
from collections.abc import AsyncIterator

import pytest
from alembic import command
from alembic.config import Config
from pydantic import SecretStr
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.core.config import get_settings
from app.domain.schemas import DbConnectionRequest

SYSTEM_TABLES = "users, db_columns, db_tables, db_metadata, db_connections"


@pytest.fixture(scope="session")
def migrated_system_db() -> None:
    command.upgrade(Config("alembic.ini"), "head")


@pytest.fixture
async def sessionmaker(migrated_system_db) -> AsyncIterator[async_sessionmaker[AsyncSession]]:
    # NullPool: every test runs in its own event loop, so connections must not be reused.
    engine = create_async_engine(get_settings().system_db_url, poolclass=NullPool)
    async with engine.begin() as conn:
        await conn.execute(text(f"TRUNCATE {SYSTEM_TABLES} CASCADE"))
    yield async_sessionmaker(engine, expire_on_commit=False)
    await engine.dispose()


@pytest.fixture
async def session(sessionmaker) -> AsyncIterator[AsyncSession]:
    async with sessionmaker() as session:
        yield session


@pytest.fixture
def target_request() -> DbConnectionRequest:
    return DbConnectionRequest(
        host=os.environ["TEST_TARGET_DB_HOST"],
        port=int(os.environ.get("TEST_TARGET_DB_PORT", "5432")),
        database=os.environ["TEST_TARGET_DB_NAME"],
        username=os.environ["TEST_TARGET_DB_USER"],
        password=SecretStr(os.environ["TEST_TARGET_DB_PASSWORD"]),
    )
