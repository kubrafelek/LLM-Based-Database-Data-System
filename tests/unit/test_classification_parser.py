import pytest

from app.domain.enums import PiiCategory
from app.services.classification_parser import (
    PROBABILITY_SUM_TOLERANCE,
    normalize_distribution,
    parse_classification,
)


def test_normalize_fills_missing_categories_and_rescales():
    distribution = normalize_distribution({"EMAIL_ADDRESS": 0.6, " full_name ": 0.2, "not_pii": 0.9})
    assert set(distribution) == set(PiiCategory)
    assert len(distribution) == 12
    assert distribution[PiiCategory.EMAIL_ADDRESS] == 0.75
    assert distribution[PiiCategory.FULL_NAME] == 0.25
    assert distribution[PiiCategory.TCKN] == 0.0


def test_normalized_distribution_sums_to_one_within_tolerance():
    distribution = normalize_distribution({category.value: 1 / 3 for category in PiiCategory})
    assert abs(sum(distribution.values()) - 1) <= PROBABILITY_SUM_TOLERANCE


@pytest.mark.parametrize(
    "raw",
    [{}, {"unknown": 0.5}, {"email_address": "abc"}, {"email_address": 1.5}, None, [1, 2]],
)
def test_normalize_rejects_unusable_input(raw):
    with pytest.raises(ValueError):
        normalize_distribution(raw)


def test_normalize_returns_none_when_no_category_fits():
    assert normalize_distribution({category.value: 0 for category in PiiCategory}) is None
    assert normalize_distribution({"email_address": 0}) is None


def test_no_fitting_category_gives_uniform_distribution_and_zero_likelihood():
    parsed = parse_classification(
        {"pii_likelihood": 0.4, "classification": {c.value: 0 for c in PiiCategory}, "reasoning": "price"}
    )
    assert parsed.category_fits is False
    assert parsed.pii_likelihood == 0.0
    assert set(parsed.classification.values()) == {round(1 / 12, 4)}
    assert abs(sum(parsed.classification.values()) - 1) <= PROBABILITY_SUM_TOLERANCE
    assert parsed.reasoning == "price"


def test_parse_classification():
    parsed = parse_classification(
        {"pii_likelihood": 0.9, "classification": {"tckn": 0.8, "ssn": 0.2}, "reasoning": "11 digits"}
    )
    assert parsed.pii_likelihood == 0.9
    assert parsed.classification[PiiCategory.TCKN] == 0.8
    assert parsed.reasoning == "11 digits"


@pytest.mark.parametrize("likelihood", [None, "high", -0.1, 1.2, float("nan"), True])
def test_parse_classification_rejects_invalid_pii_likelihood(likelihood):
    with pytest.raises(ValueError):
        parse_classification({"pii_likelihood": likelihood, "classification": {"tckn": 1}})


def test_parse_classification_ignores_non_string_reasoning():
    parsed = parse_classification({"pii_likelihood": 0.5, "classification": {"tckn": 1}, "reasoning": 42})
    assert parsed.reasoning is None
