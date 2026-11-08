package com.llmdiscovery.common;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Migrations run before the app (compose "migrate" service: MIGRATE_ONLY=true), not from every app
 * process: several replicas starting at once would race, and the app should not need DDL rights.
 * The app only checks that no migration is pending. MIGRATE_ON_START=true migrates on start instead
 * (single instance); Flyway's own lock keeps concurrent migrations safe. Same rules as Python.
 */
@Configuration
public class MigrationConfig {

    private static final Logger log = LoggerFactory.getLogger(MigrationConfig.class);

    @Bean
    public FlywayMigrationStrategy migrationStrategy(AppProperties properties) {
        return flyway -> {
            if (properties.isMigrateOnStart() || properties.isMigrateOnly()) {
                flyway.migrate();
                return;
            }
            MigrationInfo[] pending = flyway.info().pending();
            if (pending.length > 0) {
                String versions = Arrays.stream(pending).map(m -> String.valueOf(m.getVersion()))
                        .collect(Collectors.joining(", "));
                throw new IllegalStateException("System DB schema is not current: pending migration(s) " + versions
                        + ". Run the migrations first: `docker compose run --rm migrate`, "
                        + "or set MIGRATE_ON_START=true for a single-instance setup.");
            }
        };
    }

    /** MIGRATE_ONLY=true: Flyway has run during startup; exit before seeding users or serving requests. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public ApplicationRunner exitAfterMigration(AppProperties properties, ApplicationContext context) {
        return args -> {
            if (properties.isMigrateOnly()) {
                log.info("Migrations applied (MIGRATE_ONLY); exiting");
                System.exit(SpringApplication.exit(context, () -> 0));
            }
        };
    }
}
