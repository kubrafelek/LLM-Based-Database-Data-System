import base64
import binascii
from functools import lru_cache
from pathlib import Path
from typing import Literal, Self

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy.engine import URL

from app.domain.enums import MaskingMode, SslMode

# The development-only secrets shipped in .env.example. They let `cp .env.example .env` start the
# stack as is, and are rejected when APP_ENV=production.
EXAMPLE_SECRETS = {
    "dev-only-jwt-secret-change-me-0123456789abcdef",
    "ZGV2LW9ubHktZW5jcnlwdGlvbi1rZXktMzJieXRlcyE=",
}
MIN_JWT_SECRET_LENGTH = 32


class Settings(BaseSettings):
    """All runtime configuration, loaded from environment variables / the .env file."""

    # hide_input_in_errors: a validation error must not echo the settings (they contain secrets).
    model_config = SettingsConfigDict(
        env_file=".env", env_file_encoding="utf-8", extra="ignore", hide_input_in_errors=True
    )

    # Application
    app_env: Literal["development", "test", "production"] = "development"
    log_level: str = "INFO"
    log_format: Literal["text", "json"] = "text"
    # When set, GET /metrics needs "Authorization: Bearer <METRICS_TOKEN>"; empty = open (restrict it at the proxy)
    metrics_token: str = ""
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
    # Failed-login throttling (in memory, per process). 0 disables a limit.
    auth_max_failed_attempts: int = Field(default=5, ge=0)  # per client IP + username
    auth_max_failed_attempts_per_ip: int = Field(default=20, ge=0)  # per client IP, any username
    auth_failure_window_seconds: int = Field(default=300, ge=1)

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
    # Comma-separated hostnames, IPs or CIDRs a target may use. Empty = any host except the denied
    # ones (system DB, loopback, link-local/cloud metadata, multicast, unspecified, reserved).
    # A listed loopback address or hostname is allowed; the system DB never is.
    target_db_allowed_hosts: str = ""
    # TLS to target databases. verify-ca / verify-full check the certificate against
    # TARGET_DB_SSL_ROOT_CERT (a PEM CA bundle), or the system trust store when it is empty.
    target_db_ssl_mode: SslMode = SslMode.PREFER
    target_db_ssl_root_cert: str = ""
    max_sample_count: int = 100
    sample_masking_mode: MaskingMode = MaskingMode.PARTIAL
    # Fuse TCKN / Luhn checks on the raw samples into the LLM distribution
    checksum_validation_enabled: bool = True
    # Reuse a /classify result while the samples and settings are unchanged; 0 disables the cache
    classification_cache_ttl_seconds: int = 86400

    @model_validator(mode="after")
    def _check_secrets(self) -> Self:
        """Fail at startup with a clear message instead of failing on the first request."""
        problems = []
        for name in ("system_db_host", "system_db_name", "system_db_user", "auth_username", "auth_password"):
            if not getattr(self, name).strip():
                problems.append(f"{name.upper()} must not be empty")
        if len(self.jwt_secret_key) < MIN_JWT_SECRET_LENGTH:
            problems.append(
                f"JWT_SECRET_KEY must be at least {MIN_JWT_SECRET_LENGTH} characters "
                "(generate one with: openssl rand -hex 32)"
            )
        try:
            key_bytes = len(base64.b64decode(self.encryption_key, validate=True))
        except (binascii.Error, ValueError):
            key_bytes = -1
        if key_bytes != 32:
            problems.append(
                "ENCRYPTION_KEY must be base64 of exactly 32 bytes (generate one with: openssl rand -base64 32)"
            )
        if self.target_db_ssl_root_cert and not Path(self.target_db_ssl_root_cert).is_file():
            problems.append(f"TARGET_DB_SSL_ROOT_CERT file not found: {self.target_db_ssl_root_cert}")
        if self.app_env == "production":
            secrets = {
                "JWT_SECRET_KEY": self.jwt_secret_key,
                "ENCRYPTION_KEY": self.encryption_key,
                "AUTH_PASSWORD": self.auth_password,
                "SYSTEM_DB_PASSWORD": self.system_db_password,
            }
            for name, value in secrets.items():
                if value in EXAMPLE_SECRETS or value.startswith("change-me"):
                    problems.append(f"{name} still has its .env.example value; not allowed with APP_ENV=production")
        if problems:
            raise ValueError("Invalid configuration in .env:\n  - " + "\n  - ".join(problems))
        return self

    @property
    def cors_origins(self) -> list[str]:
        return [origin.strip() for origin in self.cors_allowed_origins.split(",") if origin.strip()]

    @property
    def target_allowed_hosts(self) -> list[str]:
        return [entry.strip() for entry in self.target_db_allowed_hosts.split(",") if entry.strip()]

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
