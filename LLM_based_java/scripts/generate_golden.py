"""Golden test data for the Java port, produced by the Python reference implementation.

Run inside the Python test image (it has the app and its dependencies):

    cd LLM_based_python
    docker compose --profile test run --rm --no-deps -T tests python - \
        < ../LLM_based_java/scripts/generate_golden.py \
        > ../LLM_based_java/src/test/resources/golden.json

The Java GoldenTest asserts that the port gives exactly these outputs.
"""

import base64
import json
import os
import sys

os.environ.setdefault("SYSTEM_DB_HOST", "unused")
os.environ.setdefault("SYSTEM_DB_NAME", "unused")
os.environ.setdefault("SYSTEM_DB_USER", "unused")
os.environ.setdefault("SYSTEM_DB_PASSWORD", "unused")
os.environ.setdefault("AUTH_USERNAME", "admin")
os.environ.setdefault("AUTH_PASSWORD", "secret")
os.environ.setdefault("JWT_SECRET_KEY", "golden-jwt-secret-with-enough-length-1234")
os.environ.setdefault("ENCRYPTION_KEY", base64.b64encode(b"k" * 32).decode())
os.environ.setdefault("LLM_API_KEY", "unused")

from app.core.config import get_settings  # noqa: E402
from app.core.security import SecretCipher, create_access_token, hash_password  # noqa: E402
from app.domain.enums import MaskingMode, PiiCategory  # noqa: E402
from app.infrastructure.llm.prompts import SYSTEM_PROMPT, ColumnContext, build_user_prompt  # noqa: E402
from app.services import checksum_evidence as ce  # noqa: E402
from app.services.classification_parser import parse_classification  # noqa: E402
from app.services.sample_sanitizer import mask_sample  # noqa: E402

VALUES = [
    "ahmet.yilmaz@email.com", "Ayşe.Şahin@yahoo.com", "contact: a.b@c.io and x@y.org",
    "12345678901", "10000000146", "55443322110", "01234567890", " 98765432109 ",
    "+90 532 123 4567", "(555) 123-4567", "1985-03-15", "192.168.1.100",
    "2001:0db8:85a3:0000:0000:8a2e:0370:7334", "::1", "256.1.1.1", "192.168.1.1/32",
    "4532-1234-5678-9012", "5555555555554444", "4111 1111 1111 1111", "****-****-****-1234",
    "Atatürk Caddesi No:123 Daire:5", "AHMET YILMAZ", "Ahmet", "TR33 0006 1005 1978 6457 8413 26",
    "", "   ", "12345", 'say "hi"\nnew line\ttab', "ümlaut ÄÖÜ ß", "€100.50",
]

SAMPLE_SETS = [
    ["12345678901", "98765432109", "11223344556", "66778899001", "55443322110"],
    ["10000000146", "55443322110", "18452847234", "76543210930"],
    ["5555555555554444", "4111 1111 1111 1111", "378282246310005"],
    ["ahmet.yilmaz@email.com", "fatma.kaya@gmail.com"],
    ["192.168.1.100", "10.0.0.50", "172.16.0.10"],
    ["+90 532 123 4567", "+90 533 987 6543"],
    ["10000000146", "x", "y", "z", "w"],
    ["a@b.com", "c@d.com", "e@f.com", "g@h.com", "nope"],
    [],
    ["", " "],
]


def dist(**p):
    return {c: p.get(c.value, 0.0) for c in PiiCategory}


FUSE_CASES = [
    (dist(tckn=0.5, national_id_number=0.5), 0.9, SAMPLE_SETS[0]),
    (dist(tckn=0.1, national_id_number=0.9), 0.9, SAMPLE_SETS[0]),
    (dist(ssn=1.0), 0.9, ["12345678901", "23456789012"]),
    (dist(phone_number=1.0), 0.2, SAMPLE_SETS[2]),
    (dist(email_address=0.95, full_name=0.05), 0.97, SAMPLE_SETS[3]),
    (dist(ip_address=0.7, phone_number=0.3), 0.33333, SAMPLE_SETS[4]),
    ({c: round(1 / 12, 4) for c in PiiCategory}, 0.0, SAMPLE_SETS[0]),
]

