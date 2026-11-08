package com.llmdiscovery.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmdiscovery.IntegrationTest;
import com.llmdiscovery.common.AppException;
import com.llmdiscovery.common.AppProperties;
import com.llmdiscovery.discovery.MetadataDtos.DbConnectionRequest;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** TLS to the target DB (compose starts test-target-db with ssl=on and the dev certificate in db/certs). */
class TargetTlsIntegrationTest extends IntegrationTest {

    @Autowired AppProperties properties;

    TargetDatabase target(String mode, String rootCert) {
        AppProperties p = properties.copy();
        p.setTargetDbSslMode(mode);
        p.setTargetDbSslRootCert(rootCert);
        return new TargetDatabase(p, new TargetHostPolicy(p));
    }

    static ConnectionInfo info(String host) {
        DbConnectionRequest r = targetRequest();
        return new ConnectionInfo(host == null ? r.host() : host, r.port(), r.database(), r.username(), r.password());
    }

    static String ip() throws Exception {
        return InetAddress.getByName(targetRequest().host()).getHostAddress();
    }

    static boolean sslInUse(Connection conn) throws SQLException {
        try (ResultSet rs = conn.createStatement()
                .executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prefer", "require"})
    void connectionIsEncrypted(String mode) throws SQLException {
        try (Connection conn = target(mode, "").connect(info(null))) {
            assertThat(sslInUse(conn)).isTrue();
        }
    }

    @Test
    void disableConnectsInPlainText() throws SQLException {
        try (Connection conn = target("disable", "").connect(info(null))) {
            assertThat(sslInUse(conn)).isFalse();
        }
    }

    @Test
    void verifyFullAcceptsTheTrustedCertificate() throws SQLException {
        try (Connection conn = target("verify-full", env("TEST_TLS_CA_CERT")).connect(info(null))) {
            assertThat(sslInUse(conn)).isTrue();
        }
    }

    @Test
    void verifyFullRejectsANameTheCertificateDoesNotCover() throws Exception {
        String ip = ip(); // the IP of test-target-db is not in the certificate's names
        assertThatThrownBy(() -> target("verify-full", env("TEST_TLS_CA_CERT")).connect(info(ip)))
                .isInstanceOf(AppException.TargetDatabase.class);
    }

    @Test
    void verifyCaChecksTheIssuerButNotTheName() throws Exception {
        try (Connection conn = target("verify-ca", env("TEST_TLS_CA_CERT")).connect(info(ip()))) {
            assertThat(sslInUse(conn)).isTrue();
        }
    }

    @Test
    void verifyCaRejectsAnUntrustedCertificate() {
        // JVM trust store only: the dev CA is not in it.
        assertThatThrownBy(() -> target("verify-ca", "").connect(info(null)))
                .isInstanceOf(AppException.TargetDatabase.class);
    }
}
