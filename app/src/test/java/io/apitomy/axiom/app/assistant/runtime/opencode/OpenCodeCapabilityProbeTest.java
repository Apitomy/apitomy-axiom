package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeCapabilityProbeTest {

    @Test
    void passesWhenRequiredEndpointsArePresent() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(true, true)) {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(server.baseUrl());
            OpenCodeCapabilityProbe probe = new OpenCodeCapabilityProbe();

            OpenCodeCapabilityProbe.Result result = probe.probe(client);

            assertTrue(result.compatible());
            assertNull(result.code());
        }
    }

    @Test
    void failsWhenEventStreamEndpointMissing() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(false, true)) {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(server.baseUrl());
            OpenCodeCapabilityProbe probe = new OpenCodeCapabilityProbe();

            OpenCodeCapabilityProbe.Result result = probe.probe(client);

            assertFalse(result.compatible());
            assertEquals("EVENT_STREAM_UNRELIABLE", result.code());
        }
    }

    @Test
    void failsWhenPermissionEndpointMissing() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(true, false)) {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(server.baseUrl());
            OpenCodeCapabilityProbe probe = new OpenCodeCapabilityProbe();

            OpenCodeCapabilityProbe.Result result = probe.probe(client);

            assertFalse(result.compatible());
            assertEquals("PERMISSION_PROTOCOL_UNSUPPORTED", result.code());
        }
    }

    private static final class FakeOpenCodeServer implements AutoCloseable {

        private static final String SESSION_ID = "session-1";

        private final HttpServer server;

        private FakeOpenCodeServer(HttpServer server) {
            this.server = server;
        }

        static FakeOpenCodeServer start(boolean includeEventEndpoint,
                                        boolean includePermissionEndpoint) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/global/health", new JsonHandler(200, "{\"healthy\":true,\"version\":\"test\"}"));
            server.createContext("/session", new JsonHandler(201, "{\"id\":\"" + SESSION_ID + "\"}"));
            if (includeEventEndpoint) {
                server.createContext("/event", new EmptyHandler(200));
            }
            server.createContext("/session/" + SESSION_ID + "/prompt_async", new EmptyHandler(204));
            server.createContext("/session/" + SESSION_ID + "/abort", new EmptyHandler(200));
            if (includePermissionEndpoint) {
                server.createContext("/session/" + SESSION_ID + "/permissions/probe-permission-id",
                        new EmptyHandler(200));
            }
            server.start();
            return new FakeOpenCodeServer(server);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class EmptyHandler implements HttpHandler {

        private final int statusCode;

        private EmptyHandler(int statusCode) {
            this.statusCode = statusCode;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
        }
    }

    private static final class JsonHandler implements HttpHandler {

        private final int statusCode;
        private final String body;

        private JsonHandler(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        }
    }
}
