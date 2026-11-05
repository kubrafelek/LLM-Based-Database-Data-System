import uuid

import pytest

from app.core.config import get_settings
from app.domain.enums import MaskingMode
from app.services.classification_cache import cache_key, sample_fingerprint

COLUMN = uuid.UUID("00000000-0000-0000-0000-000000000001")
SAMPLES = ["12345678901", "98765432109"]


def key(samples=SAMPLES, column=COLUMN, sample_count=10, model="gpt-4o-mini", **settings_overrides):
    settings = get_settings().model_copy(update=settings_overrides)
    return cache_key(column, sample_count, samples, model, settings)


def test_key_is_stable_and_independent_of_sample_order():
    assert key() == key()
    assert key(samples=list(reversed(SAMPLES))) == key()


@pytest.mark.parametrize(
    "changed",
    [
        {"samples": ["12345678901", "98765432108"]},
        {"samples": SAMPLES + ["11223344556"]},
        {"column": uuid.UUID("00000000-0000-0000-0000-000000000002")},
        {"sample_count": 5},
        {"model": "gpt-4o"},
        {"llm_base_url": "http://ollama:11434/v1"},
        {"sample_masking_mode": MaskingMode.STRICT},
        {"checksum_validation_enabled": False},
    ],
)
def test_any_input_change_gives_a_new_key(changed):
    assert key(**changed) != key()


def test_fingerprint_is_keyed():
    # Without the server's key, a fingerprint cannot be recomputed from guessed values.
    assert sample_fingerprint(SAMPLES, "key-a") != sample_fingerprint(SAMPLES, "key-b")
    assert len(sample_fingerprint(SAMPLES, "key-a")) == 64
    assert "12345678901" not in sample_fingerprint(SAMPLES, "key-a")
