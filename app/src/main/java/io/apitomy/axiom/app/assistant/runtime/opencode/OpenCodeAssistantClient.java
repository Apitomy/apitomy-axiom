package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
     * Sends an async prompt to an existing OpenCode session without a system prompt.
     *
     * @param sessionId session identifier
     * @param prompt prompt text
     * @param model provider/model string or null
     * @param tools optional tools object
     */
    public void sendPromptAsync(String sessionId, String prompt, String model, JsonNode tools) {
        sendPromptAsync(sessionId, prompt, model, tools, null);
    }

    /**
     * Sends an async prompt to an existing OpenCode session.
     *
     * @param sessionId session identifier
     * @param prompt prompt text
     * @param model provider/model string or null
     * @param tools optional tools object
     * @param system system prompt added to OpenCode's own system prompt for this message; omitted when null or
     *               blank. OpenCode stores it per message, so callers must pass it on every prompt.
     */
    public void sendPromptAsync(String sessionId, String prompt, String model, JsonNode tools, String system) {
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
            body.set("tools", normalizePromptTools(tools));
        }
        if (system != null && !system.isBlank()) {
            body.put("system", system);
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
     * Deletes a session.
     *
     * @param sessionId session identifier
     * @throws IllegalStateException if the request fails or returns a status other than 200 or 204
     */
    public void deleteSession(String sessionId) {
        String path = "/session/" + sessionId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .DELETE()
                .timeout(Duration.ofSeconds(30))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            if (statusCode != 200 && statusCode != 204) {
                throw new IllegalStateException("OpenCode request failed: " + path + " -> HTTP " + statusCode);
            }
        } catch (IOException e) {
            throw new IllegalStateException("OpenCode request failed: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during OpenCode request: " + path, e);
        }
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
        body.put("response", allow ? "once" : "reject");
        postJson("/session/" + sessionId + "/permissions/" + permissionId, body, 200);
    }

    /**
     * Returns the connection status of every MCP server known to the OpenCode server.
     *
     * @return statuses keyed by MCP server name
     * @throws IllegalStateException if the request fails
     */
    public Map<String, McpServerStatus> mcpStatus() {
        JsonNode body = getJson("/mcp");
        Map<String, McpServerStatus> statuses = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            statuses.put(field.getKey(), new McpServerStatus(
                    value.path("status").asText(""),
                    value.hasNonNull("error") ? value.path("error").asText() : null));
        }
        return statuses;
    }

    /**
     * Returns the providers and models configured in the OpenCode server ({@code GET /config/providers}).
     *
     * @return model ids per provider id and default model id per provider id, both in insertion order
     * @throws IllegalStateException if the request fails or the response has an unexpected shape
     */
    public ProviderCatalog providerCatalog() {
        JsonNode body = getJson("/config/providers");
        JsonNode providers = body.path("providers");
        if (!providers.isArray()) {
            throw new IllegalStateException("OpenCode /config/providers response has no providers array");
        }
        Map<String, Set<String>> models = new LinkedHashMap<>();
        for (JsonNode provider : providers) {
            String providerId = provider.path("id").asText("");
            if (providerId.isBlank()) {
                continue;
            }
            Set<String> modelIds = new LinkedHashSet<>();
            JsonNode providerModels = provider.path("models");
            if (providerModels.isObject()) {
                providerModels.fieldNames().forEachRemaining(modelIds::add);
            } else if (providerModels.isArray()) {
                for (JsonNode providerModel : providerModels) {
                    String modelId = providerModel.path("id").asText("");
                    if (!modelId.isBlank()) {
                        modelIds.add(modelId);
                    }
                }
            }
            models.put(providerId, Collections.unmodifiableSet(modelIds));
        }
        Map<String, String> defaults = new LinkedHashMap<>();
        JsonNode defaultNode = body.path("default");
        if (defaultNode.isObject()) {
            defaultNode.fields().forEachRemaining(field -> {
                if (field.getValue().isTextual()) {
                    defaults.put(field.getKey(), field.getValue().asText());
                }
            });
        }
        return new ProviderCatalog(Collections.unmodifiableMap(models), Collections.unmodifiableMap(defaults));
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
        try {
            HttpResponse<java.io.InputStream> response = openEventStreamResponse();
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

    private HttpResponse<java.io.InputStream> openEventStreamResponse() throws IOException, InterruptedException {
        HttpResponse<java.io.InputStream> primaryResponse = httpClient.send(
                eventStreamRequest(baseUrl + "/event"),
                HttpResponse.BodyHandlers.ofInputStream());

        int primaryStatusCode = primaryResponse.statusCode();
        if (primaryStatusCode != 404 && primaryStatusCode != 405) {
            return primaryResponse;
        }

        primaryResponse.body().close();

        return httpClient.send(
                eventStreamRequest(baseUrl + "/global/event"),
                HttpResponse.BodyHandlers.ofInputStream());
    }

    private HttpRequest eventStreamRequest(String endpoint) {
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Accept", "text/event-stream")
                .GET()
                .timeout(Duration.ofMinutes(30))
                .build();
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

    private JsonNode normalizePromptTools(JsonNode tools) {
        JsonNode allowed = tools.path("allowed");
        if (!allowed.isArray()) {
            return tools;
        }

        ObjectNode normalized = MAPPER.createObjectNode();
        for (JsonNode entry : allowed) {
            if (entry instanceof TextNode textNode) {
                String toolName = textNode.asText("").trim();
                if (!toolName.isEmpty()) {
                    normalized.put(toolName, true);
                }
            }
        }
        return normalized;
    }

    private JsonNode getJson(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "OpenCode request failed: " + path + " -> HTTP " + response.statusCode());
            }
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("OpenCode request failed: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during OpenCode request: " + path, e);
        }
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
     * Providers and models configured in the OpenCode server, as reported by {@code GET /config/providers}.
     *
     * @param models model ids keyed by provider id
     * @param defaults default model id keyed by provider id
     */
    public record ProviderCatalog(Map<String, Set<String>> models, Map<String, String> defaults) {

        /**
         * Checks whether a {@code provider/model} string names a model known to this catalog.
         *
         * @param model model in provider/model format
         * @return true when the provider exists and lists the model id
         */
        public boolean contains(String model) {
            if (model == null) {
                return false;
            }
            int slash = model.indexOf('/');
            if (slash <= 0 || slash == model.length() - 1) {
                return false;
            }
            Set<String> providerModels = models.get(model.substring(0, slash));
            return providerModels != null && providerModels.contains(model.substring(slash + 1));
        }
    }

    /**
     * Connection status of a single MCP server as reported by {@code GET /mcp}.
     *
     * @param status status value ({@code connected}, {@code failed}, {@code disabled}, {@code needs_auth}, ...)
     * @param error error message when available, otherwise null
     */
    public record McpServerStatus(String status, String error) {

        /**
         * @return true when the server is connected
         */
        public boolean connected() {
            return "connected".equals(status);
        }
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
