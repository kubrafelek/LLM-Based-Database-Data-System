"""API contract and business rules every implementation (Python, Java) must satisfy."""

import base64
import uuid

import pytest

CATEGORIES = {
    "email_address", "phone_number", "ssn", "credit_card_number", "national_id_number", "full_name",
    "first_name", "last_name", "tckn", "home_address", "date_of_birth", "ip_address",
}  # fmt: skip
DEMO_RELATIONS = {
    "customers", "customer_addresses", "employees", "products", "payment_methods",
    "orders", "order_items", "audit_logs", "customer_order_summary",
}  # fmt: skip
DEMO_EMAILS = ["ahmet.yilmaz@email.com", "fatma.kaya@gmail.com", "mehmet.demir@hotmail.com"]
ZERO_UUID = "00000000-0000-0000-0000-000000000000"


def classify(client, auth, column_id, **body):
    return client.post("/api/v1/classify", json={"column_id": column_id, **body}, headers=auth)


def assert_valid_distribution(result):
    assert set(result["classification"]) == CATEGORIES
    assert all(0 <= p <= 1 for p in result["classification"].values())
    assert abs(sum(result["classification"].values()) - 1) < 1e-3
    assert 0 <= result["pii_likelihood"] <= 1
    assert result["predicted_category"] is None or result["predicted_category"] in CATEGORIES


# ---------- 1. Authentication ----------


def test_auth_with_json_body_returns_a_bearer_token(client, credentials):
    username, password = credentials
    response = client.post("/api/v1/auth", json={"username": username, "password": password})
    assert response.status_code == 200
    body = response.json()
    assert body["token_type"] == "bearer"
    assert body["access_token"]
    assert body["expires_in"] > 0


def test_auth_with_basic_credentials(client, credentials):
    basic = base64.b64encode(":".join(credentials).encode()).decode()
    response = client.post("/api/v1/auth", headers={"Authorization": f"Basic {basic}"})
    assert response.status_code == 200
    assert response.json()["access_token"]


def test_auth_rejects_a_wrong_password(client, credentials):
    response = client.post("/api/v1/auth", json={"username": credentials[0], "password": "wrong-password"})
    assert response.status_code == 401


def test_logout_revokes_only_that_token(client, credentials):
    username, password = credentials
    login = {"username": username, "password": password}
    first = client.post("/api/v1/auth", json=login).json()["access_token"]
    second = client.post("/api/v1/auth", json=login).json()["access_token"]

    response = client.post("/api/v1/auth/logout", headers={"Authorization": f"Bearer {first}"})
    assert response.status_code == 204

    revoked = client.get("/api/v1/metadata", headers={"Authorization": f"Bearer {first}"})
    assert revoked.status_code == 401
    assert revoked.json() == {"detail": "Invalid or expired token"}
    assert client.get("/api/v1/metadata", headers={"Authorization": f"Bearer {second}"}).status_code == 200


def test_logout_needs_a_bearer_token(client, credentials):
    assert client.post("/api/v1/auth/logout").status_code == 401
    basic = base64.b64encode(":".join(credentials).encode()).decode()
    response = client.post("/api/v1/auth/logout", headers={"Authorization": f"Basic {basic}"})
    assert response.status_code == 400
    assert response.json() == {"detail": "Only Bearer tokens can be revoked; Basic credentials have no session"}


def test_readiness_checks_the_system_database(client):
    response = client.get("/health/ready")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "checks": {"system_db": "ok"}}


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("GET", "/api/v1/metadata"),
        ("GET", f"/api/v1/metadata/{ZERO_UUID}"),
        ("DELETE", f"/api/v1/metadata/{ZERO_UUID}"),
        ("POST", "/api/v1/db/metadata"),
        ("POST", "/api/v1/classify"),
    ],
)
def test_protected_endpoints_require_a_token(client, method, path):
    assert client.request(method, path).status_code == 401
    assert client.request(method, path, headers={"Authorization": "Bearer not-a-jwt"}).status_code == 401


