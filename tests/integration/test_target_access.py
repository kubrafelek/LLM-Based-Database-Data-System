"""Least privilege on the target database: read-only sessions and the SELECT-only role."""

import asyncpg
import pytest

from app.api.dependencies import get_cipher
from app.core.config import get_settings
from app.domain.schemas import ClassifyRequest
from app.infrastructure.database.target_database import ConnectionInfo, connect_target
from app.services import metadata_service
from app.services.classification_service import classify_column
from tests.fakes import FakeLLMProvider

INSERT = "INSERT INTO customers (first_name, last_name, email) VALUES ('x', 'y', 'ro.test@example.com')"


def connection_info(request) -> ConnectionInfo:
    return ConnectionInfo(
        host=request.host,
        port=request.port,
        database=request.database,
        username=request.username,
        password=request.password.get_secret_value(),
    )


async def test_app_session_is_read_only_even_for_the_table_owner(target_request):
    async with connect_target(connection_info(target_request), get_settings()) as conn:
        with pytest.raises(asyncpg.ReadOnlySQLTransactionError):
            await conn.execute(INSERT)


async def test_readonly_role_can_extract_and_classify(session, readonly_target_request):
    detail = await metadata_service.create_metadata(session, readonly_target_request, get_settings(), get_cipher())
    assert detail.table_count == 9  # 8 tables + 1 view, same as with the owner

    customers = next(t for t in detail.tables if t.table_name == "customers")
    email = next(c.column_id for c in customers.columns if c.column_name == "email")
    result = await classify_column(
        session, ClassifyRequest(column_id=email), get_settings(), get_cipher(), FakeLLMProvider().client()
    )
    assert result.sample_count_used == 5


async def test_readonly_role_cannot_write_even_outside_read_only_mode(readonly_target_request):
    # A plain connection, without the app's read-only session settings: privileges still forbid writes.
    info = connection_info(readonly_target_request)
    conn = await asyncpg.connect(
        host=info.host, port=info.port, database=info.database, user=info.username, password=info.password
    )
    try:
        await conn.execute("SET default_transaction_read_only = off")
        with pytest.raises(asyncpg.InsufficientPrivilegeError):
            await conn.execute(INSERT)
        with pytest.raises(asyncpg.InsufficientPrivilegeError):
            await conn.execute("CREATE TABLE ro_probe (id int)")
    finally:
        await conn.close()
