package com.llmdiscovery.classification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmdiscovery.common.AppException;
import com.llmdiscovery.common.AppMetrics;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Provider-agnostic client for OpenAI-compatible /chat/completions endpoints.
 *
 * <p>Transport failures (timeouts, 429, 5xx) are retried with exponential backoff. Replies that are
 * not valid JSON, or that the caller's parse function rejects with IllegalArgumentException, are
 * re-requested up to parseRetries times.
 */
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(408, 409, 429, 500, 502, 503, 504);
    // OpenAI answers 429 both for rate limits (retryable) and for an exhausted balance (not).
    static final Set<String> QUOTA_ERROR_CODES = Set.of("insufficient_quota", "credit_balance_exhausted");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String model;
    private final int maxRetries;
    private final int parseRetries;
    private final boolean jsonMode;
    private final long retryBackoffMillis;
    private final RestClient http;

    public LlmClient(String baseUrl, String apiKey, String model, double timeoutSeconds, int maxRetries,
            int parseRetries, boolean jsonMode, long retryBackoffMillis) {
        this.model = model;
        this.maxRetries = maxRetries;
        this.parseRetries = parseRetries;
        this.jsonMode = jsonMode;
        this.retryBackoffMillis = retryBackoffMillis;
        Duration timeout = Duration.ofMillis((long) (timeoutSeconds * 1000));
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/+$", "") + "/")
                .requestFactory(factory);
        // Some local providers (e.g. Ollama) need no key; an empty "Bearer " header is invalid.
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.http = builder.build();
    }

    public String model() {
        return model;
    }

    /** Parse a JSON object from an LLM reply, tolerating code fences or surrounding text. */
    static Map<String, Object> extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start == -1 || end <= start) {
            throw new IllegalArgumentException("no JSON object in LLM response");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(content.substring(start, end + 1));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON in LLM response: " + e.getOriginalMessage(), e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("LLM response JSON is not an object");
        }
        return MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    public <T> T completeJson(List<Prompts.Message> messages, Function<Map<String, Object>, T> parse) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        int attempts = Math.max(1, parseRetries + 1);
        String lastError = "no attempt made";
        for (int attempt = 1; attempt <= attempts; attempt++) {
            String content = send(body);
            try {
                return parse.apply(extractJsonObject(content));
            } catch (IllegalArgumentException e) {
                lastError = e.getMessage();
                log.warn("Unusable LLM response (attempt {}/{}): {}", attempt, attempts, e.getMessage());
            }
        }
        throw new AppException.Llm("LLM returned an unusable response after " + attempts + " attempts: "
                + lastError);
    }

    private record Reply(int status, String body) {}

    private String send(Map<String, Object> body) {
        long start = System.nanoTime();
        String outcome = "error";
        try {
            String content = sendWithRetries(body);
            outcome = "ok";
            return content;
        } finally {
            AppMetrics.llmRequest(outcome).increment();
            AppMetrics.llmDuration(System.nanoTime() - start);
        }
    }

    private String sendWithRetries(Map<String, Object> body) {
        String error = "LLM request failed";
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            Reply reply = null;
            try {
                reply = http.post().uri("chat/completions").contentType(MediaType.APPLICATION_JSON).body(body)
                        .exchange((request, response) -> new Reply(
                                response.getStatusCode().value(), new String(response.getBody().readAllBytes())));
            } catch (ResourceAccessException e) {
                error = e.getCause() instanceof HttpTimeoutException
                        ? "LLM request timed out"
                        : "LLM provider unreachable: "
                                + (e.getCause() != null ? e.getCause().getClass().getSimpleName() : "IOException");
            }
            if (reply != null) {
                if (reply.status() == 429 && isQuotaError(reply.body())) {
                    throw new AppException.Llm("LLM provider quota exhausted; check the account's billing/credits");
                }
                if (RETRYABLE_STATUS_CODES.contains(reply.status())) {
                    error = "LLM provider returned HTTP " + reply.status();
                } else if (reply.status() >= 400) {
                    throw new AppException.Llm("LLM provider returned HTTP " + reply.status());
                } else {
                    return extractContent(reply.body());
                }
            }
            if (attempt < maxRetries) {
                log.warn("{}; retrying ({}/{})", error, attempt + 1, maxRetries);
                sleep(retryBackoffMillis * (1L << attempt));
            }
        }
        throw new AppException.Llm(error);
    }

    private static boolean isQuotaError(String body) {
        try {
            JsonNode error = MAPPER.readTree(body).path("error");
            // asText(""), not null: Set.of(...).contains(null) throws, unlike Python's `None in set`.
            return error.isObject() && (QUOTA_ERROR_CODES.contains(error.path("type").asText(""))
                    || QUOTA_ERROR_CODES.contains(error.path("code").asText("")));
        } catch (IOException e) {
            return false;
        }
    }

    private static String extractContent(String body) {
        try {
            JsonNode content = MAPPER.readTree(body).path("choices").path(0).path("message").path("content");
            if (content.isTextual()) {
                return content.asText();
            }
        } catch (IOException e) {
            throw new AppException.Llm("LLM provider returned an unexpected response format", e);
        }
        throw new AppException.Llm("LLM provider returned an unexpected response format");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException.Llm("LLM request interrupted", e);
        }
    }
}
