package com.llmdiscovery.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class MigrationConfigTest {

    static Flyway flyway(MigrationInfo... pending) {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        when(info.pending()).thenReturn(pending);
        return flyway;
    }

    static MigrationInfo version(String v) {
        MigrationInfo info = mock(MigrationInfo.class);
        when(info.getVersion()).thenReturn(MigrationVersion.fromVersion(v));
        return info;
    }

    static AppProperties properties(boolean migrateOnStart, boolean migrateOnly) {
        AppProperties p = new AppProperties();
        p.setMigrateOnStart(migrateOnStart);
        p.setMigrateOnly(migrateOnly);
        return p;
    }

    @Test
    void byDefaultTheAppOnlyChecksAndRefusesPendingMigrations() {
        Flyway flyway = flyway(version("3"));
        assertThatThrownBy(() -> new MigrationConfig().migrationStrategy(properties(false, false)).migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending migration(s) 3")
                .hasMessageContaining("docker compose run --rm migrate");
        verify(flyway, never()).migrate();
    }

    @Test
    void aCurrentSchemaStartsWithoutMigrating() {
        Flyway flyway = flyway();
        assertThatCode(() -> new MigrationConfig().migrationStrategy(properties(false, false)).migrate(flyway))
                .doesNotThrowAnyException();
        verify(flyway, never()).migrate();
    }

    @Test
    void migrateOnStartAndMigrateOnlyMigrate() {
        for (AppProperties p : new AppProperties[] {properties(true, false), properties(false, true)}) {
            Flyway flyway = flyway(version("3"));
            new MigrationConfig().migrationStrategy(p).migrate(flyway);
            verify(flyway).migrate();
        }
    }
}
