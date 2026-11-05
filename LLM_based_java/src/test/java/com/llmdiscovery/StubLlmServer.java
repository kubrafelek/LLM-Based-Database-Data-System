package com.llmdiscovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmdiscovery.classification.LlmClient;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An OpenAI-compatible /chat/completions endpoint for tests (the Java counterpart of tests/fakes.py).
 * Records every request; replies with queued responses, then with the default reply.
 */
public class StubLlmServer implements AutoCloseable {

    public record Response(int status, String body) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    public final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private final Deque<Response> queued = new ArrayDeque<>();
    private final HttpServer server;
    private volatile String defaultContent;

    public StubLlmServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode body = MAPPER.readTree(exchange.getRequestBody());
            requests.add(body);
            authorizationHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            Response response;
            synchronized (queued) {
                response = queued.isEmpty() ? null : queued.poll();
            }
            if (response == null) {
                String userPrompt = body.path("messages").path(1).path("content").asText();
                String content = defaultContent != null ? defaultContent
                        : userPrompt.contains("@") ? classificationReply(0.97, Map.of("email_address", 0.95, "full_name", 0.05))
                        : classificationReply(0.1, Map.of("tckn", 0.5, "ssn", 0.5));
                response = new Response(200, chatCompletion(content));
            }
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    public static String chatCompletion(String content) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", content)))));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String classificationReply(double piiLikelihood, Map<String, Double> probabilities) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "pii_likelihood", piiLikelihood, "classification", probabilities, "reasoning", "fake"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public StubLlmServer reply(String content) {
        this.defaultContent = content;
        return this;
    }

    public StubLlmServer enqueue(int status, String body) {
        synchronized (queued) {
            queued.add(new Response(status, body));
        }
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public LlmClient client() {
        return client("test", 2, true);
    }

    public LlmClient client(String apiKey, int maxRetries, boolean jsonMode) {
        return new LlmClient(baseUrl(), apiKey, "fake-model", 5, maxRetries, 2, jsonMode, 0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
