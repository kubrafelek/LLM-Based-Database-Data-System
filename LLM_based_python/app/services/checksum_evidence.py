"""Deterministic checks on raw samples (TCKN algorithm, Luhn, email and IP syntax), fused with the
LLM distribution.

Only categories with a syntactic definition are checked. Names, addresses, phone numbers and
dates of birth depend on context (a date is not necessarily a birth date), so they stay with the LLM.
The checks run on the unmasked values inside the system; only match counts leave this module.
"""

import ipaddress
import re
from collections.abc import Callable
from dataclasses import dataclass

from app.domain.enums import PiiCategory
from app.services.classification_parser import PROBABILITY_DECIMALS

# Share of non-empty samples that must pass a check before it counts as evidence.
MIN_MATCH_RATIO = 0.8

# How far the distribution is pulled toward the category the check supports:
# fused = (1 - weight) * llm + weight * one_hot(category).
# A passing checksum is strong evidence (random 11-digit numbers pass the TCKN check ~1% of
# the time, random card-length numbers pass Luhn ~10%). The TCKN format alone is weaker,
# but the case study's own TCKN examples (12345678901, 98765432109) only satisfy the format.
TCKN_CHECKSUM_WEIGHT = 0.6
TCKN_FORMAT_WEIGHT = 0.3
LUHN_WEIGHT = 0.6
# A full email address or a parseable IP address is unambiguous syntax.
EMAIL_WEIGHT = 0.6
IP_ADDRESS_WEIGHT = 0.6

# A check for a specific category also explains the generic category it is a case of:
# an 11-digit TCKN-shaped column the LLM calls "national ID number" is a TCKN column.
# Probability on the generic category moves to the specific one before mixing.
SPECIALIZES = {PiiCategory.TCKN: PiiCategory.NATIONAL_ID_NUMBER}

TCKN_PATTERN = re.compile(r"[1-9]\d{10}")
CARD_SEPARATORS = re.compile(r"[ -]")
CARD_PATTERN = re.compile(r"\d{13,19}")
EMAIL_PATTERN = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")


@dataclass(frozen=True)
class ChecksumEvidence:
    category: PiiCategory
    check: str  # "tckn_checksum" | "tckn_format" | "luhn" | "email_format" | "ip_address"
    matched: int
    checked: int
    weight: float


def is_tckn_format(value: str) -> bool:
    return TCKN_PATTERN.fullmatch(value.strip()) is not None


def is_valid_tckn(value: str) -> bool:
    """11 digits, no leading 0, digit 10 and 11 are the official check digits."""
    value = value.strip()
    if not is_tckn_format(value):
        return False
    d = [int(ch) for ch in value]
    odd_sum = d[0] + d[2] + d[4] + d[6] + d[8]
    even_sum = d[1] + d[3] + d[5] + d[7]
    return d[9] == (odd_sum * 7 - even_sum) % 10 and d[10] == sum(d[:10]) % 10


def is_valid_luhn_card(value: str) -> bool:
    """13-19 digits (spaces and hyphens allowed) passing the Luhn check."""
    digits = CARD_SEPARATORS.sub("", value.strip())
    if CARD_PATTERN.fullmatch(digits) is None:
        return False
    total = 0
    for index, ch in enumerate(reversed(digits)):
        digit = int(ch)
        if index % 2 == 1:
            digit = digit * 2 - 9 if digit > 4 else digit * 2
        total += digit
    return total % 10 == 0


def is_email(value: str) -> bool:
    return EMAIL_PATTERN.fullmatch(value.strip()) is not None


def is_ip_address(value: str) -> bool:
    """IPv4 in dotted-quad form or IPv6 (inet values are sampled via host(), so no netmask)."""
    try:
        ipaddress.ip_address(value.strip())
    except ValueError:
        return False
    return True


# Single-tier checks: (category, check name, predicate, weight).
SIMPLE_CHECKS: list[tuple[PiiCategory, str, Callable[[str], bool], float]] = [
    (PiiCategory.CREDIT_CARD_NUMBER, "luhn", is_valid_luhn_card, LUHN_WEIGHT),
    (PiiCategory.EMAIL_ADDRESS, "email_format", is_email, EMAIL_WEIGHT),
    (PiiCategory.IP_ADDRESS, "ip_address", is_ip_address, IP_ADDRESS_WEIGHT),
]


def collect_evidence(raw_samples: list[str]) -> list[ChecksumEvidence]:
    values = [value for value in raw_samples if value.strip()]
    if not values:
        return []

    def passes(check) -> int:
        return sum(1 for value in values if check(value))

    evidence = []
    tckn_valid = passes(is_valid_tckn)
    tckn_format = passes(is_tckn_format)
    if tckn_valid >= MIN_MATCH_RATIO * len(values):
        evidence.append(
            ChecksumEvidence(PiiCategory.TCKN, "tckn_checksum", tckn_valid, len(values), TCKN_CHECKSUM_WEIGHT)
        )
    elif tckn_format >= MIN_MATCH_RATIO * len(values):
        evidence.append(
            ChecksumEvidence(PiiCategory.TCKN, "tckn_format", tckn_format, len(values), TCKN_FORMAT_WEIGHT)
        )

    for category, check, predicate, weight in SIMPLE_CHECKS:
        matched = passes(predicate)
        if matched >= MIN_MATCH_RATIO * len(values):
            evidence.append(ChecksumEvidence(category, check, matched, len(values), weight))
    return evidence


def fuse(
    classification: dict[PiiCategory, float],
    pii_likelihood: float,
    evidence: list[ChecksumEvidence],
) -> tuple[dict[PiiCategory, float], float]:
    """Pull the LLM distribution and pii_likelihood toward the categories the checks support."""
    fused = dict(classification)
    for item in evidence:
        generic = SPECIALIZES.get(item.category)
        if generic is not None:
            fused[item.category] += fused[generic]
            fused[generic] = 0.0
        fused = {
            category: (1 - item.weight) * p + (item.weight if category is item.category else 0.0)
            for category, p in fused.items()
        }
        pii_likelihood = (1 - item.weight) * pii_likelihood + item.weight
    return (
        {category: round(p, PROBABILITY_DECIMALS) for category, p in fused.items()},
        round(pii_likelihood, PROBABILITY_DECIMALS),
    )
