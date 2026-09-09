package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import io.apitomy.axiom.agents.opencode.OpenCodeServerManager;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OpenCodeAssistantProtocolHarnessTest {

    private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration ABORT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PROGRESS_TIMEOUT = Duration.ofSeconds(12);

    @Test
    void verifiesSessionPromptEventAndAbortFlow() {
        Assumptions.assumeTrue(OpenCodeServerManager.isOpenCodeAvailable());

        OpenCodeSessionServerProcess serverProcess =
                new OpenCodeSessionServerProcess("opencode", "127.0.0.1", 0, 30);

        serverProcess.start();
        assertTrue(serverProcess.isAlive());

        try {
            String baseUrl = serverProcess.baseUrl();
            OpenCodeAssistantClient client = new OpenCodeAssistantClient(baseUrl);
            verifyRuntimeHealth(client);

            String sessionId = client.createSession("Axiom protocol harness");
            assertNotNull(sessionId);
            assertFalse(sessionId.isBlank());

            try (EventStreamTap eventTap = EventStreamTap.start(baseUrl)) {
                verifyPromptAndCoreEvents(client, eventTap, sessionId);
                verifyAbortFlow(client, eventTap, sessionId);
            }
        } finally {
            serverProcess.stop();
            assertFalse(serverProcess.isAlive());
        }
    }

    private void verifyRuntimeHealth(OpenCodeAssistantClient client) {
        OpenCodeAssistantClient.HealthStatus healthStatus = client.health();
        assertTrue(healthStatus.healthy(), "OpenCode /global/health must report healthy=true");
    }

    private void verifyPromptAndCoreEvents(OpenCodeAssistantClient client,
                                           EventStreamTap eventTap,
                                           String sessionId) {
        int startIndex = eventTap.size();
        client.sendPromptAsync(sessionId, "Reply with exactly HARNESS-READY.", null, null);

        boolean sawAssistantEvent = eventTap.awaitFromIndex(startIndex,
                events -> hasAssistantEvent(events, sessionId),
                EVENT_TIMEOUT);
        assertTrue(sawAssistantEvent,
                () -> "Prompt should emit assistant event. Events: "
                        + eventTap.describeFromIndex(startIndex));

        boolean sawStatusOrCompletionEvent = eventTap.awaitFromIndex(startIndex,
                events -> hasStatusOrCompletionEvent(events, sessionId),
                EVENT_TIMEOUT);
        assertTrue(sawStatusOrCompletionEvent,
                () -> "Prompt should emit status or completion event. Events: "
                        + eventTap.describeFromIndex(startIndex));

        boolean sawToolOrStatusOrCompletionEvent = eventTap.awaitFromIndex(startIndex,
                events -> hasToolOrStatusOrCompletionEvent(events, sessionId),
                EVENT_TIMEOUT);
        assertTrue(sawToolOrStatusOrCompletionEvent,
                () -> "Prompt should emit tool/status/completion event. Events: "
                        + eventTap.describeFromIndex(startIndex));
    }

    private void verifyAbortFlow(OpenCodeAssistantClient client,
                                 EventStreamTap eventTap,
                                 String sessionId) {
        int startIndex = eventTap.size();
        client.sendPromptAsync(sessionId,
                "Use any available tool to perform a 20 second wait before responding.",
                null,
                null);

        boolean sawProgressSignal = eventTap.awaitFromIndex(startIndex,
                events -> hasProgressSignal(events, sessionId),
                PROGRESS_TIMEOUT);
        assertTrue(sawProgressSignal,
                "Expected permission, tool, or status event before aborting long-running turn");

        eventTap.firstEventFromIndex(startIndex,
                        event -> eventMatchesSession(event, sessionId)
                                && "session.permission.requested".equals(eventType(event))
                                && !event.payload().path("requestId").asText("").isBlank())
                .ifPresent(permission -> {
                    String requestId = permission.payload().path("requestId").asText("");
                    if (!requestId.isBlank()) {
                        client.respondPermission(sessionId, requestId, true);
                    }
                });

        int abortIndex = eventTap.size();
        client.abort(sessionId);

        boolean reachedTerminalState = eventTap.awaitFromIndex(abortIndex,
                events -> hasAbortTerminalSignal(events, sessionId),
                ABORT_TIMEOUT);
        assertTrue(reachedTerminalState,
                () -> "Abort should produce deterministic terminal signal. Events: "
                        + eventTap.describeFromIndex(abortIndex));
    }

    private static boolean hasAssistantEvent(List<OpenCodeAssistantClient.OpenCodeRawEvent> events,
                                             String sessionId) {
        return events.stream().anyMatch(event -> {
            if (!eventMatchesSession(event, sessionId)) {
                return false;
            }
            String type = eventType(event);
            return "session.message.part".equals(event.eventName())
                    || type.startsWith("message.part.");
        });
    }

    private static boolean hasStatusOrCompletionEvent(List<OpenCodeAssistantClient.OpenCodeRawEvent> events,
                                                      String sessionId) {
        return events.stream().anyMatch(event -> {
            if (!eventMatchesSession(event, sessionId)) {
                return false;
            }
            String type = eventType(event);
            return "session.turn.completed".equals(event.eventName())
                    || type.startsWith("session.status")
                    || "session.idle".equals(type);
        });
    }

    private static boolean hasToolOrStatusOrCompletionEvent(List<OpenCodeAssistantClient.OpenCodeRawEvent> events,
                                                            String sessionId) {
        return events.stream().anyMatch(event -> {
            if (!eventMatchesSession(event, sessionId)) {
                return false;
            }
            String type = eventType(event);
            return type.contains("tool")
                    || type.startsWith("session.status")
                    || "session.idle".equals(type)
                    || "session.turn.completed".equals(event.eventName());
        });
    }

    private static boolean hasProgressSignal(List<OpenCodeAssistantClient.OpenCodeRawEvent> events,
                                             String sessionId) {
        return events.stream().anyMatch(event -> {
            if (!eventMatchesSession(event, sessionId)) {
                return false;
            }
            String type = eventType(event);
            return "session.permission.requested".equals(type)
                    || type.contains("tool")
                    || type.startsWith("session.status")
                    || type.startsWith("message.part.");
        });
    }

    private static boolean hasAbortTerminalSignal(List<OpenCodeAssistantClient.OpenCodeRawEvent> events,
                                                  String sessionId) {
        return events.stream().anyMatch(event -> {
            if (!eventMatchesSession(event, sessionId)) {
                return false;
            }
            String eventName = event.eventName();
            String type = eventType(event);
            if ("session.turn.completed".equals(eventName) || "session.turn.completed".equals(type)) {
                return true;
            }
            if ("session.tool.completed".equals(eventName) || "session.tool.completed".equals(type)) {
                return event.payload().path("interrupted").asBoolean(false);
            }
            if (eventName.contains("abort") || type.contains("abort")) {
                return true;
            }
            if ("session.idle".equals(type)) {
                return true;
            }
            return false;
        });
    }

    private static boolean eventMatchesSession(OpenCodeAssistantClient.OpenCodeRawEvent event,
                                               String sessionId) {
        Optional<String> maybeEventSessionId = extractSessionId(event.payload());
        return maybeEventSessionId.isPresent() && sessionId.equals(maybeEventSessionId.get());
    }

    private static Optional<String> extractSessionId(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return Optional.empty();
        }
        return findSessionIdRecursive(payload);
    }

    private static Optional<String> findSessionIdRecursive(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }

        if (node.isObject()) {
            String directSessionId = firstNonBlank(
                    node.path("sessionID").asText(""),
                    node.path("sessionId").asText(""),
                    node.path("session_id").asText("")
            );
            if (!directSessionId.isBlank()) {
                return Optional.of(directSessionId);
            }

            JsonNode sessionNode = node.path("session");
            if (sessionNode.isObject()) {
                String nestedSessionId = firstNonBlank(
                        sessionNode.path("id").asText(""),
                        sessionNode.path("sessionID").asText(""),
                        sessionNode.path("sessionId").asText(""),
                        sessionNode.path("session_id").asText("")
                );
                if (!nestedSessionId.isBlank()) {
                    return Optional.of(nestedSessionId);
                }
            }

            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                Optional<String> nested = findSessionIdRecursive(entry.getValue());
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }

        if (node.isArray()) {
            for (JsonNode element : node) {
                Optional<String> nested = findSessionIdRecursive(element);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }

        return Optional.empty();
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private static String eventType(OpenCodeAssistantClient.OpenCodeRawEvent event) {
        return event.payload().path("type").asText("");
    }

    private static final class EventStreamTap implements AutoCloseable {

        private final List<OpenCodeAssistantClient.OpenCodeRawEvent> events;
        private final List<Thread> streamThreads;
        private final AtomicReference<Throwable> streamError;
        private final AtomicBoolean closed;

        private EventStreamTap() {
            this.events = new CopyOnWriteArrayList<>();
            this.streamThreads = new CopyOnWriteArrayList<>();
            this.streamError = new AtomicReference<>();
            this.closed = new AtomicBoolean(false);
        }

        static EventStreamTap start(String baseUrl) {
            EventStreamTap tap = new EventStreamTap();
            tap.connect(baseUrl, "/event");
            tap.connect(baseUrl, "/global/event");
            return tap;
        }

        private void connect(String baseUrl, String endpoint) {
            Thread thread = Thread.ofVirtual().name("opencode-harness" + endpoint.replace('/', '-')).start(() ->
                    stream(baseUrl + endpoint));
            streamThreads.add(thread);
        }

        private void stream(String endpointUrl) {
            HttpClient httpClient = HttpClient.newBuilder().build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build();

            try {
                HttpResponse<java.io.InputStream> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int statusCode = response.statusCode();
                if (statusCode == 404 || statusCode == 405) {
                    response.body().close();
                    return;
                }
                if (statusCode != 200) {
                    response.body().close();
                    streamError.compareAndSet(null,
                            new IllegalStateException("SSE connection failed for " + endpointUrl + " with HTTP "
                                    + statusCode));
                    return;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    OpenCodeAssistantClient.parseSseEvents(reader, events::add);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!closed.get()) {
                    streamError.compareAndSet(null, e);
                }
            } catch (IOException e) {
                if (!closed.get()) {
                    streamError.compareAndSet(null, e);
                }
            }
        }

        int size() {
            return events.size();
        }

        boolean hasFromIndex(int startIndex,
                             java.util.function.Predicate<OpenCodeAssistantClient.OpenCodeRawEvent> predicate) {
            List<OpenCodeAssistantClient.OpenCodeRawEvent> snapshot = eventsFromIndex(startIndex);
            return snapshot.stream().anyMatch(predicate);
        }

        java.util.Optional<OpenCodeAssistantClient.OpenCodeRawEvent> firstEventFromIndex(
                int startIndex,
                java.util.function.Predicate<OpenCodeAssistantClient.OpenCodeRawEvent> predicate) {
            List<OpenCodeAssistantClient.OpenCodeRawEvent> snapshot = eventsFromIndex(startIndex);
            return snapshot.stream().filter(predicate).findFirst();
        }

        boolean awaitFromIndex(int startIndex,
                               java.util.function.Predicate<List<OpenCodeAssistantClient.OpenCodeRawEvent>> condition,
                               Duration timeout) {
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                Throwable throwable = streamError.get();
                if (throwable != null) {
                    throw new IllegalStateException("OpenCode event stream failed", throwable);
                }
                List<OpenCodeAssistantClient.OpenCodeRawEvent> snapshot = eventsFromIndex(startIndex);
                if (condition.test(snapshot)) {
                    return true;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for OpenCode events", e);
                }
            }
            return false;
        }

        String describeFromIndex(int startIndex) {
            List<OpenCodeAssistantClient.OpenCodeRawEvent> snapshot = eventsFromIndex(startIndex);
            List<String> names = new ArrayList<>();
            for (OpenCodeAssistantClient.OpenCodeRawEvent event : snapshot) {
                String payloadType = event.payload().path("type").asText("");
                String payloadStatus = event.payload().path("status").asText("");
                names.add(event.eventName() + "|type=" + payloadType + "|status=" + payloadStatus);
            }
            return names.toString();
        }

        private List<OpenCodeAssistantClient.OpenCodeRawEvent> eventsFromIndex(int startIndex) {
            int safeStart = Math.max(0, Math.min(startIndex, events.size()));
            return events.subList(safeStart, events.size());
        }

        @Override
        public void close() {
            closed.set(true);
            for (Thread streamThread : streamThreads) {
                streamThread.interrupt();
            }
        }
    }
}