PAYLOADS = [
    {"pii_likelihood": 0.97, "classification": {"email_address": 0.95, "full_name": 0.05}, "reasoning": "x"},
    {"pii_likelihood": "0.5", "classification": {"EMAIL_ADDRESS": 0.6, " full_name ": 0.2, "not_pii": 0.9}},
    {"pii_likelihood": 0.0, "classification": {c.value: 0.0 for c in PiiCategory}},
    {"pii_likelihood": 0.3, "classification": {c.value: 1 / 3 for c in PiiCategory}, "reasoning": 42},
    {"pii_likelihood": 0.123456789, "classification": {"tckn": 0.33333, "ssn": 0.66667, "email_address": "abc"}},
    {"pii_likelihood": 0.8, "classification": {"tckn": 1e-9, "ssn": 2e-9}},
    {"pii_likelihood": 1.5, "classification": {"ssn": 1}},
    {"pii_likelihood": True, "classification": {"ssn": 1}},
    {"pii_likelihood": 0.5, "classification": {}},
    {"pii_likelihood": 0.5, "classification": {"unknown": 0.5}},
    {"pii_likelihood": 0.5, "classification": [1, 2]},
    {"classification": {"ssn": 1}},
]

PROMPT_CASES = [
    (ColumnContext("public", "customers", "email", "character varying(100)"), ["a***@email.com", "b***@gmail.com"]),
    (ColumnContext("public", "customers", "last_login", "timestamp without time zone"), []),
    (ColumnContext("sales", 'we"ird', "notes", "text"), ['say "hi"\nnew line', "ümlaut ÄÖÜ", "back\\slash", "ctrl\x01"]),
]


def evidence_json(items):
    return [
        {"category": e.category.value, "check": e.check, "matched": e.matched, "checked": e.checked, "weight": e.weight}
        for e in items
    ]


def parse_case(payload):
    try:
        parsed = parse_classification(payload)
    except ValueError as exc:
        return {"payload": payload, "error": str(exc)}
    return {
        "payload": payload,
        "classification": {c.value: p for c, p in parsed.classification.items()},
        "pii_likelihood": parsed.pii_likelihood,
        "reasoning": parsed.reasoning,
        "category_fits": parsed.category_fits,
    }


settings = get_settings()
cipher = SecretCipher(settings.encryption_key)
token, _ = create_access_token("python-user", settings.model_copy(update={"jwt_expire_minutes": 60 * 24 * 365 * 50}))

golden = {
    "masking": [{"value": v, **{m.value: mask_sample(v, m) for m in MaskingMode}} for v in VALUES],
    "checks": [
        {
            "value": v,
            "tckn_format": ce.is_tckn_format(v),
            "tckn_checksum": ce.is_valid_tckn(v),
            "luhn": ce.is_valid_luhn_card(v),
            "email": ce.is_email(v),
            "ip_address": ce.is_ip_address(v),
        }
        for v in VALUES
    ],
    "evidence": [{"samples": s, "evidence": evidence_json(ce.collect_evidence(s))} for s in SAMPLE_SETS],
    "fuse": [
        {
            "classification": {c.value: p for c, p in llm.items()},
            "pii_likelihood": pii,
            "samples": samples,
            "fused": {c.value: p for c, p in ce.fuse(llm, pii, ce.collect_evidence(samples))[0].items()},
            "fused_pii_likelihood": ce.fuse(llm, pii, ce.collect_evidence(samples))[1],
        }
        for llm, pii, samples in FUSE_CASES
    ],
    "parse": [parse_case(p) for p in PAYLOADS],
    "system_prompt": SYSTEM_PROMPT,
    "user_prompts": [
        {
            "context": {"schema_name": c.schema_name, "table_name": c.table_name, "column_name": c.column_name,
                        "data_type": c.data_type},
            "samples": s,
            "mode": m.value,
            "prompt": build_user_prompt(c, s, m),
        }
        for c, s in PROMPT_CASES
        for m in MaskingMode
    ],
    # Interoperability: values produced by the Python service that the Java service must accept.
    "interop": {
        "encryption_key": settings.encryption_key,
        "encrypted": cipher.encrypt("demo-password-ü"),
        "plaintext": "demo-password-ü",
        "argon2_password": "secret",
        "argon2_hash": hash_password("secret"),
        "jwt_secret": settings.jwt_secret_key,
        "jwt": token,
        "jwt_subject": "python-user",
    },
}
json.dump(golden, sys.stdout, ensure_ascii=False, indent=1)
