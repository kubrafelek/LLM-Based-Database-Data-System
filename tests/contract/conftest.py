"""Black-box API contract tests: HTTP only, no imports from `app`.

They run against any deployment of the API (the Python service now, the Java port later),
so both implementations are held to the same contract and business rules:

    CONTRACT_BASE_URL=http://app:8000 pytest tests/contract

Credentials and the target database default to the values in .env (AUTH_*, DEMO_DB_*).
The target must be the case study demo database. Classification calls a real LLM, so the
tests only assert rules that hold for any LLM answer.
"""

import os
from collections.abc import Iterator

import httpx
import pytest


def env(name: str, default: str | None = None) -> str:
    value = os.environ.get(name, default)
    if value is None:
        pytest.fail(f"{name} must be set for contract tests")
    return value


@pytest.fixture(scope="session")
def credentials() -> tuple[str, str]:
    return env("CONTRACT_USERNAME", os.environ.get("AUTH_USERNAME")), env(
        "CONTRACT_PASSWORD", os.environ.get("AUTH_PASSWORD")
    )


@pytest.fixture(scope="session")
def target() -> dict:
    return {
        "host": env("CONTRACT_TARGET_HOST", "demo-db"),
        "port": int(env("CONTRACT_TARGET_PORT", "5432")),
        "database": env("CONTRACT_TARGET_DATABASE", os.environ.get("DEMO_DB_NAME")),
        "username": env("CONTRACT_TARGET_USERNAME", os.environ.get("DEMO_DB_USER")),
        "password": env("CONTRACT_TARGET_PASSWORD", os.environ.get("DEMO_DB_PASSWORD")),
    }


@pytest.fixture(scope="session")
def client() -> Iterator[httpx.Client]:
    with httpx.Client(base_url=env("CONTRACT_BASE_URL").rstrip("/"), timeout=180) as c:
        yield c


@pytest.fixture(scope="session")
def auth(client, credentials) -> dict[str, str]:
    username, password = credentials
    response = client.post("/api/v1/auth", json={"username": username, "password": password})
    assert response.status_code == 200, response.text
    return {"Authorization": f"Bearer {response.json()['access_token']}"}


@pytest.fixture(scope="session")
def snapshot(client, auth, target) -> Iterator[dict]:
    """One metadata extraction shared by the read-only tests; deleted at the end."""
    response = client.post("/api/v1/db/metadata", json=target, headers=auth)
    assert response.status_code == 201, response.text
    detail = response.json()
    yield detail
    client.delete(f"/api/v1/metadata/{detail['metadata_id']}", headers=auth)


@pytest.fixture(scope="session")
def column_id(snapshot):
    def find(table: str, column: str) -> str:
        t = next(t for t in snapshot["tables"] if t["table_name"] == table)
        return next(c["column_id"] for c in t["columns"] if c["column_name"] == column)

    return find
