import asyncio
from logging.config import fileConfig

from sqlalchemy import text
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import create_async_engine

from alembic import context
from app.core.config import get_settings
from app.domain.models import Base

config = context.config
if config.config_file_name is not None:
    fileConfig(config.config_file_name, disable_existing_loggers=False)

target_metadata = Base.metadata


def run_migrations_offline() -> None:
    context.configure(
        url=get_settings().system_db_url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )
    with context.begin_transaction():
        context.run_migrations()


# Any constant; shared by every process that migrates this database.
MIGRATION_LOCK_ID = 7_460_157_020


def do_run_migrations(connection: Connection) -> None:
    context.configure(connection=connection, target_metadata=target_metadata)
    with context.begin_transaction():
        # Two migrators at once (e.g. MIGRATE_ON_START on several replicas): the second waits here, then
        # reads the already-updated version and has nothing to do. Released when the transaction ends.
        connection.execute(text("SELECT pg_advisory_xact_lock(:id)"), {"id": MIGRATION_LOCK_ID})
        context.run_migrations()


async def run_migrations_online() -> None:
    engine = create_async_engine(get_settings().system_db_url)
    async with engine.connect() as connection:
        await connection.run_sync(do_run_migrations)
    await engine.dispose()


if context.is_offline_mode():
    run_migrations_offline()
else:
    asyncio.run(run_migrations_online())
