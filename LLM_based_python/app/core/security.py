import base64
import binascii
import os
import secrets
from datetime import datetime, timedelta, timezone
from functools import lru_cache

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.core.config import Settings
from app.core.exceptions import DecryptionError

NONCE_SIZE = 12

_password_hasher = PasswordHasher()


def hash_password(password: str) -> str:
    return _password_hasher.hash(password)


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return _password_hasher.verify(password_hash, password)
    except (VerificationError, InvalidHashError):
        return False


@lru_cache
def dummy_password_hash() -> str:
    """Hash checked when a username does not exist, so response time doesn't reveal it."""
    return hash_password(secrets.token_urlsafe(16))


def create_access_token(subject: str, settings: Settings) -> tuple[str, int]:
    expires_in = settings.jwt_expire_minutes * 60
    now = datetime.now(timezone.utc)
    payload = {"sub": subject, "iat": now, "exp": now + timedelta(seconds=expires_in)}
    token = jwt.encode(payload, settings.jwt_secret_key, algorithm=settings.jwt_algorithm)
    return token, expires_in


def decode_access_token(token: str, settings: Settings) -> str:
    """Return the token subject. Raises jwt.PyJWTError if the token is invalid or expired."""
    payload = jwt.decode(
        token,
        settings.jwt_secret_key,
        algorithms=[settings.jwt_algorithm],
        options={"require": ["sub", "exp"]},
    )
    return payload["sub"]


class SecretCipher:
    """AES-256-GCM encryption for secrets stored in the system database.

    Stored format: base64(nonce[12] || ciphertext || tag[16]). This is the same layout
    javax.crypto produces for "AES/GCM/NoPadding", so a Java port can read the same data.
    """

    def __init__(self, key_b64: str):
        try:
            key = base64.b64decode(key_b64, validate=True)
        except binascii.Error as exc:
            raise ValueError("ENCRYPTION_KEY must be valid base64") from exc
        if len(key) != 32:
            raise ValueError("ENCRYPTION_KEY must decode to exactly 32 bytes")
        self._aesgcm = AESGCM(key)

    def encrypt(self, plaintext: str) -> str:
        nonce = os.urandom(NONCE_SIZE)
        ciphertext = self._aesgcm.encrypt(nonce, plaintext.encode(), None)
        return base64.b64encode(nonce + ciphertext).decode()

    def decrypt(self, token: str) -> str:
        try:
            raw = base64.b64decode(token, validate=True)
            plaintext = self._aesgcm.decrypt(raw[:NONCE_SIZE], raw[NONCE_SIZE:], None)
        except (binascii.Error, InvalidTag, ValueError) as exc:
            raise DecryptionError(
                "Stored credentials could not be decrypted (was ENCRYPTION_KEY changed?)"
            ) from exc
        return plaintext.decode()
