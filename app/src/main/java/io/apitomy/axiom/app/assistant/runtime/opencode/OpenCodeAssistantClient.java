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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * HTTP/SSE client for interacting with an OpenCode session-scoped server.
 */
public final class OpenCodeAssistantClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(10);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final String authorization;

    /**
     * Creates an OpenCode assistant client for the provided base URL without authentication.
     *
     * @param baseUrl OpenCode server base URL
     */
    public OpenCodeAssistantClient(String baseUrl) {
        this(baseUrl, null);
    }

    /**
     * Creates an OpenCode assistant client that authenticates with HTTP Basic {@code opencode:<password>}.
     *
     * @param baseUrl OpenCode server base URL
     * @param password server password, or {@code null}/blank for no authentication
     */
    public OpenCodeAssistantClient(String baseUrl, String password) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.authorization = password == null || password.isBlank()
                ? null
                : "Basic " + Base64.getEncoder().encodeToString(
                        ("opencode:" + password).getBytes(StandardCharsets.UTF_8));
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
     * Returns the {@code Authorization} header value this client sends, if a password is configured.
     *
     * @return header value, or empty when no password is configured
     */
    public Optional<String> authorizationHeader() {
        return Optional.ofNullable(authorization);
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(baseUrl + path));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return builder;
    }

    /**
     * Queries OpenCode global health status.
     *
     * @return health response wrapper
     */
    public HealthStatus health() {
        HttpRequest request = request("/global/health")
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
     */
    public void sendPromptAsync(String sessionId, String prompt, String model) {
        sendPromptAsync(sessionId, prompt, model, null);
    }

    /**
     * Sends an async prompt to an existing OpenCode session.
     *
     * @param sessionId session identifier
     * @param prompt prompt text
     * @param model provider/model string or null
     * @param system system prompt added to OpenCode's own system prompt for this message; omitted when null or
     *               blank. OpenCode stores it per message, so callers must pass it on every prompt.
     */
    public void sendPromptAsync(String sessionId, String prompt, String model, String system) {
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
        deleteSession(sessionId, Duration.ofSeconds(30));
    }

    /**
     * Deletes a session with a request timeout.
     *
     * @param sessionId session identifier
     * @param timeout request timeout
     * @throws IllegalStateException if the request fails or returns a status other than 200 or 204
     */
    public void deleteSession(String sessionId, Duration timeout) {
        String path = "/session/" + sessionId;
        HttpRequest request = request(path)
                .DELETE()
                .timeout(timeout)
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
        respondPermission(sessionId, permissionId, allow, false);
    }

    /**
     * Responds to a pending permission request. When {@code always} is set (and the request
     * is allowed), OpenCode approves matching requests for the rest of the session.
     *
     * @param sessionId session identifier
     * @param permissionId permission identifier
     * @param allow true for allow, false for deny
     * @param always when allowing, reply {@code "always"} instead of {@code "once"}
     */
    public void respondPermission(String sessionId, String permissionId, boolean allow, boolean always) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("response", !allow ? "reject" : (always ? "always" : "once"));
        postJson("/session/" + sessionId + "/permissions/" + permissionId, body, 200);
    }

    /**
     * Answers a pending question request ({@code POST /question/{requestID}/reply}).
     *
     * @param requestId question request identifier
     * @param answers one answer per question, in question order; each answer is the list of selected labels (or
     *                free text)
     */
    public void replyQuestion(String requestId, List<List<String>> answers) {
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode answersNode = body.putArray("answers");
        answers.forEach(answer -> {
            ArrayNode answerNode = answersNode.addArray();
            answer.forEach(answerNode::add);
        });
        postJson("/question/" + requestId + "/reply", body, 200, 204);
    }

    /**
     * Rejects (dismisses) a pending question request ({@code POST /question/{requestID}/reject}).
     *
     * @param requestId question request identifier
     */
    public void rejectQuestion(String requestId) {
        postJson("/question/" + requestId + "/reject", MAPPER.createObjectNode(), 200, 204);
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
     * Returns the names of the commands known to the OpenCode server ({@code GET /command}).
     *
     * @return command names without the leading slash, in server order
     * @throws IllegalStateException if the request fails or the response is not an array
     */
    public List<String> listCommands() {
        JsonNode body = getJson("/command");
        if (!body.isArray()) {
            throw new IllegalStateException("OpenCode /command response is not an array");
        }
        List<String> names = new ArrayList<>();
        for (JsonNode command : body) {
            String name = command.path("name").asText("");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /**
     * Returns the ids of the built-in tools known to the OpenCode server ({@code GET /experimental/tool/ids}).
     *
     * @return tool ids in server order
     * @throws IllegalStateException if the request fails or the response is not an array
     */
    public List<String> toolIds() {
        JsonNode body = getJson("/experimental/tool/ids");
        if (!body.isArray()) {
            throw new IllegalStateException("OpenCode /experimental/tool/ids response is not an array");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode id : body) {
            if (id.isTextual() && !id.asText().isBlank()) {
                ids.add(id.asText());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * Runs an OpenCode command in a session ({@code POST /session/{id}/command}). The call is synchronous: it
     * returns when the command's turn has finished, or fails after 10 minutes.
     *
     * @param sessionId session identifier
     * @param command command name without the leading slash
     * @param arguments command arguments (may be empty)
     * @param model provider/model string, or null to omit it
     * @throws IllegalStateException if the request fails or returns a non-200 status
     */
    public void runCommand(String sessionId, String command, String arguments, String model) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("command", command);
        body.put("arguments", arguments == null ? "" : arguments);
        if (model != null && !model.isBlank()) {
            body.put("model", model);
        }
        postJson("/session/" + sessionId + "/command", body, COMMAND_TIMEOUT, 200);
    }

    /**
     * Connects to OpenCode global event stream and emits parsed raw events.
     *
     * @param onEvent callback invoked per event
     */
    public void connectEvents(Consumer<OpenCodeRawEvent> onEvent) {
        startEventStream(onEvent, throwable -> {
            throw new IllegalStateException("OpenCode event stream terminated", throwable);
        }, false);
    }

    /**
     * Connects to OpenCode global event stream and emits parsed raw events.
     *
     * <p>{@code onError} is called once when the stream fails or ends (end of stream is reported as an
     * {@link IllegalStateException}), unless the returned handle was closed first.
     *
     * @param onEvent callback invoked per event
     * @param onError callback invoked when stream setup or parsing fails, or when the stream ends
     * @return handle that stops the stream; after closing it {@code onError} is not called
     */
    public EventStream connectEvents(Consumer<OpenCodeRawEvent> onEvent, Consumer<Throwable> onError) {
        return startEventStream(onEvent, onError, true);
    }

    private EventStream startEventStream(Consumer<OpenCodeRawEvent> onEvent,
                                         Consumer<Throwable> onError,
                                         boolean reportEndOfStream) {
        Objects.requireNonNull(onEvent, "onEvent");
        Objects.requireNonNull(onError, "onError");
        StreamHandle handle = new StreamHandle();
        Thread thread = Thread.ofVirtual().name("opencode-events").unstarted(() -> {
            try {
                streamEvents(onEvent, handle);
                if (reportEndOfStream && !handle.closed) {
                    onError.accept(new IllegalStateException("OpenCode event stream ended"));
                }
            } catch (Throwable throwable) {
                if (!handle.closed) {
                    onError.accept(throwable);
                }
            }
        });
        handle.thread = thread;
        thread.start();
        return handle;
    }

    private void streamEvents(Consumer<OpenCodeRawEvent> onEvent, StreamHandle handle) {
        try {
            HttpResponse<java.io.InputStream> response = openEventStreamResponse();
            if (!handle.attach(response.body())) {
                return;
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Failed to connect OpenCode events: HTTP " + response.statusCode());
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                parseSseEvents(reader, event -> {
                    if (!handle.closed) {
                        onEvent.accept(event);
                    }
                });
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stream OpenCode events", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while streaming OpenCode events", e);
        }
    }

    /**
     * Handle to a running OpenCode event stream.
     */
    public interface EventStream extends AutoCloseable {

        /**
         * Stops the stream. After this call the stream's error callback is not invoked.
         */
        @Override
        void close();
    }

    private static final class StreamHandle implements EventStream {

        private volatile boolean closed;
        private volatile Thread thread;
        private java.io.InputStream body;

        /**
         * Registers the response body; returns false (and closes it) if the handle is already closed.
         */
        synchronized boolean attach(java.io.InputStream responseBody) {
            if (closed) {
                closeQuietly(responseBody);
                return false;
            }
            body = responseBody;
            return true;
        }

        @Override
        public void close() {
            java.io.InputStream toClose;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                toClose = body;
            }
            if (toClose != null) {
                closeQuietly(toClose);
            } else if (thread != null) {
                // Still connecting: interrupt the pending send so the thread exits promptly.
                thread.interrupt();
            }
        }

        private static void closeQuietly(java.io.InputStream stream) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Closing is best effort.
            }
        }
    }

    private HttpResponse<java.io.InputStream> openEventStreamResponse() throws IOException, InterruptedException {
        HttpResponse<java.io.InputStream> primaryResponse = httpClient.send(
                eventStreamRequest("/event"),
                HttpResponse.BodyHandlers.ofInputStream());

        int primaryStatusCode = primaryResponse.statusCode();
        if (primaryStatusCode != 404 && primaryStatusCode != 405) {
            return primaryResponse;
        }

        primaryResponse.body().close();

        return httpClient.send(
                eventStreamRequest("/global/event"),
                HttpResponse.BodyHandlers.ofInputStream());
    }

    private HttpRequest eventStreamRequest(String path) {
        return request(path)
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

    private JsonNode getJson(String path) {
        HttpRequest request = request(path)
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
        return postJson(path, body, Duration.ofSeconds(30), okStatuses);
    }

    private JsonNode postJson(String path, JsonNode body, Duration timeout, int... okStatuses) {
        HttpRequest request = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(timeout)
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
