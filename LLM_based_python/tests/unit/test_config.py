import pytest
from pydantic import ValidationError

from app.core.config import Settings

DEV_JWT = "dev-only-jwt-secret-change-me-0123456789abcdef"
DEV_KEY = "ZGV2LW9ubHktZW5jcnlwdGlvbi1rZXktMzJieXRlcyE="


def settings(**overrides) -> Settings:
    return Settings(_env_file=None, **overrides)


def test_test_environment_settings_are_valid():
    assert settings().jwt_algorithm == "HS256"


@pytest.mark.parametrize(
    ("overrides", "message"),
    [
        ({"jwt_secret_key": ""}, "JWT_SECRET_KEY must be at least 32 characters"),
        ({"jwt_secret_key": "too-short"}, "JWT_SECRET_KEY must be at least 32 characters"),
        ({"encryption_key": ""}, "ENCRYPTION_KEY must be base64 of exactly 32 bytes"),
        ({"encryption_key": "not base64!"}, "ENCRYPTION_KEY must be base64 of exactly 32 bytes"),
        ({"encryption_key": "c2hvcnQ="}, "ENCRYPTION_KEY must be base64 of exactly 32 bytes"),
        ({"auth_password": "  "}, "AUTH_PASSWORD must not be empty"),
        ({"target_db_ssl_root_cert": "/no/such/ca.crt"}, "TARGET_DB_SSL_ROOT_CERT file not found"),
        ({"target_db_ssl_mode": "sometimes"}, "target_db_ssl_mode"),
        ({"log_format": "xml"}, "log_format"),
    ],
)
def test_invalid_configuration_fails_with_a_clear_message(overrides, message):
    with pytest.raises(ValidationError, match=message):
        settings(**overrides)


def test_all_problems_are_reported_at_once():
    with pytest.raises(ValidationError) as exc_info:
        settings(jwt_secret_key="", encryption_key="")
    assert "JWT_SECRET_KEY" in str(exc_info.value)
    assert "ENCRYPTION_KEY" in str(exc_info.value)


def test_example_secrets_work_in_development():
    assert settings(jwt_secret_key=DEV_JWT, encryption_key=DEV_KEY).app_env == "test"


@pytest.mark.parametrize(
    "overrides",
    [
        {"jwt_secret_key": DEV_JWT},
        {"encryption_key": DEV_KEY},
        {"auth_password": "change-me-admin"},
        {"system_db_password": "change-me-system-db"},
    ],
)
def test_example_secrets_are_rejected_in_production(overrides):
    with pytest.raises(ValidationError, match="not allowed with APP_ENV=production"):
        settings(app_env="production", **overrides)


def test_production_with_real_secrets_is_valid():
    assert settings(app_env="production", auth_password="a-real-password").app_env == "production"
