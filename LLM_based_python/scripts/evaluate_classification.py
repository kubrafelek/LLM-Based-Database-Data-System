"""Classify a fixed set of demo DB columns through the HTTP API and score the results.

Talks only to the public API, so it can evaluate any implementation of it (the Python
service now, the Java port later) and the two reports can be compared row by row.
Standard library only; runs inside the app container:

    docker compose exec -T app python - < scripts/evaluate_classification.py

Environment (defaults come from the app's .env):
    API_URL        default http://localhost:8000/api/v1
    AUTH_USERNAME, AUTH_PASSWORD
    DEMO_DB_HOST   default demo-db
    DEMO_DB_NAME; DEMO_DB_READONLY_USER / _PASSWORD (falls back to DEMO_DB_USER / _PASSWORD)
    SAMPLE_COUNT   default 10
    REPEAT         classify every column this many times, bypassing the cache, and report
                   whether the outcome is stable (temperature=0 is not fully deterministic); default 1
    REQUEST_TIMEOUT seconds per classification, default 600
"""

import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request

API_URL = os.environ.get("API_URL", "http://localhost:8000/api/v1").rstrip("/")
SAMPLE_COUNT = int(os.environ.get("SAMPLE_COUNT", "10"))
REPEAT = max(1, int(os.environ.get("REPEAT", "1")))
TIMEOUT = float(os.environ.get("REQUEST_TIMEOUT", "600"))
PII_THRESHOLD = 0.5
NOT_PII = "not_pii"

# (table, column) -> acceptable outcomes. An outcome is the predicted category when
# pii_likelihood >= 0.5, otherwise "not_pii". Several outcomes are accepted where the
# case study categories genuinely overlap.
EXPECTED: dict[tuple[str, str], tuple[str, ...]] = {
    ("customers", "first_name"): ("first_name",),
    ("customers", "last_name"): ("last_name",),
    ("customers", "email"): ("email_address",),
    ("customers", "phone_number"): ("phone_number",),
    ("customers", "date_of_birth"): ("date_of_birth",),
    ("customers", "national_id"): ("tckn",),
    ("customers", "customer_uuid"): (NOT_PII,),  # random identifier, not personal data by itself
    ("customers", "gender"): (NOT_PII,),
    ("customers", "registration_date"): (NOT_PII,),
    ("customers", "customer_notes"): (NOT_PII,),
    ("customer_addresses", "street_address"): ("home_address",),
    ("customer_addresses", "city"): ("home_address", NOT_PII),
    ("customer_addresses", "postal_code"): ("home_address", NOT_PII),
    ("employees", "first_name"): ("first_name",),
    ("employees", "email"): ("email_address",),
    ("employees", "phone"): ("phone_number",),
    ("employees", "social_security_number"): ("ssn", "tckn"),  # 11-digit values
    ("employees", "tax_number"): (NOT_PII, "national_id_number"),
    ("employees", "bank_account_iban"): (NOT_PII,),  # not one of the 12 categories
    ("employees", "salary"): (NOT_PII,),
    ("employees", "hire_date"): (NOT_PII,),
    ("payment_methods", "card_number_masked"): ("credit_card_number",),
    ("payment_methods", "card_holder_name"): ("full_name",),
    ("payment_methods", "bank_name"): (NOT_PII,),
    ("orders", "order_number"): (NOT_PII,),
    ("orders", "notes"): (NOT_PII,),
    ("audit_logs", "ip_address"): ("ip_address",),
    ("audit_logs", "user_agent"): (NOT_PII,),
    # JSONB change sets: one of five rows embeds a name and an email, the rest are statuses and numbers.
    ("audit_logs", "new_values"): (NOT_PII, "email_address", "first_name", "full_name"),
    ("products", "product_name"): (NOT_PII,),
    ("products", "unit_price"): (NOT_PII,),
}


