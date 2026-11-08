package com.llmdiscovery.common;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Same cases as the Python tests/unit/test_observability.py for the parts without HTTP. */
class ObservabilityTest {

    @Test
    void validSuppliedRequestIdIsReused() {
        assertThat(ObservabilityFilter.resolveRequestId("trace-42.a_b")).isEqualTo("trace-42.a_b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "line\nbreak", "ü"})
    void unsafeRequestIdIsReplaced(String supplied) {
        assertThat(ObservabilityFilter.resolveRequestId(supplied)).matches("[0-9a-f]{32}");
        assertThat(ObservabilityFilter.resolveRequestId("a".repeat(129))).matches("[0-9a-f]{32}");
        assertThat(ObservabilityFilter.resolveRequestId(null)).matches("[0-9a-f]{32}");
    }

    static LoggingEvent event(Level level, Map<String, String> mdc, Throwable thrown) {
        LoggingEvent event = new LoggingEvent("x", new LoggerContext().getLogger("app.test"), level, "hello {}",
                thrown, new Object[] {"world"});
        event.setMDCPropertyMap(mdc);
        return event;
    }

    @Test
    void jsonLineHasTheRequestIdAndTypedAccessFields() throws Exception {
        String line = new JsonLogFormatter().format(event(Level.INFO, Map.of(
                "request_id", "req-1", "method", "GET", "path", "/health", "status", "200",
                "duration_ms", "1.5", "client_ip", "10.0.0.1"), null));
        assertThat(line).endsWith("\n");
        JsonNode entry = new ObjectMapper().readTree(line);
        assertThat(entry.get("message").asText()).isEqualTo("hello world");
        assertThat(entry.get("level").asText()).isEqualTo("INFO");
        assertThat(entry.get("logger").asText()).isEqualTo("app.test");
        assertThat(entry.get("request_id").asText()).isEqualTo("req-1");
        assertThat(entry.get("timestamp").asText()).endsWith("+00:00");
        assertThat(entry.get("status").isInt()).isTrue();
        assertThat(entry.get("duration_ms").asDouble()).isEqualTo(1.5);
        assertThat(entry.get("client_ip").asText()).isEqualTo("10.0.0.1");
    }

    @Test
    void jsonLineOutsideARequestWithAnExceptionUsesPythonLevelNames() throws Exception {
        JsonNode entry = new ObjectMapper().readTree(
                new JsonLogFormatter().format(event(Level.WARN, Map.of(), new IllegalStateException("bad"))));
        assertThat(entry.has("request_id")).isFalse();
        assertThat(entry.get("level").asText()).isEqualTo("WARNING");
        assertThat(entry.get("exception").asText()).contains("IllegalStateException: bad");
    }

    @Test
    void knownLabelValuesAreRegisteredAtZero() {
        String scrape = AppMetrics.REGISTRY.scrape();
        assertThat(scrape).contains("llm_requests_total{outcome=\"ok\"}")
                .contains("classification_cache_total{result=\"miss\"}")
                .contains("target_db_connections_total{outcome=\"denied\"}")
                .contains("login_failures_total");
    }
}
