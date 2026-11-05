"""Validates and normalizes the LLM's classification output. Never trust it as-is."""

import math
from dataclasses import dataclass
from typing import Any

from app.domain.enums import PiiCategory

PROBABILITY_DECIMALS = 4
# Rounding each probability to 4 decimals can move the sum by up to 12 * 0.00005.
PROBABILITY_SUM_TOLERANCE = 1e-3

# Returned when no category fits: maximal uncertainty, still a distribution summing to 1.
UNIFORM_DISTRIBUTION = {
    category: round(1 / len(PiiCategory), PROBABILITY_DECIMALS) for category in PiiCategory
}


@dataclass
class ParsedClassification:
    classification: dict[PiiCategory, float]
    pii_likelihood: float
    reasoning: str | None
    # False when the model scored every category 0, i.e. "none of the categories fit".
    category_fits: bool = True


def _to_probability(value: Any) -> float:
    if isinstance(value, bool):
        raise ValueError("probability must be a number")
    try:
        probability = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError("probability must be a number") from exc
    if not math.isfinite(probability) or not 0 <= probability <= 1:
        raise ValueError(f"probability {probability} is outside [0, 1]")
    return probability


def normalize_distribution(raw: Any) -> dict[PiiCategory, float] | None:
    """Map scores onto all 12 categories and rescale them to sum to 1.

    Unknown keys and unusable values are dropped; missing categories get 0.
    Returns None when the scores are valid but all 0 ("no category fits").
    """
    if not isinstance(raw, dict):
        raise ValueError("'classification' must be an object")
    scores = {category: 0.0 for category in PiiCategory}
    valid_entries = 0
    for key, value in raw.items():
        try:
            category = PiiCategory(str(key).strip().lower())
            score = _to_probability(value)
        except ValueError:
            continue
        scores[category] = score
        valid_entries += 1
    if valid_entries == 0:
        raise ValueError("'classification' contains no valid category probabilities")
    total = sum(scores.values())
    if total == 0:
        return None
    return {category: round(score / total, PROBABILITY_DECIMALS) for category, score in scores.items()}


def parse_classification(payload: dict[str, Any]) -> ParsedClassification:
    pii_likelihood = round(_to_probability(payload.get("pii_likelihood")), PROBABILITY_DECIMALS)
    reasoning = payload.get("reasoning")
    reasoning = reasoning if isinstance(reasoning, str) else None

    classification = normalize_distribution(payload.get("classification"))
    if classification is None:
        # A column that fits no category is not PII of any supported kind.
        return ParsedClassification(dict(UNIFORM_DISTRIBUTION), 0.0, reasoning, category_fits=False)
    return ParsedClassification(classification, pii_likelihood, reasoning)
