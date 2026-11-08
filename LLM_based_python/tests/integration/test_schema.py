"""The app refuses to start on a system DB that is not at the latest migration."""

import asyncio

import pytest

from alembic import command
from alembic.config import Config
from app.infrastructure.database.schema import SchemaNotCurrentError, ensure_schema_is_current, expected_revision


async def test_current_schema_passes(sessionmaker):
    await ensure_schema_is_current(sessionmaker.kw["bind"])


async def test_outdated_schema_is_refused_with_instructions(sessionmaker):
    config = Config("alembic.ini")
    # alembic's env.py runs its own event loop, so it needs a thread here
    await asyncio.to_thread(command.downgrade, config, "-1")
    try:
        with pytest.raises(SchemaNotCurrentError, match="docker compose run --rm migrate") as exc:
            await ensure_schema_is_current(sessionmaker.kw["bind"])
        assert expected_revision() in str(exc.value)
    finally:
        await asyncio.to_thread(command.upgrade, config, "head")
