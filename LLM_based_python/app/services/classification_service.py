from datetime import datetime, timedelta, timezone

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.exceptions import InvalidRequestError, NotFoundError
from app.core.security import SecretCipher
from app.domain.schemas import ChecksumEvidenceOut, ClassifyRequest, ClassifyResponse
from app.infrastructure.database.target_database import ConnectionInfo, connect_target, fetch_samples
from app.infrastructure.llm.llm_client import LLMClient
from app.infrastructure.llm.prompts import ColumnContext, build_messages
from app.repositories import classification_repository, metadata_repository
from app.services.checksum_evidence import collect_evidence, fuse
from app.services.classification_cache import cache_key
from app.services.classification_parser import parse_classification
from app.services.sample_sanitizer import mask_sample


async def classify_column(
    session: AsyncSession,
    request: ClassifyRequest,
    settings: Settings,
    cipher: SecretCipher,
    llm: LLMClient,
) -> ClassifyResponse:
    if request.sample_count > settings.max_sample_count:
        raise InvalidRequestError(f"sample_count must be at most {settings.max_sample_count}")

    record = await metadata_repository.get_column_context(session, request.column_id)
    if record is None:
        raise NotFoundError(f"Column {request.column_id} not found")
    column, table, connection = record

    info = ConnectionInfo(
        host=connection.host,
        port=connection.port,
        database=connection.database_name,
        username=connection.username,
        password=cipher.decrypt(connection.encrypted_password),
    )
    async with connect_target(info, settings) as conn:
        raw_samples = await fetch_samples(
            conn,
            table.schema_name,
            table.table_name,
            column.column_name,
            column.data_type,
            request.sample_count,
        )

    # The samples are fetched even on a cache hit: they are what tells us the data is unchanged.
    now = datetime.now(timezone.utc)
    ttl = settings.classification_cache_ttl_seconds
    key = cache_key(column.id, request.sample_count, raw_samples, llm.model, settings)
    if ttl > 0 and not request.force_refresh:
        hit = await classification_repository.get_fresh(session, column.id, key, now - timedelta(seconds=ttl))
        if hit is not None:
            return ClassifyResponse.model_validate({**hit.result, "cached": True})

    masking_mode = settings.sample_masking_mode
    samples = [mask_sample(value, masking_mode) for value in raw_samples]
    context = ColumnContext(
        schema_name=table.schema_name,
        table_name=table.table_name,
        column_name=column.column_name,
        data_type=column.data_type,
    )
    parsed = await llm.complete_json(build_messages(context, samples, masking_mode), parse_classification)

    evidence = collect_evidence(raw_samples) if settings.checksum_validation_enabled else []
    classification, pii_likelihood = fuse(parsed.classification, parsed.pii_likelihood, evidence)
    # A passing check makes its category fit even when the LLM said none did.
    category_fits = parsed.category_fits or bool(evidence)
    predicted = max(classification, key=classification.__getitem__) if category_fits else None
    response = ClassifyResponse(
        column_id=column.id,
        metadata_id=table.metadata_id,
        schema_name=table.schema_name,
        table_name=table.table_name,
        column_name=column.column_name,
        data_type=column.data_type,
        sample_count_requested=request.sample_count,
        sample_count_used=len(samples),
        masking_mode=masking_mode,
        classification={category.value: p for category, p in classification.items()},
        predicted_category=predicted,
        pii_likelihood=pii_likelihood,
        llm_classification={category.value: p for category, p in parsed.classification.items()},
        llm_pii_likelihood=parsed.pii_likelihood,
        checksum_evidence=[
            ChecksumEvidenceOut(
                category=item.category,
                check=item.check,
                matched=item.matched,
                checked=item.checked,
                weight=item.weight,
            )
            for item in evidence
        ],
        reasoning=parsed.reasoning,
        model=llm.model,
        classified_at=now,
        cached=False,
    )
    if ttl > 0:
        stored = response.model_dump(mode="json", exclude={"cached"})
        await classification_repository.save(session, column.id, key, stored, now)
        await session.commit()
    return response
