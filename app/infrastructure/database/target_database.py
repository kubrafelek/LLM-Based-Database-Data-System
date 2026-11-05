"""Access to external (target) PostgreSQL databases: connecting, schema inspection, sampling."""

import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass, field

import asyncpg

from app.core.config import Settings
from app.core.exceptions import InvalidRequestError, StaleMetadataError, TargetDatabaseError

logger = logging.getLogger(__name__)

CONNECT_ERROR = "Could not connect to the target database. Check the connection details and that the database is reachable."
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


@asynccontextmanager
async def connect_target(info: ConnectionInfo, settings: Settings) -> AsyncIterator[asyncpg.Connection]:
    """Open a read-only, time-limited connection to a target database."""
    try:
        conn = await asyncpg.connect(
            host=info.host,
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
    except (OSError, asyncio.TimeoutError, asyncpg.PostgresError, asyncpg.InterfaceError) as exc:
        # Driver messages can contain user names or server details; keep them out of the response.
        logger.warning("Target DB connection to %s failed: %s", info.describe(), _error_code(exc))
        raise TargetDatabaseError(CONNECT_ERROR) from exc
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
    return (
        f"SELECT value FROM ("
        f"SELECT DISTINCT left({value}, {MAX_VALUE_LENGTH}) AS value "
        f"FROM {source} WHERE {column} IS NOT NULL"
        f") AS distinct_values ORDER BY md5(value), value LIMIT $1"
    )


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
