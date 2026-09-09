package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeAssistantClientEventStreamTest {

    @Test
    void fallsBackToGlobalEventEndpointWhenPrimaryEventEndpointIsUnmapped() throws Exception {
        try (FakeServer server = FakeServer.start()) {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(server.baseUrl());
            CountDownLatch eventSeen = new CountDownLatch(1);
            CountDownLatch terminal = new CountDownLatch(1);
            AtomicReference<OpenCodeAssistantClient.OpenCodeRawEvent> eventRef = new AtomicReference<>();
            AtomicReference<Throwable> errorRef = new AtomicReference<>();

            client.connectEvents(event -> {
                eventRef.set(event);
                eventSeen.countDown();
            }, error -> {
                errorRef.set(error);
                terminal.countDown();
            });

            assertTrue(eventSeen.await(3, TimeUnit.SECONDS));
            assertNull(errorRef.get());

            OpenCodeAssistantClient.OpenCodeRawEvent event = eventRef.get();
            assertNotNull(event);
            assertEquals("session.turn.completed", event.eventName());
            assertTrue(event.payload().path("success").asBoolean(false));
        }
    }

    private static final class FakeServer implements AutoCloseable {

        private final HttpServer server;

        private FakeServer(HttpServer server) {
            this.server = server;
        }

        static FakeServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/global/event", new GlobalEventHandler());
            server.start();
            return new FakeServer(server);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class GlobalEventHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write("event: session.turn.completed\n".getBytes(StandardCharsets.UTF_8));
                outputStream.write("data: {\"sessionID\":\"s1\",\"success\":true}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
        }
    }
}
