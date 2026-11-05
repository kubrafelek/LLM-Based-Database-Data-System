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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

@Entity
@Table(name = "db_tables")
public class DbTable {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "metadata_id", nullable = false)
    private DbMetadata metadata;

    @Column(name = "schema_name", nullable = false)
    private String schemaName;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "table_type", nullable = false, length = 32)
    private String tableType;

    @OneToMany(mappedBy = "table", cascade = CascadeType.PERSIST)
    @Fetch(FetchMode.SUBSELECT)
    private List<DbColumn> columns = new ArrayList<>();

    protected DbTable() {}

    DbTable(DbMetadata metadata, String schemaName, String tableName, String tableType) {
        this.id = UUID.randomUUID();
        this.metadata = metadata;
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.tableType = tableType;
    }

    public void addColumn(String columnName, String dataType, boolean nullable, int ordinalPosition) {
        columns.add(new DbColumn(this, columnName, dataType, nullable, ordinalPosition));
    }

    public UUID getId() { return id; }
    public DbMetadata getMetadata() { return metadata; }
    public String getSchemaName() { return schemaName; }
    public String getTableName() { return tableName; }
    public String getTableType() { return tableType; }
    public List<DbColumn> getColumns() { return columns; }
}
