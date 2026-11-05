from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy.engine import URL

from app.domain.enums import MaskingMode


class Settings(BaseSettings):
    """All runtime configuration, loaded from environment variables / the .env file."""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # Application
    app_env: Literal["development", "test", "production"] = "development"
    log_level: str = "INFO"
    cors_allowed_origins: str = ""  # comma-separated; empty disables CORS
    max_request_body_bytes: int = 64 * 1024

    # System database (users, metadata, encrypted connection info)
    system_db_host: str
    system_db_port: int = 5432
    system_db_name: str
    system_db_user: str
    system_db_password: str

    # Authentication. The admin user is created/updated from these values at startup
    # and stored with an Argon2 hash.
    auth_username: str
    auth_password: str
    jwt_secret_key: str
    jwt_algorithm: str = "HS256"
    jwt_expire_minutes: int = 60

    # Base64-encoded 32-byte key used to encrypt stored target DB passwords (AES-256-GCM)
    encryption_key: str

    # OpenAI-compatible LLM
    llm_base_url: str = "https://api.openai.com/v1"
    llm_api_key: str
    llm_model: str = "gpt-4o-mini"
    llm_timeout: float = 30.0
    llm_max_retries: int = 2
    llm_json_mode: bool = True

    # Target database access and sampling
    target_db_connect_timeout_seconds: float = 10.0
    target_db_statement_timeout_ms: int = 15000
    max_sample_count: int = 100
    sample_masking_mode: MaskingMode = MaskingMode.PARTIAL
    # Fuse TCKN / Luhn checks on the raw samples into the LLM distribution
    checksum_validation_enabled: bool = True
    # Reuse a /classify result while the samples and settings are unchanged; 0 disables the cache
    classification_cache_ttl_seconds: int = 86400

    @property
    def cors_origins(self) -> list[str]:
        return [origin.strip() for origin in self.cors_allowed_origins.split(",") if origin.strip()]

    @property
    def system_db_url(self) -> str:
        return URL.create(
            drivername="postgresql+asyncpg",
            username=self.system_db_user,
            password=self.system_db_password,
            host=self.system_db_host,
            port=self.system_db_port,
            database=self.system_db_name,
        ).render_as_string(hide_password=False)


@lru_cache
def get_settings() -> Settings:
    return Settings()
