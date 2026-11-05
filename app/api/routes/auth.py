from fastapi import APIRouter, Body, Depends, HTTPException, status
from fastapi.security import HTTPBasicCredentials
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import basic_scheme
from app.core.config import Settings, get_settings
from app.core.security import create_access_token
from app.domain.schemas import LoginRequest, TokenResponse
from app.infrastructure.database.system_database import get_session
from app.services import auth_service

router = APIRouter(tags=["auth"])


@router.post("/auth", response_model=TokenResponse)
async def issue_token(
    body: LoginRequest | None = Body(default=None),
    basic: HTTPBasicCredentials | None = Depends(basic_scheme),
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> TokenResponse:
    """Exchange credentials for a short-lived Bearer token.

    Credentials can be sent as a JSON body or with HTTP Basic authentication.
    """
    if body is not None:
        username, password = body.username, body.password.get_secret_value()
    elif basic is not None:
        username, password = basic.username, basic.password
    else:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Provide credentials as a JSON body or with HTTP Basic authentication",
            headers={"WWW-Authenticate": "Basic"},
        )

    if not await auth_service.authenticate(session, username, password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid credentials",
            headers={"WWW-Authenticate": "Basic"},
        )
    token, expires_in = create_access_token(username, settings)
    return TokenResponse(access_token=token, expires_in=expires_in)
