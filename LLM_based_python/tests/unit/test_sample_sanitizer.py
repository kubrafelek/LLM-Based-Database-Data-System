import pytest

from app.domain.enums import MaskingMode
from app.services.sample_sanitizer import mask_sample


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("ahmet.yilmaz@email.com", "a***********@email.com"),
        ("12345678901", "12*******01"),
        ("+90 532 123 4567", "+90 *** *** **67"),
        ("1985-03-15", "19**-**-15"),
        ("192.168.1.100", "19*.***.*.*00"),
        ("4532-1234-5678-9012", "45**-****-****-**12"),
        # Already masked or too few digits: unchanged
        ("****-****-****-1234", "****-****-****-1234"),
        ("Atatürk Caddesi No:123 Daire:5", "Atatürk Caddesi No:123 Daire:5"),
        # Names are kept so they can still be classified
        ("Ahmet", "Ahmet"),
    ],
)
def test_partial_masking(value, expected):
    assert mask_sample(value, MaskingMode.PARTIAL) == expected


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("Ayşe.Şahin@yahoo.com", "Xxxx.Xxxxx@xxxxx.xxx"),
        ("+90 535 777 8888", "+99 999 999 9999"),
        ("AHMET YILMAZ", "XXXXX XXXXXX"),
    ],
)
def test_strict_masking_keeps_only_shape(value, expected):
    assert mask_sample(value, MaskingMode.STRICT) == expected


def test_no_masking():
    assert mask_sample("12345678901", MaskingMode.NONE) == "12345678901"
