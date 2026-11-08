package com.llmdiscovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmdiscovery.discovery.MetadataDtos.DbConnectionRequest;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** End to end over HTTP through the real app (filters, controllers, error mapping), LLM stubbed. */
class ApiFlowIntegrationTest extends IntegrationTest {

    @LocalServerPort
    int port;

    RestClient http;
    final ObjectMapper mapper = new ObjectMapper();

    record Reply(int status, JsonNode body) {}

    @BeforeEach
    void client() {
        http = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    Reply call(String method, String path, Object body, String authorization) {
        var spec = http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
        if (authorization != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            return new Reply(res.getStatusCode().value(), bytes.length == 0 ? null : mapper.readTree(bytes));
        });
    }

    String bearer() {
        return "Bearer " + call("POST", "/api/v1/auth", Map.of("username", "admin", "password", "secret"), null)
                .body().get("access_token").asText();
    }

    Map<String, Object> target() {
        DbConnectionRequest r = targetRequest();
        return Map.of("host", r.host(), "port", r.port(), "database", r.database(),
                "username", r.username(), "password", r.password());
    }

    @Test
    void fullBusinessFlow() {
        String auth = bearer();
        Reply created = call("POST", "/api/v1/db/metadata", target(), auth);
        assertThat(created.status()).isEqualTo(201);
        String metadataId = created.body().get("metadata_id").asText();
        assertThat(created.body().get("table_count").asInt()).isEqualTo(9);

        Reply list = call("GET", "/api/v1/metadata", null, auth);
        assertThat(list.body().get("total").asInt()).isOne();
        assertThat(list.body().at("/items/0/metadata_id").asText()).isEqualTo(metadataId);

        Reply detail = call("GET", "/api/v1/metadata/" + metadataId, null, auth);
        String emailColumn = null;
        for (JsonNode table : detail.body().get("tables")) {
            for (JsonNode column : table.get("columns")) {
                if (table.get("table_name").asText().equals("customers") && column.get("column_name").asText().equals("email")) {
                    emailColumn = column.get("column_id").asText();
                }
            }
        }

        Reply classified = call("POST", "/api/v1/classify", Map.of("column_id", emailColumn), auth);
        assertThat(classified.status()).isEqualTo(200);
        assertThat(classified.body().get("predicted_category").asText()).isEqualTo("email_address");
        assertThat(classified.body().get("sample_count_requested").asInt()).isEqualTo(10);
        assertThat(classified.body().get("classification").size()).isEqualTo(12);

        Reply deleted = call("DELETE", "/api/v1/metadata/" + metadataId, null, auth);
        assertThat(deleted.body().get("deleted").asBoolean()).isTrue();
        assertThat(call("GET", "/api/v1/metadata/" + metadataId, null, auth).status()).isEqualTo(404);
    }

    @Test
    void authAcceptsBasicCredentialsAndRejectsInvalidOnes() {
        String basic = "Basic " + Base64.getEncoder().encodeToString("admin:secret".getBytes());
        assertThat(call("POST", "/api/v1/auth", null, basic).status()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/metadata", null, basic).status()).isEqualTo(200);
        Reply wrong = call("POST", "/api/v1/auth", Map.of("username", "admin", "password", "nope"), null);
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.body().get("detail").asText()).isEqualTo("Invalid credentials");
        assertThat(call("POST", "/api/v1/auth", null, null).status()).isEqualTo(401);
    }

    @Test
    void pdfRootPathsWorkAsAliases() {
        String auth = "Bearer " + call("POST", "/auth", Map.of("username", "admin", "password", "secret"), null)
                .body().get("access_token").asText();
        Reply created = call("POST", "/db/metadata", target(), auth);
        assertThat(created.status()).isEqualTo(201);
        assertThat(call("GET", "/metadata/" + created.body().get("metadata_id").asText(), null, auth).status())
                .isEqualTo(200);
    }