def test_pdf_root_paths_are_aliases(client, credentials, auth):
    username, password = credentials
    assert client.post("/auth", json={"username": username, "password": password}).status_code == 200
    assert client.get("/metadata", headers=auth).status_code == 200


def test_oversized_body_is_rejected(client, auth):
    response = client.post(
        "/api/v1/db/metadata", content=b"x" * (1024 * 1024), headers={**auth, "Content-Type": "application/json"}
    )
    assert response.status_code == 413


# ---------- 2. Metadata extraction ----------


def test_extraction_returns_tables_and_unique_column_ids(snapshot, target):
    uuid.UUID(snapshot["metadata_id"])
    assert {t["table_name"] for t in snapshot["tables"]} == DEMO_RELATIONS
    assert snapshot["table_count"] == len(snapshot["tables"])
    assert snapshot["database_name"] == target["database"]

    columns = [c for t in snapshot["tables"] for c in t["columns"]]
    ids = [c["column_id"] for c in columns]
    assert len(set(ids)) == len(ids)
    for column in columns:
        uuid.UUID(column["column_id"])
        assert column["data_type"]
        assert isinstance(column["is_nullable"], bool)
        assert column["ordinal_position"] >= 1


@pytest.mark.parametrize(
    ("table", "column", "data_type"),
    [
        ("customers", "email", "character varying(100)"),
        ("customers", "date_of_birth", "date"),
        ("employees", "salary", "numeric(10,2)"),
        ("audit_logs", "ip_address", "inet"),
    ],
)
def test_extraction_reports_postgres_data_types(snapshot, table, column, data_type):
    t = next(t for t in snapshot["tables"] if t["table_name"] == table)
    assert next(c["data_type"] for c in t["columns"] if c["column_name"] == column) == data_type


def test_extraction_never_returns_the_password(client, auth, target, snapshot):
    detail = client.get(f"/api/v1/metadata/{snapshot['metadata_id']}", headers=auth).text
    assert target["password"] not in detail


def test_extraction_with_wrong_credentials_gives_a_generic_error(client, auth, target):
    response = client.post("/api/v1/db/metadata", json={**target, "password": "wrong-pw-123"}, headers=auth)
    assert response.status_code == 400
    assert "wrong-pw-123" not in response.text


def test_extraction_validates_input(client, auth, target):
    assert client.post("/api/v1/db/metadata", json={**target, "port": 70000}, headers=auth).status_code == 422
    assert client.post("/api/v1/db/metadata", json={"host": "x"}, headers=auth).status_code == 422


# ---------- 3./4. Metadata list and detail ----------


def test_list_contains_the_snapshot(client, auth, snapshot):
    body = client.get("/api/v1/metadata", params={"limit": 100}, headers=auth).json()
    item = next(i for i in body["items"] if i["metadata_id"] == snapshot["metadata_id"])
    assert item["database_name"] == snapshot["database_name"]
    assert item["table_count"] == snapshot["table_count"]
    assert item["created_at"]
    assert body["total"] >= 1


def test_detail_matches_the_extraction(client, auth, snapshot):
    response = client.get(f"/api/v1/metadata/{snapshot['metadata_id']}", headers=auth)
    assert response.status_code == 200
    ids = lambda d: {c["column_id"] for t in d["tables"] for c in t["columns"]}  # noqa: E731
    assert ids(response.json()) == ids(snapshot)


def test_unknown_or_malformed_metadata_id(client, auth):
    assert client.get(f"/api/v1/metadata/{ZERO_UUID}", headers=auth).status_code == 404
    assert client.get("/api/v1/metadata/not-a-uuid", headers=auth).status_code == 422


# ---------- 6. Classification ----------


def test_classification_response_shape(client, auth, column_id):
    response = classify(client, auth, column_id("customers", "email"))
    assert response.status_code == 200
    result = response.json()
    assert_valid_distribution(result)
    assert result["sample_count_requested"] == 10  # default
    assert 0 < result["sample_count_used"] <= 10
    assert result["masking_mode"] in {"none", "partial", "strict"}
    assert set(result["llm_classification"]) == CATEGORIES
    assert isinstance(result["cached"], bool)
    assert result["classified_at"]


