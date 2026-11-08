package com.llmdiscovery.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmdiscovery.IntegrationTest;
import com.llmdiscovery.common.AppException;
import com.llmdiscovery.discovery.MetadataDtos.DbConnectionRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Least privilege on the target database: read-only sessions and the SELECT-only role. */
class TargetAccessIntegrationTest extends IntegrationTest {

    static final String INSERT =
            "INSERT INTO customers (first_name, last_name, email) VALUES ('x', 'y', 'ro.test@example.com')";

    @Autowired TargetDatabase targetDatabase;
    @Autowired MetadataService metadataService;

    static ConnectionInfo info(DbConnectionRequest r) {
        return new ConnectionInfo(r.host(), r.port(), r.database(), r.username(), r.password());
    }

    @Test
    void theAppSessionIsReadOnlyEvenForTheTableOwner() throws SQLException {
        try (Connection conn = targetDatabase.connect(info(targetRequest()))) {
            assertThatThrownBy(() -> conn.createStatement().execute(INSERT))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("25006"));
        }
    }

    @Test
    void theReadonlyRoleCannotWriteEvenOutsideReadOnlyMode() throws SQLException {
        // A plain connection, without the app's read-only session settings: privileges still forbid writes.
        DbConnectionRequest r = readonlyTargetRequest();
        String url = "jdbc:postgresql://" + r.host() + ":5432/" + r.database();
        try (Connection conn = DriverManager.getConnection(url, r.username(), r.password())) {
            conn.createStatement().execute("SET default_transaction_read_only = off");
            assertThatThrownBy(() -> conn.createStatement().execute(INSERT))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> conn.createStatement().execute("CREATE TABLE ro_probe (id int)"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void theSystemDatabaseCannotBeUsedAsATarget() {
        // Valid credentials for the system DB must not let a caller read the service's own tables.
        DbConnectionRequest system = new DbConnectionRequest(env("SYSTEM_DB_HOST"), 5432, env("SYSTEM_DB_NAME"),
                env("SYSTEM_DB_USER"), env("SYSTEM_DB_PASSWORD"), null);
        assertThatThrownBy(() -> metadataService.create(system))
                .isInstanceOf(AppException.TargetHostNotAllowed.class);
    }
}
