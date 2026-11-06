import base64
from datetime import UTC, datetime, timedelta

import jwt
import pytest

from app.core.config import get_settings
from app.core.exceptions import DecryptionError
from app.core.security import (
    SecretCipher,
    create_access_token,
    decode_access_token,
    hash_password,
    verify_password,
)


def make_cipher() -> SecretCipher:
    return SecretCipher(base64.b64encode(b"x" * 32).decode())


def test_cipher_round_trip_uses_random_nonce():
    cipher = make_cipher()
    first, second = cipher.encrypt("p@ss wörd"), cipher.encrypt("p@ss wörd")
    assert first != second
    assert cipher.decrypt(first) == "p@ss wörd"


def test_cipher_rejects_tampered_ciphertext():
    cipher = make_cipher()
    raw = bytearray(base64.b64decode(cipher.encrypt("secret")))
    raw[-1] ^= 0x01
    with pytest.raises(DecryptionError):
        cipher.decrypt(base64.b64encode(bytes(raw)).decode())


def test_cipher_rejects_wrong_key():
    token = make_cipher().encrypt("secret")
    other = SecretCipher(base64.b64encode(b"y" * 32).decode())
    with pytest.raises(DecryptionError):
        other.decrypt(token)


@pytest.mark.parametrize("key", ["not base64!", base64.b64encode(b"short").decode()])
def test_cipher_rejects_invalid_key(key):
    with pytest.raises(ValueError):
        SecretCipher(key)


def test_password_hash_is_argon2_and_verifies():
    password_hash = hash_password("secret")
    assert password_hash.startswith("$argon2")
    assert "secret" not in password_hash
    assert verify_password("secret", password_hash)
    assert not verify_password("wrong", password_hash)


def test_verify_password_rejects_malformed_hash():
    assert not verify_password("secret", "not-a-hash")


def test_token_round_trip():
    settings = get_settings()
    token, expires_in = create_access_token("admin", settings)
    assert expires_in == settings.jwt_expire_minutes * 60
    assert decode_access_token(token, settings) == "admin"


def test_expired_token_is_rejected():
    settings = get_settings()
    past = datetime.now(UTC) - timedelta(minutes=5)
    token = jwt.encode({"sub": "admin", "exp": past}, settings.jwt_secret_key, algorithm="HS256")
    with pytest.raises(jwt.ExpiredSignatureError):
        decode_access_token(token, settings)


def test_token_signed_with_other_key_is_rejected():
    settings = get_settings()
    future = datetime.now(UTC) + timedelta(minutes=5)
    token = jwt.encode({"sub": "admin", "exp": future}, "another-secret-key-of-sufficient-length", algorithm="HS256")
    with pytest.raises(jwt.InvalidSignatureError):
        decode_access_token(token, settings)
