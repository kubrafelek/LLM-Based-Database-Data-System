from typing import Annotated

import jwt
from fastapi import APIRouter, Body, Depends, HTTPException, Response, status

from app.api.dependencies import (
    INVALID_TOKEN,
    BasicCredentialsDep,
    BearerDep,
    ClientIpDep,
    LoginLimiterDep,
    SessionDep,
    SettingsDep,
    TokenValidatorDep,
    require_user,
)
from app.core.security import create_access_token, decode_token
from app.domain.schemas import LoginRequest, TokenResponse
from app.repositories import token_repository, user_repository
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

    user = await auth_service.authenticate_throttled(session, username, password, client_ip, limiter)
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid credentials",
            headers={"WWW-Authenticate": "Basic"},
        )
    token, expires_in = create_access_token(username, settings, user.token_version)
    return TokenResponse(access_token=token, expires_in=expires_in)


@router.post("/auth/logout", status_code=status.HTTP_204_NO_CONTENT, response_class=Response)
async def revoke_token(
    session: SessionDep,
    settings: SettingsDep,
    bearer: BearerDep,
    basic: BasicCredentialsDep,
    token_is_valid: TokenValidatorDep,
) -> Response:
    """Revoke the Bearer token sent with this request; it is rejected from now on."""
    if bearer is None:
        if basic is not None:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Only Bearer tokens can be revoked; Basic credentials have no session",
            )
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="Not authenticated", headers={"WWW-Authenticate": "Bearer"}
        )
    try:
        claims = decode_token(bearer.credentials, settings)
    except jwt.PyJWTError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail=INVALID_TOKEN, headers={"WWW-Authenticate": "Bearer"}
        ) from exc
    if claims.token_id is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="This token has no ID and cannot be revoked"
        )
    if not await token_is_valid(claims):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail=INVALID_TOKEN, headers={"WWW-Authenticate": "Bearer"}
        )
    await token_repository.revoke(session, claims.token_id, claims.expires_at)
    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/auth/logout-all", status_code=status.HTTP_204_NO_CONTENT, response_class=Response)
async def revoke_all_tokens(session: SessionDep, username: Annotated[str, Depends(require_user)]) -> Response:
    """Revoke every token of the calling user (Bearer token or Basic credentials).

    Use it when a token may have leaked: with the password (Basic) it works even without a valid token.
    """
    await user_repository.bump_token_version(session, username)
    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)
