package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeCapabilityProbeTest {

    @Test
    void passesWhenRequiredEndpointsArePresent() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(ServerConfig.defaults())) {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(server.baseUrl());
            OpenCodeCapabilityProbe probe = new OpenCodeCapabilityProbe();

            OpenCodeCapabilityProbe.Result result = probe.probe(client);

            assertTrue(result.compatible());
            assertNull(result.code());
        }
    }

    @Test
    void failsWhenEventStreamEndpointMissing() throws Exception {
        ServerConfig config = ServerConfig.defaults().withPrimaryEvent(null).withGlobalEvent(null);
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(config)) {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(server.baseUrl());
            OpenCodeCapabilityProbe probe = new OpenCodeCapabilityProbe();

            OpenCodeCapabilityProbe.Result result = probe.probe(client);

            assertFalse(result.compatible());
            assertEquals("EVENT_STREAM_UNRELIABLE", result.code());
        }
    }

    @Test
    void failsWhenPermissionEndpointMissing() throws Exception {
        ServerConfig config = ServerConfig.defaults().withPermissionMode(PermissionMode.MISSING);
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(config)) {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(server.baseUrl());
            OpenCodeCapabilityProbe probe = new OpenCodeCapabilityProbe();

            OpenCodeCapabilityProbe.Result result = probe.probe(client);

            assertFalse(result.compatible());
            assertEquals("PERMISSION_PROTOCOL_UNSUPPORTED", result.code());
        }
    }

    @Test
    void failsWhenEventStreamEndpointReturnsNonSseContentType() throws Exception {
        ServerConfig config = ServerConfig.defaults()
                .withPrimaryEvent(new EndpointSpec(200, "application/json", "{}"));
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(config)) {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(server.baseUrl());
            OpenCodeCapabilityProbe probe = new OpenCodeCapabilityProbe();

            OpenCodeCapabilityProbe.Result result = probe.probe(client);

            assertFalse(result.compatible());
            assertEquals("EVENT_STREAM_UNRELIABLE", result.code());
        }
    }

    @Test
    void failsWhenPrimaryEventEndpointReturnsServerErrorEvenIfGlobalEventEndpointIsHealthy() throws Exception {
        ServerConfig config = ServerConfig.defaults()
                .withPrimaryEvent(new EndpointSpec(500, "text/event-stream", ""))
                .withGlobalEvent(new EndpointSpec(200, "text/event-stream", "data:{}\n\n"));
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(config)) {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(server.baseUrl());
            OpenCodeCapabilityProbe probe = new OpenCodeCapabilityProbe();

            OpenCodeCapabilityProbe.Result result = probe.probe(client);

            assertFalse(result.compatible());
            assertEquals("EVENT_STREAM_UNRELIABLE", result.code());
        }
    }

    @Test
    void passesWhenPrimaryEventEndpointIsUnmappedAndGlobalEventEndpointSupportsSse() throws Exception {
        ServerConfig config = ServerConfig.defaults()
                .withPrimaryEvent(null)
                .withGlobalEvent(new EndpointSpec(200, "text/event-stream", "data:{}\n\n"));
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(config)) {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(server.baseUrl());
            OpenCodeCapabilityProbe probe = new OpenCodeCapabilityProbe();

            OpenCodeCapabilityProbe.Result result = probe.probe(client);

            assertTrue(result.compatible());
            assertNull(result.code());
        }
    }

    @Test
    void passesWhenPermissionEndpointIsMappedButSyntheticPermissionIdIsNotPending() throws Exception {
        ServerConfig config = ServerConfig.defaults().withPermissionMode(PermissionMode.BUSINESS_STATE_REJECTED);
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(config)) {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(server.baseUrl());
            OpenCodeCapabilityProbe probe = new OpenCodeCapabilityProbe();

            OpenCodeCapabilityProbe.Result result = probe.probe(client);

            assertTrue(result.compatible());
            assertNull(result.code());
        }
    }

    private static final class FakeOpenCodeServer implements AutoCloseable {

        private static final String SESSION_ID = "session-1";

        private final HttpServer server;

        private FakeOpenCodeServer(HttpServer server) {
            this.server = server;
        }

        static FakeOpenCodeServer start(ServerConfig config) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/global/health", new JsonHandler(200, "{\"healthy\":true,\"version\":\"test\"}"));
            server.createContext("/session", new JsonHandler(201, "{\"id\":\"" + SESSION_ID + "\"}"));
            if (config.primaryEvent() != null) {
                server.createContext("/event", new EndpointHandler(config.primaryEvent()));
            }
            if (config.globalEvent() != null) {
                server.createContext("/global/event", new EndpointHandler(config.globalEvent()));
            }
            server.createContext("/session/" + SESSION_ID + "/prompt_async", new EmptyHandler(204));
            server.createContext("/session/" + SESSION_ID + "/abort", new EmptyHandler(200));
            if (config.permissionMode() != PermissionMode.MISSING) {
                server.createContext("/session/" + SESSION_ID + "/permissions/probe-permission-id", exchange -> {
                    String method = exchange.getRequestMethod();
                    if ("OPTIONS".equalsIgnoreCase(method)) {
                        exchange.getResponseHeaders().add("Allow", "POST,OPTIONS");
                        exchange.sendResponseHeaders(204, -1);
                        exchange.close();
                        return;
                    }
                    if ("POST".equalsIgnoreCase(method)) {
                        int statusCode = config.permissionMode() == PermissionMode.SUPPORTED
                                ? 200
                                : 404;
                        exchange.sendResponseHeaders(statusCode, -1);
                        exchange.close();
                        return;
                    }
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                });
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

    private enum PermissionMode {
        SUPPORTED,
        MISSING,
        BUSINESS_STATE_REJECTED
    }

    private record EndpointSpec(int statusCode, String contentType, String body) {
    }

    private record ServerConfig(EndpointSpec primaryEvent, EndpointSpec globalEvent, PermissionMode permissionMode) {

        static ServerConfig defaults() {
            return new ServerConfig(
                    new EndpointSpec(200, "text/event-stream", "data:{}\n\n"),
                    null,
                    PermissionMode.SUPPORTED
            );
        }

        ServerConfig withPrimaryEvent(EndpointSpec value) {
            return new ServerConfig(value, globalEvent, permissionMode);
        }

        ServerConfig withGlobalEvent(EndpointSpec value) {
            return new ServerConfig(primaryEvent, value, permissionMode);
        }

        ServerConfig withPermissionMode(PermissionMode value) {
            return new ServerConfig(primaryEvent, globalEvent, Objects.requireNonNull(value, "value"));
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

    private static final class EndpointHandler implements HttpHandler {

        private final EndpointSpec endpointSpec;

        private EndpointHandler(EndpointSpec endpointSpec) {
            this.endpointSpec = Objects.requireNonNull(endpointSpec, "endpointSpec");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (endpointSpec.contentType() != null && !endpointSpec.contentType().isBlank()) {
                exchange.getResponseHeaders().add("Content-Type", endpointSpec.contentType());
            }
            byte[] payload = endpointSpec.body() == null
                    ? new byte[0]
                    : endpointSpec.body().getBytes(StandardCharsets.UTF_8);
            if (payload.length == 0) {
                exchange.sendResponseHeaders(endpointSpec.statusCode(), -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(endpointSpec.statusCode(), payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        }
    }
}
