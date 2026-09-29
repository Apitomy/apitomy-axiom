package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeAssistantClientMcpStatusTest {

    // Captured from opencode 1.18.33
    private static final String MCP_RESPONSE = "{\"axiom\":{\"status\":\"connected\"},"
            + "\"bogus\":{\"status\":\"failed\",\"error\":\"ENOENT: no such file or directory\"}}";

    @Test
    void parsesMcpStatusResponse() throws Exception {
        HttpServer server = startServer(200, MCP_RESPONSE);
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl(server));

            Map<String, OpenCodeAssistantClient.McpServerStatus> statuses = client.mcpStatus();

            assertEquals(2, statuses.size());
            assertTrue(statuses.get("axiom").connected());
            assertNull(statuses.get("axiom").error());
            assertFalse(statuses.get("bogus").connected());
            assertEquals("failed", statuses.get("bogus").status());
            assertEquals("ENOENT: no such file or directory", statuses.get("bogus").error());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void throwsOnHttpError() throws Exception {
        HttpServer server = startServer(500, "{}");
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl(server));
            assertThrows(IllegalStateException.class, client::mcpStatus);
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
