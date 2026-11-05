package com.llmdiscovery.discovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/** A column of a snapshot. Its UUID is the column_id used by /classify; every snapshot gets new ones. */
@Entity
@Table(name = "db_columns")
public class DbColumn {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "table_id", nullable = false)
    private DbTable table;

    @Column(name = "column_name", nullable = false)
    private String columnName;

    @Column(name = "data_type", nullable = false)
    private String dataType;

    @Column(name = "is_nullable", nullable = false)
    private boolean nullable;

    @Column(name = "ordinal_position", nullable = false)
    private int ordinalPosition;

    protected DbColumn() {}

    DbColumn(DbTable table, String columnName, String dataType, boolean nullable, int ordinalPosition) {
        this.id = UUID.randomUUID();
        this.table = table;
        this.columnName = columnName;
        this.dataType = dataType;
        this.nullable = nullable;
        this.ordinalPosition = ordinalPosition;
    }

    public UUID getId() { return id; }
    public DbTable getTable() { return table; }
    public String getColumnName() { return columnName; }
    public String getDataType() { return dataType; }
    public boolean isNullable() { return nullable; }
    public int getOrdinalPosition() { return ordinalPosition; }
}
