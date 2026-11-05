"""Cache key for /classify results.

A cached result is reused only when everything that shaped it is unchanged: the column, the
requested sample count, the sample values themselves, the model, the prompt and the settings
that affect the answer. Sampling is deterministic (see build_sample_query), so unchanged data
gives the same samples and the same key; changed data gives a new key.
"""

import hashlib
import hmac
import json
import uuid

from app.core.config import Settings
from app.infrastructure.llm.prompts import SYSTEM_PROMPT

# Bump when the user prompt template or the parsing / fusion logic changes, so old results are not reused.
# (System prompt changes are picked up automatically through PROMPT_FINGERPRINT.)
CACHE_LOGIC_VERSION = 1
PROMPT_FINGERPRINT = hashlib.sha256(SYSTEM_PROMPT.encode()).hexdigest()


def sample_fingerprint(raw_samples: list[str], secret: str) -> str:
    """Keyed hash of the raw sample set. Raw values are never stored, and a keyed hash
    cannot be brute-forced back to short values (e.g. 11-digit TCKNs) without the key."""
    payload = json.dumps(sorted(raw_samples), ensure_ascii=False).encode()
    return hmac.new(secret.encode(), payload, hashlib.sha256).hexdigest()


def cache_key(
    column_id: uuid.UUID, sample_count: int, raw_samples: list[str], model: str, settings: Settings
) -> str:
    parts = {
        "column_id": str(column_id),
        "sample_count": sample_count,
        "samples": sample_fingerprint(raw_samples, settings.encryption_key),
        "llm_base_url": settings.llm_base_url,
        "model": model,
        "prompt": PROMPT_FINGERPRINT,
        "masking_mode": settings.sample_masking_mode.value,
        "checksum_validation": settings.checksum_validation_enabled,
        "logic": CACHE_LOGIC_VERSION,
    }
    return hashlib.sha256(json.dumps(parts, sort_keys=True).encode()).hexdigest()
