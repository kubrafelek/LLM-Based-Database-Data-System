# TASK.md: Progress Tracker

Status, decisions, tests and changes. Paths are relative to [LLM_based_python/](LLM_based_python/) or [LLM_based_java/](LLM_based_java/). Requirements: [docs/SPECIFICATION.md](docs/SPECIFICATION.md) and `case_study.pdf`. Usage and API: [README.md](README.md).

**Last updated:** 2026-11-08 · **Phase:** both tasks complete; hardening steps 34–38 merged.

## 1. Status

| Area | Status | Notes |
|---|---|---|
| Architecture, setup | ✅ | Spec §10 layers; Docker Compose per folder |
| Schema | ✅ | `users` (+ `token_version`), `revoked_tokens`, connections 1─N snapshots 1─N tables 1─N columns 1─N `classification_results`; Alembic `0001`–`0005`, Flyway `V1`–`V3`, run by a `migrate` service |
| Authentication | ✅ | JSON or Basic → JWT (`jti`, `ver`); Argon2id; logout, logout-all, password change revokes |
| Metadata extraction & CRUD | ✅ | `pg_catalog`, optional schema filter, pagination, PDF path aliases |
| LLM classification | ✅ | Masking, 12 keys + `pii_likelihood`, validation, retries, deterministic checks, cache |
| Security | ✅ | Spec §9 controls, SELECT-only target login, login throttling, SSRF host policy, target TLS modes |
| Operations | ✅ | Readiness, request IDs, JSON logs, Prometheus metrics |
| Testing | ✅ | Python 295 (97% cov), Java 232, contract 43/43 on both; CI: ruff, mypy, both suites |
| Java migration | ✅ | Spring Boot / Java 21; golden 102/102; evaluation 29/29 ([report](LLM_based_java/docs/MIGRATION_REPORT.md)) |

Spec deviations (🟡): `asyncpg` instead of `psycopg` (D4); no `APP_SECRET_KEY` (D7).

## 2. Decisions

| ID | Decision |
|---|---|
| D1 | Non-PII: 12 keys sum to 1 plus a separate `pii_likelihood`; all-zero LLM reply → `predicted_category: null`, likelihood 0 |
| D2 | Auth accepts a JSON body and HTTP Basic |
| D3 | `SAMPLE_MASKING_MODE=none\|partial\|strict`, default `partial` |
| D4 | `httpx` `LLMClient` instead of the `openai` SDK; `asyncpg` instead of `psycopg` |
| D5 | Optional `schema_name` filter (null = all non-system schemas) |
| D6 | Same host/port/db/user → connection upsert, password refreshed |
| D7 | `APP_SECRET_KEY` omitted (no use); `APP_ENV=production` disables Swagger |
| D8 | Case study root paths served as hidden aliases of `/api/v1` |
| D9 | `gpt-4o-mini`; Ollama `llama3.1` optional (slow, weak on JSON) |
| D10 | TCKN / Luhn on raw samples; TCKN evidence moves `national_id_number` to `tckn`; fusion `(1−w)·llm + w·one_hot` when ≥ 80% pass |
| D11 | Static web UI at `/ui/`, strict CSP, token in `sessionStorage` |
| D12 | Email / IP format checks (weight 0.6); scores documented as uncalibrated |
| D13 | Cache keyed on column, count, HMAC sample fingerprint, model, prompt, logic version, masking, checks; deterministic sampling; TTL; `force_refresh` |
| D14 | HTTP-only contract suite, LLM-independent assertions |
| D15 | Open spec questions answered in README "Design decisions" and here |
| D16 | SELECT-only target login + read-only session |
| D17 | Two self-contained folders, fixed compose project names |
| D18 | Java 21, Spring Boot 3.5, Maven, JPA + Flyway, JDBC for targets, interchangeable Argon2id/JWT |
| D19 | Verification: unchanged contract suite, Python-generated golden data; fix the port, not the spec |
| D20 | Dev-only keys in `.env.example`, rejected in production; connection deleted with its last snapshot |
| D21 | Failed logins per IP + username (5) and per IP (20) in 300 s → `429` + `Retry-After`, before Argon2; in memory |
| D22 | Target host policy: system DB never; link-local, multicast, unspecified, reserved denied; loopback only if listed; optional allow list; connect to the checked addresses |
| D23 | Java trusts `X-Forwarded-*` only from loopback (as uvicorn), otherwise D21 could be bypassed |
| D24 | `TARGET_DB_SSL_MODE` (default `prefer`) + root cert; `verify-full` connects by host name; dev CA in `db/certs` |
| D25 | `/health/ready`: `SELECT 1` on the system DB, 3 s → 200/503; no LLM call |
| D26 | `jti` per token; `/auth/logout` stores it in `revoked_tokens` until expiry |
| D27 | `X-Request-ID` reused when safe, else generated; on every response and log line |
| D28 | `LOG_FORMAT=text\|json`; same JSON fields in both services; one access line per request |
| D29 | Prometheus `/metrics` (optional `METRICS_TOKEN`): HTTP by route template, LLM, cache, logins, target connections; known labels at 0 |
| D30 | `users.token_version` + `ver` claim; raised by a new `AUTH_PASSWORD` or `/auth/logout-all` (Bearer or Basic) |
| D31 | Migrations in a one-off `migrate` service; app refuses an outdated schema; Alembic advisory lock; `MIGRATE_ON_START` fallback |

