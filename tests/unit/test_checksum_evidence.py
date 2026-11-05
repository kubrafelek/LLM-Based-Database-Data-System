import pytest

from app.domain.enums import PiiCategory
from app.services.checksum_evidence import (
    LUHN_WEIGHT,
    TCKN_CHECKSUM_WEIGHT,
    TCKN_FORMAT_WEIGHT,
    collect_evidence,
    fuse,
    is_tckn_format,
    is_valid_luhn_card,
    is_valid_tckn,
)

# Check digits computed with the official algorithm.
VALID_TCKNS = ["10000000146", "55443322110", "18452847234", "76543210930"]


@pytest.mark.parametrize("value", VALID_TCKNS)
def test_valid_tckn(value):
    assert is_valid_tckn(value)


@pytest.mark.parametrize(
    "value",
    [
        "12345678901",  # case study example: right format, wrong check digits
        "10000000147",  # last digit off by one
        "01234567890",  # leading zero
        "1000000014",  # 10 digits
        "1000-000-0146",
        "",
    ],
)
def test_invalid_tckn(value):
    assert not is_valid_tckn(value)


def test_tckn_format_ignores_check_digits():
    assert is_tckn_format("12345678901")
    assert is_tckn_format(" 98765432109 ")
    assert not is_tckn_format("01234567890")
    assert not is_tckn_format("+90 532 123 4567")


@pytest.mark.parametrize(
    "value",
    ["5555555555554444", "4111 1111 1111 1111", "4532-0151-1283-0366", "378282246310005"],
)
def test_valid_luhn_card(value):
    assert is_valid_luhn_card(value)


@pytest.mark.parametrize(
    "value",
    ["4532-1234-5678-9012", "5555555555554445", "****-****-****-1234", "123456789012", "4111/1111/1111/1111"],
)
def test_invalid_luhn_card(value):
    assert not is_valid_luhn_card(value)


def test_checksum_valid_tckns_give_strong_evidence():
    [evidence] = collect_evidence(VALID_TCKNS + [""])  # empty values are not counted
    assert evidence.category is PiiCategory.TCKN
    assert evidence.check == "tckn_checksum"
    assert (evidence.matched, evidence.checked) == (4, 4)
    assert evidence.weight == TCKN_CHECKSUM_WEIGHT


def test_tckn_format_only_gives_weak_evidence():
    # The demo customers.national_id values: 11 digits, only one passes the checksum.
    demo = ["12345678901", "98765432109", "11223344556", "66778899001", "55443322110"]
    [evidence] = collect_evidence(demo)
    assert evidence.check == "tckn_format"
    assert evidence.weight == TCKN_FORMAT_WEIGHT


def test_luhn_evidence():
    [evidence] = collect_evidence(["5555555555554444", "4111 1111 1111 1111", "378282246310005"])
    assert evidence.category is PiiCategory.CREDIT_CARD_NUMBER
    assert evidence.weight == LUHN_WEIGHT


@pytest.mark.parametrize(
    "samples",
    [
        [],
        ["", "  "],
        ["ahmet.yilmaz@email.com", "fatma.kaya@gmail.com"],
        ["+90 532 123 4567", "+90 533 987 6543"],
        ["1234567890", "2345678901"],  # 10-digit tax numbers
        ["10000000146", "x", "y", "z", "w"],  # below the 80% match ratio
        ["4532-1234-5678-9012", "1234 5678 9012 3456"],  # card-shaped, Luhn fails
    ],
)
def test_no_evidence(samples):
    assert collect_evidence(samples) == []


def distribution(**probabilities):
    return {category: probabilities.get(category.value, 0.0) for category in PiiCategory}


@pytest.mark.parametrize(
    "llm",
    [
        # Replies gpt-4o-mini gave for the demo customers.national_id column on repeated runs.
        {"tckn": 0.5, "national_id_number": 0.5},
        {"tckn": 0.1, "national_id_number": 0.9},
        {"national_id_number": 1.0},
    ],
)
def test_tckn_evidence_absorbs_the_generic_national_id(llm):
    [evidence] = collect_evidence(["12345678901", "98765432109"])

    fused, pii = fuse(distribution(**llm), 0.9, [evidence])

    assert fused[PiiCategory.TCKN] == 1.0
    assert fused[PiiCategory.NATIONAL_ID_NUMBER] == 0.0
    assert pii == 0.93


def test_tckn_evidence_only_shifts_unrelated_categories():
    # employees.social_security_number is also 11 digits; the LLM's ssn answer should survive.
    [evidence] = collect_evidence(["12345678901", "23456789012"])

    fused, _ = fuse(distribution(ssn=1.0), 0.9, [evidence])

    assert fused[PiiCategory.SSN] == 0.7
    assert fused[PiiCategory.TCKN] == 0.3
    assert abs(sum(fused.values()) - 1) < 1e-3


def test_luhn_evidence_mixes_toward_credit_card():
    [evidence] = collect_evidence(["5555555555554444", "4111111111111111"])

    fused, pii = fuse(distribution(phone_number=1.0), 0.2, [evidence])

    assert fused[PiiCategory.CREDIT_CARD_NUMBER] == 0.6
    assert fused[PiiCategory.PHONE_NUMBER] == 0.4
    assert pii == 0.68


def test_fuse_without_evidence_is_identity():
    classification = {category: 1 / 12 for category in PiiCategory}
    fused, pii = fuse(classification, 0.25, [])
    assert pii == 0.25
    assert all(abs(fused[c] - 1 / 12) < 1e-4 for c in PiiCategory)
