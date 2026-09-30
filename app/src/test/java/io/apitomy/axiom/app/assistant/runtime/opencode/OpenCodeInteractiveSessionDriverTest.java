package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import io.apitomy.axiom.app.assistant.AssistantSession;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeInteractiveSessionDriverTest {

    @Test
    void startRunsProcessAndProbeAndTransitionsToRunning() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start()) {
            FakeServerProcess process = new FakeServerProcess(server.baseUrl());
            AtomicInteger probeCalls = new AtomicInteger();

            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    process,
                    client -> {
                        probeCalls.incrementAndGet();
                        return OpenCodeCapabilityProbe.Result.pass();
                    },
                    new OpenCodeEventNormalizer(),
                    event -> {
                    },
                    event -> {
                    },
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null
            );

            driver.start();

            assertEquals(1, process.startCalls());
            assertEquals(1, probeCalls.get());
            assertEquals(AssistantSession.Status.RUNNING, driver.getStatus());
            assertTrue(driver.isAlive());

            driver.destroy();
        }
    }

    @Test
    void acceptsSecondPromptWhileTurnActive() throws Exception {
        CountDownLatch promptSubmitted = new CountDownLatch(1);
        EventResponder eventResponder = exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            promptSubmitted.await(3, TimeUnit.SECONDS);
            Thread.sleep(500);
            exchange.getResponseBody().close();
        };

        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(eventResponder, promptSubmitted)) {
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> {
                    },
                    event -> {
                    },
                    new OpenCodeInteractiveSessionDriver.SessionSettings(
                            "Axiom Session", "github-copilot/claude-sonnet-5", null, Set.of(),
                            "You are the Axiom Configuration Assistant.", null));

            driver.start();
            driver.sendUserMessage("first");

            // "Busy" = no turn_complete received yet (what the old guard rejected); opencode queueing verified manually.
            assertDoesNotThrow(() -> driver.sendUserMessage("second while busy"));
            assertEquals(2, server.promptCallCount());
            JsonNode second = new ObjectMapper().readTree(server.promptBodies().get(1));
            assertEquals("second while busy", second.path("parts").get(0).path("text").asText());
            assertEquals("You are the Axiom Configuration Assistant.", second.path("system").asText());

            driver.destroy();
        }
    }

    @Test
    void interruptTriggersAbort() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start()) {
            FakeServerProcess process = new FakeServerProcess(server.baseUrl());

            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    process,
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> {
                    },
                    event -> {
                    },
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null
            );

            driver.start();
            driver.sendUserMessage("first");

            driver.interrupt();

            assertEquals(1, server.abortCallCount());

            driver.destroy();
        }
    }

    @Test
    void ignoresEventWithMissingSessionId() throws Exception {
        CountDownLatch eventWritten = new CountDownLatch(1);
        String eventPayload = "event: message\n"
                + "data: {\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                + "{\"id\":\"prt_1\",\"messageID\":\"msg_1\",\"type\":\"text\",\"text\":\"hello\"}}}\n\n";
        EventResponder eventResponder = exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(eventPayload.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                eventWritten.countDown();
            }
        };

        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(eventResponder)) {
            FakeServerProcess process = new FakeServerProcess(server.baseUrl());
            List<io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent> events =
                    new CopyOnWriteArrayList<>();

            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    process,
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> addUnlessSessionInit(events, event),
                    events::add,
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null
            );

            driver.start();

            assertTrue(eventWritten.await(3, TimeUnit.SECONDS));
            waitUntil(() -> !events.isEmpty(), Duration.ofMillis(500));
            assertTrue(events.isEmpty());

            driver.destroy();
        }
    }

    @Test
    void acceptsEnvelopeEventWhenSessionIdIsUnderProperties() throws Exception {
        CountDownLatch eventWritten = new CountDownLatch(1);
        String eventPayload = "event: message\n"
                + "data: {\"type\":\"message.part.updated\",\"properties\":{\"sessionID\":\"session-1\",\"part\":{\"type\":\"text\",\"text\":\"hello\"}}}\n\n";
        EventResponder eventResponder = exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(eventPayload.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                eventWritten.countDown();
            }
        };

        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(eventResponder)) {
            FakeServerProcess process = new FakeServerProcess(server.baseUrl());
            List<io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent> events =
                    new CopyOnWriteArrayList<>();

            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    process,
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> addUnlessSessionInit(events, event),
                    events::add,
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null
            );

            driver.start();

            assertTrue(eventWritten.await(3, TimeUnit.SECONDS));
            waitUntil(() -> !events.isEmpty(), Duration.ofSeconds(2));
            assertEquals("assistant_text", events.getFirst().type());
            assertEquals("hello", events.getFirst().data().path("text").asText());

            driver.destroy();
        }
    }

    @Test
    void ignoresUserMessagePartUpdatedEventsFromEnvelopeStream() throws Exception {
        CountDownLatch eventWritten = new CountDownLatch(1);
        String eventPayload = "event: message\n"
                + "data: {\"type\":\"message.updated\",\"properties\":{\"sessionID\":\"session-1\",\"info\":{\"id\":\"msg-user-1\",\"role\":\"user\"}}}\n\n"
                + "event: message\n"
                + "data: {\"type\":\"message.part.updated\",\"properties\":{\"sessionID\":\"session-1\",\"part\":{\"type\":\"text\",\"text\":\"What time is it?\",\"messageID\":\"msg-user-1\"}}}\n\n"
                + "event: message\n"
                + "data: {\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"session-1\"}}\n\n";
        EventResponder eventResponder = exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(eventPayload.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                eventWritten.countDown();
            }
        };

        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(eventResponder)) {
            FakeServerProcess process = new FakeServerProcess(server.baseUrl());
            List<io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent> events =
                    new CopyOnWriteArrayList<>();

            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    process,
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> addUnlessSessionInit(events, event),
                    events::add,
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null
            );

            driver.start();

            assertTrue(eventWritten.await(3, TimeUnit.SECONDS));
            waitUntil(() -> !events.isEmpty(), Duration.ofSeconds(2));
            assertEquals(1, events.size());
            assertEquals("turn_complete", events.getFirst().type());

            driver.destroy();
        }
    }

    @Test
    void deliversToolUseBeforePermissionRequestForPendingToolPart() throws Exception {
        CountDownLatch eventWritten = new CountDownLatch(1);
        String part = "data: {\"type\":\"message.part.updated\",\"properties\":{\"sessionID\":\"session-1\","
                + "\"part\":{\"id\":\"prt_1\",\"sessionID\":\"session-1\",\"messageID\":\"m1\",\"type\":\"tool\","
                + "\"tool\":\"glob\",\"callID\":\"toolu_X\",\"state\":{\"status\":\"%s\",\"input\":%s}}}}\n\n";
        String eventPayload = "event: message\n" + String.format(part, "pending", "{}")
                + "event: message\n"
                + "data: {\"type\":\"permission.asked\",\"properties\":{\"id\":\"per_Y\",\"sessionID\":\"session-1\","
                + "\"permission\":\"glob\",\"patterns\":[\"*.txt\"],\"metadata\":{\"pattern\":\"*.txt\"},"
                + "\"tool\":{\"messageID\":\"m1\",\"callID\":\"toolu_X\"}}}\n\n"
                + "event: message\n" + String.format(part, "running", "{\"pattern\":\"*.txt\"}")
                + "event: message\n" + String.format(part, "completed", "{\"pattern\":\"*.txt\"},\"output\":\"a.txt\"")
                + "event: message\n"
                + "data: {\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"session-1\"}}\n\n";
        EventResponder eventResponder = exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(eventPayload.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                eventWritten.countDown();
            }
        };

        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(eventResponder)) {
            List<String> ordered = new CopyOnWriteArrayList<>();
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> {
                        if (!"session_init".equals(event.type())) {
                            ordered.add("event:" + event.type());
                        }
                    },
                    event -> ordered.add("approval:" + event.type()),
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null
            );

            driver.start();

            assertTrue(eventWritten.await(3, TimeUnit.SECONDS));
            waitUntil(() -> ordered.contains("event:turn_complete"), Duration.ofSeconds(2));
            assertEquals(List.of("event:tool_use", "approval:permission_request", "event:tool_result",
                    "event:turn_complete"), ordered);

            driver.destroy();
        }
    }

    @Test
    void streamFailureTransitionsToError() throws Exception {
        CountDownLatch promptSubmitted = new CountDownLatch(1);
        EventResponder eventResponder = exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            promptSubmitted.await(3, TimeUnit.SECONDS);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write("event: message\n".getBytes(StandardCharsets.UTF_8));
                outputStream.write(("data: {\"type\":\"message.part.updated\",\"properties\":"
                        + "{\"sessionID\":\"session-1\",\"part\":{\"id\":\"prt_1\",\"sessionID\":\"session-1\","
                        + "\"messageID\":\"msg_1\",\"type\":\"text\"\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
        };

        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(eventResponder, promptSubmitted)) {
            FakeServerProcess process = new FakeServerProcess(server.baseUrl());
            List<io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent> events =
                    new CopyOnWriteArrayList<>();
            AtomicReference<io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent> terminal =
                    new AtomicReference<>();

            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    process,
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> {
                        events.add(event);
                        if ("session_ended".equals(event.type())) {
                            terminal.set(event);
                        }
                    },
                    events::add,
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null
            );

            driver.start();
            driver.sendUserMessage("first");

            waitUntil(() -> driver.getStatus() == AssistantSession.Status.ERROR, Duration.ofSeconds(3));

            assertEquals(AssistantSession.Status.ERROR, driver.getStatus());
            assertNotNull(driver.getErrorMessage());
            assertEquals(1, server.promptCallCount());
            assertNotNull(terminal.get());

            driver.destroy();
        }
    }

    @Test
    void sendUserMessageWrapsPromptFailureWithCauseMessage() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start()) {
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> {
                    },
                    event -> {
                    },
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null
            );

            driver.start();
            IOException error = assertThrows(IOException.class, () -> driver.sendUserMessage("FAIL_WITH_500"));
            assertTrue(error.getMessage().startsWith("Failed to submit OpenCode prompt: "), error.getMessage());
            assertNotNull(error.getCause());
            assertTrue(error.getMessage().contains(error.getCause().getMessage()), error.getMessage());
            assertTrue(error.getMessage().length() > "Failed to submit OpenCode prompt: ".length());

            driver.destroy();
        }
    }

    @Test
    void sendUserMessageAcceptsAllowedToolsArrayPayload() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start()) {
            FakeServerProcess process = new FakeServerProcess(server.baseUrl());

            ObjectMapper mapper = new ObjectMapper();
            ObjectNode tools = mapper.createObjectNode();
            ArrayNode allowed = tools.putArray("allowed");
            allowed.add("Read(*)");
            allowed.add("Write(*)");

            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    process,
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> {
                    },
                    event -> {
                    },
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    tools
            );

            driver.start();
            assertDoesNotThrow(() -> driver.sendUserMessage("first"));
            assertEquals(1, server.promptCallCount());

            driver.destroy();
        }
    }

    @Test
    void startDoesNotOverwriteErrorWhenStreamFailsDuringConnect() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start()) {
            FakeServerProcess process = new FakeServerProcess(server.baseUrl());
            AtomicReference<io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent> terminal =
                    new AtomicReference<>();

            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    process,
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> {
                        if ("session_ended".equals(event.type())) {
                            terminal.set(event);
                        }
                    },
                    event -> {
                    },
                    (openCodeAssistantClient, onEvent, onError) ->
                            onError.accept(new IllegalStateException("synthetic stream failure")),
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null
            );

            driver.start();

            assertEquals(AssistantSession.Status.ERROR, driver.getStatus());
            assertNotNull(driver.getErrorMessage());
            assertTrue(driver.getErrorMessage().contains("synthetic stream failure"));
            assertNotNull(terminal.get());

            driver.destroy();
        }
    }

    private static final class FakeServerProcess implements OpenCodeInteractiveSessionDriver.ServerProcessHandle {

        private final String baseUrl;
        private final AtomicInteger startCalls = new AtomicInteger();
        private volatile boolean alive;

        private FakeServerProcess(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        @Override
        public void start() {
            startCalls.incrementAndGet();
            alive = true;
        }

        @Override
        public void stop() {
            alive = false;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public String baseUrl() {
            return baseUrl;
        }

        int startCalls() {
            return startCalls.get();
        }
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
    }

    @FunctionalInterface
    private interface BooleanSupplier {

        boolean getAsBoolean();
    }

    @Test
    void emitsSessionErrorForExpectedMcpServersThatAreNotConnected() throws Exception {
        String mcp = "{\"axiom\":{\"status\":\"connected\"},"
                + "\"broken\":{\"status\":\"failed\",\"error\":\"ENOENT\"},"
                + "\"user-global\":{\"status\":\"failed\",\"error\":\"ignored\"}}";
        try (FakeOpenCodeServer server = FakeOpenCodeServer.startWithMcp(mcp)) {
            List<SseEvent> events = new CopyOnWriteArrayList<>();
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    events::add,
                    event -> {
                    },
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null,
                    Set.of("axiom", "broken", "missing"));

            driver.start();

            List<String> messages = events.stream()
                    .filter(e -> "session_error".equals(e.type()))
                    .filter(e -> "McpServerUnavailable".equals(e.data().path("name").asText()))
                    .map(e -> e.data().path("message").asText())
                    .toList();
            assertEquals(List.of(
                    "MCP server 'broken' is unavailable (failed): ENOENT",
                    "MCP server 'missing' was not loaded by OpenCode"), messages);
            assertEquals(AssistantSession.Status.RUNNING, driver.getStatus());

            driver.destroy();
        }
    }

    @Test
    void emitsNoMcpWarningsWhenAllExpectedServersConnected() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.startWithMcp("{\"axiom\":{\"status\":\"connected\"}}")) {
            List<SseEvent> events = new CopyOnWriteArrayList<>();
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    events::add,
                    event -> {
                    },
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null,
                    Set.of("axiom"));

            driver.start();

            assertTrue(events.stream().noneMatch(e -> "McpServerUnavailable".equals(e.data().path("name").asText())));
            assertEquals(AssistantSession.Status.RUNNING, driver.getStatus());
            driver.destroy();
        }
    }

    @Test
    void emitsSingleMcpWarningWhenMcpStatusQueryFails() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(
                exchange -> new EventHandler().handle(exchange), null, 500, "{\"error\":\"boom\"}")) {
            List<SseEvent> events = new CopyOnWriteArrayList<>();
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    events::add,
                    event -> {
                    },
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null,
                    Set.of("axiom"));

            driver.start();

            List<String> messages = events.stream()
                    .filter(e -> "session_error".equals(e.type()))
                    .filter(e -> "McpServerUnavailable".equals(e.data().path("name").asText()))
                    .map(e -> e.data().path("message").asText())
                    .toList();
            assertEquals(1, messages.size());
            assertTrue(messages.get(0).startsWith("Unable to verify MCP server status: "), messages.get(0));
            assertEquals(AssistantSession.Status.RUNNING, driver.getStatus());
            driver.destroy();
        }
    }

    @Test
    void mcpWarningSinkFailureDoesNotFailSession() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.startWithMcp("{\"axiom\":{\"status\":\"failed\"}}")) {
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> {
                        if ("McpServerUnavailable".equals(event.data().path("name").asText())) {
                            throw new IllegalStateException("sink failure");
                        }
                    },
                    event -> {
                    },
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null,
                    Set.of("axiom"));

            driver.start();

            assertEquals(AssistantSession.Status.RUNNING, driver.getStatus());
            driver.destroy();
        }
    }

    @Test
    void sendUserMessageIncludesSessionSystemPrompt() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start()) {
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> {
                    },
                    event -> {
                    },
                    new OpenCodeInteractiveSessionDriver.SessionSettings(
                            "Axiom Session", "github-copilot/claude-sonnet-5", null, Set.of(),
                            "You are the Axiom Configuration Assistant.", null));
            driver.start();

            driver.sendUserMessage("hello");

            JsonNode body =
                    new ObjectMapper().readTree(server.lastPromptBody());
            assertEquals("You are the Axiom Configuration Assistant.", body.path("system").asText());
            assertEquals("hello", body.path("parts").get(0).path("text").asText());
            driver.destroy();
        }
    }

    @Test
    void sendUserMessageIncludesSessionSystemPromptOnEveryPrompt() throws Exception {
        String systemPrompt = "You are the Axiom Configuration Assistant.";
        AtomicReference<FakeOpenCodeServer> serverRef = new AtomicReference<>();
        CountDownLatch testDone = new CountDownLatch(1);
        String idleEvent = "event: message\n"
                + "data: {\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"session-1\"}}\n\n";
        EventResponder eventResponder = exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                int completedTurns = 0;
                while (completedTurns < 2 && System.nanoTime() < deadline) {
                    FakeOpenCodeServer current = serverRef.get();
                    if (current != null && current.promptBodies().size() > completedTurns) {
                        outputStream.write(idleEvent.getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                        completedTurns++;
                    } else {
                        Thread.sleep(10);
                    }
                }
                testDone.await(5, TimeUnit.SECONDS);
            }
        };

        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(eventResponder)) {
            serverRef.set(server);
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> {
                    },
                    event -> {
                    },
                    new OpenCodeInteractiveSessionDriver.SessionSettings(
                            "Axiom Session", "github-copilot/claude-sonnet-5", null, Set.of(), systemPrompt, null));
            driver.start();

            driver.sendUserMessage("first");

            driver.sendUserMessage("second");

            List<String> bodies = server.promptBodies();
            assertEquals(2, bodies.size());
            ObjectMapper mapper = new ObjectMapper();
            JsonNode first = mapper.readTree(bodies.get(0));
            JsonNode second = mapper.readTree(bodies.get(1));
            assertEquals(systemPrompt, first.path("system").asText());
            assertEquals("first", first.path("parts").get(0).path("text").asText());
            assertEquals(systemPrompt, second.path("system").asText());
            assertEquals("second", second.path("parts").get(0).path("text").asText());
            testDone.countDown();
            driver.destroy();
        }
    }

    @Test
    void sendUserMessageOmitsSystemWhenNoSystemPrompt() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start()) {
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> {
                    },
                    event -> {
                    },
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null);
            driver.start();

            driver.sendUserMessage("hello");

            JsonNode body =
                    new ObjectMapper().readTree(server.lastPromptBody());
            assertFalse(body.has("system"));
            driver.destroy();
        }
    }

    private static void addUnlessSessionInit(List<SseEvent> events, SseEvent event) {
        if (!"session_init".equals(event.type())) {
            events.add(event);
        }
    }

    private static final String DEFAULT_PROVIDERS = "{\"providers\":[{\"id\":\"github-copilot\","
            + "\"models\":{\"claude-sonnet-5\":{},\"gpt-5.4\":{}}}],"
            + "\"default\":{\"github-copilot\":\"gpt-5.4\"}}";

    @Test
    void validTemplateModelIsUsedWithoutError() throws Exception {
        ModelRun run = runWithModels(200, DEFAULT_PROVIDERS, "github-copilot/claude-sonnet-5",
                "github-copilot/gpt-5.4");

        assertTrue(run.modelErrors().isEmpty());
        assertEquals("github-copilot/claude-sonnet-5", run.sessionInit().path("model").asText());
        assertEquals("opencode", run.sessionInit().path("engine").asText());
        assertPromptModel(run.prompt(), "github-copilot", "claude-sonnet-5");
    }

    @Test
    void blankTemplateModelFallsBackToConfiguredDefaultWithoutError() throws Exception {
        ModelRun run = runWithModels(200, DEFAULT_PROVIDERS, null, "github-copilot/gpt-5.4");

        assertTrue(run.modelErrors().isEmpty());
        assertEquals("github-copilot/gpt-5.4", run.sessionInit().path("model").asText());
        assertPromptModel(run.prompt(), "github-copilot", "gpt-5.4");
    }

    @Test
    void unknownTemplateModelEmitsErrorAndFallsBack() throws Exception {
        ModelRun run = runWithModels(200, DEFAULT_PROVIDERS, "github-copilot/nope", "github-copilot/gpt-5.4");

        assertEquals(1, run.modelErrors().size());
        String message = run.modelErrors().get(0).path("message").asText();
        assertTrue(message.contains("github-copilot/nope"), message);
        assertTrue(message.contains("github-copilot/gpt-5.4"), message);
        assertPromptModel(run.prompt(), "github-copilot", "gpt-5.4");
        assertEquals("github-copilot/gpt-5.4", run.sessionInit().path("model").asText());
        assertEquals(AssistantSession.Status.RUNNING, run.status());
    }

    @Test
    void templateModelWithoutSlashEmitsErrorAndFallsBack() throws Exception {
        ModelRun run = runWithModels(200, DEFAULT_PROVIDERS, "sonnet", "github-copilot/gpt-5.4");

        assertEquals(1, run.modelErrors().size());
        String message = run.modelErrors().get(0).path("message").asText();
        assertTrue(message.contains("sonnet"), message);
        assertTrue(message.contains("github-copilot/gpt-5.4"), message);
        assertPromptModel(run.prompt(), "github-copilot", "gpt-5.4");
        assertEquals(AssistantSession.Status.RUNNING, run.status());
    }

    @Test
    void neitherModelValidUsesOpenCodeDefault() throws Exception {
        ModelRun run = runWithModels(200, DEFAULT_PROVIDERS, "x/y", "a/b");

        assertEquals(1, run.modelErrors().size());
        String message = run.modelErrors().get(0).path("message").asText();
        assertTrue(message.contains("x/y"), message);
        assertTrue(message.contains("a/b"), message);
        assertTrue(message.contains("OpenCode's default model"), message);
        assertFalse(run.prompt().has("model"));
        assertEquals("", run.sessionInit().path("model").asText("missing"));
        assertEquals(AssistantSession.Status.RUNNING, run.status());
    }

    @Test
    void invalidModelsReportNoModelEvenWhenProviderHasDefault() throws Exception {
        ModelRun run = runWithModels(200, DEFAULT_PROVIDERS, "github-copilot/nope", "a/b");

        assertEquals(1, run.modelErrors().size());
        assertTrue(run.modelErrors().get(0).path("message").asText().contains("OpenCode's default model"));
        assertEquals("", run.sessionInit().path("model").asText("missing"));
        assertFalse(run.prompt().has("model"));
    }

    @Test
    void sameInvalidTemplateAndFallbackIsListedOnce() throws Exception {
        ModelRun run = runWithModels(200, DEFAULT_PROVIDERS, "x/y", "x/y");

        assertEquals(1, run.modelErrors().size());
        String message = run.modelErrors().get(0).path("message").asText();
        assertEquals(message.indexOf("x/y"), message.lastIndexOf("x/y"), message);
        assertTrue(message.contains("OpenCode's default model"), message);
        assertFalse(run.prompt().has("model"));
    }

    @Test
    void unreadableProvidersSkipsValidation() throws Exception {
        ModelRun run = runWithModels(500, "{\"error\":\"boom\"}", "github-copilot/whatever",
                "github-copilot/gpt-5.4");

        assertTrue(run.modelErrors().isEmpty());
        assertPromptModel(run.prompt(), "github-copilot", "whatever");
        assertEquals("github-copilot/whatever", run.sessionInit().path("model").asText());
    }

    private record ModelRun(List<SseEvent> events, JsonNode prompt, AssistantSession.Status status) {

        List<JsonNode> modelErrors() {
            return events.stream()
                    .filter(event -> "session_error".equals(event.type()))
                    .map(SseEvent::data)
                    .filter(data -> "ModelUnavailable".equals(data.path("name").asText()))
                    .toList();
        }

        JsonNode sessionInit() {
            List<JsonNode> inits = events.stream()
                    .filter(event -> "session_init".equals(event.type()))
                    .map(SseEvent::data)
                    .toList();
            assertEquals(1, inits.size());
            return inits.get(0);
        }
    }

    private static ModelRun runWithModels(int providersStatus, String providersBody,
                                          String templateModel, String fallbackModel) throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.startWithProviders(providersStatus, providersBody)) {
            List<SseEvent> events = new CopyOnWriteArrayList<>();
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    events::add,
                    event -> {
                    },
                    new OpenCodeInteractiveSessionDriver.SessionSettings(
                            "Axiom Session", templateModel, null, Set.of(), null, fallbackModel));
            driver.start();
            AssistantSession.Status status = driver.getStatus();
            driver.sendUserMessage("hello");
            JsonNode prompt = new ObjectMapper().readTree(server.lastPromptBody());
            driver.destroy();
            return new ModelRun(List.copyOf(events), prompt, status);
        }
    }

    private static void assertPromptModel(JsonNode prompt, String providerId, String modelId) {
        assertEquals(providerId, prompt.path("model").path("providerID").asText());
        assertEquals(modelId, prompt.path("model").path("modelID").asText());
    }

    @Test
    void sessionSettingsNormalizesExpectedMcpServers() {
        OpenCodeInteractiveSessionDriver.SessionSettings settings =
                new OpenCodeInteractiveSessionDriver.SessionSettings("t", null, null, null, null, null);

        assertEquals(Set.of(), settings.expectedMcpServers());
    }

    private static EventResponder replayFixture(String fixture) {
        return exchange -> {
            List<JsonNode> events = OpenCodeEventFixtures.load(fixture);
            String capturedSessionId = OpenCodeEventFixtures.first(events,
                            event -> "session.created".equals(event.path("type").asText()))
                    .path("properties").path("info").path("id").asText();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                for (JsonNode event : events) {
                    String line = event.toString().replace(capturedSessionId, "session-1");
                    outputStream.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
                }
                outputStream.flush();
            }
        };
    }

    private static List<SseEvent> replayThroughDriver(String fixture, List<SseEvent> permissionEvents)
            throws Exception {
        List<SseEvent> events = new CopyOnWriteArrayList<>();
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(replayFixture(fixture))) {
            OpenCodeInteractiveSessionDriver driver = new OpenCodeInteractiveSessionDriver(
                    new FakeServerProcess(server.baseUrl()),
                    client -> OpenCodeCapabilityProbe.Result.pass(),
                    new OpenCodeEventNormalizer(),
                    event -> addUnlessSessionInit(events, event),
                    permissionEvents::add,
                    "Axiom Session",
                    "github-copilot/claude-sonnet-5",
                    null);
            driver.start();
            waitUntil(() -> events.stream().anyMatch(event -> "turn_complete".equals(event.type())),
                    Duration.ofSeconds(5));
            assertTrue(events.stream().anyMatch(event -> "turn_complete".equals(event.type())),
                    "No turn_complete received; events: " + events);
            driver.destroy();
        }
        return events;
    }

    @Test
    void replaysRealToolCallStreamIntoAssistantEvents() throws Exception {
        List<SseEvent> permissionEvents = new CopyOnWriteArrayList<>();

        List<SseEvent> events = replayThroughDriver("1.18.33-tool-calls.jsonl", permissionEvents);

        List<String> summary = events.stream()
                .map(event -> switch (event.type()) {
                    case "tool_use" -> "tool_use:" + event.data().path("name").asText() + ":"
                            + event.data().path("id").asText();
                    case "tool_result" -> "tool_result:" + event.data().path("toolUseId").asText();
                    case "assistant_text" -> "assistant_text:" + event.data().path("text").asText();
                    default -> event.type();
                })
                .toList();
        assertEquals(List.of(
                "thinking",
                "tool_use:read:toolu_01AaGr1CuwaqRudKSw25JKcY",
                "tool_use:bash:toolu_01LG86GQEhToDrjJWeL12dQw",
                "tool_result:toolu_01AaGr1CuwaqRudKSw25JKcY",
                "tool_result:toolu_01LG86GQEhToDrjJWeL12dQw",
                "assistant_text:DONE",
                "turn_complete"), summary);
        assertEquals(1, permissionEvents.size());
        assertEquals("permission_request", permissionEvents.get(0).type());
        SseEvent turnComplete = events.get(events.size() - 1);
        assertEquals(0.0702413, turnComplete.data().path("costUsd").asDouble(), 1e-9);
        assertEquals(140, turnComplete.data().path("outputTokens").asLong());
    }

    @Test
    void replaysRealToolErrorStreamIntoAssistantEvents() throws Exception {
        List<SseEvent> events = replayThroughDriver("1.18.33-tool-error.jsonl", new CopyOnWriteArrayList<>());

        List<SseEvent> results = events.stream().filter(event -> "tool_result".equals(event.type())).toList();
        assertEquals(1, results.size());
        assertEquals("", results.get(0).data().path("stdout").asText());
        assertEquals("File not found: /tmp/opencode/cap/does-not-exist.txt",
                results.get(0).data().path("stderr").asText());
        assertTrue(events.stream().noneMatch(event -> "unhandled_event".equals(event.type())));
        assertEquals("turn_complete", events.get(events.size() - 1).type());
    }

    @FunctionalInterface
    private interface EventResponder {

        void handle(HttpExchange exchange) throws Exception;
    }

    private static final class FakeOpenCodeServer implements AutoCloseable {

        private static final String SESSION_ID = "session-1";

        private final HttpServer server;
        private final AtomicInteger promptCalls = new AtomicInteger();
        private final AtomicInteger abortCalls = new AtomicInteger();
        private final AtomicReference<String> lastPromptBody = new AtomicReference<>();
        private final List<String> promptBodies = new CopyOnWriteArrayList<>();

        private FakeOpenCodeServer(HttpServer server) {
            this.server = server;
        }

        static FakeOpenCodeServer start() throws IOException {
            return start(exchange -> new EventHandler().handle(exchange), null);
        }

        static FakeOpenCodeServer start(EventResponder eventResponder) throws IOException {
            return start(eventResponder, null);
        }

        static FakeOpenCodeServer start(EventResponder eventResponder,
                                        CountDownLatch promptSubmitted) throws IOException {
            return start(eventResponder, promptSubmitted, "{}");
        }

        static FakeOpenCodeServer startWithMcp(String mcpResponse) throws IOException {
            return start(exchange -> new EventHandler().handle(exchange), null, mcpResponse);
        }

        static FakeOpenCodeServer start(EventResponder eventResponder,
                                        CountDownLatch promptSubmitted,
                                        String mcpResponse) throws IOException {
            return start(eventResponder, promptSubmitted, 200, mcpResponse);
        }

        static FakeOpenCodeServer startWithProviders(int providersStatusCode, String providersResponse)
                throws IOException {
            FakeOpenCodeServer fake = start(exchange -> new EventHandler().handle(exchange), null, 200, "{}");
            fake.server.createContext("/config/providers", new JsonHandler(providersStatusCode, providersResponse));
            return fake;
        }

        static FakeOpenCodeServer start(EventResponder eventResponder,
                                        CountDownLatch promptSubmitted,
                                        int mcpStatusCode,
                                        String mcpResponse) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeOpenCodeServer fakeOpenCodeServer = new FakeOpenCodeServer(server);
            server.createContext("/session", new JsonHandler(201, "{\"id\":\"" + SESSION_ID + "\"}"));
            server.createContext("/event", exchange -> {
                try {
                    eventResponder.handle(exchange);
                } catch (Exception e) {
                    throw new IOException(e);
                } finally {
                    exchange.close();
                }
            });
            server.createContext("/session/" + SESSION_ID + "/prompt_async", exchange -> {
                fakeOpenCodeServer.promptCalls.incrementAndGet();
                if (promptSubmitted != null) {
                    promptSubmitted.countDown();
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                fakeOpenCodeServer.lastPromptBody.set(body);
                fakeOpenCodeServer.promptBodies.add(body);
                if (body.contains("FAIL_WITH_500")) {
                    byte[] payload = "{\"name\":\"UnknownError\",\"data\":{\"message\":\"boom from server\"}}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, payload.length);
                    exchange.getResponseBody().write(payload);
                    exchange.close();
                    return;
                }
                if (body.contains("\"tools\":{\"allowed\"")) {
                    byte[] payload = "{\"name\":\"BadRequest\",\"data\":{\"message\":\"Expected boolean\",\"kind\":\"Payload\"}}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(400, payload.length);
                    exchange.getResponseBody().write(payload);
                    exchange.close();
                    return;
                }
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            server.createContext("/session/" + SESSION_ID + "/abort", exchange -> {
                fakeOpenCodeServer.abortCalls.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.createContext("/session/" + SESSION_ID + "/permissions/perm-1", exchange -> {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.createContext("/mcp", new JsonHandler(mcpStatusCode, mcpResponse));
            // Serve each exchange on its own thread so a long-lived /event stream cannot block prompt_async.
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return fakeOpenCodeServer;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<String> promptBodies() {
            return promptBodies;
        }

        String lastPromptBody() {
            return lastPromptBody.get();
        }

        int promptCallCount() {
            return promptCalls.get();
        }

        int abortCallCount() {
            return abortCalls.get();
        }

        @Override
        public void close() {
            server.stop(0);
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

    private static final class EventHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write("event: session.turn.completed\n".getBytes(StandardCharsets.UTF_8));
                outputStream.write("data: {\"sessionID\":\"session-1\",\"success\":true}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
        }
    }
}
