package com.llmdiscovery.discovery;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of the metadata endpoints (same JSON as the Python service). */
public final class MetadataDtos {

    private MetadataDtos() {}

    public record DbConnectionRequest(
            @NotNull @Size(min = 1, max = 255) @Schema(example = "demo-db") String host,
            @Min(1) @Max(65535) Integer port,
            @NotNull @Size(min = 1, max = 255) @Schema(example = "llm_discovery_demo") String database,
            @NotNull @Size(min = 1, max = 255) String username,
            @NotNull @Size(max = 1024) String password,
            @Size(min = 1, max = 63) @Schema(description = "Only extract this schema. All non-system schemas when omitted.")
                    String schemaName) {

        public DbConnectionRequest {
            port = port == null ? 5432 : port;
        }

        @Override
        public String toString() {
            return "DbConnectionRequest[" + host + ":" + port + "/" + database + "]";
        }
    }

    public record ColumnOut(
            UUID columnId, String columnName, String dataType,
            @JsonProperty("is_nullable") boolean isNullable, int ordinalPosition) {}

    public record TableOut(UUID tableId, String schemaName, String tableName, String tableType, List<ColumnOut> columns) {}

    public record MetadataSummary(
            UUID metadataId, UUID connectionId, String databaseName, String schemaName,
            String host, int port, OffsetDateTime createdAt, int tableCount) {}

    public record MetadataDetail(
            UUID metadataId, UUID connectionId, String databaseName, String schemaName,
            String host, int port, OffsetDateTime createdAt, int tableCount, List<TableOut> tables) {}

    public record MetadataList(long total, List<MetadataSummary> items) {}

    public record DeleteResponse(UUID metadataId, boolean deleted) {}
}
