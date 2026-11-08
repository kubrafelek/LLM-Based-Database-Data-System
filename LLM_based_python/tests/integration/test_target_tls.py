"""TLS to the target DB (compose starts test-target-db with ssl=on and the dev certificate in db/certs)."""

import os
import socket

import pytest

from app.core.config import get_settings
from app.core.exceptions import TargetDatabaseError
from app.domain.enums import SslMode
from app.infrastructure.database.target_database import ConnectionInfo, connect_target

SSL_IN_USE = "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()"


def info(request, host: str | None = None) -> ConnectionInfo:
    return ConnectionInfo(
        host=host or request.host,
        port=request.port,
        database=request.database,
        username=request.username,
        password=request.password.get_secret_value(),
    )


def tls_settings(mode: SslMode, root_cert: str = ""):
    return get_settings().model_copy(update={"target_db_ssl_mode": mode, "target_db_ssl_root_cert": root_cert})


@pytest.fixture
def ca_cert() -> str:
    return os.environ["TEST_TLS_CA_CERT"]


@pytest.mark.parametrize("mode", [SslMode.PREFER, SslMode.REQUIRE])
async def test_connection_is_encrypted(target_request, mode):
    async with connect_target(info(target_request), tls_settings(mode)) as conn:
        assert await conn.fetchval(SSL_IN_USE) is True


async def test_disable_connects_in_plain_text(target_request):
    async with connect_target(info(target_request), tls_settings(SslMode.DISABLE)) as conn:
        assert await conn.fetchval(SSL_IN_USE) is False


async def test_verify_full_accepts_the_trusted_certificate(target_request, ca_cert):
    async with connect_target(info(target_request), tls_settings(SslMode.VERIFY_FULL, ca_cert)) as conn:
        assert await conn.fetchval(SSL_IN_USE) is True


async def test_verify_full_rejects_a_name_the_certificate_does_not_cover(target_request, ca_cert):
    # The IP address of test-target-db is not in the certificate's names.
    ip = socket.gethostbyname(target_request.host)
    with pytest.raises(TargetDatabaseError):
        async with connect_target(info(target_request, host=ip), tls_settings(SslMode.VERIFY_FULL, ca_cert)):
            pass


async def test_verify_ca_checks_the_issuer_but_not_the_name(target_request, ca_cert):
    ip = socket.gethostbyname(target_request.host)
    async with connect_target(info(target_request, host=ip), tls_settings(SslMode.VERIFY_CA, ca_cert)) as conn:
        assert await conn.fetchval(SSL_IN_USE) is True


async def test_verify_ca_rejects_an_untrusted_certificate(target_request):
    # System trust store only: the dev CA is not in it.
    with pytest.raises(TargetDatabaseError):
        async with connect_target(info(target_request), tls_settings(SslMode.VERIFY_CA)):
            pass
