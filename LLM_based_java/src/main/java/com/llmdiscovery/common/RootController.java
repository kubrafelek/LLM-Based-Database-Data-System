package com.llmdiscovery.common;

import io.swagger.v3.oas.annotations.Hidden;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

@RestController
public class RootController {

    private static final Logger log = LoggerFactory.getLogger(RootController.class);
    static final int READINESS_TIMEOUT_SECONDS = 3;

    private final DataSource dataSource;
    private final ExecutorService readinessExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "readiness-check");
        thread.setDaemon(true);
        return thread;
    });
    private Future<Boolean> inFlight;

    public RootController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Liveness: the process answers. Used by the container health check. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /**
     * Readiness: the system database answers within READINESS_TIMEOUT_SECONDS. The LLM is not called (it
     * costs money and time). The check runs on one background thread, because the connection pool waits up
     * to 30 s for a connection. A probe that arrives while an earlier check is still running waits on that
     * same check (still at most READINESS_TIMEOUT_SECONDS), so probes during an outage do not pile up threads.
     */
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        boolean ok;
        synchronized (this) {
            if (inFlight == null || inFlight.isDone()) {
                inFlight = readinessExecutor.submit(this::systemDbAnswers);
            }
        }
        try {
            ok = inFlight.get(READINESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Readiness check failed: system DB did not answer within {} s", READINESS_TIMEOUT_SECONDS);
            ok = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ok = false;
        } catch (ExecutionException e) {
            ok = false;
        }
        if (!ok) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "unavailable", "checks", Map.of("system_db", "unavailable")));
        }
        return ResponseEntity.ok(Map.of("status", "ok", "checks", Map.of("system_db", "ok")));
    }

    private boolean systemDbAnswers() {
        try (Connection conn = dataSource.getConnection(); Statement statement = conn.createStatement()) {
            statement.setQueryTimeout(READINESS_TIMEOUT_SECONDS);
            statement.execute("SELECT 1");
            return true;
        } catch (Exception e) { // any failure means "not ready"; the reason stays in the log
            log.warn("Readiness check failed: system DB {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** Browser UI over the same /api/v1 endpoints (same origin, so no CORS needed). */
    @Hidden
    @GetMapping("/")
    public ResponseEntity<Void> root() {
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).header("Location", "/ui/").build();
    }

    @Hidden
    @GetMapping({"/ui", "/ui/"})
    public ModelAndView ui() {
        return new ModelAndView("forward:/ui/index.html");
    }
}
