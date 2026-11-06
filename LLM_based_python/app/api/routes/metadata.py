from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Query, status

from app.api.dependencies import CipherDep, SessionDep, SettingsDep, require_user
from app.domain.schemas import DbConnectionRequest, DeleteResponse, MetadataDetail, MetadataList
from app.services import metadata_service

router = APIRouter(tags=["metadata"], dependencies=[Depends(require_user)])


@router.post("/db/metadata", response_model=MetadataDetail, status_code=status.HTTP_201_CREATED)
async def create_metadata(
    request: DbConnectionRequest,
    session: SessionDep,
    settings: SettingsDep,
    cipher: CipherDep,
) -> MetadataDetail:
    """Connect to a PostgreSQL database, extract its tables/columns and store them."""
    return await metadata_service.create_metadata(session, request, settings, cipher)


@router.get("/metadata", response_model=MetadataList)
async def list_metadata(
    session: SessionDep,
    limit: Annotated[int, Query(ge=1, le=500)] = 50,
    offset: Annotated[int, Query(ge=0)] = 0,
) -> MetadataList:
    return await metadata_service.list_metadata(session, limit, offset)


@router.get("/metadata/{metadata_id}", response_model=MetadataDetail)
async def get_metadata(metadata_id: UUID, session: SessionDep) -> MetadataDetail:
    return await metadata_service.get_metadata(session, metadata_id)


@router.delete("/metadata/{metadata_id}", response_model=DeleteResponse)
async def delete_metadata(metadata_id: UUID, session: SessionDep) -> DeleteResponse:
    """Delete a snapshot with its tables, columns and cached results.

    The stored connection is deleted too when no other snapshot uses it.
    """
    await metadata_service.delete_metadata(session, metadata_id)
    return DeleteResponse(metadata_id=metadata_id, deleted=True)
