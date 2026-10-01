package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenCodeAssistantClientCommandsTest {

    @Test
    void listCommandsParsesNames() throws Exception {
        HttpServer server = startServer("/command", 200,
                "[{\"name\":\"init\",\"description\":\"d\",\"template\":\"t\",\"source\":\"command\",\"hints\":[]},"
                        + "{\"name\":\"review\",\"template\":\"t\",\"subtask\":true},{\"description\":\"no name\"}]",
                null);
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl(server));
            assertEquals(List.of("init", "review"), client.listCommands());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void toolIdsParsesStringArray() throws Exception {
        HttpServer server = startServer("/experimental/tool/ids", 200, "[\"invalid\",\"bash\",\"read\"]", null);
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl(server));
            assertEquals(List.of("invalid", "bash", "read"), client.toolIds());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listCommandsThrowsOnHttpError() throws Exception {
        HttpServer server = startServer("/command", 500, "{}", null);
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl(server));
            assertThrows(IllegalStateException.class, client::listCommands);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void toolIdsThrowsOnHttpError() throws Exception {
        HttpServer server = startServer("/experimental/tool/ids", 500, "{}", null);
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl(server));
            assertThrows(IllegalStateException.class, client::toolIds);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runCommandPostsCommandArgumentsAndModel() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer("/session/s/command", 200, "{}", body);
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl(server));
            client.runCommand("s", "review", "main", "github-copilot/gpt-5.4");

            JsonNode json = new ObjectMapper().readTree(body.get());
            assertEquals("review", json.path("command").asText());
            assertEquals("main", json.path("arguments").asText());
            assertEquals("github-copilot/gpt-5.4", json.path("model").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runCommandOmitsNullModel() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = startServer("/session/s/command", 200, "{}", body);
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl(server));
            client.runCommand("s", "init", "", null);

            JsonNode json = new ObjectMapper().readTree(body.get());
            assertEquals("init", json.path("command").asText());
            assertEquals("", json.path("arguments").asText("missing"));
            assertFalse(json.has("model"));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(String path, int status, String body, AtomicReference<String> requestBody)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            String received = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (requestBody != null) {
                requestBody.set(received);
            }
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
