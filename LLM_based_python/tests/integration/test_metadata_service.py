import logging

import pytest
from pydantic import SecretStr
from sqlalchemy import func, select

from app.api.dependencies import get_cipher
from app.core.config import get_settings
from app.core.exceptions import InvalidConnectionError, InvalidRequestError, NotFoundError
from app.domain.models import DbColumn, DbConnection, DbTable
from app.infrastructure.database.target_database import CONNECT_ERROR
from app.services import metadata_service

DEMO_RELATIONS = {
    "audit_logs",
    "customer_addresses",
    "customer_order_summary",
    "customers",
    "employees",
    "order_items",
    "orders",
    "payment_methods",
    "products",
}


async def create(session, request):
    return await metadata_service.create_metadata(session, request, get_settings(), get_cipher())


async def test_extracts_and_persists_demo_schema(session, target_request):
    detail = await create(session, target_request)

    assert {t.table_name for t in detail.tables} == DEMO_RELATIONS
    assert detail.table_count == len(DEMO_RELATIONS)
    customers = next(t for t in detail.tables if t.table_name == "customers")
    types = {c.column_name: c.data_type for c in customers.columns}
    assert types["email"] == "character varying(100)"
    assert types["date_of_birth"] == "date"
    view = next(t for t in detail.tables if t.table_name == "customer_order_summary")
    assert view.table_type == "VIEW"

    stored_columns = await session.scalar(select(func.count()).select_from(DbColumn))
    assert stored_columns == sum(len(t.columns) for t in detail.tables)
    column_ids = [c.column_id for t in detail.tables for c in t.columns]
    assert len(set(column_ids)) == len(column_ids)


async def test_password_is_encrypted_at_rest(session, target_request):
    await create(session, target_request)
    connection = await session.scalar(select(DbConnection))
    password = target_request.password.get_secret_value()
    assert password not in connection.encrypted_password
    assert get_cipher().decrypt(connection.encrypted_password) == password


async def test_snapshots_reuse_the_same_connection(session, target_request):
    first = await create(session, target_request)
    second = await create(session, target_request)

    assert first.metadata_id != second.metadata_id
    assert first.connection_id == second.connection_id
    assert await session.scalar(select(func.count()).select_from(DbConnection)) == 1


async def test_schema_filter(session, target_request):
    detail = await create(session, target_request.model_copy(update={"schema_name": "public"}))
    assert detail.schema_name == "public"
    assert detail.table_count == len(DEMO_RELATIONS)

    with pytest.raises(InvalidRequestError):
        await create(session, target_request.model_copy(update={"schema_name": "missing"}))


async def test_list_get_and_delete(session, target_request):
    first = await create(session, target_request)
    second = await create(session, target_request)

    page = await metadata_service.list_metadata(session, limit=1, offset=0)
    assert page.total == 2
    assert [item.metadata_id for item in page.items] == [second.metadata_id]
    assert page.items[0].table_count == len(DEMO_RELATIONS)

    detail = await metadata_service.get_metadata(session, first.metadata_id)
    assert detail.tables == first.tables

    await metadata_service.delete_metadata(session, first.metadata_id)
    with pytest.raises(NotFoundError):
        await metadata_service.get_metadata(session, first.metadata_id)
    with pytest.raises(NotFoundError):
        await metadata_service.delete_metadata(session, first.metadata_id)

    # Only the deleted snapshot's tables are gone; the shared connection stays.
    assert await session.scalar(select(func.count()).select_from(DbTable)) == len(DEMO_RELATIONS)
    assert await session.scalar(select(func.count()).select_from(DbConnection)) == 1


async def test_bad_credentials_give_generic_error_and_no_secret_in_logs(session, target_request, caplog):
    wrong = target_request.model_copy(update={"password": SecretStr("wrong-password-123")})
    with caplog.at_level(logging.DEBUG), pytest.raises(InvalidConnectionError) as exc_info:
        await create(session, wrong)

    # Generic message: the driver's "password authentication failed for user ..." is not exposed.
    assert exc_info.value.detail == CONNECT_ERROR
    assert "wrong-password-123" not in caplog.text
    assert await session.scalar(select(func.count()).select_from(DbConnection)) == 0
