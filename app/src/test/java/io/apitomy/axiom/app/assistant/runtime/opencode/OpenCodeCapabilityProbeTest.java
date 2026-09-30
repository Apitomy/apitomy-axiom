package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.apitomy.axiom.agents.opencode.OpenCodeServerManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeCapabilityProbeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String OP_SESSION = "post /session";
    private static final String OP_PROMPT = "post /session/{sessionID}/prompt_async";
    private static final String OP_PERMISSION = "post /session/{sessionID}/permissions/{permissionID}";
    private static final String OP_ABORT = "post /session/{sessionID}/abort";
    private static final String OP_EVENT = "get /event";
    private static final String OP_GLOBAL_EVENT = "get /global/event";

    private static final List<String> ALL_OPS =
            List.of(OP_SESSION, OP_PROMPT, OP_PERMISSION, OP_ABORT, OP_EVENT, OP_GLOBAL_EVENT);

    @BeforeEach
    void clearProbeCache() {
        OpenCodeCapabilityProbe.clearCache();
    }

    @Test
    void passesUsingSpecWithoutCreatingSessionsOrPrompting() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(ServerConfig.defaults())) {
            OpenCodeCapabilityProbe.Result result = probe(server);

            assertTrue(result.compatible());
            assertNull(result.code());
            assertEquals(0, server.sessionPosts.get());
            assertEquals(0, server.promptPosts.get());
            assertEquals(0, server.permissionPosts.get());
            assertEquals(0, server.deletes.get());
        }
    }

    @Test
    void sendsAuthorizationOnDocAndEventStreamWhenClientHasPassword() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(ServerConfig.defaults())) {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(server.baseUrl(), "pw");

            OpenCodeCapabilityProbe.Result result = new OpenCodeCapabilityProbe().probe(client);

            String expected = "Basic " + Base64.getEncoder()
                    .encodeToString("opencode:pw".getBytes(StandardCharsets.UTF_8));
            assertTrue(result.compatible());
            assertEquals(expected, server.authByPath.get("/doc"));
            assertEquals(expected, server.authByPath.get("/event"));
        }
    }

    @Test
    void sendsNoAuthorizationWhenClientHasNoPassword() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(ServerConfig.defaults())) {
            assertTrue(probe(server).compatible());

            assertEquals("<none>", server.authByPath.get("/doc"));
            assertEquals("<none>", server.authByPath.get("/event"));
        }
    }

    @Test
    void failsWithPromptCodeWhenPromptAsyncMissingFromSpec() throws Exception {
        assertSpecFailure(OP_PROMPT, "PROMPT_PROTOCOL_UNSUPPORTED");
    }

    @Test
    void failsWithPermissionCodeWhenPermissionEndpointMissingFromSpec() throws Exception {
        assertSpecFailure(OP_PERMISSION, "PERMISSION_PROTOCOL_UNSUPPORTED");
    }

    @Test
    void failsWithInterruptCodeWhenAbortMissingFromSpec() throws Exception {
        assertSpecFailure(OP_ABORT, "INTERRUPT_PROTOCOL_UNSUPPORTED");
    }

    @Test
    void failsWithSessionCodeWhenSessionCreateMissingFromSpec() throws Exception {
        assertSpecFailure(OP_SESSION, "SESSION_PROTOCOL_UNSUPPORTED");
    }

    @Test
    void failsWhenSpecDeclaresNoEventStream() throws Exception {
        String spec = doc(OP_SESSION, OP_PROMPT, OP_PERMISSION, OP_ABORT);
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(ServerConfig.defaults().withDoc(200, spec))) {
            OpenCodeCapabilityProbe.Result result = probe(server);

            assertFalse(result.compatible());
            assertEquals("EVENT_STREAM_UNRELIABLE", result.code());
            assertEquals(0, server.sessionPosts.get());
            assertEquals(0, server.promptPosts.get());
            assertEquals(0, server.permissionPosts.get());
        }
    }

    @Test
    void realOpenCodePassesProbeWithoutCreatingSessions(@TempDir Path workDir) throws Exception {
        Assumptions.assumeTrue(OpenCodeServerManager.isOpenCodeAvailable());
        OpenCodeCapabilityProbe.clearCache();
        OpenCodeSessionServerProcess process = new OpenCodeSessionServerProcess(
                "opencode", "127.0.0.1", 0, 30, Map.of(), workDir);
        try {
            process.start();
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(process.baseUrl(), process.password());
            int before = sessionCount(client);

            OpenCodeCapabilityProbe.Result result = new OpenCodeCapabilityProbe().probe(client);

            assertTrue(result.compatible(), String.valueOf(result));
            assertEquals(before, sessionCount(client));
        } finally {
            process.stop();
        }
    }

    @Test
    void cachesPassingResultPerVersion() throws Exception {
        try (FakeOpenCodeServer first = FakeOpenCodeServer.start(ServerConfig.defaults().withVersion("1.0.0"))) {
            assertTrue(probe(first).compatible());
            assertTrue(probe(first).compatible());
            assertEquals(1, first.docHits.get());
        }
        try (FakeOpenCodeServer second = FakeOpenCodeServer.start(ServerConfig.defaults().withVersion("2.0.0"))) {
            assertTrue(probe(second).compatible());
            assertEquals(1, second.docHits.get());
        }
    }

    @Test
    void doesNotCacheBlankVersion() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(ServerConfig.defaults().withVersion(""))) {
            assertTrue(probe(server).compatible());
            assertTrue(probe(server).compatible());
            assertEquals(2, server.docHits.get());
        }
    }

    @Test
    void doesNotCacheFailures() throws Exception {
        String spec = doc(OP_SESSION, OP_PERMISSION, OP_ABORT, OP_EVENT, OP_GLOBAL_EVENT);
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(ServerConfig.defaults().withDoc(200, spec))) {
            assertFalse(probe(server).compatible());
            assertFalse(probe(server).compatible());
            assertEquals(2, server.docHits.get());
        }
    }

    @Test
    void fallsBackWithoutPromptingAndDeletesProbeSessionWhenSpecUnavailable() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(ServerConfig.defaults().withDoc(404, "{}"))) {
            OpenCodeCapabilityProbe.Result result = probe(server);

            assertTrue(result.compatible());
            assertNull(result.code());
            assertEquals(1, server.sessionPosts.get());
            assertEquals(0, server.promptPosts.get());
            assertEquals(0, server.permissionPosts.get());
            assertEquals(1, server.deletes.get());
            assertEquals("/session/" + FakeOpenCodeServer.SESSION_ID, server.lastDeletePath);
        }
    }

    @Test
    void failsWhenEventStreamEndpointMissing() throws Exception {
        ServerConfig config = ServerConfig.defaults().withPrimaryEvent(null).withGlobalEvent(null);
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(config)) {
            OpenCodeCapabilityProbe.Result result = probe(server);

            assertFalse(result.compatible());
            assertEquals("EVENT_STREAM_UNRELIABLE", result.code());
        }
    }

    @Test
    void failsWhenEventStreamEndpointReturnsNonSseContentType() throws Exception {
        ServerConfig config = ServerConfig.defaults()
                .withPrimaryEvent(new EndpointSpec(200, "application/json", "{}"));
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(config)) {
            OpenCodeCapabilityProbe.Result result = probe(server);

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
            OpenCodeCapabilityProbe.Result result = probe(server);

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
            OpenCodeCapabilityProbe.Result result = probe(server);

            assertTrue(result.compatible());
            assertNull(result.code());
        }
    }

    private static void assertSpecFailure(String missingOperation, String expectedCode) throws Exception {
        String[] operations = ALL_OPS.stream()
                .filter(op -> !op.equals(missingOperation))
                .toArray(String[]::new);
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(
                ServerConfig.defaults().withDoc(200, doc(operations)))) {
            OpenCodeCapabilityProbe.Result result = probe(server);

            assertFalse(result.compatible());
            assertEquals(expectedCode, result.code());
            assertEquals(0, server.sessionPosts.get());
            assertEquals(0, server.promptPosts.get());
            assertEquals(0, server.permissionPosts.get());
        }
    }

    private static int sessionCount(OpenCodeAssistantClient client) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(client.baseUrl() + "/session"))
                .timeout(Duration.ofSeconds(10))
                .GET();
        client.authorizationHeader().ifPresent(value -> builder.header("Authorization", value));
        HttpRequest request = builder.build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body()).size();
    }

    private static OpenCodeCapabilityProbe.Result probe(FakeOpenCodeServer server) {
        OpenCodeAssistantClient client = new OpenCodeAssistantClient(server.baseUrl());
        return new OpenCodeCapabilityProbe().probe(client);
    }

    /**
     * Builds a minimal OpenAPI document declaring the given operations.
     *
     * @param operations operations such as {@code "post /session"}
     * @return OpenAPI JSON
     */
    static String doc(String... operations) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("openapi", "3.1.0");
        ObjectNode paths = root.putObject("paths");
        Arrays.stream(operations).forEach(op -> {
            String[] parts = op.split(" ", 2);
            ObjectNode pathItem = paths.has(parts[1])
                    ? (ObjectNode) paths.get(parts[1])
                    : paths.putObject(parts[1]);
            pathItem.putObject(parts[0]);
        });
        return root.toString();
    }

    private static final class FakeOpenCodeServer implements AutoCloseable {

        private static final String SESSION_ID = "ses_probe";

        private final HttpServer server;
        private final AtomicInteger docHits = new AtomicInteger();
        private final AtomicInteger sessionPosts = new AtomicInteger();
        private final AtomicInteger promptPosts = new AtomicInteger();
        private final AtomicInteger permissionPosts = new AtomicInteger();
        private final AtomicInteger deletes = new AtomicInteger();
        private volatile String lastDeletePath;
        private final Map<String, String> authByPath = new ConcurrentHashMap<>();

        private FakeOpenCodeServer(HttpServer server) {
            this.server = server;
        }

        static FakeOpenCodeServer start(ServerConfig config) throws IOException {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeOpenCodeServer fake = new FakeOpenCodeServer(httpServer);
            httpServer.createContext("/global/health", new JsonHandler(200,
                    "{\"healthy\":true,\"version\":\"" + config.version() + "\"}"));
            httpServer.createContext("/doc", exchange -> {
                fake.recordAuth(exchange);
                fake.docHits.incrementAndGet();
                new JsonHandler(config.docStatus(), config.docBody()).handle(exchange);
            });
            httpServer.createContext("/session", exchange -> fake.handleSession(exchange));
            if (config.primaryEvent() != null) {
                EndpointHandler primary = new EndpointHandler(config.primaryEvent());
                httpServer.createContext("/event", exchange -> {
                    fake.recordAuth(exchange);
                    primary.handle(exchange);
                });
            }
            if (config.globalEvent() != null) {
                httpServer.createContext("/global/event", new EndpointHandler(config.globalEvent()));
            }
            httpServer.start();
            return fake;
        }

        private void recordAuth(HttpExchange exchange) {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            authByPath.put(exchange.getRequestURI().getPath(), header == null ? "<none>" : header);
        }

        private void handleSession(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("POST".equalsIgnoreCase(method) && "/session".equals(path)) {
                sessionPosts.incrementAndGet();
                new JsonHandler(200, "{\"id\":\"" + SESSION_ID + "\"}").handle(exchange);
                return;
            }
            if ("POST".equalsIgnoreCase(method) && path.endsWith("/prompt_async")) {
                promptPosts.incrementAndGet();
                sendEmpty(exchange, 204);
                return;
            }
            if ("POST".equalsIgnoreCase(method) && path.contains("/permissions/")) {
                permissionPosts.incrementAndGet();
                sendEmpty(exchange, 200);
                return;
            }
            if ("DELETE".equalsIgnoreCase(method)) {
                deletes.incrementAndGet();
                lastDeletePath = path;
                new JsonHandler(200, "true").handle(exchange);
                return;
            }
            sendEmpty(exchange, 200);
        }

        private static void sendEmpty(HttpExchange exchange, int statusCode) throws IOException {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record EndpointSpec(int statusCode, String contentType, String body) {
    }

    private record ServerConfig(EndpointSpec primaryEvent, EndpointSpec globalEvent, String version,
                                int docStatus, String docBody) {

        static ServerConfig defaults() {
            return new ServerConfig(
                    new EndpointSpec(200, "text/event-stream", "data:{}\n\n"),
                    null,
                    "test",
                    200,
                    doc(ALL_OPS.toArray(new String[0]))
            );
        }

        ServerConfig withPrimaryEvent(EndpointSpec value) {
            return new ServerConfig(value, globalEvent, version, docStatus, docBody);
        }

        ServerConfig withGlobalEvent(EndpointSpec value) {
            return new ServerConfig(primaryEvent, value, version, docStatus, docBody);
        }

        ServerConfig withVersion(String value) {
            return new ServerConfig(primaryEvent, globalEvent, Objects.requireNonNull(value), docStatus, docBody);
        }

        ServerConfig withDoc(int status, String body) {
            return new ServerConfig(primaryEvent, globalEvent, version, status, body);
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
