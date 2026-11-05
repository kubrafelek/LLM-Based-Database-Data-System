import base64
import os
from pathlib import Path

import pytest

# Settings are read from the environment on first use, so test defaults must be in
# place before any app module is imported. Values already set (e.g. by the compose
# "tests" service) win.
TEST_ENV = {
    "APP_ENV": "test",
    "SYSTEM_DB_HOST": "localhost",
    "SYSTEM_DB_NAME": "test",
    "SYSTEM_DB_USER": "test",
    "SYSTEM_DB_PASSWORD": "test",
    "AUTH_USERNAME": "admin",
    "AUTH_PASSWORD": "secret",
    "JWT_SECRET_KEY": "test-jwt-secret-with-enough-length-1234",
    "ENCRYPTION_KEY": base64.b64encode(b"k" * 32).decode(),
    "LLM_API_KEY": "test",
    "LLM_BASE_URL": "http://llm.test/v1",
    "LLM_MODEL": "test-model",
}
for key, value in TEST_ENV.items():
    os.environ.setdefault(key, value)

TESTS_DIR = Path(__file__).parent
DB_TEST_DIRS = (TESTS_DIR / "integration", TESTS_DIR / "e2e")


def pytest_collection_modifyitems(config: pytest.Config, items: list[pytest.Item]) -> None:
    """Integration and e2e tests need real databases; run them with RUN_INTEGRATION=1."""
    if os.environ.get("RUN_INTEGRATION") == "1":
        return
    skip = pytest.mark.skip(reason="needs databases; set RUN_INTEGRATION=1 (see README)")
    for item in items:
        if any(item.path.is_relative_to(directory) for directory in DB_TEST_DIRS):
            item.add_marker(skip)
