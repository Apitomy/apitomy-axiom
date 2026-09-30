package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link OpenCodeAssistantClient} sends HTTP Basic auth when configured with a password.
 */
class OpenCodeAssistantClientAuthTest {

    private static final String NONE = "<none>";
    private static final String EXPECTED = "Basic "
            + Base64.getEncoder().encodeToString("opencode:pw".getBytes(StandardCharsets.UTF_8));

    private HttpServer server;
    private final Map<String, String> authByRequest = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/global/health", exchange -> {
            record(exchange);
            send(exchange, 200, "application/json", "{\"healthy\":true,\"version\":\"1\"}");
        });
        server.createContext("/session", exchange -> {
            record(exchange);
            send(exchange, 200, "application/json", "{\"id\":\"ses_1\"}");
        });
        server.createContext("/mcp", exchange -> {
            record(exchange);
            send(exchange, 200, "application/json", "{}");
        });
        server.createContext("/event", exchange -> {
            record(exchange);
            send(exchange, 200, "text/event-stream", "data: {\"type\":\"server.connected\"}\n\n");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsBasicAuthOnEveryRequestWhenPasswordSet() throws Exception {
        OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl(), "pw");

        exerciseClient(client);

        assertEquals(EXPECTED, authByRequest.get("GET /global/health"));
        assertEquals(EXPECTED, authByRequest.get("POST /session"));
        assertEquals(EXPECTED, authByRequest.get("GET /mcp"));
        assertEquals(EXPECTED, authByRequest.get("GET /event"));
        assertEquals(Optional.of(EXPECTED), client.authorizationHeader());
    }

    @Test
    void sendsNoAuthWhenPasswordMissing() throws Exception {
        OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl());

        exerciseClient(client);

        assertEquals(4, authByRequest.size());
        authByRequest.forEach((request, header) -> assertEquals(NONE, header, request));
        assertEquals(Optional.empty(), client.authorizationHeader());
    }

    @Test
    void blankPasswordMeansNoAuth() {
        OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl(), "  ");

        client.health();

        assertEquals(NONE, authByRequest.get("GET /global/health"));
        assertEquals(Optional.empty(), client.authorizationHeader());
    }

    private void exerciseClient(OpenCodeAssistantClient client) throws InterruptedException {
        assertTrue(client.health().healthy());
        assertEquals("ses_1", client.createSession("t"));
        assertTrue(client.mcpStatus().isEmpty());
        CountDownLatch received = new CountDownLatch(1);
        client.connectEvents(event -> received.countDown(), throwable -> { });
        assertTrue(received.await(5, TimeUnit.SECONDS), "SSE event not received");
    }

    private void record(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        authByRequest.put(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath(),
                header == null ? NONE : header);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
