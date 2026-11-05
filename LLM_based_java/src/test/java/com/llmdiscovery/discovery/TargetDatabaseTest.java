package com.llmdiscovery.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmdiscovery.common.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TargetDatabaseTest {

    @Test
    void quoteIdentEscapesDoubleQuotes() {
        assertThat(TargetDatabase.quoteIdent("we\"ird")).isEqualTo("\"we\"\"ird\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a\0b", "ççççççççççççççççççççççççççççççççç"})
    void quoteIdentRejectsInvalidIdentifiers(String name) {
        // the last one is 33 characters but 66 bytes, over PostgreSQL's 63-byte limit
        assertThatThrownBy(() -> TargetDatabase.quoteIdent(name)).isInstanceOf(AppException.InvalidRequest.class);
    }

    @Test
    void sampleQueryQuotesIdentifiersBindsTheLimitAndIsDeterministic() {
        String query = TargetDatabase.buildSampleQuery("public", "Customers", "e-mail", "character varying(100)");
        assertThat(query).contains("FROM \"public\".\"Customers\"").contains("left(\"e-mail\"::text")
                .contains("ORDER BY md5(value), value").doesNotContain("random()").endsWith("LIMIT ?");
    }

    @Test
    void sampleQueryStripsTheNetmaskFromInet() {
        assertThat(TargetDatabase.buildSampleQuery("public", "audit_logs", "ip_address", "inet"))
                .contains("left(host(\"ip_address\")");
    }

    @Test
    void connectionInfoToStringHidesThePassword() {
        assertThat(new ConnectionInfo("h", 5432, "db", "u", "s3cret").toString()).doesNotContain("s3cret");
    }
}
