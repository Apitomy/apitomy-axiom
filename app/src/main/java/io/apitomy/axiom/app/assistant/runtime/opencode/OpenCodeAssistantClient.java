package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * HTTP/SSE client for interacting with an OpenCode session-scoped server.
 */
public final class OpenCodeAssistantClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient;

    /**
     * Creates an OpenCode assistant client for the provided base URL.
     *
     * @param baseUrl OpenCode server base URL
     */
    public OpenCodeAssistantClient(String baseUrl) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Returns the configured OpenCode server base URL.
     *
     * @return OpenCode base URL
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Queries OpenCode global health status.
     *
     * @return health response wrapper
     */
    public HealthStatus health() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/global/health"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new HealthStatus(false, null);
            }
            JsonNode body = MAPPER.readTree(response.body());
            return new HealthStatus(body.path("healthy").asBoolean(false), body.path("version").asText(null));
        } catch (IOException e) {
            return new HealthStatus(false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while querying OpenCode health", e);
        }
    }

    /**
     * Creates a new OpenCode session.
     *
     * @param title optional session title
     * @return session identifier
     */
    public String createSession(String title) {
        ObjectNode body = MAPPER.createObjectNode();
        if (title != null && !title.isBlank()) {
            body.put("title", title);
        }
        JsonNode response = postJson("/session", body, 201, 200);
        return response.path("id").asText();
    }

    /**
     * Sends an async prompt to an existing OpenCode session.
     *
     * @param sessionId session identifier
     * @param prompt prompt text
     * @param model provider/model string or null
     * @param tools optional tools object
     */
    public void sendPromptAsync(String sessionId, String prompt, String model, JsonNode tools) {
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode parts = body.putArray("parts");
        ObjectNode textPart = parts.addObject();
        textPart.put("type", "text");
        textPart.put("text", prompt);

        if (model != null && model.contains("/")) {
            String[] split = model.split("/", 2);
            ObjectNode modelNode = body.putObject("model");
            modelNode.put("providerID", split[0]);
            modelNode.put("modelID", split[1]);
        }
        if (tools != null && !tools.isNull()) {
            body.set("tools", tools);
        }

        postJson("/session/" + sessionId + "/prompt_async", body, 204);
    }

    /**
     * Aborts a running session.
     *
     * @param sessionId session identifier
     */
    public void abort(String sessionId) {
        postJson("/session/" + sessionId + "/abort", MAPPER.createObjectNode(), 200);
    }

    /**
     * Responds to a pending permission request.
     *
     * @param sessionId session identifier
     * @param permissionId permission identifier
     * @param allow true for allow, false for deny
     */
    public void respondPermission(String sessionId, String permissionId, boolean allow) {
        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode response = body.putObject("response");
        response.put("behavior", allow ? "allow" : "deny");
        postJson("/session/" + sessionId + "/permissions/" + permissionId, body, 200);
    }

    /**
     * Connects to OpenCode global event stream and emits parsed raw events.
     *
     * @param onEvent callback invoked per event
     */
    public void connectEvents(Consumer<OpenCodeRawEvent> onEvent) {
        connectEvents(onEvent, throwable -> {
            throw new IllegalStateException("OpenCode event stream terminated", throwable);
        });
    }

    /**
     * Connects to OpenCode global event stream and emits parsed raw events.
     *
     * @param onEvent callback invoked per event
     * @param onError callback invoked when stream setup or parsing fails
     */
    public void connectEvents(Consumer<OpenCodeRawEvent> onEvent, Consumer<Throwable> onError) {
        Objects.requireNonNull(onEvent, "onEvent");
        Objects.requireNonNull(onError, "onError");
        Thread.ofVirtual().name("opencode-events").start(() -> {
            try {
                streamEvents(onEvent);
            } catch (Throwable throwable) {
                onError.accept(throwable);
            }
        });
    }

    private void streamEvents(Consumer<OpenCodeRawEvent> onEvent) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/event"))
                .header("Accept", "text/event-stream")
                .GET()
                .timeout(Duration.ofMinutes(30))
                .build();

        try {
            HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Failed to connect OpenCode events: HTTP " + response.statusCode());
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                parseSseEvents(reader, onEvent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stream OpenCode events", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while streaming OpenCode events", e);
        }
    }

    static void parseSseEvents(BufferedReader reader, Consumer<OpenCodeRawEvent> onEvent) throws IOException {
        String eventName = null;
        StringBuilder dataBuilder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                emitBufferedEvent(eventName, dataBuilder, onEvent);
                eventName = null;
                dataBuilder.setLength(0);
                continue;
            }
            if (line.startsWith("event:")) {
                eventName = parseFieldValue(line, "event:");
            } else if (line.startsWith("data:")) {
                if (dataBuilder.length() > 0) {
                    dataBuilder.append('\n');
                }
                dataBuilder.append(parseFieldValue(line, "data:"));
            }
        }
        emitBufferedEvent(eventName, dataBuilder, onEvent);
    }

    private static void emitBufferedEvent(String eventName,
                                          StringBuilder dataBuilder,
                                          Consumer<OpenCodeRawEvent> onEvent) throws IOException {
        if (dataBuilder.length() == 0 && eventName == null) {
            return;
        }

        String resolvedEventName = (eventName == null || eventName.isBlank()) ? "message" : eventName;
        JsonNode payload = dataBuilder.length() == 0
                ? MAPPER.createObjectNode()
                : MAPPER.readTree(dataBuilder.toString());
        onEvent.accept(new OpenCodeRawEvent(resolvedEventName, payload));
    }

    private static String parseFieldValue(String line, String fieldPrefix) {
        String value = line.substring(fieldPrefix.length());
        if (!value.isEmpty() && value.charAt(0) == ' ') {
            return value.substring(1);
        }
        return value;
    }

    private JsonNode postJson(String path, JsonNode body, int... okStatuses) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(30))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            for (int okStatus : okStatuses) {
                if (statusCode == okStatus) {
                    String responseBody = response.body();
                    if (responseBody == null || responseBody.isBlank()) {
                        return MAPPER.createObjectNode();
                    }
                    return MAPPER.readTree(responseBody);
                }
            }
            throw new IllegalStateException("OpenCode request failed: " + path + " -> HTTP " + statusCode);
        } catch (IOException e) {
            throw new IllegalStateException("OpenCode request failed: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during OpenCode request: " + path, e);
        }
    }

    /**
     * Health response metadata from OpenCode.
     *
     * @param healthy health flag
     * @param version server version
     */
    public record HealthStatus(boolean healthy, String version) {
    }

    /**
     * Raw OpenCode SSE event envelope.
     *
     * @param eventName SSE event name
     * @param payload JSON event payload
     */
    public record OpenCodeRawEvent(String eventName, JsonNode payload) {
    }
}
