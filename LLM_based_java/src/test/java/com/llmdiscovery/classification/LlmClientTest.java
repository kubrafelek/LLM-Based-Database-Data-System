package com.llmdiscovery.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmdiscovery.StubLlmServer;
import com.llmdiscovery.common.AppException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LlmClientTest {

    private static final List<Prompts.Message> MESSAGES =
            List.of(new Prompts.Message("system", "s"), new Prompts.Message("user", "u"));
    private static final String OK = StubLlmServer.chatCompletion("{\"a\": 1}");

    private final StubLlmServer server = new StubLlmServer().reply("{\"a\": 1}");

    private static Map<String, Object> call(LlmClient client) {
        return client.completeJson(MESSAGES, p -> p);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void sendsAnOpenAiCompatibleRequest() {
        assertThat(call(server.client())).isEqualTo(Map.of("a", 1));
        var body = server.requests.get(0);
        assertThat(body.get("model").asText()).isEqualTo("fake-model");
        assertThat(body.get("temperature").asInt()).isZero();
        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_object");
        assertThat(body.at("/messages/1/content").asText()).isEqualTo("u");
        assertThat(server.authorizationHeaders.get(0)).isEqualTo("Bearer test");
    }

    @Test
    void jsonModeCanBeDisabled() {
        server.client("test", 2, false).completeJson(MESSAGES, p -> p);
        assertThat(server.requests.get(0).has("response_format")).isFalse();
    }

    @Test
    void emptyApiKeySendsNoAuthorizationHeader() {
        server.client("", 2, true).completeJson(MESSAGES, p -> p);
        assertThat(server.authorizationHeaders.get(0)).isEqualTo("null");
    }

    @Test
    void retriesTransientFailures() {
        server.enqueue(503, "{}").enqueue(429, "{\"error\": {\"type\": \"rate_limit\"}}");
        assertThat(call(server.client())).isEqualTo(Map.of("a", 1));
        assertThat(server.requests).hasSize(3);
    }

    @Test
    void givesUpAfterMaxRetries() {
        server.enqueue(500, "{}").enqueue(500, "{}").enqueue(500, "{}");
        assertThatThrownBy(() -> server.client().completeJson(MESSAGES, p -> p))
                .isInstanceOf(AppException.Llm.class).hasMessage("LLM provider returned HTTP 500");
        assertThat(server.requests).hasSize(3);
    }

    @Test
    void doesNotRetryClientErrors() {
        server.enqueue(400, "{}");
        assertThatThrownBy(() -> server.client().completeJson(MESSAGES, p -> p))
                .hasMessage("LLM provider returned HTTP 400");
        assertThat(server.requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"error\": {\"type\": \"insufficient_quota\"}}",
        "{\"error\": {\"code\": \"credit_balance_exhausted\"}}",
    })
    void exhaustedQuotaFailsFast(String body) {
        server.enqueue(429, body);
        assertThatThrownBy(() -> server.client().completeJson(MESSAGES, p -> p))
                .hasMessageContaining("quota exhausted");
        assertThat(server.requests).hasSize(1);
    }

    @Test
    void reRequestsWhenTheParserRejectsAReply() {
        server.enqueue(200, StubLlmServer.chatCompletion("not json")).enqueue(200, OK);
        assertThat(call(server.client())).isEqualTo(Map.of("a", 1));
        assertThat(server.requests).hasSize(2);
    }

    @Test
    void raisesAfterExhaustingParseRetries() {
        for (int i = 0; i < 3; i++) {
            server.enqueue(200, StubLlmServer.chatCompletion("[1, 2]"));
        }
        assertThatThrownBy(() -> server.client().completeJson(MESSAGES, p -> p))
                .hasMessageStartingWith("LLM returned an unusable response after 3 attempts");
    }

    @Test
    void rejectsAnUnexpectedResponseShape() {
        server.enqueue(200, "{\"choices\": []}");
        assertThatThrownBy(() -> server.client().completeJson(MESSAGES, p -> p))
                .hasMessage("LLM provider returned an unexpected response format");
    }

    @Test
    void extractJsonToleratesCodeFences() {
        assertThat(LlmClient.extractJsonObject("```json\n{\"a\": 1}\n```")).isEqualTo(Map.of("a", 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"no json", "{broken", "[1, 2]"})
    void extractJsonRejectsInvalidContent(String content) {
        assertThatThrownBy(() -> LlmClient.extractJsonObject(content)).isInstanceOf(IllegalArgumentException.class);
    }
}
