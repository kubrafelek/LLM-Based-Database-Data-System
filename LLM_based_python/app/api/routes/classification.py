from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_cipher, get_llm_client, require_user
from app.core.config import Settings, get_settings
from app.core.security import SecretCipher
from app.domain.schemas import ClassifyRequest, ClassifyResponse
from app.infrastructure.database.system_database import get_session
from app.infrastructure.llm.llm_client import LLMClient
from app.services.classification_service import classify_column

router = APIRouter(tags=["classification"], dependencies=[Depends(require_user)])


@router.post("/classify", response_model=ClassifyResponse)
async def classify(
    request: ClassifyRequest,
    session: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
    cipher: SecretCipher = Depends(get_cipher),
    llm: LLMClient = Depends(get_llm_client),
) -> ClassifyResponse:
    """Sample a column's values and return a probability distribution over the 12 PII categories."""
    return await classify_column(session, request, settings, cipher, llm)
