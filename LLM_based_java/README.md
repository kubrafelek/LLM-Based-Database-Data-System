# LLM Data Discovery: Java implementation

> Task 2 of the case study: the Python system in [../LLM_based_python](../LLM_based_python/) converted to Java with an AI coding assistant. How it was done (steps, methods, prompts, corrections, verification) is in **[docs/MIGRATION_REPORT.md](docs/MIGRATION_REPORT.md)**. All commands below run from this folder (`cd LLM_based_java`).

A Spring Boot service that connects to PostgreSQL databases, extracts and stores their schema metadata, and classifies columns into 12 PII categories. It samples column values, masks them, asks an OpenAI-compatible LLM for a probability distribution, and backs the answer with deterministic checks (TCKN, Luhn, email, IP). Its HTTP API and JSON contract are identical to the Python service.

## Architecture

Three independent feature modules plus shared infrastructure (`src/main/java/com/llmdiscovery/`):

| Module | Responsibility |
|---|---|
| `common` | Configuration from `.env` (`AppProperties`), `{"detail"}` error mapping (`ErrorHandler`), request size limit, CORS, UI security headers, AES-256-GCM (`SecretCipher`) |
| `auth` | `POST /auth` (JSON body or HTTP Basic → JWT), Argon2id users, admin seeding, request filter that accepts a Bearer token or Basic credentials |
| `discovery` | Target DB access (`TargetDatabase`: read-only JDBC sessions, `pg_catalog` inspection, deterministic sampling), snapshots (JPA entities), metadata CRUD |
| `classification` | Masking, prompts, `LlmClient` (retry/backoff), output parser, checksum evidence and fusion, result cache |

```
Client ── /api/v1/* ──> AuthenticationFilter ──> Controllers ──> Services
                                                    │               ├─ TargetDatabase (JDBC) ──> target PostgreSQL (read-only)
                                                    │               ├─ JPA / JdbcTemplate ─────> system PostgreSQL (Flyway)
                                                    │               └─ LlmClient (RestClient) ─> OpenAI-compatible API
                                                    └─ /ui (static), /docs (Swagger), /health
```

## Technology Stack

| Purpose | Library |
|---|---|
| Runtime | Java 21, Spring Boot 3.3 (Web MVC, Validation) |
| System DB | Spring Data JPA (Hibernate 6), `JdbcTemplate` for upserts/JSONB, Flyway migrations |
| Target DBs | PostgreSQL JDBC driver (`PGSimpleDataSource`) |
| LLM | Spring `RestClient` on the JDK `HttpClient` |
| Security | Spring Security (stateless `SecurityFilterChain`, Bearer or Basic), Argon2id (Bouncy Castle), jjwt (HS256), JCA AES-GCM |
| Docs | springdoc-openapi (Swagger UI at `/docs`) |
| Tests | JUnit 5, AssertJ, Spring Boot Test |

## Prerequisites

Docker with Compose v2. A JDK 21 and Maven 3.9 are only needed to build or run tests outside Docker.

## Environment Configuration

