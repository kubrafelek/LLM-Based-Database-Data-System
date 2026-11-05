import pytest

from app.core.exceptions import InvalidRequestError
from app.infrastructure.database.target_database import ConnectionInfo, build_sample_query, quote_ident


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


def test_sample_query_strips_netmask_from_inet():
    query = build_sample_query("public", "audit_logs", "ip_address", "inet")
    assert 'left(host("ip_address")' in query


def test_connection_info_repr_hides_password():
    info = ConnectionInfo("db", 5432, "app", "user", "top-secret")
    assert "top-secret" not in repr(info)
    assert "top-secret" not in info.describe()
