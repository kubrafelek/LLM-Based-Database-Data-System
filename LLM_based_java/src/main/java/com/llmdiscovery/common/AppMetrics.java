package com.llmdiscovery.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.Arrays;

/**
 * Prometheus metrics served at GET /metrics, with the same names and labels as the Python service
 * (app/core/metrics.py). One process-wide registry, like prometheus_client's default registry, so the
 * classes that record metrics need no extra constructor argument.
 */
public final class AppMetrics {

    public static final PrometheusMeterRegistry REGISTRY = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    // prometheus_client's default histogram buckets, for http_request_duration_seconds
    private static final Duration[] HTTP_BUCKETS = seconds(
            0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10);
    private static final Duration[] LLM_BUCKETS = seconds(0.5, 1, 2, 3, 5, 8, 13, 21, 34, 60, 120, 300);

    static {
        // Known label values start at 0, so dashboards and rate() see them before the first event.
        for (String outcome : new String[] {"ok", "error"}) {
            llmRequest(outcome);
        }
        cacheLookup("hit");
        cacheLookup("miss");
        for (String outcome : new String[] {"ok", "error", "denied"}) {
            targetDbConnection(outcome);
        }
        loginFailures();
        loginThrottled();
        Timer.builder("llm.request.duration").description("LLM provider call duration, including retries")
                .serviceLevelObjectives(LLM_BUCKETS).register(REGISTRY);
    }

    private AppMetrics() {}

    private static Duration[] seconds(double... values) {
        return Arrays.stream(values).mapToObj(v -> Duration.ofNanos((long) (v * 1e9))).toArray(Duration[]::new);
    }

    public static void httpRequest(String method, String route, int status, long nanos) {
        Counter.builder("http.requests").description("HTTP requests handled")
                .tags("method", method, "route", route, "status", String.valueOf(status))
                .register(REGISTRY).increment();
        Timer.builder("http.request.duration").description("HTTP request duration")
                .tags("method", method, "route", route).serviceLevelObjectives(HTTP_BUCKETS)
                .register(REGISTRY).record(Duration.ofNanos(nanos));
    }

    public static Counter llmRequest(String outcome) {
        return Counter.builder("llm.requests").description("Calls to the LLM provider")
                .tag("outcome", outcome).register(REGISTRY);
    }

    public static void llmDuration(long nanos) {
        Timer.builder("llm.request.duration").description("LLM provider call duration, including retries")
                .serviceLevelObjectives(LLM_BUCKETS).register(REGISTRY).record(Duration.ofNanos(nanos));
    }

    public static Counter cacheLookup(String result) {
        return Counter.builder("classification.cache").description("Classification cache lookups")
                .tag("result", result).register(REGISTRY);
    }

    public static Counter loginFailures() {
        return Counter.builder("login.failures").description("Failed logins (wrong username or password)")
                .register(REGISTRY);
    }

    public static Counter loginThrottled() {
        return Counter.builder("login.throttled")
                .description("Logins refused with 429 by the failed-login limiter").register(REGISTRY);
    }

    public static Counter targetDbConnection(String outcome) {
        return Counter.builder("target.db.connections").description("Target database connection attempts")
                .tag("outcome", outcome).register(REGISTRY);
    }
}
