# Python → Java Migration Report (Task 2)

This report documents how the FastAPI system in [LLM_based_python](../../LLM_based_python/) was converted into the Spring Boot service in this folder with an AI coding assistant: the tool, the method, the prompts, each step, the defects the process caught, and how equivalence was verified.

**Result:** the Java service passes the Python project's black-box API contract suite unchanged (37/37), classifies the 29 demo columns exactly like the Python service (29/29, same scores), and has its own suite of 161 JUnit tests, 102 of which are golden tests generated from the Python implementation.

---

## 1. Tool and setup

| Item | Value |
|---|---|
| AI tool | Claude Code (Anthropic), VS Code extension, model Claude Opus 5.5 |
| Mode | Agentic: the assistant reads the repository, writes files, runs Maven and Docker, and reads the results itself |
| Human role | Sets the goal, approves the technical choices, reviews the result |
| Source of truth | The Python implementation and its test suites (unit, integration, e2e, contract) |
| Target | Java 21, Spring Boot 3.3, Maven, Spring Data JPA + Flyway, JDBC for target DBs, `RestClient` for the LLM, JUnit 5 |

## 2. Method

The conversion was **test-anchored and module-by-module**, not a one-shot "translate this repository" prompt. Five techniques kept the port faithful:

1. **Analysis before translation.** The whole Python source was read first, and the behaviors that a literal translation would get wrong were listed up front (section 4).
2. **Feature modules.** `common` → `auth` → `discovery` → `classification`, each mapped to its Python counterpart and built in that order. That way every module depends only on modules that are already done.
3. **The Python contract as the specification.** The HTTP-only contract suite (`LLM_based_python/tests/contract`, 37 tests) was run unchanged against the Java service. Status codes, JSON field names and order, error bodies and business rules were all fixed by it.
4. **Golden tests generated from the reference.** [scripts/generate_golden.py](../scripts/generate_golden.py) runs the Python code on 98 inputs and writes the outputs to `src/test/resources/golden.json`. `GoldenTest` (102 cases) requires the Java code to give exactly the same output. The inputs cover masking, checks, evidence, fusion, LLM-output parsing, prompt text, and data written by Python (encrypted password, Argon2 hash, JWT).
5. **Real-LLM comparison.** The same evaluation script (`scripts/evaluate_classification.py`) was run against both services with `gpt-4o-mini`.

## 3. Prompts

### 3.1 Prompts given by the user (verbatim, Turkish, with translation)

| # | Prompt | Translation |
|---|---|---|
| U1 | *"java geliştirmesi için LLM_based_java adında bi klasörde java geliştirmelerini yapalım şuana kadarki yapılan geliştirmeleride LLM_based_python klasörüne taşıyabilirsin"* | "Let's do the Java development in a folder called LLM_based_java; you can move everything developed so far into an LLM_based_python folder." |
| U2 | (context pasted with U1) *"Üç farklı alanı doğru bir araya getirmek: 1. Database metadata discovery. 2. LLM tabanlı PII classification. 3. AI destekli Python → Java migration. Bunları birbirinden bağımsız modüller olarak tasarlarsan hem geliştirmesi hem test etmesi hem de Java'ya taşıması çok daha kolay olur."* | "Bring three areas together correctly: discovery, LLM classification, AI-assisted migration. Designing them as independent modules makes development, testing and the Java port much easier." |
| U3 | *"yok başlayabilirsin"*, answering the proposed stack (Java 21, Spring Boot 3, Maven, JPA + Flyway, JDBC for targets, RestClient, JUnit 5) | "No [changes], you can start." |

### 3.2 Step prompts

Within the session the assistant worked autonomously from U1–U3. The step prompts below are the instructions it executed for each step. They are written so that each step can be reproduced with any coding assistant on this repository.

**P0: Analysis**
> Read every file under `LLM_based_python/app`. For each module, list its public behavior, its inputs and outputs, and every place where a literal Python → Java translation would change behavior (rounding, modulo, regex, Unicode, null handling, JSON formatting, HTTP status codes, ORM semantics). Dump the live system DB schema with `pg_dump --schema-only` so that the Java migration reproduces it exactly.

**P1: Skeleton and `common`**
> Create a Maven Spring Boot 3.3 / Java 21 project in `LLM_based_java`. Read every setting from the same environment variable names and defaults as `LLM_based_python/app/core/config.py`, and import `.env` when running locally. Port `core/exceptions.py`, `core/security.py` (`SecretCipher`, same `base64(nonce‖ciphertext‖tag)` layout) and the `main.py` middleware: the request size limit (413), CORS allow-list, UI CSP headers, `/` → `/ui/`, `/health`. Map every error to `{"detail": …}`, and every validation error to 422 like FastAPI. Write a Flyway `V1` identical to the Alembic 0001–0003 schema, with baseline-on-migrate so that an existing Python system DB can be shared.

