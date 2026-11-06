from functools import lru_cache
from typing import Annotated

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBasic, HTTPBasicCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings, get_settings
from app.core.security import SecretCipher, decode_access_token
from app.infrastructure.database.system_database import get_session
from app.infrastructure.llm.llm_client import LLMClient
from app.services import auth_service

bearer_scheme = HTTPBearer(auto_error=False, description="JWT obtained from POST /api/v1/auth")
basic_scheme = HTTPBasic(auto_error=False)


@lru_cache
def get_cipher() -> SecretCipher:
    return SecretCipher(get_settings().encryption_key)


@lru_cache
def get_llm_client() -> LLMClient:
    return LLMClient.from_settings(get_settings())


# Dependency aliases used by the route handlers.
SessionDep = Annotated[AsyncSession, Depends(get_session)]
SettingsDep = Annotated[Settings, Depends(get_settings)]
CipherDep = Annotated[SecretCipher, Depends(get_cipher)]
LLMDep = Annotated[LLMClient, Depends(get_llm_client)]
BasicCredentialsDep = Annotated[HTTPBasicCredentials | None, Depends(basic_scheme)]


async def require_user(
    bearer: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)],
    basic: BasicCredentialsDep,
    settings: SettingsDep,
    session: SessionDep,
) -> str:
    """Accept either a Bearer token from /api/v1/auth or Basic credentials directly."""
    if bearer is not None:
        try:
            return decode_access_token(bearer.credentials, settings)
        except jwt.PyJWTError as exc:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid or expired token",
                headers={"WWW-Authenticate": "Bearer"},
            ) from exc
    if basic is not None and await auth_service.authenticate(session, basic.username, basic.password):
        return basic.username
    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Not authenticated",
        headers={"WWW-Authenticate": "Bearer"},
    )