Same variables as the Python service (see its [README](../LLM_based_python/README.md#environment-configuration)). Only the default host ports differ, so both services can run side by side: `APP_PORT=8080`, `DEMO_DB_EXPOSED_PORT=5434`.

```bash
cp .env.example .env
# Fill in: SYSTEM_DB_PASSWORD, AUTH_PASSWORD, JWT_SECRET_KEY (≥ 32 bytes), ENCRYPTION_KEY (base64 32 bytes),
#          LLM_API_KEY, DEMO_DB_PASSWORD, DEMO_DB_READONLY_PASSWORD
# .env.example ships development-only keys, so the stack starts as is. For anything shared:
sed -i.bak "s|^JWT_SECRET_KEY=.*|JWT_SECRET_KEY=$(openssl rand -hex 32)|" .env
sed -i.bak "s|^ENCRYPTION_KEY=.*|ENCRYPTION_KEY=$(openssl rand -base64 32)|" .env && rm .env.bak
```

`JWT_SECRET_KEY` must be at least 32 bytes for HS256 (jjwt refuses weaker keys).

## Docker Compose Startup

```bash
docker compose up --build -d
curl http://localhost:8080/health   # {"status":"ok"}
open http://localhost:8080/         # web UI
```

| Service | Purpose |
|---|---|
| `app` | Spring Boot on port `8080` (non-root, healthcheck) |
| `system-db` | PostgreSQL 16, the service's own database |
| `demo-db` | PostgreSQL 16 seeded from [db/demo/](db/demo/) (case study script + SELECT-only `discovery_reader` login); host port `5434` |

## Database Initialization and Migrations

- Flyway runs [V1__schema.sql](src/main/resources/db/migration/V1__schema.sql) on startup. It is the same schema as the Python service after Alembic 0001–0003.
- `baseline-on-migrate` is on: pointed at a system DB created by the Python service, Flyway treats it as V1, so **both services can share one system database**. Encrypted passwords, Argon2 hashes and JWTs are interchangeable (golden-tested).
- The admin user is created/updated from `AUTH_USERNAME` / `AUTH_PASSWORD` at startup.

## API

Identical to the Python service, including the PDF root-path aliases (`/auth`, `/db/metadata`, `/metadata`, `/metadata/{id}`, `/classify`): see its [API section](../LLM_based_python/README.md#api-endpoints). Swagger UI: http://localhost:8080/docs (disabled when `APP_ENV=production`).

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<AUTH_PASSWORD>"}' | jq -r .access_token)
curl -s -X POST localhost:8080/api/v1/db/metadata -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"host":"demo-db","port":5432,"database":"llm_discovery_demo","username":"discovery_reader","password":"<DEMO_DB_READONLY_PASSWORD>"}'
curl -s -X POST localhost:8080/api/v1/classify -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"column_id":"<column_id>","sample_count":10}'
```

Classification, masking, checksum evidence, the cache and the design decisions work exactly as described in the Python README ([How Classification Works](../LLM_based_python/README.md#how-classification-works), [Design Decisions & Assumptions](../LLM_based_python/README.md#design-decisions--assumptions)).

## Running Tests

| Suite | What | Command |
|---|---|---|
| Golden (102) | Java output == Python output for masking, checks, evidence, fusion, parsing, prompt text; reads Python-written ciphertext, Argon2 hash and JWT | `mvn test` |
| Unit (32) | LLM client against a stub server (retries, quota, parse retries), cipher/JWT/Argon2, SQL quoting and sampling query, cache key | `mvn test` |
| Integration (27) | Against throwaway PostgreSQL databases: metadata persistence, encryption at rest, cascade delete, pagination, classification flow, cache, read-only access, full HTTP flow | `docker compose --profile test run --build --rm tests mvn -B test` |
| Contract (37) | The Python project's black-box HTTP suite, unchanged, against this service | `docker compose --profile contract run --build --rm contract-tests` |

```bash
docker compose --profile test run --build --rm tests mvn -B test   # all 161 JUnit tests
docker compose rm -sf test-system-db test-target-db                # clean up the test databases
docker compose --profile contract run --build --rm contract-tests  # 37 contract tests (needs `up -d` and a real LLM key)
```

Integration tests run when `RUN_INTEGRATION` is set (the compose `tests` service sets it); plain `mvn test` runs golden and unit tests only.

Real-LLM evaluation with the Python project's script:

```bash
docker compose --profile contract run --rm -T -e API_URL=http://app:8080/api/v1 contract-tests \
  python - < ../LLM_based_python/scripts/evaluate_classification.py
```

To regenerate the golden data after changing the Python reference, see [scripts/generate_golden.py](scripts/generate_golden.py).

## Security Considerations

The same controls as the Python service ([Security Considerations](../LLM_based_python/README.md#security-considerations)): Argon2id with a dummy-hash timing guard, AES-256-GCM at rest, read-only and time-limited target sessions plus a SELECT-only login, validated and quoted identifiers with a bound limit, masked and delimited LLM input, strictly validated LLM output, request size and sample limits, no credentials or sample values in logs or responses, CORS off by default, a strict CSP on `/ui`. Java specifics:

- Target connections are configured field by field on `PGSimpleDataSource`, never by concatenating a JDBC URL, so a database name cannot inject driver parameters.
- Unexpected exceptions return a generic `{"detail": "Internal Server Error"}`; stack traces only go to the log.
- The container runs as a non-root user.

## Differences from the Python service

See [MIGRATION_REPORT.md §8](docs/MIGRATION_REPORT.md#8-remaining-differences-accepted): stricter ASCII-only digit checks, a slightly smaller validation error body, separate cache keys when sharing a system DB, and compose test databases instead of Testcontainers.
