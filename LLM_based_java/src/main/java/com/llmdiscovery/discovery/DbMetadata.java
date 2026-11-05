package com.llmdiscovery.discovery;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

/** One metadata extraction snapshot of a target database. */
@Entity
@Table(name = "db_metadata")
public class DbMetadata {

    @Id
    private UUID id;

    // RESTRICT in the schema: a connection can only go away once none of its snapshots reference it.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_id", nullable = false)
    private DbConnection connection;

    @Column(name = "database_name", nullable = false)
    private String databaseName;

    @Column(name = "schema_name")
    private String schemaName;

    @Column(name = "table_count", nullable = false)
    private int tableCount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    // Deleting a snapshot removes its tables and columns through ON DELETE CASCADE.
    @OneToMany(mappedBy = "metadata", cascade = CascadeType.PERSIST)
    @Fetch(FetchMode.SUBSELECT)
    private List<DbTable> tables = new ArrayList<>();

    protected DbMetadata() {}

    public DbMetadata(DbConnection connection, String databaseName, String schemaName) {
        this.id = UUID.randomUUID();
        this.connection = connection;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public DbTable addTable(String schemaName, String tableName, String tableType) {
        DbTable table = new DbTable(this, schemaName, tableName, tableType);
        tables.add(table);
        tableCount = tables.size();
        return table;
    }

    public UUID getId() { return id; }
    public DbConnection getConnection() { return connection; }
    public String getDatabaseName() { return databaseName; }
    public String getSchemaName() { return schemaName; }
    public int getTableCount() { return tableCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public List<DbTable> getTables() { return tables; }
}
