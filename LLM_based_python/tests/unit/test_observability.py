"""Request IDs, JSON / text logs and the /metrics endpoint."""

import json
import logging
import re

import pytest
from fastapi.testclient import TestClient
from prometheus_client import REGISTRY

from app.core.config import get_settings
from app.core.logging_config import TEXT_FORMAT, JsonFormatter, RequestIdFilter
from app.core.request_context import request_id_var, resolve_request_id
from app.main import app

client = TestClient(app)
HEX_ID = re.compile(r"[0-9a-f]{32}")


def requests_counted(route: str, status: str, method: str = "GET") -> float:
    labels = {"method": method, "route": route, "status": status}
    return REGISTRY.get_sample_value("http_requests_total", labels) or 0


def test_a_request_id_is_generated_and_returned():
    first = client.get("/health").headers["x-request-id"]
    second = client.get("/health").headers["x-request-id"]
    assert HEX_ID.fullmatch(first)
    assert first != second


def test_a_valid_supplied_request_id_is_reused():
    response = client.get("/health", headers={"X-Request-ID": "trace-42.a_b"})
    assert response.headers["x-request-id"] == "trace-42.a_b"


@pytest.mark.parametrize("supplied", ["has space", "a" * 129, "line\\nbreak", "ü"])
def test_an_unsafe_supplied_request_id_is_replaced(supplied):
    assert HEX_ID.fullmatch(resolve_request_id(supplied))


def test_error_responses_carry_the_request_id_too():
    assert client.get("/api/v1/metadata").headers["x-request-id"]  # 401
    too_big = client.post("/api/v1/classify", content=b"x" * (get_settings().max_request_body_bytes + 1))
    assert too_big.status_code == 413
    assert too_big.headers["x-request-id"]


def test_unexpected_errors_carry_the_request_id():
    from app.infrastructure.database.system_database import get_session

    async def broken_session():
        raise RuntimeError("boom")
        yield  # pragma: no cover

    app.dependency_overrides[get_session] = broken_session
    try:
        response = TestClient(app, raise_server_exceptions=False).get(
            "/health/ready", headers={"X-Request-ID": "req-500"}
        )
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 500
    assert response.headers["x-request-id"] == "req-500"


def test_requests_are_counted_by_route_template():
    before = requests_counted("/api/v1/metadata/{metadata_id}", "401")
    client.get("/api/v1/metadata/00000000-0000-0000-0000-000000000000")
    client.get("/api/v1/metadata/11111111-1111-1111-1111-111111111111")
    assert requests_counted("/api/v1/metadata/{metadata_id}", "401") == before + 2


def test_versioned_and_alias_paths_get_separate_labels():
    before_v1 = requests_counted("/api/v1/metadata", "401")
    before_alias = requests_counted("/metadata", "401")
    client.get("/api/v1/metadata")
    client.get("/metadata")
    assert requests_counted("/api/v1/metadata", "401") == before_v1 + 1
    assert requests_counted("/metadata", "401") == before_alias + 1


def test_static_ui_files_share_one_label():
    before = requests_counted("/ui/*", "200")
    client.get("/ui/app.js")
    client.get("/ui/styles.css")
    assert requests_counted("/ui/*", "200") == before + 2


def test_unknown_paths_share_one_label():
    before = requests_counted("unmatched", "404")
    client.get("/no/such/path/123")
    client.get("/another/unknown")
    assert requests_counted("unmatched", "404") == before + 2


def test_metrics_endpoint_serves_prometheus_text():
    client.get("/health")
    response = client.get("/metrics")
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/plain")
    body = response.text
    assert 'http_requests_total{method="GET",route="/health",status="200"}' in body
    assert "http_request_duration_seconds_bucket" in body
    for name in ("llm_requests", "classification_cache", "login_failures", "target_db_connections"):
        assert f"# TYPE {name}_total counter" in body


def test_metrics_token_is_required_when_set(monkeypatch):
    import app.main as main

    monkeypatch.setattr(main, "settings", main.settings.model_copy(update={"metrics_token": "scrape-secret"}))
    assert client.get("/metrics").status_code == 401
    assert client.get("/metrics", headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert client.get("/metrics", headers={"Authorization": "Bearer scrape-secret"}).status_code == 200


def make_record(**extra) -> logging.LogRecord:
    record = logging.LogRecord("app.test", logging.INFO, __file__, 1, "hello %s", ("world",), None)
    for key, value in extra.items():
        setattr(record, key, value)
    RequestIdFilter().filter(record)
    return record


def test_json_log_line_has_the_request_id_and_access_fields():
    token = request_id_var.set("req-1")
    try:
        record = make_record(method="GET", path="/health", status=200, duration_ms=1.5, client_ip="10.0.0.1")
    finally:
        request_id_var.reset(token)
    entry = json.loads(JsonFormatter().format(record))
    assert entry["message"] == "hello world"
    assert entry["level"] == "INFO"
    assert entry["logger"] == "app.test"
    assert entry["request_id"] == "req-1"
    assert entry["timestamp"].endswith("+00:00")
    assert {k: entry[k] for k in ("method", "path", "status", "duration_ms", "client_ip")} == {
        "method": "GET",
        "path": "/health",
        "status": 200,
        "duration_ms": 1.5,
        "client_ip": "10.0.0.1",
    }


def test_json_log_line_outside_a_request_and_with_an_exception():
    try:
        raise ValueError("bad")
    except ValueError:
        import sys

        record = make_record()
        record.exc_info = sys.exc_info()
    entry = json.loads(JsonFormatter().format(record))
    assert "request_id" not in entry
    assert "ValueError: bad" in entry["exception"]


def test_text_log_line_shows_the_request_id():
    token = request_id_var.set("req-2")
    try:
        line = logging.Formatter(TEXT_FORMAT).format(make_record())
    finally:
        request_id_var.reset(token)
    assert "[app.test] [req-2] hello world" in line
