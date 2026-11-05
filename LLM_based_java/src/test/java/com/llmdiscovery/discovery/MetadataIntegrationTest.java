package com.llmdiscovery.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmdiscovery.IntegrationTest;
import com.llmdiscovery.common.AppException;
import com.llmdiscovery.common.SecretCipher;
import com.llmdiscovery.discovery.MetadataDtos.DbConnectionRequest;
import com.llmdiscovery.discovery.MetadataDtos.MetadataDetail;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MetadataIntegrationTest extends IntegrationTest {

    static final Set<String> DEMO_RELATIONS = Set.of("customers", "customer_addresses", "employees", "products",
            "payment_methods", "orders", "order_items", "audit_logs", "customer_order_summary");

    @Autowired
    MetadataService service;

    @Autowired
    SecretCipher cipher;

    @Test
    void extractsAndPersistsTheDemoSchema() {
        MetadataDetail detail = service.create(targetRequest());

        assertThat(detail.tables().stream().map(MetadataDtos.TableOut::tableName).collect(Collectors.toSet()))
                .isEqualTo(DEMO_RELATIONS);
        assertThat(detail.tableCount()).isEqualTo(9);
        assertThat(detail.tables().stream().filter(t -> t.tableName().equals("customer_order_summary")).findFirst()
                .orElseThrow().tableType()).isEqualTo("VIEW");
        List<UUID> ids = detail.tables().stream().flatMap(t -> t.columns().stream()).map(MetadataDtos.ColumnOut::columnId).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM db_columns", Integer.class)).isEqualTo(ids.size());
        MetadataDtos.ColumnOut email = detail.tables().stream().filter(t -> t.tableName().equals("customers"))
                .flatMap(t -> t.columns().stream()).filter(c -> c.columnName().equals("email")).findFirst().orElseThrow();
        assertThat(email.dataType()).isEqualTo("character varying(100)");
        assertThat(email.isNullable()).isFalse();
    }

    @Test
    void passwordIsEncryptedAtRest() {
        service.create(targetRequest());
        String stored = jdbc.queryForObject("SELECT encrypted_password FROM db_connections", String.class);
        assertThat(stored).isNotEqualTo(targetRequest().password());
        assertThat(cipher.decrypt(stored)).isEqualTo(targetRequest().password());
    }

    @Test
    void listGetAndDeleteKeepTheConnection() {
        MetadataDetail created = service.create(targetRequest());

        assertThat(service.list(50, 0).items()).singleElement()
                .satisfies(item -> assertThat(item.metadataId()).isEqualTo(created.metadataId()));
        assertThat(service.get(created.metadataId()).tables()).hasSize(9);

        service.delete(created.metadataId());
        assertThatThrownBy(() -> service.get(created.metadataId())).isInstanceOf(AppException.NotFound.class);
        assertThatThrownBy(() -> service.delete(created.metadataId())).isInstanceOf(AppException.NotFound.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM db_tables", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM db_connections", Integer.class)).isOne();
    }

    @Test
    void listPaginatesNewestFirst() {
        MetadataDetail first = service.create(targetRequest());
        MetadataDetail second = service.create(targetRequest());
        assertThat(service.list(1, 0).items().get(0).metadataId()).isEqualTo(second.metadataId());
        assertThat(service.list(1, 1).items().get(0).metadataId()).isEqualTo(first.metadataId());
        assertThat(service.list(1, 1).total()).isEqualTo(2);
    }

    @Test
    void schemaFilter() {
        DbConnectionRequest base = targetRequest();
        DbConnectionRequest publicOnly = new DbConnectionRequest(
                base.host(), base.port(), base.database(), base.username(), base.password(), "public");
        assertThat(service.create(publicOnly).schemaName()).isEqualTo("public");
        DbConnectionRequest missing = new DbConnectionRequest(
                base.host(), base.port(), base.database(), base.username(), base.password(), "nope");
        assertThatThrownBy(() -> service.create(missing)).isInstanceOf(AppException.InvalidRequest.class);
    }

    @Test
    void snapshotsReuseTheSameConnection() {
        assertThat(service.create(targetRequest()).connectionId()).isEqualTo(service.create(targetRequest()).connectionId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM db_connections", Integer.class)).isOne();
    }

    @Test
    void badCredentialsGiveAGenericClientError() {
        DbConnectionRequest base = targetRequest();
        DbConnectionRequest wrong = new DbConnectionRequest(
                base.host(), base.port(), base.database(), base.username(), "wrong-pw-123", null);
        assertThatThrownBy(() -> service.create(wrong))
                .isInstanceOf(AppException.InvalidConnection.class)
                .hasMessageNotContaining("wrong-pw-123")
                .hasMessageStartingWith("Could not connect to the target database");
    }
}
