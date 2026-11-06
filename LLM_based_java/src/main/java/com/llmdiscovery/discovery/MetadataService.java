package com.llmdiscovery.discovery;

import com.llmdiscovery.common.AppException;
import com.llmdiscovery.common.SecretCipher;
import com.llmdiscovery.discovery.MetadataDtos.ColumnOut;
import com.llmdiscovery.discovery.MetadataDtos.DbConnectionRequest;
import com.llmdiscovery.discovery.MetadataDtos.MetadataDetail;
import com.llmdiscovery.discovery.MetadataDtos.MetadataList;
import com.llmdiscovery.discovery.MetadataDtos.MetadataSummary;
import com.llmdiscovery.discovery.MetadataDtos.TableOut;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MetadataService {

    private final TargetDatabase targetDatabase;
    private final ConnectionRepository connections;
    private final MetadataRepository metadata;
    private final SecretCipher cipher;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;

    public MetadataService(
            TargetDatabase targetDatabase,
            ConnectionRepository connections,
            MetadataRepository metadata,
            SecretCipher cipher,
            EntityManager entityManager,
            TransactionTemplate transaction) {
        this.targetDatabase = targetDatabase;
        this.connections = connections;
        this.metadata = metadata;
        this.cipher = cipher;
        this.entityManager = entityManager;
        this.transaction = transaction;
    }

    public MetadataDetail create(DbConnectionRequest request) {
        ConnectionInfo info = new ConnectionInfo(
                request.host(), request.port(), request.database(), request.username(), request.password());
        // The target DB is read outside the system DB transaction.
        List<TargetDatabase.ExtractedTable> extracted;
        try (Connection conn = targetDatabase.connect(info)) {
            extracted = targetDatabase.extractTables(conn, request.schemaName());
        } catch (AppException.TargetDatabase e) {
            // The caller supplied these details, so this is a client error rather than a 502.
            throw new AppException.InvalidConnection(e.detail(), e);
        } catch (SQLException e) {
            throw new AppException.InvalidConnection(TargetDatabase.CONNECT_ERROR, e);
        }
        if (request.schemaName() != null && extracted.isEmpty()) {
            throw new AppException.InvalidRequest(
                    "Schema '" + request.schemaName() + "' does not exist or has no tables");
        }

        return transaction.execute(status -> {
            UUID connectionId = connections.upsert(request.host(), request.port(), request.database(),
                    request.username(), cipher.encrypt(request.password()));
            DbMetadata snapshot = new DbMetadata(
                    entityManager.getReference(DbConnection.class, connectionId), request.database(), request.schemaName());
            for (TargetDatabase.ExtractedTable table : extracted) {
                DbTable stored = snapshot.addTable(table.schemaName(), table.tableName(), table.tableType());
                table.columns().forEach(c -> stored.addColumn(c.columnName(), c.dataType(), c.nullable(), c.ordinalPosition()));
            }
            // persist, not save(): IDs are assigned by the app, so save() would merge() and look the rows up first.
            entityManager.persist(snapshot);
            return toDetail(snapshot, connectionId, request.host(), request.port());
        });
    }

    public MetadataList list(int limit, int offset) {
        return transaction.execute(status -> {
            List<DbMetadata> rows = entityManager
                    .createQuery("select m from DbMetadata m join fetch m.connection order by m.createdAt desc, m.id",
                            DbMetadata.class)
                    .setFirstResult(offset)
                    .setMaxResults(limit)
                    .getResultList();
            List<MetadataSummary> items = rows.stream().map(m -> new MetadataSummary(
                    m.getId(), m.getConnection().getId(), m.getDatabaseName(), m.getSchemaName(),
                    m.getConnection().getHost(), m.getConnection().getPort(), m.getCreatedAt(), m.getTableCount()))
                    .toList();
            return new MetadataList(metadata.count(), items);
        });
    }

    public MetadataDetail get(UUID metadataId) {
        return transaction.execute(status -> {
            DbMetadata snapshot = metadata.findWithConnection(metadataId)
                    .orElseThrow(() -> new AppException.NotFound("Metadata " + metadataId + " not found"));
            DbConnection connection = snapshot.getConnection();
            return toDetail(snapshot, connection.getId(), connection.getHost(), connection.getPort());
        });
    }

    /**
     * Delete a snapshot with its tables, columns and cached results. The stored connection is deleted
     * too once no other snapshot uses it: credentials are not kept longer than they are needed.
     */
    public void delete(UUID metadataId) {
        UUID connectionId = transaction.execute(status -> {
            UUID id = metadata.findConnectionId(metadataId)
                    .orElseThrow(() -> new AppException.NotFound("Metadata " + metadataId + " not found"));
            metadata.deleteSnapshot(metadataId);
            return id;
        });
        try {
            transaction.executeWithoutResult(status -> connections.deleteIfUnused(connectionId));
        } catch (DataIntegrityViolationException e) {
            // A snapshot created concurrently for the same connection (FK ON DELETE RESTRICT): keep it.
        }
    }

    private static MetadataDetail toDetail(DbMetadata snapshot, UUID connectionId, String host, int port) {
        List<TableOut> tables = snapshot.getTables().stream()
                .sorted(Comparator.comparing(DbTable::getSchemaName).thenComparing(DbTable::getTableName))
                .map(t -> new TableOut(t.getId(), t.getSchemaName(), t.getTableName(), t.getTableType(),
                        t.getColumns().stream()
                                .sorted(Comparator.comparingInt(DbColumn::getOrdinalPosition))
                                .map(c -> new ColumnOut(c.getId(), c.getColumnName(), c.getDataType(),
                                        c.isNullable(), c.getOrdinalPosition()))
                                .toList()))
                .toList();
        return new MetadataDetail(snapshot.getId(), connectionId, snapshot.getDatabaseName(), snapshot.getSchemaName(),
                host, port, snapshot.getCreatedAt(), snapshot.getTableCount(), tables);
    }
}