def test_classification_never_returns_sample_values(client, auth, column_id):
    text = classify(client, auth, column_id("customers", "email")).text
    for email in DEMO_EMAILS:
        assert email not in text


@pytest.mark.parametrize(
    ("body", "status"),
    [
        ({"column_id": ZERO_UUID}, 404),
        ({"column_id": "not-a-uuid"}, 422),
        ({}, 422),
    ],
)
def test_classification_validates_column_id(client, auth, body, status):
    assert client.post("/api/v1/classify", json=body, headers=auth).status_code == status


@pytest.mark.parametrize("sample_count", [0, -1, 101])
def test_classification_validates_sample_count(client, auth, column_id, sample_count):
    response = classify(client, auth, column_id("customers", "email"), sample_count=sample_count)
    assert response.status_code == 422


def test_tckn_format_evidence(client, auth, column_id):
    result = classify(client, auth, column_id("customers", "national_id")).json()
    assert_valid_distribution(result)
    [evidence] = result["checksum_evidence"]
    assert (evidence["category"], evidence["check"]) == ("tckn", "tckn_format")
    assert (evidence["matched"], evidence["checked"]) == (5, 5)
    # The generic national_id_number score is absorbed by tckn, whatever the LLM said.
    assert result["classification"]["national_id_number"] == 0
    assert result["classification"]["tckn"] >= 0.3


@pytest.mark.parametrize(
    ("table", "column", "category", "check"),
    [
        ("customers", "email", "email_address", "email_format"),
        ("audit_logs", "ip_address", "ip_address", "ip_address"),
    ],
)
def test_syntax_evidence_dominates(client, auth, column_id, table, column, category, check):
    result = classify(client, auth, column_id(table, column)).json()
    assert [e["check"] for e in result["checksum_evidence"]] == [check]
    assert result["classification"][category] >= 0.6  # (1 - 0.6) * llm + 0.6
    assert result["predicted_category"] == category


@pytest.mark.parametrize(
    ("table", "column"),
    [("products", "unit_price"), ("payment_methods", "card_number_masked"), ("customers", "first_name")],
)
def test_no_evidence_without_matching_values(client, auth, column_id, table, column):
    # Masked card numbers cannot pass Luhn; prices and names match no syntax check.
    result = classify(client, auth, column_id(table, column)).json()
    assert_valid_distribution(result)
    assert result["checksum_evidence"] == []
    assert result["classification"] == result["llm_classification"]


def test_unchanged_column_is_served_from_cache(client, auth, column_id):
    cid = column_id("customers", "phone_number")
    first = classify(client, auth, cid, force_refresh=True).json()
    second = classify(client, auth, cid).json()
    refreshed = classify(client, auth, cid, force_refresh=True).json()

    assert (first["cached"], second["cached"], refreshed["cached"]) == (False, True, False)
    assert second["classification"] == first["classification"]
    assert second["classified_at"] == first["classified_at"]


# ---------- 5. Delete ----------


def test_delete_snapshot_keeps_the_connection(client, auth, target, snapshot):
    created = client.post("/api/v1/db/metadata", json=target, headers=auth).json()
    # Same target -> same stored connection, new snapshot with new column IDs.
    assert created["connection_id"] == snapshot["connection_id"]
    assert created["metadata_id"] != snapshot["metadata_id"]
    some_column = created["tables"][0]["columns"][0]["column_id"]

    response = client.delete(f"/api/v1/metadata/{created['metadata_id']}", headers=auth)
    assert response.status_code == 200
    assert response.json() == {"metadata_id": created["metadata_id"], "deleted": True}

    assert client.get(f"/api/v1/metadata/{created['metadata_id']}", headers=auth).status_code == 404
    assert client.delete(f"/api/v1/metadata/{created['metadata_id']}", headers=auth).status_code == 404
    assert classify(client, auth, some_column).status_code == 404
    # The shared snapshot (same connection) is untouched.
    assert client.get(f"/api/v1/metadata/{snapshot['metadata_id']}", headers=auth).status_code == 200
