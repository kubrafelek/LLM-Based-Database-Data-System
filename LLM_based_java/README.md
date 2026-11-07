# LLM Data Discovery: Java implementation

A Spring Boot service that connects to a PostgreSQL database, stores its tables and columns, and uses an LLM to estimate which columns contain personal data (12 PII categories such as email, phone, TCKN, IBAN).

It is the Java port of [../LLM_based_python](../LLM_based_python/) with the same API. How the migration was done is in [docs/MIGRATION_REPORT.md](docs/MIGRATION_REPORT.md).

All commands below run from this folder (`cd LLM_based_java`).

---

## Quick start

You need **Docker with Compose v2**. Nothing else.

### 1. Create the `.env` file

```bash
cp .env.example .env
```

Open `.env` and set **`LLM_API_KEY`** (for example an OpenAI key). Everything else has working development defaults:

| Variable | Default | Used for |
|---|---|---|
| `AUTH_USERNAME` / `AUTH_PASSWORD` | `admin` / `change-me-admin` | Logging in to the API |
| `DEMO_DB_READONLY_USER` / `DEMO_DB_READONLY_PASSWORD` | `discovery_reader` / `change-me-demo-reader` | Read-only login to the demo database |
| `LLM_BASE_URL` / `LLM_MODEL` | OpenAI / `gpt-4o-mini` | Any OpenAI-compatible API works |

> Before sharing the deployment, replace the development keys:
> ```bash
> sed -i.bak "s|^JWT_SECRET_KEY=.*|JWT_SECRET_KEY=$(openssl rand -hex 32)|" .env
> sed -i.bak "s|^ENCRYPTION_KEY=.*|ENCRYPTION_KEY=$(openssl rand -base64 32)|" .env && rm .env.bak
> ```

### 2. Start the stack

```bash
docker compose up --build -d
curl http://localhost:8080/health
# {"status":"ok"}
```

This starts three containers:

| Service | What it is |
|---|---|
| `app` | The API on http://localhost:8080 |
| `system-db` | The service's own PostgreSQL (users, snapshots, results) |
| `demo-db` | A sample PostgreSQL with fake customer data, to try the service on |

### 3. Try it

Pick one:

