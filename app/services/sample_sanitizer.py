"""Masks sample values before they leave the system for the LLM provider."""

import re

from app.domain.enums import MaskingMode

MASK_CHAR = "*"
# Values with fewer digits than this (house numbers, short codes) are left alone.
MIN_DIGITS_TO_MASK = 6
# Digits kept visible at each end of a masked number, e.g. 12*******01.
VISIBLE_EDGE_DIGITS = 2

EMAIL_PATTERN = re.compile(r"([A-Za-z0-9._%+-]+)@([A-Za-z0-9.-]+\.[A-Za-z]{2,})")


def mask_sample(value: str, mode: MaskingMode) -> str:
    if mode is MaskingMode.NONE:
        return value
    if mode is MaskingMode.STRICT:
        return _mask_strict(value)
    return _mask_partial(value)


def _mask_partial(value: str) -> str:
    """Hide identifying parts while keeping the format the classifier relies on."""
    value = EMAIL_PATTERN.sub(
        lambda m: m.group(1)[0] + MASK_CHAR * (len(m.group(1)) - 1) + "@" + m.group(2), value
    )
    digit_count = sum(ch.isdigit() for ch in value)
    if digit_count < MIN_DIGITS_TO_MASK:
        return value

    masked = []
    seen = 0
    for ch in value:
        if ch.isdigit():
            seen += 1
            if VISIBLE_EDGE_DIGITS < seen <= digit_count - VISIBLE_EDGE_DIGITS:
                masked.append(MASK_CHAR)
                continue
        masked.append(ch)
    return "".join(masked)


def _mask_strict(value: str) -> str:
    """Keep only the shape: letters -> x/X, digits -> 9, everything else unchanged."""
    return "".join(
        ("X" if ch.isupper() else "x") if ch.isalpha() else "9" if ch.isdigit() else ch for ch in value
    )
