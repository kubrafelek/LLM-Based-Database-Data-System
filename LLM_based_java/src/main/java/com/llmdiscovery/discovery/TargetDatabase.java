package com.llmdiscovery.discovery;

import com.llmdiscovery.common.AppException;
import com.llmdiscovery.common.AppProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Access to external (target) PostgreSQL databases: connecting, schema inspection, sampling. */
@Component
public class TargetDatabase {

    private static final Logger log = LoggerFactory.getLogger(TargetDatabase.class);

    static final String CONNECT_ERROR =
            "Could not connect to the target database. Check the connection details and that the database is reachable.";
    static final String EXTRACT_ERROR = "Could not read metadata from the target database.";
    static final String SAMPLE_ERROR = "Could not read sample values from the target database.";

    // PostgreSQL truncates identifiers to NAMEDATALEN - 1 bytes.
    static final int MAX_IDENTIFIER_BYTES = 63;
    // Long values (free text, JSON) are truncated before being sent to the LLM.
    static final int MAX_VALUE_LENGTH = 200;

    private final AppProperties properties;
    private final TargetHostPolicy hostPolicy;

    public TargetDatabase(AppProperties properties, TargetHostPolicy hostPolicy) {
        this.properties = properties;
        this.hostPolicy = hostPolicy;
    }

    /**
     * Open a read-only, time-limited connection. The caller closes it. The host must pass the target
     * host policy (TargetHostNotAllowed otherwise); the driver connects to the checked addresses.
     */
    public Connection connect(ConnectionInfo info) {
        List<InetAddress> addresses;
        try {
            addresses = hostPolicy.allowedAddresses(info.host());
        } catch (UnknownHostException e) {
            log.warn("Target DB connection to {} failed: {}", info.describe(), e.getClass().getSimpleName());
            throw new AppException.TargetDatabase(CONNECT_ERROR, e);
        }
        // Set field by field (not a URL string), so a database name cannot smuggle in JDBC parameters.
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setServerNames(addresses.stream().map(TargetHostPolicy::jdbcHost).toArray(String[]::new));
        int[] ports = new int[addresses.size()];
        Arrays.fill(ports, info.port());
        source.setPortNumbers(ports);
        source.setDatabaseName(info.database());
        source.setUser(info.username());
        source.setPassword(info.password());
        source.setConnectTimeout((int) Math.ceil(properties.getTargetDbConnectTimeoutSeconds()));
        source.setLoginTimeout((int) Math.ceil(properties.getTargetDbConnectTimeoutSeconds()));
        source.setApplicationName("llm-data-discovery");
        source.setOptions("-c default_transaction_read_only=on -c statement_timeout="
                + properties.getTargetDbStatementTimeoutMs());
        try {
            return source.getConnection();
        } catch (SQLException e) {
            // Driver messages can contain user names or server details; keep them out of the response.
            log.warn("Target DB connection to {} failed: {}", info.describe(), errorCode(e));
            throw new AppException.TargetDatabase(CONNECT_ERROR, e);
        }
    }

    static String errorCode(SQLException e) {
        return e.getSQLState() != null ? e.getSQLState() : e.getClass().getSimpleName();
    }

    /** Validate and quote a PostgreSQL identifier (same quoting rules as quote_ident()). */
    public static String quoteIdent(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0
                || name.getBytes(StandardCharsets.UTF_8).length > MAX_IDENTIFIER_BYTES) {
            throw new AppException.InvalidRequest("Invalid SQL identifier");
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    // --- Schema inspection --------------------------------------------------

    // pg_catalog instead of information_schema: format_type() gives the full type (e.g.
    // "character varying(50)", "numeric(10,2)", "inet") and materialized views are included.
    static final String EXTRACT_COLUMNS_QUERY = """
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
              AND (CAST(? AS text) IS NULL OR n.nspname = CAST(? AS text))
            ORDER BY n.nspname, c.relname, a.attnum
            """;

    static final Map<String, String> TABLE_TYPES = Map.of(
            "r", "TABLE", "p", "TABLE", "v", "VIEW", "m", "MATERIALIZED_VIEW", "f", "FOREIGN_TABLE");

    public record ExtractedColumn(String columnName, String dataType, boolean nullable, int ordinalPosition) {}

    public record ExtractedTable(String schemaName, String tableName, String tableType, List<ExtractedColumn> columns) {}

    public List<ExtractedTable> extractTables(Connection conn, String schemaName) {
        Map<List<String>, ExtractedTable> tables = new LinkedHashMap<>();
        try (PreparedStatement statement = conn.prepareStatement(EXTRACT_COLUMNS_QUERY)) {
            for (int i = 1; i <= 2; i++) {
                if (schemaName == null) {
                    statement.setNull(i, Types.VARCHAR);
                } else {
                    statement.setString(i, schemaName);
                }
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    List<String> key = List.of(rows.getString("schema_name"), rows.getString("table_name"));
                    ExtractedTable table = tables.computeIfAbsent(key, k -> new ExtractedTable(
                            k.get(0), k.get(1), tableType(rows), new ArrayList<>()));
                    table.columns().add(new ExtractedColumn(
                            rows.getString("column_name"),
                            rows.getString("data_type"),
                            rows.getBoolean("is_nullable"),
                            rows.getInt("ordinal_position")));
                }
            }
        } catch (SQLException e) {
            log.warn("Metadata extraction failed: {}", errorCode(e));
            throw new AppException.TargetDatabase(EXTRACT_ERROR, e);
        }
        return new ArrayList<>(tables.values());
    }

    private static String tableType(ResultSet row) {
        try {
            return TABLE_TYPES.get(row.getString("relkind"));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- Sampling -----------------------------------------------------------

    /**
     * Distinct non-null values, cast to text so every type (inet, jsonb, date...) works.
     *
     * <p>Ordered by md5(value): a pseudo-random spread over the values that is stable while the data
     * is unchanged, so classification results can be cached on the sample set (and are reproducible).
     * Identifiers are validated and quoted; the limit is a bind parameter.
     */
    public static String buildSampleQuery(String schemaName, String tableName, String columnName, String dataType) {
        String column = quoteIdent(columnName);
        // inet::text appends the netmask ("10.0.0.1/32"); host() gives the plain address.
        String value = "inet".equals(dataType) ? "host(" + column + ")" : column + "::text";
        String source = quoteIdent(schemaName) + "." + quoteIdent(tableName);
        return "SELECT value FROM ("
                + "SELECT DISTINCT left(" + value + ", " + MAX_VALUE_LENGTH + ") AS value "
                + "FROM " + source + " WHERE " + column + " IS NOT NULL"
                + ") AS distinct_values ORDER BY md5(value), value LIMIT ?";
    }

    public List<String> fetchSamples(
            Connection conn, String schemaName, String tableName, String columnName, String dataType, int limit) {
        String query = buildSampleQuery(schemaName, tableName, columnName, dataType);
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(query)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        } catch (SQLException e) {
            // 42P01 undefined_table, 42703 undefined_column
            if ("42P01".equals(e.getSQLState()) || "42703".equals(e.getSQLState())) {
                throw new AppException.StaleMetadata(schemaName + "." + tableName + "." + columnName
                        + " no longer exists in the target database; re-run metadata extraction", e);
            }
            log.warn("Sampling {}.{} failed: {}", schemaName, tableName, errorCode(e));
            throw new AppException.TargetDatabase(SAMPLE_ERROR, e);
        }
        return values;
    }
}