## 3. Next steps

- Optional: batch / whole-table classification endpoint.
- Not covered by the contract suite: D21/D22 (would block later tests / need a system-DB target) and TLS (integration tests only).
- Open: tracing (OpenTelemetry); a separate DDL-only DB login for the `migrate` service.

## 4. Tests

Commands are in [README.md](README.md#running-tests). Totals (2026-11-08, parametrized cases included):

| Suite | Count | Covers |
|---|---|---|
| Python unit | 244 | API guards and errors, checks and fusion, cache keys, parser, config validation, LLM client, prompts, masking, security (JWT `jti`/`ver`), host policy, rate limit, observability |
| Python integration | 41 | Auth and admin seeding, schema check, metadata service, read-only target access, token revocation, target TLS modes, classification flow |
| Python e2e | 10 | Full business flow, Basic auth, aliases, logout / logout-all, password rotation, metrics, readiness |
| Contract | 43 | Same HTTP suite against both services: auth, logout(-all), readiness, request ID, metrics, extraction, classify, cache, delete |
| Java unit | 187 | Golden 102 plus the same cases as Python for the LLM client, security, host policy, limiter, observability, migration strategy |
| Java integration | 45 | Metadata, classification, target access and TLS, full HTTP flow |
| Evaluation | 29 columns | Real LLM over HTTP, §6 |

## 5. Change log

- **Steps 1–15:** FastAPI baseline, spec alignment (layers, `users`, Argon2id, `/api/v1`, 12-key response, masking, `LLMClient`, test levels), PDF path aliases.
- **Steps 16–25:** Real-LLM evaluation 11/29 → 29/29 via non-PII handling (D1), checksum fusion (D10), email/IP checks (D12), cache (D13); contract suite (D14).
- **Steps 26–33:** SELECT-only login (D16); repo split (D17); dependency audit; Java port (D18, D19: contract 37/37, evaluation 29/29); startup secret validation (D20); CI; README rewrites; web UI refresh.
- **Step 34–35:** Login throttling and SSRF host policy in both services (D21–D23); mypy in CI.
- **Step 36:** Target TLS, readiness, single-token revocation (D24–D26). Port defects caught: jjwt coerced numeric/empty `jti`; Java readiness hung 30 s.
- **Step 37:** Request IDs, JSON logs, metrics (D27–D29). Fixed: FastAPI nested routers dropped `/api/v1` from route labels; Spring 401s had no route.
- **Step 38:** Revoke all tokens (D30); migrations outside the app (D31). Verified live: outdated schema refused, concurrent Alembic runs safe.
- **Step 39:** Docs consolidated: one root README (Python and Java READMEs merged), TASK.md shortened, specification moved to `docs/SPECIFICATION.md`.

## 6. Classification evaluation

29 demo columns, `sample_count=10`, `partial` masking; outcome = predicted category if `pii_likelihood ≥ 0.5`, else `not_pii`.

| Run | LLM / code state | Result |
|---|---|---|
| 1–2 | Ollama `llama3.1`, `gpt-4o-mini` before fixes | 11/29, 13/29 (non-PII → 502) |
| 3 | `gpt-4o-mini`, robustness fixes | 28/29 (miss: `national_id`) |
| 4–5 | + checksum fusion, email/IP, cache | **29/29** |
| Java | Java port | **29/29**, same scores |

PII columns found (P = top probability): `customers.{first_name, last_name, email, phone_number, date_of_birth}` 1.00; `customers.national_id` → `tckn` 1.00; `customer_addresses.street_address` → `home_address` 1.00; `employees.{first_name, email, phone}` 1.00; `employees.social_security_number` → `ssn` 0.70; `payment_methods.card_number_masked` → `credit_card_number` 1.00, `card_holder_name` → `full_name` 0.80; `audit_logs.ip_address` 1.00. The other 15 columns (e.g. `city`, `postal_code`, `tax_number`, `salary`, `unit_price`) → `not_pii`, likelihood 0.00.
