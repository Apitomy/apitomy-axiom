package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.apitomy.axiom.app.assistant.AssistantSession;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static final class FakeOpenCodeServer implements AutoCloseable {

        private static final String SESSION_ID = "session-1";

        private final HttpServer server;
        private final AtomicInteger promptCalls = new AtomicInteger();
        private final AtomicInteger abortCalls = new AtomicInteger();

        private FakeOpenCodeServer(HttpServer server) {
            this.server = server;
        }

        static FakeOpenCodeServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeOpenCodeServer fakeOpenCodeServer = new FakeOpenCodeServer(server);
            server.createContext("/session", new JsonHandler(201, "{\"id\":\"" + SESSION_ID + "\"}"));
            server.createContext("/event", new EventHandler());
            server.createContext("/session/" + SESSION_ID + "/prompt_async", exchange -> {
                fakeOpenCodeServer.promptCalls.incrementAndGet();
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
            exchange.close();
        }
    }
}
