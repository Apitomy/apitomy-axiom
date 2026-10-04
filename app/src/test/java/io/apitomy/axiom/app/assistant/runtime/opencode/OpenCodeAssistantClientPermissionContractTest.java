package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenCodeAssistantClientPermissionContractTest {

    private static final String SESSION_ID = "session-1";
    private static final String PERMISSION_ID = "per-1";
    private static final String QUESTION_ID = "que_1";

    @Test
    void respondPermissionSendsContractCompliantPayload() throws Exception {
        RecordingPermissionServer recordingServer = RecordingPermissionServer.start();
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(recordingServer.baseUrl());

            client.respondPermission(SESSION_ID, PERMISSION_ID, true);

            assertEquals("POST", recordingServer.method());
            assertEquals("/session/" + SESSION_ID + "/permissions/" + PERMISSION_ID, recordingServer.path());
            assertEquals("{\"response\":\"once\"}", recordingServer.body());
        } finally {
            recordingServer.close();
        }
    }

    @Test
    void respondPermissionAlwaysSendsAlwaysResponse() throws Exception {
        assertEquals("{\"response\":\"always\"}", permissionBody(true, true));
    }

    @Test
    void respondPermissionDenySendsRejectEvenWhenAlwaysRequested() throws Exception {
        assertEquals("{\"response\":\"reject\"}", permissionBody(false, false));
        assertEquals("{\"response\":\"reject\"}", permissionBody(false, true));
    }

    private static String permissionBody(boolean allow, boolean always) throws Exception {
        RecordingPermissionServer recordingServer = RecordingPermissionServer.start();
        try {
            new OpenCodeAssistantClient(recordingServer.baseUrl())
                    .respondPermission(SESSION_ID, PERMISSION_ID, allow, always);
            return recordingServer.body();
        } finally {
            recordingServer.close();
        }
    }

    @Test
    void replyQuestionPostsAnswersInQuestionOrder() throws Exception {
        RecordingPermissionServer recordingServer = RecordingPermissionServer.start();
        try {
            new OpenCodeAssistantClient(recordingServer.baseUrl())
                    .replyQuestion(QUESTION_ID, List.of(List.of("Weekly"), List.of("a", "b")));

            assertEquals("POST", recordingServer.method());
            assertEquals("/question/" + QUESTION_ID + "/reply", recordingServer.path());
            assertEquals("{\"answers\":[[\"Weekly\"],[\"a\",\"b\"]]}", recordingServer.body());
        } finally {
            recordingServer.close();
        }
    }

    @Test
    void rejectQuestionPostsToRejectEndpoint() throws Exception {
        RecordingPermissionServer recordingServer = RecordingPermissionServer.start();
        try {
            new OpenCodeAssistantClient(recordingServer.baseUrl()).rejectQuestion(QUESTION_ID);

            assertEquals("POST", recordingServer.method());
            assertEquals("/question/" + QUESTION_ID + "/reject", recordingServer.path());
        } finally {
            recordingServer.close();
        }
    }

    @Test
    void sendPromptAsyncSendsOnlyTextPartWithoutModelOrSystem() throws Exception {
        RecordingPermissionServer recordingServer = RecordingPermissionServer.start();
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(recordingServer.baseUrl());

            client.sendPromptAsync(SESSION_ID, "hello", null);

            assertEquals("POST", recordingServer.method());
            assertEquals("/session/" + SESSION_ID + "/prompt_async", recordingServer.path());
            assertEquals("{\"parts\":[{\"type\":\"text\",\"text\":\"hello\"}]}", recordingServer.body());
        } finally {
            recordingServer.close();
        }
    }

    @Test
    void sendPromptAsyncIncludesSystemPromptWhenProvided() throws Exception {
        RecordingPermissionServer recordingServer = RecordingPermissionServer.start();
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(recordingServer.baseUrl());

            client.sendPromptAsync(SESSION_ID, "hello", null, "You are the Axiom Configuration Assistant.");

            com.fasterxml.jackson.databind.JsonNode body =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(recordingServer.rawBody());
            assertEquals("You are the Axiom Configuration Assistant.", body.path("system").asText());
            assertEquals("hello", body.path("parts").get(0).path("text").asText());
        } finally {
            recordingServer.close();
        }
    }

    @Test
    void sendPromptAsyncOmitsBlankSystemPrompt() throws Exception {
        RecordingPermissionServer recordingServer = RecordingPermissionServer.start();
        try {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(recordingServer.baseUrl());

            client.sendPromptAsync(SESSION_ID, "hello", null, "   ");

            assertEquals("{\"parts\":[{\"type\":\"text\",\"text\":\"hello\"}]}", recordingServer.body());
        } finally {
            recordingServer.close();
        }
    }

    private static final class RecordingPermissionServer implements AutoCloseable {

        private final HttpServer server;
        private volatile String method;
        private volatile String path;
        private volatile String body;
        private volatile String rawBody;

        private RecordingPermissionServer(HttpServer server) {
            this.server = server;
        }

        static RecordingPermissionServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            RecordingPermissionServer recordingServer = new RecordingPermissionServer(server);
            server.createContext("/session/" + SESSION_ID + "/permissions/" + PERMISSION_ID,
                    new PermissionHandler(recordingServer));
            server.createContext("/question/" + QUESTION_ID, new PermissionHandler(recordingServer));
            server.createContext("/session/" + SESSION_ID + "/prompt_async",
                    new PromptHandler(recordingServer));
            server.start();
            return recordingServer;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String method() {
            return method;
        }

        String path() {
            return path;
        }

        String body() {
            return body;
        }

        String rawBody() {
            return rawBody;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class PermissionHandler implements HttpHandler {

        private final RecordingPermissionServer recordingServer;

        private PermissionHandler(RecordingPermissionServer recordingServer) {
            this.recordingServer = recordingServer;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            recordingServer.method = exchange.getRequestMethod();
            recordingServer.path = exchange.getRequestURI().getPath();
            recordingServer.body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", "");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        }
    }

    private static final class PromptHandler implements HttpHandler {

        private final RecordingPermissionServer recordingServer;

        private PromptHandler(RecordingPermissionServer recordingServer) {
            this.recordingServer = recordingServer;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            recordingServer.method = exchange.getRequestMethod();
            recordingServer.path = exchange.getRequestURI().getPath();
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            recordingServer.rawBody = requestBody;
            recordingServer.body = requestBody.replaceAll("\\s+", "");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }
    }
}
