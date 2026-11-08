import asyncio
import logging

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.exceptions import TooManyAttemptsError
from app.core.metrics import LOGIN_FAILURES, LOGIN_THROTTLED
from app.core.rate_limit import LoginAttemptLimiter
from app.core.security import dummy_password_hash, hash_password, verify_password
from app.domain.models import User
from app.repositories import user_repository

logger = logging.getLogger(__name__)


async def authenticate(session: AsyncSession, username: str, password: str) -> User | None:
    """The user when the password is right, else None."""
    user = await user_repository.get_by_username(session, username)
    # Verify against a dummy hash for unknown users so timing doesn't reveal which usernames exist.
    password_hash = user.password_hash if user is not None else dummy_password_hash()
    valid = await asyncio.to_thread(verify_password, password, password_hash)
    return user if user is not None and valid else None


async def authenticate_throttled(
    session: AsyncSession, username: str, password: str, client_ip: str, limiter: LoginAttemptLimiter
) -> User | None:
    """authenticate() behind the failed-login limiter: raises TooManyAttemptsError (429) while blocked.

    The check runs before the password hash, so a blocked client cannot keep testing passwords.
    """
    try:
        limiter.check(client_ip, username)
    except TooManyAttemptsError:
        LOGIN_THROTTLED.inc()
        raise
    user = await authenticate(session, username, password)
    if user is not None:
        limiter.record_success(client_ip, username)
        return user
    limiter.record_failure(client_ip, username)
    LOGIN_FAILURES.inc()
    # No username in the log: people sometimes type their password into that field.
    logger.warning("Failed login from %s", client_ip)
    return None


async def ensure_admin_user(session: AsyncSession, settings: Settings) -> None:
    """Create the admin user from AUTH_USERNAME/AUTH_PASSWORD, or update its password if it changed."""
    user = await user_repository.get_by_username(session, settings.auth_username)
    if user is None:
        user_repository.add(
            session,
            User(username=settings.auth_username, password_hash=hash_password(settings.auth_password)),
        )
        logger.info("Created admin user %r", settings.auth_username)
    elif not verify_password(settings.auth_password, user.password_hash):
        user.password_hash = hash_password(settings.auth_password)
        # A new password must also end the sessions opened with the old one.
        user.token_version += 1
        logger.info("Updated password of admin user %r and revoked its tokens", settings.auth_username)
    else:
        return
    await session.commit()