    @Test
    void unreachableTargetGivesAGeneric400() {
        Map<String, Object> body = Map.of("host", "no-such-host.invalid", "port", 5432, "database", "x",
                "username", "u", "password", "p");
        Reply reply = call("POST", "/api/v1/db/metadata", body, bearer());
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.body().get("detail").asText()).startsWith("Could not connect to the target database");
    }

    @Test
    void validationErrorsAre422AndUiIsServed() {
        String auth = bearer();
        assertThat(call("POST", "/api/v1/classify", Map.of("column_id", "not-a-uuid"), auth).status()).isEqualTo(422);
        assertThat(call("GET", "/api/v1/metadata?limit=0", null, auth).status()).isEqualTo(422);
        assertThat(call("GET", "/api/v1/metadata/not-a-uuid", null, auth).status()).isEqualTo(422);
        assertThat(call("GET", "/health", null, null).body().get("status").asText()).isEqualTo("ok");
        String page = http.get().uri("/ui/").retrieve().toEntity(String.class).getHeaders()
                .getFirst("Content-Security-Policy");
        assertThat(page).contains("script-src 'self'").contains("frame-ancestors 'none'");
    }

    @Test
    void repeatedWrongPasswordsGet429WithRetryAfter() {
        Map<String, String> wrong = Map.of("username", "admin", "password", "guess");
        for (int i = 0; i < 5; i++) {
            assertThat(call("POST", "/api/v1/auth", wrong, null).status()).isEqualTo(401);
        }
        var blocked = http.post().uri("/api/v1/auth").contentType(MediaType.APPLICATION_JSON).body(wrong)
                .exchange((req, res) -> Map.of(
                        "status", String.valueOf(res.getStatusCode().value()),
                        "retry", String.valueOf(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)),
                        "body", new String(res.getBody().readAllBytes())));
        assertThat(blocked.get("status")).isEqualTo("429");
        assertThat(Integer.parseInt(blocked.get("retry"))).isBetween(1, 300);
        assertThat(blocked.get("body")).contains("Too many failed login attempts. Try again later.");

        // Basic credentials on a protected endpoint share the same limit, even the right password.
        String basic = "Basic " + Base64.getEncoder().encodeToString("admin:secret".getBytes());
        Reply viaBasic = call("GET", "/api/v1/metadata", null, basic);
        assertThat(viaBasic.status()).isEqualTo(429);
        assertThat(viaBasic.body().get("detail").asText()).isEqualTo("Too many failed login attempts. Try again later.");
    }

    @Test
    void logoutRevokesOnlyThatToken() {
        String first = bearer();
        String second = bearer();
        assertThat(call("POST", "/api/v1/auth/logout", null, first).status()).isEqualTo(204);

        Reply revoked = call("GET", "/api/v1/metadata", null, first);
        assertThat(revoked.status()).isEqualTo(401);
        assertThat(revoked.body().get("detail").asText()).isEqualTo("Invalid or expired token");
        assertThat(call("POST", "/auth/logout", null, first).status()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/metadata", null, second).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM revoked_tokens", Integer.class)).isEqualTo(1);
    }

    @Test
    void logoutNeedsABearerToken() {
        assertThat(call("POST", "/api/v1/auth/logout", null, null).status()).isEqualTo(401);
        assertThat(call("POST", "/api/v1/auth/logout", null, "Bearer not-a-jwt").status()).isEqualTo(401);
        String basic = "Basic " + Base64.getEncoder().encodeToString("admin:secret".getBytes());
        Reply viaBasic = call("POST", "/api/v1/auth/logout", null, basic);
        assertThat(viaBasic.status()).isEqualTo(400);
        assertThat(viaBasic.body().get("detail").asText())
                .isEqualTo("Only Bearer tokens can be revoked; Basic credentials have no session");
    }

    @Test
    void readinessChecksTheSystemDatabase() {
        Reply ready = call("GET", "/health/ready", null, null);
        assertThat(ready.status()).isEqualTo(200);
        assertThat(ready.body().get("status").asText()).isEqualTo("ok");
        assertThat(ready.body().get("checks").get("system_db").asText()).isEqualTo("ok");
    }
}
