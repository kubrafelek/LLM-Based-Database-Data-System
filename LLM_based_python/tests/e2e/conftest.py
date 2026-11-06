from collections.abc import AsyncIterator

import httpx
import pytest

from app.api.dependencies import get_llm_client
from app.core.config import get_settings
from app.infrastructure.database.system_database import get_session
from app.main import app
from app.services.auth_service import ensure_admin_user
from tests.db_fixtures import migrated_system_db, session, sessionmaker, target_request  # noqa: F401
from tests.fakes import FakeLLMProvider


@pytest.fixture
def llm_provider() -> FakeLLMProvider:
    return FakeLLMProvider()


@pytest.fixture
async def api(sessionmaker, llm_provider) -> AsyncIterator[httpx.AsyncClient]:  # noqa: F811 (pytest fixture)
    """The real app over ASGI, with the test system DB and a fake LLM provider."""

    async def test_session():
        async with sessionmaker() as s:
            yield s

    async with sessionmaker() as s:
        await ensure_admin_user(s, get_settings())

    app.dependency_overrides[get_session] = test_session
    app.dependency_overrides[get_llm_client] = llm_provider.client
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://testserver") as client:
        yield client
    app.dependency_overrides.clear()
