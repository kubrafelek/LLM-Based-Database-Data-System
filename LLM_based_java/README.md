# LLM Data Discovery: Java implementation

Task 2 of the case study: the Python system in [../LLM_based_python](../LLM_based_python/) converted to Java with AI assistance. Every step, method and prompt is recorded in `docs/MIGRATION_REPORT.md`.

**Status:** not started yet.

## Planned module structure

The system is split into three independent feature modules plus shared infrastructure, so each can be built, tested and compared with its Python counterpart on its own:

| Module | Responsibility | Python counterpart |
|---|---|---|
| `auth` | Basic / JSON login, JWT, admin user | `api/routes/auth.py`, `services/auth_service.py`, `core/security.py` |
| `discovery` | Target DB connections, schema inspection, metadata snapshots (CRUD) | `infrastructure/database/target_database.py`, `services/metadata_service.py`, `repositories/metadata_repository.py`, `connection_repository.py` |
| `classification` | Sampling, masking, prompt, LLM client, parsing, checksum evidence, cache | `services/classification_*.py`, `checksum_evidence.py`, `sample_sanitizer.py`, `infrastructure/llm/` |
| `common` | Configuration from `.env`, encryption, errors, request limits | `core/` |

Verification: the Python contract suite and evaluation script, both HTTP-only, run unchanged against this service.
