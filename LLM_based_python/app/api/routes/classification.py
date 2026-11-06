from fastapi import APIRouter, Depends

from app.api.dependencies import CipherDep, LLMDep, SessionDep, SettingsDep, require_user
from app.domain.schemas import ClassifyRequest, ClassifyResponse
from app.services.classification_service import classify_column

router = APIRouter(tags=["classification"], dependencies=[Depends(require_user)])


@router.post("/classify", response_model=ClassifyResponse)
async def classify(
    request: ClassifyRequest,
    session: SessionDep,
    settings: SettingsDep,
    cipher: CipherDep,
    llm: LLMDep,
) -> ClassifyResponse:
    """Sample a column's values and return a probability distribution over the 12 PII categories."""
    return await classify_column(session, request, settings, cipher, llm)