**P2: `auth`**
> Port `services/auth_service.py`, `api/dependencies.py` and `api/routes/auth.py`. Use Argon2id with argon2-cffi's default parameters, so that hashes are interchangeable. Use HS256 JWTs with sub/iat/exp. `POST /api/v1/auth` and `/auth` accept a JSON body or HTTP Basic. Protected endpoints accept a Bearer token or Basic. Keep the 401 texts and `WWW-Authenticate` headers. Seed the admin user from `.env` at startup. Verify unknown users against a dummy hash.

**P3: `discovery`**
> Port `infrastructure/database/target_database.py`, `services/metadata_service.py` and the metadata repositories. Open target connections read-only with `statement_timeout`, setting the fields individually (no URL concatenation). Use the same `pg_catalog` query, the same identifier validation and quoting, and the same deterministic sampling SQL. Do the connection upsert on `uq_db_connections_target`. Snapshots are immutable; delete relies on `ON DELETE CASCADE`. Return the same JSON shape, sorted by schema/table and ordinal position, with 201 on create and the PDF alias paths.

**P4: `classification`**
> Port `sample_sanitizer.py`, `infrastructure/llm/prompts.py`, `llm_client.py`, `classification_parser.py`, `checksum_evidence.py`, `classification_cache.py` and `classification_service.py`. The prompt text must be byte-identical to Python's, including `json.dumps` formatting. Rounding must equal Python's `round(x, 4)`. Keep the retry policy (timeouts, 408/409/429/5xx; quota errors fail fast) and the parse re-requests. Keep the TCKN/Luhn/email/IP evidence with the same weights and the `national_id_number` → `tckn` transfer. Keep the cache key parts, the HMAC fingerprint, the TTL and `force_refresh`.

**P5: Docker**
> Add a multi-stage Dockerfile (build, test, runtime with a non-root user and healthcheck) and a `docker-compose.yml` named `llm-java`: app on 8080, system-db, demo-db on 5434 with the same init scripts, a `test` profile with tmpfs DBs, and a `contract` profile that builds the Python test image and runs `tests/contract` against `http://app:8080`.

**P6: Verification**
> Run the Python contract suite against the Java service, fix every failure in the Java code (never in the suite), then run the evaluation script against it. Generate golden data from the Python implementation and assert it in JUnit. Port the Python unit and integration tests (`tests/unit`, `tests/integration`, `tests/e2e`) to JUnit, against the compose test databases.

**P7: Documentation**
> Write the Java README and this report: steps, prompts, manual corrections, test results, and remaining differences.

## 4. Translation hazards identified in the analysis (P0)

| Python behavior | Literal Java translation | What the port does |
|---|---|---|
| `round(x, 4)`: correctly rounded, ties to even, on the exact binary value | `Math.round(x * 1e4) / 1e4`: half-up, double arithmetic | `new BigDecimal(x).setScale(4, HALF_EVEN)` (golden-tested) |
| `(7·odd − even) % 10` is never negative | Java `%` can be negative | `Math.floorMod` (see 6.2) |
| `None in {…}` is `False` | `Set.of(…).contains(null)` throws NPE | Default `""` (see 6.3) |
| `json.dumps` writes `", "` / `": "` separators | Jackson writes compact JSON | `PyJson` helper for prompt text (golden-tested, byte-identical) |
| `ipaddress.ip_address()` parses literals only | `InetAddress.getByName()` may do DNS lookups | Guava `InetAddresses.isInetAddress` (no DNS) |
| FastAPI validation → 422 | Spring validation → 400 | `ErrorHandler` maps all binding/validation errors to 422 |
| SQLAlchemy `session.add()` inserts | Spring Data `save()` with app-assigned IDs → `merge()` | `EntityManager.persist()` (see 6.4) |
| `asyncpg` `$1` parameters | JDBC `?` | `CAST(? AS text) IS NULL OR …` with the value bound twice |

## 5. Module mapping

| Module | Java classes | Python counterpart |
|---|---|---|
| `common` | `AppProperties`, `AppException`, `ErrorHandler`, `WebConfig`, `SecretCipher`, `RootController`, `DocsToggle` | `core/config.py`, `core/exceptions.py`, `core/security.py` (cipher), `main.py` |
| `auth` | `AuthController`, `AuthenticationFilter`, `AuthService`, `AdminUserInitializer`, `JwtService`, `PasswordHasher`, `User`, `UserRepository` | `api/routes/auth.py`, `api/dependencies.py`, `services/auth_service.py`, `core/security.py` |
| `discovery` | `MetadataController`, `MetadataService`, `TargetDatabase`, `ConnectionRepository`, `MetadataRepository`, `Db*` entities, `MetadataDtos` | `api/routes/metadata.py`, `services/metadata_service.py`, `infrastructure/database/target_database.py`, `repositories/*` |
| `classification` | `ClassificationController`, `ClassificationService`, `LlmClient`, `Prompts`, `ClassificationParser`, `ChecksumEvidence`, `SampleSanitizer`, `ClassificationCache`, `ClassificationResultRepository` | `api/routes/classification.py`, `services/classification_*.py`, `checksum_evidence.py`, `sample_sanitizer.py`, `infrastructure/llm/*` |

