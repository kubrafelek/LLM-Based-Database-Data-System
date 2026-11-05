# LLM-Based Database Data Discovery System

Discovers the schema of PostgreSQL databases and classifies columns into 12 PII categories with an OpenAI-compatible LLM. The same system is built twice, as the case study asks:

| Folder | Task | Stack | Status |
|---|---|---|---|
| [LLM_based_python/](LLM_based_python/) | Task 1: reference implementation | Python 3.12, FastAPI, PostgreSQL, Docker Compose | Complete |
| [LLM_based_java/](LLM_based_java/) | Task 2: AI-assisted Java port ([migration report](LLM_based_java/docs/MIGRATION_REPORT.md)) | Java 21, Spring Boot 3.3, PostgreSQL, Docker Compose | Complete |

Each folder is self-contained: its own `docker-compose.yml`, `.env.example`, README and demo database. Start one with:

```bash
cd LLM_based_python        # or LLM_based_java
cp .env.example .env       # then fill in the secrets and LLM_API_KEY
docker compose up --build -d
```

## Keeping the two implementations equivalent

Both expose the same HTTP API (`/api/v1/auth`, `/db/metadata`, `/metadata`, `/metadata/{id}`, `/classify`) and JSON contract, on ports 8000 (Python) and 8080 (Java). Three checks hold them to the same behavior:

- **Contract tests** ([LLM_based_python/tests/contract](LLM_based_python/tests/contract/)): auth, extraction, list/detail, validation, checksum rules, cache and delete semantics.
- **Classification evaluation** ([LLM_based_python/scripts/evaluate_classification.py](LLM_based_python/scripts/evaluate_classification.py)): 29 demo columns with expected categories.
- **Golden tests** ([LLM_based_java/scripts/generate_golden.py](LLM_based_java/scripts/generate_golden.py)): outputs of the Python code (masking, checks, fusion, parsing, prompt text, encrypted data) that the Java code must reproduce exactly.

| Check | Python | Java |
|---|---|---|
| Contract suite (unchanged) | 37/37 | 37/37 |
| Real-LLM evaluation (`gpt-4o-mini`) | 29/29 | 29/29, same scores |
| Own test suite | 187 | 161 (102 golden) |

## Repository layout

```
LLM_based_python/   FastAPI implementation, tests, demo DB scripts
LLM_based_java/     Spring Boot implementation and the AI migration report
TASK.md             Technical progress: decisions, test cases, change log, evaluation results
```

Progress, design decisions and test cases are tracked in [TASK.md](TASK.md).
