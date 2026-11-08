from typing import Annotated

from fastapi import APIRouter, Body, HTTPException, status

from app.api.dependencies import BasicCredentialsDep, ClientIpDep, LoginLimiterDep, SessionDep, SettingsDep
from app.core.security import create_access_token
from app.domain.schemas import LoginRequest, TokenResponse
from app.services import auth_service

router = APIRouter(tags=["auth"])


@router.post("/auth", response_model=TokenResponse)
async def issue_token(
    session: SessionDep,
    settings: SettingsDep,
    basic: BasicCredentialsDep,
    limiter: LoginLimiterDep,
    client_ip: ClientIpDep,
    body: Annotated[LoginRequest | None, Body()] = None,
) -> TokenResponse:
    """Exchange credentials for a short-lived Bearer token.

    Credentials can be sent as a JSON body or with HTTP Basic authentication.
    Repeated wrong passwords from one client are answered with 429 and a Retry-After header.
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

    if not await auth_service.authenticate_throttled(session, username, password, client_ip, limiter):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid credentials",
            headers={"WWW-Authenticate": "Basic"},
        )
    token, expires_in = create_access_token(username, settings)
    return TokenResponse(access_token=token, expires_in=expires_in)
