"""Access to external (target) PostgreSQL databases: connecting, schema inspection, sampling."""

import logging
import ssl
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass, field

import asyncpg

from app.core.config import Settings
from app.core.exceptions import (
    InvalidRequestError,
    StaleMetadataError,
    TargetDatabaseError,
    TargetHostNotAllowedError,
)
from app.core.metrics import TARGET_DB_CONNECTIONS
from app.domain.enums import SslMode
from app.infrastructure.database.host_policy import allowed_addresses

logger = logging.getLogger(__name__)

CONNECT_ERROR = (
    "Could not connect to the target database. Check the connection details and that the database is reachable."
)
EXTRACT_ERROR = "Could not read metadata from the target database."
SAMPLE_ERROR = "Could not read sample values from the target database."

# PostgreSQL truncates identifiers to NAMEDATALEN - 1 bytes.
MAX_IDENTIFIER_BYTES = 63
# Long values (free text, JSON) are truncated before being sent to the LLM.
MAX_VALUE_LENGTH = 200


@dataclass(frozen=True)
class ConnectionInfo:
    host: str
    port: int
    database: str
    username: str
    password: str = field(repr=False)

    def describe(self) -> str:
        """Safe for logs: no credentials."""
        return f"{self.host}:{self.port}/{self.database}"


def _error_code(exc: Exception) -> str:
    return getattr(exc, "sqlstate", None) or exc.__class__.__name__


def ssl_option(settings: Settings) -> str | ssl.SSLContext:
    """asyncpg's ssl argument for TARGET_DB_SSL_MODE.

    disable / prefer / require are passed by name (asyncpg handles them like libpq). The verifying
    modes need an SSLContext: CA from TARGET_DB_SSL_ROOT_CERT or the system trust store, plus the
    host name check for verify-full.
    """
    mode = settings.target_db_ssl_mode
    if mode in (SslMode.DISABLE, SslMode.PREFER, SslMode.REQUIRE):
        return mode.value
    context = ssl.create_default_context(cafile=settings.target_db_ssl_root_cert or None)
    context.check_hostname = mode == SslMode.VERIFY_FULL
    return context


@asynccontextmanager
async def connect_target(info: ConnectionInfo, settings: Settings) -> AsyncIterator[asyncpg.Connection]:
    """Open a read-only, time-limited connection to a target database.

    The host must pass the target host policy (host_policy.py); TargetHostNotAllowedError otherwise.
    The connection goes to the addresses that were checked, not to a fresh DNS lookup, except with
    TARGET_DB_SSL_MODE=verify-full: the certificate is matched against the name the driver connects
    to, so it gets the host name (a changed DNS answer must then also present a valid certificate).
    """
    try:
        try:
            addresses = await allowed_addresses(info.host, info.port, settings)
        except TargetHostNotAllowedError:
            TARGET_DB_CONNECTIONS.labels("denied").inc()
            raise
        verify_name = settings.target_db_ssl_mode == SslMode.VERIFY_FULL
        conn = await asyncpg.connect(
            host=info.host if verify_name else addresses,
            ssl=ssl_option(settings),
            port=info.port,
            database=info.database,
            user=info.username,
            password=info.password,
            timeout=settings.target_db_connect_timeout_seconds,
            server_settings={
                "default_transaction_read_only": "on",
                "statement_timeout": str(settings.target_db_statement_timeout_ms),
                "application_name": "llm-data-discovery",
            },
        )
    except (TimeoutError, OSError, ssl.SSLError, asyncpg.PostgresError, asyncpg.InterfaceError) as exc:
        TARGET_DB_CONNECTIONS.labels("error").inc()
        # Driver messages can contain user names or server details; keep them out of the response.
        logger.warning("Target DB connection to %s failed: %s", info.describe(), _error_code(exc))
        raise TargetDatabaseError(CONNECT_ERROR) from exc
    TARGET_DB_CONNECTIONS.labels("ok").inc()
    try:
        yield conn
    finally:
        await conn.close()


def quote_ident(name: str) -> str:
    """Validate and quote a PostgreSQL identifier (same quoting rules as quote_ident())."""
    if not name or "\x00" in name or len(name.encode()) > MAX_IDENTIFIER_BYTES:
        raise InvalidRequestError("Invalid SQL identifier")
    return '"' + name.replace('"', '""') + '"'


# --- Schema inspection ------------------------------------------------------