Size: Python app 1,974 lines in 36 files; Java main 2,715 lines in 43 files; Java tests 1,157 lines.

## 6. Steps, issues found and corrections

### 6.1 Steps P1–P5
Each module compiled on the first build after one fix: the PostgreSQL driver had to move from `runtime` to `compile` scope, because `TargetDatabase` uses `PGSimpleDataSource` directly. The stack started healthy: Flyway created the schema and the admin user was seeded.

### 6.2 TCKN modulo (caught in analysis)
The Python check digit is `(odd_sum * 7 - even_sum) % 10`. When `even_sum > 7·odd_sum` the difference is negative. Python's `%` still returns 0–9, but Java's returns a negative value, so a literal port would reject valid TCKNs. Fixed with `Math.floorMod`, covered by golden checks.

### 6.3 Contract run 1: 6 passed, 2 failed, 29 errors
- **JWT signing failed (500).** jjwt requires the key's JCA name (`HmacSHA256`), not the JWT name (`HS256`). Fixed in `JwtService`.
- **Spring's default error body leaked through** (`{"timestamp", "status", "error", "path"}`), breaking the `{"detail"}` contract. Added a catch-all handler: generic `{"detail": "Internal Server Error"}`, details only in the log.

### 6.4 Contract run 2: 16 passed, 21 errors
- **Metadata extraction failed** with `Unable to find DbTable with id …`. With app-assigned UUIDs, Spring Data's `save()` treats the entity as existing and calls `merge()`, which looks up the not-yet-inserted children. Replaced with `EntityManager.persist()`, the equivalent of SQLAlchemy's `session.add()`.

### 6.5 Contract run 3: 37/37 passed
No changes to the contract suite were needed at any point.

### 6.6 Golden tests: 96/102 on the first run
The 6 failures were a test-side artifact: `Map.of` has no key order, so the JSON strings differed. The comparison now uses JSON trees. The behavioral outputs matched on the first run: masking (30 values × 3 modes), checks, fusion, parsing including error messages, and the byte-identical system and user prompts.

### 6.7 Unit tests: a real bug in the 429 handling
`LlmClientTest` found that a 429 rate-limit reply without an `error.code` field crashed with `NullPointerException`, because `Set.of(…).contains(null)` throws. In production that would have turned a retryable rate limit into a 500. Fixed with a non-null default (section 4).

### 6.8 Integration tests: 27/27 on the first full run
These are ports of the Python integration and e2e tests: metadata persistence, encryption at rest, cascade delete, pagination, classification flow with a stub LLM, cache hit/miss/bypass/TTL/changed data, read-only session and SELECT-only role, and the full HTTP flow.

## 7. Verification results

| Check | Python | Java |
|---|---|---|
| Black-box contract suite (`tests/contract`, unchanged) | 37/37 | **37/37** |
| Real-LLM evaluation, 29 demo columns, `gpt-4o-mini` | 29/29 | **29/29** |
| Same scores (`email` 0.96, `national_id` → `tckn` 1.00 / 0.93, `social_security_number` → `ssn` 0.70, `ip_address` 0.96) | yes | **yes** |
| Own test suite | 187 (unit, integration, e2e) | **161** (102 golden, 32 unit, 27 integration) |
| Reads Python-written data (AES-GCM password, Argon2 hash, JWT) | n/a | **yes** (golden interop tests) |
| UI in headless Chrome (light/dark, 390 px) | no errors | **no errors** |

## 8. Remaining differences (accepted)

| Area | Difference | Impact |
|---|---|---|
| Unicode digits | Python's `\d` / `isdigit()` accept non-ASCII digits (e.g. Arabic-Indic, superscripts); the Java checks use ASCII `[0-9]`, and masking uses `Character.isDigit` (no superscripts) | None for real TCKN/card data; Java is stricter |
| Validation error body | Both return 422 with `detail` as a list of `{loc, msg}`; pydantic also adds `type`, `input`, `ctx` | Clients that parse only `detail` see no difference |
| Cache key bytes | Same parts and HMAC fingerprint, but Jackson vs. `json.dumps` formatting gives different key strings | When both services share one system DB, each keeps its own cache entries; no wrong reuse |
| Web security | A servlet filter instead of the Spring Security filter chain (only `spring-security-crypto` for Argon2) | Same behavior as the Python dependency; less configuration |
| Test databases | Compose-provided tmpfs DBs instead of Testcontainers | Same setup as the Python suite; needs only Docker |

## 9. Lessons

- The contract suite and the golden data made the AI-generated code checkable. Every defect above was found by a test, not by reading the code.
- The most valuable prompt instruction was "list where a literal translation changes behavior" (P0). Of the five behavioral defects in section 6, two (modulo, `contains(null)`) are exactly that class, and two more (`save()` vs. `persist()`, Spring's default error body) are framework semantics that differ from the Python stack.
- Fix the port, never the specification. The contract suite was not edited to make Java pass.
