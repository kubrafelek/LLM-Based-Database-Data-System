from uuid import UUID

from fastapi import APIRouter, Depends, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_cipher, require_user
from app.core.config import Settings, get_settings
from app.core.security import SecretCipher
from app.domain.schemas import DbConnectionRequest, DeleteResponse, MetadataDetail, MetadataList
from app.infrastructure.database.system_database import get_session
from app.services import metadata_service

router = APIRouter(tags=["metadata"], dependencies=[Depends(require_user)])


@router.post("/db/metadata", response_model=MetadataDetail, status_code=status.HTTP_201_CREATED)
async def create_metadata(
    request: DbConnectionRequest,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
    cipher: SecretCipher = Depends(get_cipher),
) -> MetadataDetail:
    """Connect to a PostgreSQL database, extract its tables/columns and store them."""
    return await metadata_service.create_metadata(session, request, settings, cipher)


@router.get("/metadata", response_model=MetadataList)
async def list_metadata(
    limit: int = Query(50, ge=1, le=500),
    offset: int = Query(0, ge=0),
    session: AsyncSession = Depends(get_session),
) -> MetadataList:
    return await metadata_service.list_metadata(session, limit, offset)


@router.get("/metadata/{metadata_id}", response_model=MetadataDetail)
async def get_metadata(metadata_id: UUID, session: AsyncSession = Depends(get_session)) -> MetadataDetail:
    return await metadata_service.get_metadata(session, metadata_id)


@router.delete("/metadata/{metadata_id}", response_model=DeleteResponse)
async def delete_metadata(metadata_id: UUID, session: AsyncSession = Depends(get_session)) -> DeleteResponse:
    """Delete a metadata snapshot with its tables and columns. The stored connection is kept."""
    await metadata_service.delete_metadata(session, metadata_id)
    return DeleteResponse(metadata_id=metadata_id, deleted=True)
