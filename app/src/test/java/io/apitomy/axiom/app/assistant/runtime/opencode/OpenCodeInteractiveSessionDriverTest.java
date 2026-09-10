package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.apitomy.axiom.app.assistant.AssistantSession;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    void rejectsSecondPromptWhileTurnActive() throws Exception {
        CountDownLatch promptSubmitted = new CountDownLatch(1);
        EventResponder eventResponder = exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            promptSubmitted.await(3, TimeUnit.SECONDS);
            Thread.sleep(500);
            exchange.getResponseBody().close();
        };

        try (FakeOpenCodeServer server = FakeOpenCodeServer.start(eventResponder, promptSubmitted)) {
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

            assertThrows(IllegalStateException.class, () -> driver.sendUserMessage("second"));
            assertEquals(1, server.promptCallCount());

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
        String eventPayload = "event: session.message.part\n"
                + "data: {\"part\":{\"type\":\"text\",\"text\":\"hello\"}}\n\n";
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
                    events::add,
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
                    events::add,
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
                    events::add,
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
    void streamFailureTransitionsToErrorAndClearsInFlightPrompt() throws Exception {
        CountDownLatch promptSubmitted = new CountDownLatch(1);
        EventResponder eventResponder = exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            promptSubmitted.await(3, TimeUnit.SECONDS);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write("event: session.message.part\n".getBytes(StandardCharsets.UTF_8));
                outputStream.write("data: {\"sessionID\":\"session-1\",\"part\":{\"type\":\"text\"\n\n"
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
            assertFalse(isTurnInFlight(driver));
            assertEquals(1, server.promptCallCount());
            assertNotNull(terminal.get());

            driver.destroy();
        }
    }

    @Test
    void sendUserMessageAcceptsAllowedToolsArrayPayload() throws Exception {
        try (FakeOpenCodeServer server = FakeOpenCodeServer.start()) {
            FakeServerProcess process = new FakeServerProcess(server.baseUrl());

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode tools = mapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode allowed = tools.putArray("allowed");
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

    private static boolean isTurnInFlight(OpenCodeInteractiveSessionDriver driver) throws Exception {
        java.lang.reflect.Field field = OpenCodeInteractiveSessionDriver.class.getDeclaredField("turnInFlight");
        field.setAccessible(true);
        Object object = field.get(driver);
        if (object instanceof java.util.concurrent.atomic.AtomicBoolean atomicBoolean) {
            return atomicBoolean.get();
        }
        return false;
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

    @FunctionalInterface
    private interface EventResponder {

        void handle(HttpExchange exchange) throws Exception;
    }

    private static final class FakeOpenCodeServer implements AutoCloseable {

        private static final String SESSION_ID = "session-1";

        private final HttpServer server;
        private final AtomicInteger promptCalls = new AtomicInteger();
        private final AtomicInteger abortCalls = new AtomicInteger();

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
            server.start();
            return fakeOpenCodeServer;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
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
