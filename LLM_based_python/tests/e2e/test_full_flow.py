"""Spec §11 end-to-end flow through the HTTP API, with a mocked LLM provider."""

from sqlalchemy import func, select

from app.core.config import get_settings
from app.domain.enums import PiiCategory
from app.domain.models import DbConnection


async def test_full_business_flow(api, session, target_request, llm_provider):
    settings = get_settings()

    # 1. Authenticate (JSON body)
    response = await api.post(
        "/api/v1/auth", json={"username": settings.auth_username, "password": settings.auth_password}
    )
    assert response.status_code == 200
    headers = {"Authorization": f"Bearer {response.json()['access_token']}"}

    # 2-3. Connect to the demo database and extract metadata
    payload = target_request.model_dump() | {"password": target_request.password.get_secret_value()}
    response = await api.post("/api/v1/db/metadata", json=payload, headers=headers)
    assert response.status_code == 201, response.text
    metadata_id = response.json()["metadata_id"]

    # 4. List
    response = await api.get("/api/v1/metadata", headers=headers)
    assert response.status_code == 200
    items = response.json()["items"]
    assert [item["metadata_id"] for item in items] == [metadata_id]
    assert items[0]["table_count"] == 9

    # 5. Detail
    response = await api.get(f"/api/v1/metadata/{metadata_id}", headers=headers)
    assert response.status_code == 200
    tables = {t["table_name"]: t for t in response.json()["tables"]}

    # 6. Select a column
    email = next(c for c in tables["customers"]["columns"] if c["column_name"] == "email")

    # 7. Classify
    response = await api.post("/api/v1/classify", json={"column_id": email["column_id"]}, headers=headers)
    assert response.status_code == 200, response.text
    result = response.json()

    # 8. Validate the probability distribution
    assert set(result["classification"]) == {c.value for c in PiiCategory}
    assert all(0 <= p <= 1 for p in result["classification"].values())
    assert abs(sum(result["classification"].values()) - 1) < 1e-3
    assert result["predicted_category"] == "email_address"
    assert result["column_id"] == email["column_id"]
    assert len(llm_provider.requests) == 1

    # 9. Delete; it was the connection's only snapshot, so the stored credentials go too
    response = await api.delete(f"/api/v1/metadata/{metadata_id}", headers=headers)
    assert response.json() == {"metadata_id": metadata_id, "deleted": True}
    assert (await api.get(f"/api/v1/metadata/{metadata_id}", headers=headers)).status_code == 404
    assert (await api.delete(f"/api/v1/metadata/{metadata_id}", headers=headers)).status_code == 404
    classify_deleted = await api.post("/api/v1/classify", json={"column_id": email["column_id"]}, headers=headers)
    assert classify_deleted.status_code == 404
    assert await session.scalar(select(func.count()).select_from(DbConnection)) == 0


async def test_auth_accepts_basic_credentials(api):
    settings = get_settings()
    credentials = (settings.auth_username, settings.auth_password)

    response = await api.post("/api/v1/auth", auth=credentials)
    assert response.status_code == 200
    assert response.json()["token_type"] == "bearer"

    # Protected endpoints also accept Basic directly
    assert (await api.get("/api/v1/metadata", auth=credentials)).status_code == 200


async def test_pdf_root_paths_work_as_aliases(api, target_request):
    settings = get_settings()
    response = await api.post("/auth", auth=(settings.auth_username, settings.auth_password))
    headers = {"Authorization": f"Bearer {response.json()['access_token']}"}

    payload = target_request.model_dump() | {"password": target_request.password.get_secret_value()}
    created = await api.post("/db/metadata", json=payload, headers=headers)
    assert created.status_code == 201
    metadata_id = created.json()["metadata_id"]

    listed = await api.get("/metadata", headers=headers)
    assert [item["metadata_id"] for item in listed.json()["items"]] == [metadata_id]
    assert (await api.get(f"/metadata/{metadata_id}", headers=headers)).status_code == 200
    assert (await api.delete(f"/metadata/{metadata_id}", headers=headers)).json()["deleted"] is True


async def test_auth_rejects_invalid_credentials(api):
    response = await api.post("/api/v1/auth", json={"username": "admin", "password": "wrong"})
    assert response.status_code == 401
    response = await api.get("/api/v1/metadata", auth=("admin", "wrong"))
    assert response.status_code == 401


async def test_unreachable_target_returns_generic_400(api):
    settings = get_settings()
    response = await api.post(
        "/api/v1/db/metadata",
        json={"host": "no-such-host.invalid", "database": "x", "username": "u", "password": "p"},
        auth=(settings.auth_username, settings.auth_password),
    )
    assert response.status_code == 400
    assert response.json()["detail"].startswith("Could not connect to the target database")


async def test_logout_revokes_only_that_token(api):
    settings = get_settings()
    body = {"username": settings.auth_username, "password": settings.auth_password}
    first = (await api.post("/api/v1/auth", json=body)).json()["access_token"]
    second = (await api.post("/api/v1/auth", json=body)).json()["access_token"]

    logout = await api.post("/api/v1/auth/logout", headers={"Authorization": f"Bearer {first}"})
    assert logout.status_code == 204
    assert logout.content == b""

    revoked = await api.get("/api/v1/metadata", headers={"Authorization": f"Bearer {first}"})
    assert revoked.status_code == 401
    assert revoked.json() == {"detail": "Invalid or expired token"}
    assert (await api.post("/auth/logout", headers={"Authorization": f"Bearer {first}"})).status_code == 401
    assert (await api.get("/api/v1/metadata", headers={"Authorization": f"Bearer {second}"})).status_code == 200


async def test_readiness_with_a_reachable_system_db(api):
    response = await api.get("/health/ready")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "checks": {"system_db": "ok"}}


async def test_metrics_follow_a_classification(api, target_request):
    """Target connections, LLM calls and cache lookups show up in /metrics."""
    from prometheus_client import REGISTRY

    def value(name: str, **labels: str) -> float:
        return REGISTRY.get_sample_value(name, labels) or 0

    settings = get_settings()
    auth = (settings.auth_username, settings.auth_password)
    before = {
        "connect_ok": value("target_db_connections_total", outcome="ok"),
        "llm_ok": value("llm_requests_total", outcome="ok"),
        "miss": value("classification_cache_total", result="miss"),
        "hit": value("classification_cache_total", result="hit"),
    }
    payload = target_request.model_dump() | {"password": target_request.password.get_secret_value()}
    detail = (await api.post("/api/v1/db/metadata", json=payload, auth=auth)).json()
    customers = next(t for t in detail["tables"] if t["table_name"] == "customers")
    email = next(c["column_id"] for c in customers["columns"] if c["column_name"] == "email")
    for _ in range(2):  # miss, then hit
        assert (await api.post("/api/v1/classify", json={"column_id": email}, auth=auth)).status_code == 200

    assert value("target_db_connections_total", outcome="ok") == before["connect_ok"] + 3
    assert value("llm_requests_total", outcome="ok") == before["llm_ok"] + 1
    assert value("classification_cache_total", result="miss") == before["miss"] + 1
    assert value("classification_cache_total", result="hit") == before["hit"] + 1
    text = (await api.get("/metrics")).text
    assert 'route="/api/v1/classify",status="200"' in text
