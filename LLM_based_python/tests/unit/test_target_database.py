import os
import ssl
from pathlib import Path

import pytest

from app.core.config import get_settings
from app.core.exceptions import InvalidRequestError
from app.domain.enums import SslMode
from app.infrastructure.database.target_database import ConnectionInfo, build_sample_query, quote_ident, ssl_option

# Mounted at TEST_TLS_CA_CERT in the compose "tests" service; the repo copy otherwise.
DEV_CA = Path(os.environ.get("TEST_TLS_CA_CERT") or Path(__file__).resolve().parents[2] / "db" / "certs" / "ca.crt")


def test_quote_ident_escapes_double_quotes():
    assert quote_ident("email") == '"email"'
    assert quote_ident('x"; DROP TABLE t; --') == '"x""; DROP TABLE t; --"'


@pytest.mark.parametrize("name", ["", "a\x00b", "x" * 64])
def test_quote_ident_rejects_invalid_identifiers(name):
    with pytest.raises(InvalidRequestError):
        quote_ident(name)


def test_sample_query_quotes_identifiers_and_binds_limit():
    query = build_sample_query("public", "Customers", "e-mail", "character varying(100)")
    assert 'FROM "public"."Customers"' in query
    assert 'left("e-mail"::text' in query
    assert query.endswith("LIMIT $1")
    # Deterministic order (cacheable), not random()
    assert "ORDER BY md5(value), value" in query
    assert "random()" not in query


def test_sample_query_strips_netmask_from_inet():
    query = build_sample_query("public", "audit_logs", "ip_address", "inet")
    assert 'left(host("ip_address")' in query


def test_connection_info_repr_hides_password():
    info = ConnectionInfo("db", 5432, "app", "user", "top-secret")
    assert "top-secret" not in repr(info)
    assert "top-secret" not in info.describe()


@pytest.mark.parametrize("mode", [SslMode.DISABLE, SslMode.PREFER, SslMode.REQUIRE])
def test_non_verifying_ssl_modes_are_passed_by_name(mode):
    assert ssl_option(get_settings().model_copy(update={"target_db_ssl_mode": mode})) == mode.value


def test_default_ssl_mode_is_prefer():
    assert get_settings().target_db_ssl_mode == SslMode.PREFER


@pytest.mark.parametrize(("mode", "check_hostname"), [(SslMode.VERIFY_CA, False), (SslMode.VERIFY_FULL, True)])
def test_verifying_ssl_modes_build_a_context_with_the_ca(mode, check_hostname):
    settings = get_settings().model_copy(update={"target_db_ssl_mode": mode, "target_db_ssl_root_cert": str(DEV_CA)})
    context = ssl_option(settings)
    assert isinstance(context, ssl.SSLContext)
    assert context.verify_mode == ssl.CERT_REQUIRED
    assert context.check_hostname is check_hostname
    assert any("dev CA" in str(cert.get("subject")) for cert in context.get_ca_certs())
