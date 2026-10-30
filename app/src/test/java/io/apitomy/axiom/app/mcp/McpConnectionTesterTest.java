package io.apitomy.axiom.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the MCP connection tester against fake servers for each transport.
 */
class McpConnectionTesterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final McpConnectionTester tester = new McpConnectionTester(MAPPER);
    private HttpServer http;

    @AfterEach
    void stop() {
        if (http != null) {
            http.stop(0);
        }
    }

    private static String fakeStdioServer() throws URISyntaxException {
        return Path.of(McpConnectionTesterTest.class.getResource("/mcp/fake-stdio-server.js").toURI()).toString();
    }

    private static List<String> toolNames(McpConnectionTester.Result result) {
        return result.tools().stream().map(McpConnectionTester.ToolInfo::name).toList();
    }

    @Test
    void stdioServerListsAllToolPages() throws Exception {
        McpConnectionTester.Result result = tester.test(McpServerDefinition.stdio("node",
                List.of(fakeStdioServer()), Map.of("FAKE_TOKEN", "tok")), Duration.ofSeconds(20));
        assertTrue(result.success(), result.error());
        assertEquals("fake-stdio", result.serverName());
        assertEquals("1.2.3", result.serverVersion());
        assertEquals(List.of("echo", "add"), toolNames(result));
        assertEquals("Echo tok", result.tools().get(0).description());
    }

    @Test
    void stdioServerThatExitsReportsExitCodeAndStderr() throws Exception {
        McpConnectionTester.Result result = tester.test(McpServerDefinition.stdio("node",
                List.of(fakeStdioServer()), Map.of("FAKE_MCP_MODE", "crash")), Duration.ofSeconds(20));
        assertFalse(result.success());
        assertTrue(result.error().contains("exited with code 3"), result.error());
        assertTrue(result.log().contains("missing FAKE_TOKEN"), result.log());
    }

    @Test
    void stdioServerThatDoesNotAnswerTimesOut() throws Exception {
        McpConnectionTester.Result result = tester.test(McpServerDefinition.stdio("node",
                List.of(fakeStdioServer()), Map.of("FAKE_MCP_MODE", "hang")), Duration.ofSeconds(2));
        assertFalse(result.success());
        assertTrue(result.error().toLowerCase().contains("timed out"), result.error());
    }

    @Test
    void unknownCommandFails() {
        McpConnectionTester.Result result = tester.test(McpServerDefinition.stdio("no-such-command-axiom-test",
                List.of(), Map.of()), Duration.ofSeconds(5));
        assertFalse(result.success());
        assertTrue(result.error().contains("no-such-command-axiom-test"), result.error());
    }

    // ── Streamable HTTP ──────────────────────────────────────────────

    private String startStreamableHttp(boolean sseResponses) throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/mcp", exchange -> {
            if (!"Bearer t".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 401, "text/plain", "unauthorized");
                return;
            }
            JsonNode msg = MAPPER.readTree(exchange.getRequestBody());
            if (!msg.has("id")) {
                respond(exchange, 202, "text/plain", "");
                return;
            }
            String method = msg.path("method").asText();
            if (!"initialize".equals(method)
                    && !"sess-1".equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))) {
                respond(exchange, 400, "text/plain", "missing session");
                return;
            }
            ObjectNode reply = MAPPER.createObjectNode().put("jsonrpc", "2.0");
            reply.set("id", msg.get("id"));
            if ("initialize".equals(method)) {
                exchange.getResponseHeaders().add("Mcp-Session-Id", "sess-1");
                reply.putObject("result").put("protocolVersion", "2025-06-18")
                        .putObject("serverInfo").put("name", "fake-http").put("version", "9");
            } else {
                reply.putObject("result").putArray("tools").addObject().put("name", "search");
            }
            if (sseResponses) {
                respond(exchange, 200, "text/event-stream", "event: message\ndata: " + reply + "\n\n");
            } else {
                respond(exchange, 200, "application/json", reply.toString());
            }
        });
        http.start();
        return "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp";
    }

    private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @Test
    void streamableHttpWithJsonResponsesAndSessionId() throws Exception {
        String url = startStreamableHttp(false);
        McpConnectionTester.Result result = tester.test(
                McpServerDefinition.remote("http", url, Map.of("Authorization", "Bearer t"), null),
                Duration.ofSeconds(10));
        assertTrue(result.success(), result.error());
        assertEquals("fake-http", result.serverName());
        assertEquals(List.of("search"), toolNames(result));
    }

    @Test
    void streamableHttpWithSseResponses() throws Exception {
        String url = startStreamableHttp(true);
        McpConnectionTester.Result result = tester.test(
                McpServerDefinition.remote("http", url, Map.of("Authorization", "Bearer t"), null),
                Duration.ofSeconds(10));
        assertTrue(result.success(), result.error());
        assertEquals(List.of("search"), toolNames(result));
    }

    @Test
    void httpErrorStatusIsReported() throws Exception {
        String url = startStreamableHttp(false);
        McpConnectionTester.Result result = tester.test(McpServerDefinition.http(url), Duration.ofSeconds(10));
        assertFalse(result.success());
        assertTrue(result.error().contains("401"), result.error());
    }

    // ── Legacy SSE ───────────────────────────────────────────────────

    @Test
    void legacySseTransport() throws Exception {
        BlockingQueue<String> outbound = new LinkedBlockingQueue<>();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        http.createContext("/sse", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("event: endpoint\ndata: /messages?session=abc\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                while (true) {
                    String next = outbound.poll(10, TimeUnit.SECONDS);
                    if (next == null) {
                        break;
                    }
                    out.write(("event: message\ndata: " + next + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (InterruptedException | IOException e) {
                // client went away
            }
        });
        http.createContext("/messages", exchange -> {
            JsonNode msg = MAPPER.readTree(exchange.getRequestBody());
            respond(exchange, 202, "text/plain", "");
            if (!msg.has("id")) {
                return;
            }
            ObjectNode reply = MAPPER.createObjectNode().put("jsonrpc", "2.0");
            reply.set("id", msg.get("id"));
            if ("initialize".equals(msg.path("method").asText())) {
                reply.putObject("result").put("protocolVersion", "2024-11-05")
                        .putObject("serverInfo").put("name", "fake-sse").put("version", "1");
            } else {
                reply.putObject("result").putArray("tools").addObject().put("name", "legacy");
            }
            outbound.add(reply.toString());
        });
        http.start();
        String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/sse";

        McpConnectionTester.Result result = tester.test(McpServerDefinition.remote("sse", url, Map.of(), null),
                Duration.ofSeconds(10));
        assertTrue(result.success(), result.error());
        assertEquals("fake-sse", result.serverName());
        assertEquals(List.of("legacy"), toolNames(result));
    }
}
