from sqlalchemy import func, select

from app.core.config import get_settings
from app.domain.models import User
from app.services import auth_service


async def test_ensure_admin_user_stores_a_hash(session):
    settings = get_settings()
    await auth_service.ensure_admin_user(session, settings)

    user = await session.scalar(select(User))
    assert user.username == settings.auth_username
    assert user.password_hash.startswith("$argon2")
    assert settings.auth_password not in user.password_hash


async def test_ensure_admin_user_is_idempotent(session):
    settings = get_settings()
    await auth_service.ensure_admin_user(session, settings)
    first_hash = (await session.scalar(select(User))).password_hash

    await auth_service.ensure_admin_user(session, settings)

    assert await session.scalar(select(func.count()).select_from(User)) == 1
    assert (await session.scalar(select(User))).password_hash == first_hash


async def test_ensure_admin_user_applies_a_changed_password(session):
    settings = get_settings()
    await auth_service.ensure_admin_user(session, settings)

    rotated = settings.model_copy(update={"auth_password": "rotated-password"})
    await auth_service.ensure_admin_user(session, rotated)

    assert await auth_service.authenticate(session, settings.auth_username, "rotated-password")
    assert not await auth_service.authenticate(session, settings.auth_username, settings.auth_password)


async def test_authenticate(session):
    settings = get_settings()
    await auth_service.ensure_admin_user(session, settings)

    assert await auth_service.authenticate(session, settings.auth_username, settings.auth_password)
    assert not await auth_service.authenticate(session, settings.auth_username, "wrong")
    assert not await auth_service.authenticate(session, "nobody", settings.auth_password)