- **Web UI:** http://localhost:8080/
- **Swagger UI:** http://localhost:8080/docs (see [Using Swagger UI](#using-swagger-ui))
- **curl:** follow the [walkthrough below](#api-walkthrough-with-curl)

---

## API walkthrough with curl

Each call below is a standalone example. Run them in order: the token from call 2 and the IDs from call 3 are used by the later calls. Replace the example IDs with your own values.

### 1. Health check (no token)

```bash
curl http://localhost:8080/health
```

### 2. Log in and get a token (no token)

```bash
curl -X POST http://localhost:8080/api/v1/auth \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"change-me-admin"}'
```

Copy `access_token` from the response and keep it in a variable for the next calls:

```bash
TOKEN=eyJhbGciOi...
```

The token is valid for **60 minutes**. When it expires you get `401 Invalid or expired token`, so log in again.

### 3. Scan a database

```bash
curl -X POST http://localhost:8080/api/v1/db/metadata \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
        "host": "demo-db",
        "port": 5432,
        "database": "llm_discovery_demo",
        "username": "discovery_reader",
        "password": "change-me-demo-reader"
      }'
```

Scan only one schema:

```bash
curl -X POST http://localhost:8080/api/v1/db/metadata \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
        "host": "demo-db",
        "port": 5432,
        "database": "llm_discovery_demo",
        "username": "discovery_reader",
        "password": "change-me-demo-reader",
        "schema_name": "public"
      }'
```

- `host` is resolved **from inside the `app` container**. Use `demo-db` for the demo database, and a reachable host name or IP for your own.
- The response contains the snapshot's `metadata_id` and a `column_id` for every column. Copy the ones you need:

```bash
METADATA_ID=3f6c2a1e-8b4d-4c7a-9e21-5d0f7b9a1c34
COLUMN_ID=a7d9e4b2-1c3f-4e8a-b5d6-9f0e2c7a4b18
```

### 4. List snapshots

```bash
curl "http://localhost:8080/api/v1/metadata" \
  -H "Authorization: Bearer $TOKEN"
```

With paging (`limit` 1–500, default 50; `offset` default 0):

```bash
curl "http://localhost:8080/api/v1/metadata?limit=10&offset=20" \
  -H "Authorization: Bearer $TOKEN"
```

### 5. View one snapshot with its tables and columns

```bash
curl http://localhost:8080/api/v1/metadata/$METADATA_ID \
  -H "Authorization: Bearer $TOKEN"
```

### 6. Classify a column

```bash
curl -X POST http://localhost:8080/api/v1/classify \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"column_id\":\"$COLUMN_ID\"}"
```

With a sample size (1–100, default 10):

```bash
curl -X POST http://localhost:8080/api/v1/classify \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"column_id\":\"$COLUMN_ID\",\"sample_count\":20}"
```

Skip the stored result and ask the LLM again:

```bash
curl -X POST http://localhost:8080/api/v1/classify \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"column_id\":\"$COLUMN_ID\",\"sample_count\":10,\"force_refresh\":true}"
```

- `classification` always has all 12 categories and they add up to 1.
- `predicted_category` is `null` when the column does not look like personal data.
- Repeating the request returns the stored result (`"cached": true`).

### 7. Delete a snapshot

Its tables, columns and classification results are deleted with it.

```bash
curl -X DELETE http://localhost:8080/api/v1/metadata/$METADATA_ID \
  -H "Authorization: Bearer $TOKEN"
```

### 8. Use Basic auth instead of a token

Any protected call also accepts HTTP Basic credentials:

```bash
curl http://localhost:8080/api/v1/metadata -u admin:change-me-admin
```

### 9. Paths without the `/api/v1` prefix

The paths from the case study PDF also work:

```bash
curl -X POST http://localhost:8080/auth \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"change-me-admin"}'

curl http://localhost:8080/metadata -H "Authorization: Bearer $TOKEN"
```

The same works for `/db/metadata`, `/metadata/{metadata_id}` and `/classify`.

### Endpoint summary

| Method | Path | Token needed |
|---|---|---|
| `GET` | `/health` | No |
| `POST` | `/api/v1/auth` | No |
| `POST` | `/api/v1/db/metadata` | Yes |
| `GET` | `/api/v1/metadata` | Yes |
| `GET` | `/api/v1/metadata/{metadata_id}` | Yes |
| `POST` | `/api/v1/classify` | Yes |
| `DELETE` | `/api/v1/metadata/{metadata_id}` | Yes |

Good to know:

- JSON fields are **snake_case** (`column_id`, not `columnId`).
- Add `| jq` to the end of any call to pretty-print the JSON.
- Errors always look like `{"detail": "..."}`.

---

## Using Swagger UI

1. Open http://localhost:8080/docs.
2. Under **auth**, open `POST /api/v1/auth` → **Try it out**, enter `{"username":"admin","password":"change-me-admin"}` → **Execute**.
3. Copy the `access_token` value (without the quotes).
4. Click **Authorize 🔓** at the top right of the page, paste the token (without `Bearer`), then click **Authorize** → **Close**.
5. The other endpoints now send the token automatically.

Swagger UI is turned off when `APP_ENV=production`.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `401 Not authenticated` | The `Authorization` header is missing. |
| `401 Invalid or expired token` | Get a new token ([call 2](#2-log-in-and-get-a-token-no-token)). |
| `401 Invalid credentials` on `/auth` | Check `AUTH_USERNAME` / `AUTH_PASSWORD` in `.env`, then run `docker compose up -d` again. |
| `400` / `502` when scanning a database | Wrong host, port or credentials, or the database is unreachable from the `app` container. |
| `502` from `/classify` | The LLM call failed: `LLM_API_KEY` is missing or invalid, or the account has no quota. The `detail` field says which. |
| App does not start | `docker compose logs app`. A missing or invalid setting is named in the log. |

Stop everything with `docker compose down` (add `-v` to delete the databases too).

---

## Running tests

```bash
mvn test                                                            # golden + unit tests (JDK 21, Maven 3.9)
docker compose --profile test run --build --rm tests mvn -B test    # all JUnit tests incl. integration
docker compose rm -sf test-system-db test-target-db                 # remove the test databases
docker compose --profile contract run --build --rm contract-tests   # Python contract suite (needs `up -d` and a real LLM key)
```

---

## More details

- **Project layout:** `src/main/java/com/llmdiscovery/` is split into `auth` (login, JWT, Argon2id), `discovery` (database scanning, snapshots), `classification` (masking, LLM, checksum checks, cache) and `common` (config, errors, encryption).
- **Database migrations:** Flyway creates the schema on startup ([V1__schema.sql](src/main/resources/db/migration/V1__schema.sql)). The admin user is created from `.env` on every startup.
- **How classification works, design decisions, security:** see the Python README ([classification](../LLM_based_python/README.md#how-classification-works), [design decisions](../LLM_based_python/README.md#design-decisions), [security](../LLM_based_python/README.md#security-considerations)). The Java service behaves the same way.
- **Differences from the Python service:** [MIGRATION_REPORT.md §8](docs/MIGRATION_REPORT.md#8-remaining-differences-accepted).
