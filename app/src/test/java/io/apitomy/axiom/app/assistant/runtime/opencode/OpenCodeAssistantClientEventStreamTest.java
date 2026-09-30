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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            // The fake ends the stream after one event; only the end-of-stream signal is acceptable.
            Throwable error = errorRef.get();
            assertTrue(error == null || END_OF_STREAM.equals(error.getMessage()), String.valueOf(error));

            OpenCodeAssistantClient.OpenCodeRawEvent event = eventRef.get();
            assertNotNull(event);
            assertEquals("session.turn.completed", event.eventName());
            assertTrue(event.payload().path("success").asBoolean(false));
        }
    }

    private static final String END_OF_STREAM = "OpenCode event stream ended";

    @Test
    void reportsEndOfStreamToErrorCallback() throws Exception {
        try (FakeServer server = FakeServer.start()) {
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(server.baseUrl());
            CountDownLatch terminal = new CountDownLatch(1);
            AtomicReference<Throwable> errorRef = new AtomicReference<>();

            client.connectEvents(event -> {
            }, error -> {
                errorRef.set(error);
                terminal.countDown();
            });

            assertTrue(terminal.await(3, TimeUnit.SECONDS));
            assertEquals(END_OF_STREAM, errorRef.get().getMessage());
        }
    }

    @Test
    void closedStreamStopsReadingAndDoesNotReportError() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/event", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(": hello\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            connected.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            OpenCodeAssistantClient client =
                    new OpenCodeAssistantClient("http://127.0.0.1:" + server.getAddress().getPort());
            CountDownLatch terminal = new CountDownLatch(1);

            OpenCodeAssistantClient.EventStream stream = client.connectEvents(event -> {
            }, error -> terminal.countDown());
            assertTrue(connected.await(3, TimeUnit.SECONDS));
            Thread.sleep(100);

            stream.close();
            release.countDown();

            assertFalse(terminal.await(300, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
            server.stop(0);
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