def request(method: str, path: str, token: str | None = None, body: dict | None = None, basic=None):
    req = urllib.request.Request(f"{API_URL}{path}", method=method)
    if body is not None:
        req.data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    if basic:
        req.add_header("Authorization", "Basic " + base64.b64encode(":".join(basic).encode()).decode())
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as response:
            return response.status, json.loads(response.read() or b"null")
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read() or b"null")


def main() -> int:
    status, body = request("POST", "/auth", basic=(os.environ["AUTH_USERNAME"], os.environ["AUTH_PASSWORD"]))
    if status != 200:
        print(f"auth failed: {status} {body}", file=sys.stderr)
        return 1
    token = body["access_token"]

    status, metadata = request(
        "POST",
        "/db/metadata",
        token,
        {
            "host": os.environ.get("DEMO_DB_HOST", "demo-db"),
            "port": 5432,
            "database": os.environ["DEMO_DB_NAME"],
            "username": os.environ.get("DEMO_DB_READONLY_USER") or os.environ["DEMO_DB_USER"],
            "password": os.environ.get("DEMO_DB_READONLY_PASSWORD") or os.environ["DEMO_DB_PASSWORD"],
        },
    )
    if status != 201:
        print(f"metadata extraction failed: {status} {metadata}", file=sys.stderr)
        return 1
    column_ids = {
        (table["table_name"], column["column_name"]): column["column_id"]
        for table in metadata["tables"]
        for column in table["columns"]
    }

    stability = f" Stable ×{REPEAT} |" if REPEAT > 1 else ""
    print(f"| Column | Expected | Predicted | P(pred) | pii_likelihood | Outcome | ✓ | Time |{stability}")
    print("|---|---|---|---|---|---|---|---|" + ("---|" if REPEAT > 1 else ""))
    passed = stable = llm_stable = 0
    for (table, column), acceptable in EXPECTED.items():
        expected = " / ".join(acceptable)
        body = {"column_id": column_ids[(table, column)], "sample_count": SAMPLE_COUNT}
        if REPEAT > 1:
            body["force_refresh"] = True  # measure the LLM, not the cache
        started = time.monotonic()
        runs = [request("POST", "/classify", token, body) for _ in range(REPEAT)]
        elapsed = (time.monotonic() - started) / REPEAT
        errors = [result for status, result in runs if status != 200]
        if errors:
            print(f"| {table}.{column} | {expected} | error: {errors[0]} | | | | ❌ | {elapsed:.0f}s |")
            continue
        results = [result for _, result in runs]
        outcomes = [outcome_of(r) for r in results]
        result = results[0]
        predicted = result["predicted_category"]  # null when no category fits
        ok = outcomes[0] in acceptable
        passed += ok
        probability = f"{result['classification'][predicted]:.2f}" if predicted else "–"
        row = (
            f"| {table}.{column} | {expected} | {predicted or '(none)'} | {probability} | "
            f"{result['pii_likelihood']:.2f} | {outcomes[0]} | {'✅' if ok else '❌'} | {elapsed:.0f}s |"
        )
        if REPEAT > 1:
            is_stable = len(set(outcomes)) == 1
            stable += is_stable
            # The LLM's own top category, before the deterministic checks are fused in.
            llm_stable += len({max(r["llm_classification"], key=r["llm_classification"].get) for r in results}) == 1
            row += f" {'✅' if is_stable else '❌ ' + ', '.join(outcomes)} |"
        print(row, flush=True)

    request("DELETE", f"/metadata/{metadata['metadata_id']}", token)
    print(f"\n**{passed}/{len(EXPECTED)} columns as expected** (sample_count={SAMPLE_COUNT})")
    if REPEAT > 1:
        print(
            f"**{stable}/{len(EXPECTED)} outcomes identical across {REPEAT} runs** "
            f"(LLM top category alone: {llm_stable}/{len(EXPECTED)})"
        )
    return 0


def outcome_of(result: dict) -> str:
    """The predicted category when pii_likelihood >= 0.5, otherwise not_pii."""
    predicted = result["predicted_category"]
    return predicted if predicted is not None and result["pii_likelihood"] >= PII_THRESHOLD else NOT_PII


if __name__ == "__main__":
    sys.exit(main())
