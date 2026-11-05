import logging
import uuid

import pytest

from app.api.dependencies import get_cipher
from app.core.config import get_settings
from app.core.exceptions import InvalidRequestError, NotFoundError
from app.domain.enums import MaskingMode, PiiCategory
from app.domain.schemas import ClassifyRequest
from app.services import metadata_service
from app.services.classification_service import classify_column
from tests.fakes import FakeLLMProvider, classification_reply

DEMO_EMAILS = [
    "ahmet.yilmaz@email.com",
    "fatma.kaya@gmail.com",
    "mehmet.demir@hotmail.com",
    "ayse.sahin@yahoo.com",
    "can.ozkan@outlook.com",
]


async def column_id(session, target_request, table: str, column: str) -> uuid.UUID:
    detail = await metadata_service.create_metadata(session, target_request, get_settings(), get_cipher())
    table_out = next(t for t in detail.tables if t.table_name == table)
    return next(c.column_id for c in table_out.columns if c.column_name == column)


async def classify(session, request, llm, **settings_overrides):
    settings = get_settings().model_copy(update=settings_overrides)
    return await classify_column(session, request, settings, get_cipher(), llm)


async def test_classifies_email_column_with_masked_samples(session, target_request, caplog):
    provider = FakeLLMProvider()
    cid = await column_id(session, target_request, "customers", "email")

    with caplog.at_level(logging.DEBUG):
        result = await classify(session, ClassifyRequest(column_id=cid), provider.client())

    assert result.predicted_category is PiiCategory.EMAIL_ADDRESS
    assert set(result.classification) == {c.value for c in PiiCategory}
    assert abs(sum(result.classification.values()) - 1) < 1e-3
    assert result.pii_likelihood == 0.97
    assert result.sample_count_used == len(DEMO_EMAILS)
    assert result.masking_mode is MaskingMode.PARTIAL

    sent = provider.requests[0]["messages"][1]["content"]
    assert "@email.com" in sent  # domain kept
    for email in DEMO_EMAILS:
        assert email not in sent  # local part masked
        assert email not in caplog.text


async def test_column_fitting_no_category_is_classified_not_rejected(session, target_request):
    # Real models (gpt-4o-mini, llama3.1) answer all-zero for non-PII columns.
    all_zero = classification_reply(0.0, **{c.value: 0.0 for c in PiiCategory})
    provider = FakeLLMProvider(reply=all_zero)
    cid = await column_id(session, target_request, "products", "unit_price")

    result = await classify(session, ClassifyRequest(column_id=cid), provider.client())

    assert result.predicted_category is None
    assert result.pii_likelihood == 0.0
    assert set(result.classification) == {c.value for c in PiiCategory}
    assert abs(sum(result.classification.values()) - 1) < 1e-3
    assert len(provider.requests) == 1  # accepted on the first reply, no retries


async def test_masking_can_be_disabled(session, target_request):
    provider = FakeLLMProvider()
    cid = await column_id(session, target_request, "customers", "email")

    request = ClassifyRequest(column_id=cid)
    await classify(session, request, provider.client(), sample_masking_mode=MaskingMode.NONE)

    sent = provider.requests[0]["messages"][1]["content"]
    assert any(email in sent for email in DEMO_EMAILS)


async def test_sample_count_limits_samples(session, target_request):
    provider = FakeLLMProvider()
    cid = await column_id(session, target_request, "customers", "email")

    result = await classify(session, ClassifyRequest(column_id=cid, sample_count=2), provider.client())

    assert result.sample_count_requested == 2
    assert result.sample_count_used == 2


async def test_empty_column_is_classified_from_metadata(session, target_request):
    provider = FakeLLMProvider()
    # The demo script never sets last_login, so every value is NULL.
    cid = await column_id(session, target_request, "customers", "last_login")

    result = await classify(session, ClassifyRequest(column_id=cid), provider.client())

    assert result.sample_count_used == 0
    assert "No non-null sample values" in provider.requests[0]["messages"][1]["content"]


async def test_sample_count_above_maximum_is_rejected(session, target_request):
    cid = await column_id(session, target_request, "customers", "email")
    request = ClassifyRequest(column_id=cid, sample_count=11)
    with pytest.raises(InvalidRequestError):
        await classify(session, request, FakeLLMProvider().client(), max_sample_count=10)


async def test_unknown_column(session):
    with pytest.raises(NotFoundError):
        await classify(session, ClassifyRequest(column_id=uuid.uuid4()), FakeLLMProvider().client())


async def test_tckn_format_breaks_the_llm_tie(session, target_request):
    # gpt-4o-mini splits the demo national_id column 0.5/0.5 between tckn and national_id_number.
    tie = classification_reply(0.9, tckn=0.5, national_id_number=0.5)
    provider = FakeLLMProvider(reply=tie)
    cid = await column_id(session, target_request, "customers", "national_id")

    result = await classify(session, ClassifyRequest(column_id=cid), provider.client())

    assert result.predicted_category is PiiCategory.TCKN
    assert result.classification["tckn"] == 1.0
    assert result.classification["national_id_number"] == 0.0
    assert result.llm_classification["tckn"] == 0.5
    assert result.llm_pii_likelihood == 0.9
    [evidence] = result.checksum_evidence
    assert (evidence.check, evidence.matched, evidence.checked) == ("tckn_format", 5, 5)
    # The checks run on the raw values; the LLM still only sees masked ones.
    assert "12345678901" not in provider.requests[0]["messages"][1]["content"]


async def test_checksum_validation_can_be_disabled(session, target_request):
    tie = classification_reply(0.9, tckn=0.5, national_id_number=0.5)
    provider = FakeLLMProvider(reply=tie)
    cid = await column_id(session, target_request, "customers", "national_id")

    request = ClassifyRequest(column_id=cid)
    result = await classify(session, request, provider.client(), checksum_validation_enabled=False)

    assert result.checksum_evidence == []
    assert result.classification == result.llm_classification
