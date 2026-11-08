"""Startup check that the system DB schema is at the latest Alembic revision.

Migrations run before the app (compose "migrate" service, or MIGRATE_ON_START=true), not from every
app process: several replicas starting at once would race, and the app should not need DDL rights.
"""

from pathlib import Path

from sqlalchemy.ext.asyncio import AsyncEngine

from alembic.config import Config
from alembic.runtime.migration import MigrationContext
from alembic.script import ScriptDirectory

PROJECT_ROOT = Path(__file__).resolve().parents[3]


class SchemaNotCurrentError(RuntimeError):
    pass


def expected_revision() -> str | None:
    config = Config(str(PROJECT_ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(PROJECT_ROOT / "alembic"))
    return ScriptDirectory.from_config(config).get_current_head()


async def current_revision(engine: AsyncEngine) -> str | None:
    async with engine.connect() as conn:
        return await conn.run_sync(lambda sync_conn: MigrationContext.configure(sync_conn).get_current_revision())


async def ensure_schema_is_current(engine: AsyncEngine) -> None:
    current, expected = await current_revision(engine), expected_revision()
    if current != expected:
        raise SchemaNotCurrentError(
            f"System DB schema is at revision {current or 'none'}, this version needs {expected}. "
            "Run the migrations first: `docker compose run --rm migrate` (or `alembic upgrade head`), "
            "or set MIGRATE_ON_START=true for a single-instance setup."
        )