# pg_catalog is used instead of information_schema because format_type() gives the
# full type (e.g. "character varying(50)", "numeric(10,2)", "inet") and also covers
# materialized views, which information_schema omits.
EXTRACT_COLUMNS_QUERY = """
SELECT
    n.nspname AS schema_name,
    c.relname AS table_name,
    c.relkind::text AS relkind,
    a.attname AS column_name,
    format_type(a.atttypid, a.atttypmod) AS data_type,
    NOT a.attnotnull AS is_nullable,
    a.attnum AS ordinal_position
FROM pg_catalog.pg_attribute a
JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'p', 'v', 'm', 'f')
  AND NOT c.relispartition
  AND a.attnum > 0
  AND NOT a.attisdropped
  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
  AND n.nspname NOT LIKE 'pg_toast%'
  AND n.nspname NOT LIKE 'pg_temp%'
  AND ($1::text IS NULL OR n.nspname = $1::text)
ORDER BY n.nspname, c.relname, a.attnum
"""

TABLE_TYPES = {
    "r": "TABLE",
    "p": "TABLE",
    "v": "VIEW",
    "m": "MATERIALIZED_VIEW",
    "f": "FOREIGN_TABLE",
}


@dataclass
class ExtractedColumn:
    column_name: str
    data_type: str
    is_nullable: bool
    ordinal_position: int


@dataclass
class ExtractedTable:
    schema_name: str
    table_name: str
    table_type: str
    columns: list[ExtractedColumn] = field(default_factory=list)


async def extract_tables(conn: asyncpg.Connection, schema_name: str | None = None) -> list[ExtractedTable]:
    try:
        rows = await conn.fetch(EXTRACT_COLUMNS_QUERY, schema_name)
    except asyncpg.PostgresError as exc:
        logger.warning("Metadata extraction failed: %s", _error_code(exc))
        raise TargetDatabaseError(EXTRACT_ERROR) from exc

    tables: dict[tuple[str, str], ExtractedTable] = {}
    for row in rows:
        key = (row["schema_name"], row["table_name"])
        table = tables.get(key)
        if table is None:
            table = ExtractedTable(
                schema_name=row["schema_name"],
                table_name=row["table_name"],
                table_type=TABLE_TYPES[row["relkind"]],
            )
            tables[key] = table
        table.columns.append(
            ExtractedColumn(
                column_name=row["column_name"],
                data_type=row["data_type"],
                is_nullable=row["is_nullable"],
                ordinal_position=row["ordinal_position"],
            )
        )
    return list(tables.values())


# --- Sampling ---------------------------------------------------------------


# Only validated, quoted identifiers are inserted into this template (PostgreSQL cannot bind
# identifiers as parameters; quoting is what quote_ident() / psycopg.sql.Identifier do too).
# The only value, the limit, is a bind parameter ($1).
SAMPLE_QUERY_TEMPLATE = (
    "SELECT value FROM ("
    "SELECT DISTINCT left({value}, {max_length}) AS value FROM {source} WHERE {column} IS NOT NULL"
    ") AS distinct_values ORDER BY md5(value), value LIMIT $1"
)


def build_sample_query(schema_name: str, table_name: str, column_name: str, data_type: str) -> str:
    """Distinct non-null values, cast to text so every type (inet, jsonb, date...) works.

    Ordered by md5(value): a pseudo-random spread over the values that is stable while the data
    is unchanged, so classification results can be cached on the sample set (and are reproducible).
    Identifiers are validated and quoted; the limit is a bind parameter.
    """
    column = quote_ident(column_name)
    # inet::text appends the netmask ("10.0.0.1/32"); host() gives the plain address.
    value = f"host({column})" if data_type == "inet" else f"{column}::text"
    source = f"{quote_ident(schema_name)}.{quote_ident(table_name)}"
    return SAMPLE_QUERY_TEMPLATE.format(value=value, max_length=MAX_VALUE_LENGTH, source=source, column=column)


async def fetch_samples(
    conn: asyncpg.Connection,
    schema_name: str,
    table_name: str,
    column_name: str,
    data_type: str,
    limit: int,
) -> list[str]:
    query = build_sample_query(schema_name, table_name, column_name, data_type)
    try:
        rows = await conn.fetch(query, limit)
    except (asyncpg.UndefinedTableError, asyncpg.UndefinedColumnError) as exc:
        raise StaleMetadataError(
            f"{schema_name}.{table_name}.{column_name} no longer exists in the target database; "
            "re-run metadata extraction"
        ) from exc
    except asyncpg.PostgresError as exc:
        logger.warning("Sampling %s.%s failed: %s", schema_name, table_name, _error_code(exc))
        raise TargetDatabaseError(SAMPLE_ERROR) from exc
    return [row["value"] for row in rows]
