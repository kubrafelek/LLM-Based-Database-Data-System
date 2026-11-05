from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field, SecretStr

from app.domain.enums import MaskingMode, PiiCategory


class LoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=150)
    password: SecretStr = Field(min_length=1, max_length=1024)


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int


class DbConnectionRequest(BaseModel):
    host: str = Field(min_length=1, max_length=255, examples=["demo-db"])
    port: int = Field(default=5432, ge=1, le=65535)
    database: str = Field(min_length=1, max_length=255, examples=["llm_discovery_demo"])
    username: str = Field(min_length=1, max_length=255)
    password: SecretStr = Field(max_length=1024)
    schema_name: str | None = Field(
        default=None,
        min_length=1,
        max_length=63,
        description="Only extract this schema. All non-system schemas when omitted.",
    )


class ColumnOut(BaseModel):
    column_id: UUID
    column_name: str
    data_type: str
    is_nullable: bool
    ordinal_position: int


class TableOut(BaseModel):
    table_id: UUID
    schema_name: str
    table_name: str
    table_type: str
    columns: list[ColumnOut]


class MetadataSummary(BaseModel):
    metadata_id: UUID
    connection_id: UUID
    database_name: str
    schema_name: str | None
    host: str
    port: int
    created_at: datetime
    table_count: int


class MetadataList(BaseModel):
    total: int
    items: list[MetadataSummary]


class MetadataDetail(MetadataSummary):
    tables: list[TableOut]


class DeleteResponse(BaseModel):
    metadata_id: UUID
    deleted: bool


class ClassifyRequest(BaseModel):
    column_id: UUID
    sample_count: int = Field(default=10, ge=1)
    force_refresh: bool = Field(default=False, description="Ignore a cached result and call the LLM again.")


class ChecksumEvidenceOut(BaseModel):
    category: PiiCategory
    check: str = Field(description="tckn_checksum, tckn_format or luhn")
    matched: int = Field(description="Non-empty samples that passed the check")
    checked: int = Field(description="Non-empty samples checked")
    weight: float = Field(description="How far the distribution was pulled toward the category")


class ClassifyResponse(BaseModel):
    column_id: UUID
    metadata_id: UUID
    schema_name: str
    table_name: str
    column_name: str
    data_type: str
    sample_count_requested: int
    sample_count_used: int
    masking_mode: MaskingMode
    classification: dict[str, float] = Field(
        description="Probability per PII category; always all 12 categories, summing to 1. "
        "The LLM distribution fused with checksum evidence. Uniform when no category fits the column. "
        "These are model confidence scores, not calibrated statistical probabilities."
    )
    predicted_category: PiiCategory | None = Field(
        description="Most likely category, or null when no category fits the column."
    )
    pii_likelihood: float = Field(
        description="Score (0-1) that the column contains PII at all. Not a calibrated probability."
    )
    llm_classification: dict[str, float] = Field(description="The LLM's distribution before fusion.")
    llm_pii_likelihood: float = Field(description="The LLM's pii_likelihood before fusion.")
    checksum_evidence: list[ChecksumEvidenceOut] = Field(
        description="Deterministic checks (TCKN algorithm, Luhn, email / IP syntax) that most samples passed."
    )
    reasoning: str | None
    model: str
    classified_at: datetime = Field(description="When the LLM produced this result.")
    cached: bool = Field(
        description="True when the result was reused: same column, samples, model, prompt and settings."
    )
