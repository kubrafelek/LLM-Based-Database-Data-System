package com.llmdiscovery;

import com.llmdiscovery.auth.AuthService;
import com.llmdiscovery.auth.LoginAttemptLimiter;
import com.llmdiscovery.discovery.MetadataDtos.DbConnectionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests against real PostgreSQL databases (compose "test" profile; run with RUN_INTEGRATION=1).
 * The system DB is truncated before each test; the target DB is seeded with the demo script.
 * The LLM is a local stub server.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    // Shared by all integration test classes; it stops with the JVM.
    protected static final StubLlmServer LLM = new StubLlmServer();

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected AuthService authService;

    @Autowired
    protected LoginAttemptLimiter loginLimiter;

    @DynamicPropertySource
    static void llm(DynamicPropertyRegistry registry) {
        registry.add("app.llm-base-url", LLM::baseUrl);
    }

    @BeforeEach
    void resetSystemDatabase() {
        jdbc.execute("TRUNCATE users, classification_results, db_columns, db_tables, db_metadata, db_connections CASCADE");
        authService.ensureAdminUser();
        loginLimiter.clear(); // failed logins in one test must not block another
        LLM.requests.clear();
        LLM.reply(null);
    }

    protected static String env(String name) {
        String value = System.getenv(name);
        if (value == null) {
            throw new IllegalStateException(name + " must be set (see the compose \"tests\" service)");
        }
        return value;
    }

    protected static DbConnectionRequest targetRequest() {
        return new DbConnectionRequest(env("TEST_TARGET_DB_HOST"), 5432, env("TEST_TARGET_DB_NAME"),
                env("TEST_TARGET_DB_USER"), env("TEST_TARGET_DB_PASSWORD"), null);
    }

    /** The same target DB via the SELECT-only role from db/demo/02_readonly_user.sh. */
    protected static DbConnectionRequest readonlyTargetRequest() {
        return new DbConnectionRequest(env("TEST_TARGET_DB_HOST"), 5432, env("TEST_TARGET_DB_NAME"),
                env("TEST_TARGET_DB_READONLY_USER"), env("TEST_TARGET_DB_READONLY_PASSWORD"), null);
    }
}
