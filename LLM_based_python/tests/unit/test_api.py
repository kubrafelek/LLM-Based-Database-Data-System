"""HTTP-level checks that need no database: routing, auth guards, validation, limits."""

import pytest
from fastapi.testclient import TestClient

from app.core.config import get_settings
from app.core.security import create_access_token
from app.main import app

# No context manager: the lifespan (admin user seeding) needs the system DB.
client = TestClient(app)
ZERO_UUID = "00000000-0000-0000-0000-000000000000"


def auth_header() -> dict[str, str]:
    token, _ = create_access_token("admin", get_settings())
    return {"Authorization": f"Bearer {token}"}


def test_health():
    assert client.get("/health").json() == {"status": "ok"}


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("get", "/api/v1/metadata"),
        ("get", f"/api/v1/metadata/{ZERO_UUID}"),
        ("delete", f"/api/v1/metadata/{ZERO_UUID}"),
        ("post", "/api/v1/db/metadata"),
        ("post", "/api/v1/classify"),
    ],
)
def test_protected_endpoints_require_auth(method, path):
    assert getattr(client, method)(path).status_code == 401


def test_ui_is_served_without_auth():
    root = client.get("/", follow_redirects=False)
    assert root.status_code == 307
    assert root.headers["location"] == "/ui/"

    page = client.get("/ui/")
    assert page.status_code == 200
    assert "script-src 'self'" in page.headers["content-security-policy"]
    assert "frame-ancestors 'none'" in page.headers["content-security-policy"]
    for asset in ("/ui/app.js", "/ui/styles.css"):
        assert client.get(asset).status_code == 200


def test_only_versioned_paths_are_documented():
    paths = set(app.openapi()["paths"])
    assert paths == {
        "/api/v1/auth",
        "/api/v1/db/metadata",
        "/api/v1/metadata",
        "/api/v1/metadata/{metadata_id}",
        "/api/v1/classify",
        "/health",
    }


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("post", "/auth"),
        ("post", "/db/metadata"),
        ("get", "/metadata"),
        ("get", f"/metadata/{ZERO_UUID}"),
        ("delete", f"/metadata/{ZERO_UUID}"),
        ("post", "/classify"),
    ],
)
def test_pdf_paths_are_served_as_aliases(method, path):
    # Exists (auth guard answers) instead of 404
    assert getattr(client, method)(path).status_code == 401


def test_auth_without_credentials_is_rejected():
    response = client.post("/api/v1/auth")
    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Basic"


def test_invalid_bearer_token_is_rejected():
    response = client.get("/api/v1/metadata", headers={"Authorization": "Bearer not-a-jwt"})
    assert response.status_code == 401


@pytest.mark.parametrize("sample_count", [0, -1, "ten"])
def test_classify_validates_sample_count(sample_count):
    response = client.post(
        "/api/v1/classify",
        json={"column_id": ZERO_UUID, "sample_count": sample_count},
        headers=auth_header(),
    )
    assert response.status_code == 422


def test_classify_validates_column_id():
    response = client.post("/api/v1/classify", json={"column_id": "not-a-uuid"}, headers=auth_header())
    assert response.status_code == 422


def test_oversized_request_body_is_rejected():
    response = client.post(
        "/api/v1/classify",
        content=b"x" * (get_settings().max_request_body_bytes + 1),
        headers={**auth_header(), "Content-Type": "application/json"},
    )
    assert response.status_code == 413


def test_cors_is_disabled_by_default():
    response = client.options(
        "/api/v1/metadata",
        headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "GET"},
    )
    assert "access-control-allow-origin" not in response.headers


def test_unexpected_errors_return_generic_json():
    from app.infrastructure.database.system_database import get_session

    async def broken_session():
        raise RuntimeError("driver said: password authentication failed for user x")
        yield  # pragma: no cover

    app.dependency_overrides[get_session] = broken_session
    try:
        response = TestClient(app, raise_server_exceptions=False).get("/api/v1/metadata", headers=auth_header())
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 500
    assert response.json() == {"detail": "Internal Server Error"}
    assert "password" not in response.text


def test_repeated_wrong_passwords_get_429_with_retry_after(monkeypatch):
    from app.api.dependencies import get_login_limiter, get_session
    from app.core.rate_limit import LoginAttemptLimiter
    from app.services import auth_service

    async def no_session():
        yield None

    async def wrong_password(session, username, password):
        return False

    monkeypatch.setattr(auth_service, "authenticate", wrong_password)
    limiter = LoginAttemptLimiter(max_per_user=2, max_per_ip=10, window_seconds=60)
    app.dependency_overrides[get_session] = no_session
    app.dependency_overrides[get_login_limiter] = lambda: limiter
    try:
        body = {"username": "admin", "password": "guess"}
        assert client.post("/api/v1/auth", json=body).status_code == 401
        assert client.post("/api/v1/auth", json=body).status_code == 401
        blocked = client.post("/api/v1/auth", json=body)
        # Basic credentials on a protected endpoint share the same limit.
        basic = client.get("/api/v1/metadata", auth=("admin", "guess"))
    finally:
        app.dependency_overrides.clear()
    assert blocked.status_code == 429
    assert blocked.json() == {"detail": "Too many failed login attempts. Try again later."}
    assert 1 <= int(blocked.headers["retry-after"]) <= 60
    assert basic.status_code == 429
