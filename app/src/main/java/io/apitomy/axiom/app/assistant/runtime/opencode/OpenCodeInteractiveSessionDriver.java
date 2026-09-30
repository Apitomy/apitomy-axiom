package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.SessionCompatibilityException;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Interactive session driver for OpenCode-backed assistant sessions.
 */
public final class OpenCodeInteractiveSessionDriver implements InteractiveSessionDriver {

    private static final Logger LOG = Logger.getLogger(OpenCodeInteractiveSessionDriver.class);

    private final ServerProcessHandle serverProcess;
    private final CapabilityProbe capabilityProbe;
    private final OpenCodeEventNormalizer normalizer;
    private final Consumer<SseEvent> eventSink;
    private final Consumer<SseEvent> autoApprovalSink;
    private final EventStreamConnector eventStreamConnector;
    private final String sessionTitle;
    private final String templateModel;
    private final String fallbackModel;
    private final JsonNode tools;
    private final Set<String> expectedMcpServers;
    private final String systemPrompt;

    private final AtomicReference<String> errorMessage = new AtomicReference<>();
    private final AtomicReference<AssistantSession.Status> status;
    private final Set<String> userMessageIds = ConcurrentHashMap.newKeySet();

    private volatile OpenCodeAssistantClient client;
    private volatile String openCodeSessionId;
    /** Effective model resolved at start; null means OpenCode's own default. */
    private volatile String model;

    /**
     * Creates an OpenCode interactive session driver without expected MCP servers.
     *
     * @param serverProcess OpenCode session server handle
     * @param capabilityProbe OpenCode capability probe
     * @param normalizer event normalizer
     * @param eventSink sink for non-permission events
     * @param autoApprovalSink sink for permission_request events
     * @param sessionTitle title used when creating OpenCode sessions
     * @param model model in provider/model format
     * @param tools optional tools payload for prompt submissions
     */
    public OpenCodeInteractiveSessionDriver(ServerProcessHandle serverProcess,
                                            CapabilityProbe capabilityProbe,
                                            OpenCodeEventNormalizer normalizer,
                                            Consumer<SseEvent> eventSink,
                                            Consumer<SseEvent> autoApprovalSink,
                                            String sessionTitle,
                                            String model,
                                            JsonNode tools) {
        this(serverProcess, capabilityProbe, normalizer, eventSink, autoApprovalSink,
                sessionTitle, model, tools, Set.of());
    }

    /**
     * Creates an OpenCode interactive session driver.
     *
     * @param serverProcess OpenCode session server handle
     * @param capabilityProbe OpenCode capability probe
     * @param normalizer event normalizer
     * @param eventSink sink for non-permission events
     * @param autoApprovalSink sink for permission_request events
     * @param sessionTitle title used when creating OpenCode sessions
     * @param model model in provider/model format
     * @param tools optional tools payload for prompt submissions
     * @param expectedMcpServers names of MCP servers configured for the session; a warning is emitted
     *                           for each one that OpenCode does not report as connected
     */
    public OpenCodeInteractiveSessionDriver(ServerProcessHandle serverProcess,
                                            CapabilityProbe capabilityProbe,
                                            OpenCodeEventNormalizer normalizer,
                                            Consumer<SseEvent> eventSink,
                                            Consumer<SseEvent> autoApprovalSink,
                                            String sessionTitle,
                                            String model,
                                            JsonNode tools,
                                            Set<String> expectedMcpServers) {
        this(serverProcess, capabilityProbe, normalizer, eventSink, autoApprovalSink,
                new SessionSettings(sessionTitle, model, tools, expectedMcpServers, null, null));
    }

    /** Canonical constructor; all other constructors delegate here (package-private for test injection). */
    /**
     * Creates an OpenCode interactive session driver.
     *
     * @param serverProcess OpenCode session server handle
     * @param capabilityProbe OpenCode capability probe
     * @param normalizer event normalizer
     * @param eventSink sink for non-permission events
     * @param autoApprovalSink sink for permission_request events
     * @param settings per-session settings
     */
    public OpenCodeInteractiveSessionDriver(ServerProcessHandle serverProcess,
                                            CapabilityProbe capabilityProbe,
                                            OpenCodeEventNormalizer normalizer,
                                            Consumer<SseEvent> eventSink,
                                            Consumer<SseEvent> autoApprovalSink,
                                            SessionSettings settings) {
        this(serverProcess, capabilityProbe, normalizer, eventSink, autoApprovalSink,
                OpenCodeAssistantClient::connectEvents, settings);
    }

    OpenCodeInteractiveSessionDriver(ServerProcessHandle serverProcess,
                                     CapabilityProbe capabilityProbe,
                                     OpenCodeEventNormalizer normalizer,
                                     Consumer<SseEvent> eventSink,
                                     Consumer<SseEvent> autoApprovalSink,
                                     EventStreamConnector eventStreamConnector,
                                     String sessionTitle,
                                     String model,
                                     JsonNode tools) {
        this(serverProcess, capabilityProbe, normalizer, eventSink, autoApprovalSink,
                eventStreamConnector, sessionTitle, model, tools, Set.of());
    }

    OpenCodeInteractiveSessionDriver(ServerProcessHandle serverProcess,
                                     CapabilityProbe capabilityProbe,
                                     OpenCodeEventNormalizer normalizer,
                                     Consumer<SseEvent> eventSink,
                                     Consumer<SseEvent> autoApprovalSink,
                                     EventStreamConnector eventStreamConnector,
                                     String sessionTitle,
                                     String model,
                                     JsonNode tools,
                                     Set<String> expectedMcpServers) {
        this(serverProcess, capabilityProbe, normalizer, eventSink, autoApprovalSink, eventStreamConnector,
                new SessionSettings(sessionTitle, model, tools, expectedMcpServers, null, null));
    }

    OpenCodeInteractiveSessionDriver(ServerProcessHandle serverProcess,
                                     CapabilityProbe capabilityProbe,
                                     OpenCodeEventNormalizer normalizer,
                                     Consumer<SseEvent> eventSink,
                                     Consumer<SseEvent> autoApprovalSink,
                                     EventStreamConnector eventStreamConnector,
                                     SessionSettings settings) {
        Objects.requireNonNull(settings, "settings");
        this.serverProcess = Objects.requireNonNull(serverProcess, "serverProcess");
        this.capabilityProbe = Objects.requireNonNull(capabilityProbe, "capabilityProbe");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.autoApprovalSink = Objects.requireNonNull(autoApprovalSink, "autoApprovalSink");
        this.eventStreamConnector = Objects.requireNonNull(eventStreamConnector, "eventStreamConnector");
        this.sessionTitle = settings.sessionTitle();
        this.templateModel = settings.model();
        this.fallbackModel = settings.fallbackModel();
        this.model = settings.model();
        this.tools = settings.tools();
        this.expectedMcpServers = settings.expectedMcpServers();
        this.systemPrompt = settings.systemPrompt();
        this.status = new AtomicReference<>(AssistantSession.Status.STARTING);
    }

    @Override
    public synchronized void start() throws IOException {
        if (status.get() == AssistantSession.Status.RUNNING) {
            return;
        }

        status.set(AssistantSession.Status.STARTING);

        try {
            serverProcess.start();
            client = new OpenCodeAssistantClient(serverProcess.baseUrl());
            OpenCodeCapabilityProbe.Result result = capabilityProbe.probe(client);
            if (!result.compatible()) {
                status.set(AssistantSession.Status.ERROR);
                errorMessage.set(result.message());
                throw new SessionCompatibilityException(result.code(), result.message(), result.details());
            }

            openCodeSessionId = client.createSession(sessionTitle);
            if (openCodeSessionId == null || openCodeSessionId.isBlank()) {
                status.set(AssistantSession.Status.ERROR);
                errorMessage.set("OpenCode session creation did not return an id");
                throw new IOException("OpenCode session creation did not return an id");
            }

            eventStreamConnector.connect(
                    client,
                    raw -> handleRawEvent(raw.eventName(), raw.payload()),
                    this::handleStreamFailure
            );
            status.compareAndSet(AssistantSession.Status.STARTING, AssistantSession.Status.RUNNING);
            // The model is resolved right after RUNNING; callers only send prompts once start() has returned,
            // so every prompt sees the effective model.
            resolveModelSafely();
            reportMcpServerStatus();
        } catch (SessionCompatibilityException e) {
            safeStopServer();
            throw e;
        } catch (RuntimeException e) {
            status.set(AssistantSession.Status.ERROR);
            errorMessage.set(e.getMessage());
            safeStopServer();
            throw new IOException("Failed to start OpenCode interactive session", e);
        }
    }

    private void resolveModelSafely() {
        try {
            resolveModel();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to resolve OpenCode session model");
        }
    }

    private void resolveModel() {
        String preferred = isBlank(templateModel) ? fallbackModel : templateModel;
        OpenCodeAssistantClient.ProviderCatalog catalog;
        try {
            catalog = client.providerCatalog();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Unable to read OpenCode providers; using model '%s' without validation", preferred);
            model = isBlank(preferred) ? null : preferred.trim();
            emitSessionInit(model);
            return;
        }
        List<String> rejected = new ArrayList<>();
        String effective = null;
        for (String candidate : modelCandidates()) {
            if (catalog.contains(candidate)) {
                effective = candidate;
                break;
            }
            rejected.add(candidate);
        }
        model = effective;
        if (!rejected.isEmpty()) {
            String using = effective != null ? "'" + effective + "'" : "OpenCode's default model";
            String subject = rejected.size() == 1 ? "Model " : "Models ";
            String verb = rejected.size() == 1 ? " is" : " are";
            emitWarning("ModelUnavailable", subject + String.join(", ", quote(rejected)) + verb
                    + " not available in OpenCode (expected provider/model from the configured providers); using "
                    + using + ".");
        }
        emitSessionInit(effective);
    }

    private List<String> modelCandidates() {
        List<String> candidates = new ArrayList<>();
        if (!isBlank(templateModel)) {
            candidates.add(templateModel.trim());
        }
        if (!isBlank(fallbackModel) && !candidates.contains(fallbackModel.trim())) {
            candidates.add(fallbackModel.trim());
        }
        return candidates;
    }

    private void emitSessionInit(String displayedModel) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("model", displayedModel == null ? "" : displayedModel);
        data.put("engine", "opencode");
        eventSink.accept(new SseEvent("session_init", data));
    }

    private static List<String> quote(List<String> values) {
        return values.stream().map(value -> "'" + value + "'").toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void reportMcpServerStatus() {
        try {
            doReportMcpServerStatus();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to report OpenCode MCP server status");
        }
    }

    private void doReportMcpServerStatus() {
        if (expectedMcpServers.isEmpty()) {
            return;
        }
        Map<String, OpenCodeAssistantClient.McpServerStatus> statuses;
        try {
            statuses = client.mcpStatus();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Unable to query OpenCode MCP server status");
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            emitWarning("McpServerUnavailable", "Unable to verify MCP server status: " + reason);
            return;
        }
        for (String name : new TreeSet<>(expectedMcpServers)) {
            OpenCodeAssistantClient.McpServerStatus serverStatus = statuses.get(name);
            if (serverStatus == null) {
                emitWarning("McpServerUnavailable", "MCP server '" + name + "' was not loaded by OpenCode");
            } else if (!serverStatus.connected()) {
                String message = "MCP server '" + name + "' is unavailable (" + serverStatus.status() + ")";
                if (serverStatus.error() != null && !serverStatus.error().isBlank()) {
                    message += ": " + serverStatus.error();
                }
                emitWarning("McpServerUnavailable", message);
            }
        }
    }

    private void emitWarning(String name, String message) {
        LOG.warn(message);
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("name", name);
        data.put("message", message);
        eventSink.accept(new SseEvent("session_error", data));
    }

    @Override
    public void sendUserMessage(String message) throws IOException {
        // OpenCode queues prompts natively: a prompt posted while the session is busy is answered after the
        // current turn (verified on opencode 1.18.33), so no client-side turn guard is needed. Aborting the
        // session discards prompts that are still queued: they are stored but never answered, and opencode
        // emits one session.idle per aborted/discarded turn (also verified on opencode 1.18.33).
        ensureRunning();
        try {
            client.sendPromptAsync(openCodeSessionId, message, model, tools, systemPrompt);
        } catch (RuntimeException e) {
            String detail = e.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = e.getClass().getSimpleName();
            }
            throw new IOException("Failed to submit OpenCode prompt: " + detail, e);
        }
    }

    @Override
    public void respondToPermission(String permissionId, boolean allow, JsonNode toolInput) throws IOException {
        ensureRunning();
        try {
            client.respondPermission(openCodeSessionId, permissionId, allow);
        } catch (RuntimeException e) {
            throw new IOException("Failed to respond to OpenCode permission request", e);
        }
    }

    @Override
    public void interrupt() {
        OpenCodeAssistantClient localClient = client;
        String localSessionId = openCodeSessionId;
        if (localClient == null || localSessionId == null || localSessionId.isBlank()) {
            return;
        }
        try {
            localClient.abort(localSessionId);
        } catch (RuntimeException e) {
            status.set(AssistantSession.Status.ERROR);
            errorMessage.set(e.getMessage());
            LOG.warnf(e, "Failed to interrupt OpenCode session %s", localSessionId);
        }
    }

    @Override
    public synchronized void destroy() {
        userMessageIds.clear();
        openCodeSessionId = null;
        safeStopServer();
        status.updateAndGet(currentStatus ->
                currentStatus == AssistantSession.Status.ERROR
                        ? AssistantSession.Status.ERROR
                        : AssistantSession.Status.STOPPED);
    }

    @Override
    public boolean isAlive() {
        return serverProcess.isAlive();
    }

    @Override
    public AssistantSession.Status getStatus() {
        return status.get();
    }

    @Override
    public String getErrorMessage() {
        return errorMessage.get();
    }

    private void handleRawEvent(String eventName, JsonNode payload) {
        if (!isCurrentSessionEvent(payload)) {
            return;
        }

        rememberUserMessageId(eventName, payload);
        if (isEchoedUserMessagePart(eventName, payload)) {
            return;
        }

        List<SseEvent> normalizedEvents = normalizer.normalize(eventName, payload);
        for (SseEvent normalizedEvent : normalizedEvents) {
            if ("permission_request".equals(normalizedEvent.type())) {
                autoApprovalSink.accept(normalizedEvent);
            } else {
                eventSink.accept(normalizedEvent);
            }
        }
    }

    private void rememberUserMessageId(String eventName, JsonNode payload) {
        String payloadType = payload.path("type").asText(eventName == null ? "" : eventName);
        if (!"message.updated".equals(payloadType)) {
            return;
        }

        JsonNode info = payload.path("properties").path("info");
        if (!info.isObject()) {
            return;
        }

        String role = info.path("role").asText("");
        String messageId = info.path("id").asText("");
        if ("user".equals(role) && !messageId.isBlank()) {
            userMessageIds.add(messageId);
        }
    }

    private boolean isEchoedUserMessagePart(String eventName, JsonNode payload) {
        String payloadType = payload.path("type").asText(eventName == null ? "" : eventName);
        if (!"message.part.updated".equals(payloadType)) {
            return false;
        }

        JsonNode part = payload.path("properties").path("part");
        if (!part.isObject()) {
            return false;
        }
        String messageId = part.path("messageID").asText("");
        if (messageId.isBlank()) {
            messageId = part.path("messageId").asText("");
        }
        return !messageId.isBlank() && userMessageIds.contains(messageId);
    }

    private boolean isCurrentSessionEvent(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return false;
        }
        String currentSessionId = openCodeSessionId;
        if (currentSessionId == null || currentSessionId.isBlank()) {
            return false;
        }

        String eventSessionId = payload.path("sessionID").asText("");
        if (eventSessionId.isEmpty()) {
            eventSessionId = payload.path("sessionId").asText("");
        }
        if (eventSessionId.isEmpty()) {
            eventSessionId = payload.path("session").path("id").asText("");
        }
        if (eventSessionId.isEmpty()) {
            JsonNode properties = payload.path("properties");
            if (properties.isObject()) {
                eventSessionId = properties.path("sessionID").asText("");
                if (eventSessionId.isEmpty()) {
                    eventSessionId = properties.path("sessionId").asText("");
                }
                if (eventSessionId.isEmpty()) {
                    eventSessionId = properties.path("session").path("id").asText("");
                }
                if (eventSessionId.isEmpty()) {
                    eventSessionId = properties.path("part").path("sessionID").asText("");
                }
                if (eventSessionId.isEmpty()) {
                    eventSessionId = properties.path("part").path("sessionId").asText("");
                }
            }
        }
        if (eventSessionId.isEmpty()) {
            return false;
        }
        return currentSessionId.equals(eventSessionId);
    }

    private void handleStreamFailure(Throwable throwable) {
        String message = throwable != null && throwable.getMessage() != null
                ? throwable.getMessage()
                : "OpenCode event stream failed";
        status.set(AssistantSession.Status.ERROR);
        errorMessage.set(message);

        com.fasterxml.jackson.databind.node.ObjectNode terminalData =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        terminalData.put("status", AssistantSession.Status.ERROR.name());
        terminalData.put("message", message);
        eventSink.accept(new SseEvent("session_ended", terminalData));
    }

    private void ensureRunning() {
        if (status.get() != AssistantSession.Status.RUNNING || client == null || openCodeSessionId == null) {
            throw new IllegalStateException("OpenCode interactive session is not running");
        }
    }

    /**
     * Per-session settings applied to the OpenCode session and its prompts.
     *
     * @param sessionTitle title used when creating the OpenCode session
     * @param model model in provider/model format, or null for OpenCode's default
     * @param tools optional tools payload for prompt submissions
     * @param expectedMcpServers names of MCP servers configured for the session; a warning is emitted for each one
     *                           that OpenCode does not report as connected
     * @param systemPrompt system prompt sent with every prompt, or null/blank for none
     * @param fallbackModel configured default model in provider/model format, used when {@code model} is blank or
     *                      unavailable; null for none
     */
    public record SessionSettings(String sessionTitle,
                                  String model,
                                  JsonNode tools,
                                  Set<String> expectedMcpServers,
                                  String systemPrompt,
                                  String fallbackModel) {

        /**
         * Normalizes a null MCP server set to an empty set and copies non-null sets.
         */
        public SessionSettings {
            expectedMcpServers = expectedMcpServers != null ? Set.copyOf(expectedMcpServers) : Set.of();
        }
    }

    @FunctionalInterface
    interface EventStreamConnector {

        void connect(OpenCodeAssistantClient openCodeAssistantClient,
                     Consumer<OpenCodeAssistantClient.OpenCodeRawEvent> onEvent,
                     Consumer<Throwable> onError);
    }

    private void safeStopServer() {
        try {
            serverProcess.stop();
        } catch (RuntimeException e) {
            LOG.debugf(e, "Ignoring OpenCode server stop failure");
        }
    }

    /**
     * Lightweight bridge used by tests and factory wiring.
     */
    @FunctionalInterface
    public interface CapabilityProbe {

        /**
         * Probes compatibility for a configured OpenCode client.
         *
         * @param openCodeAssistantClient client for probe operations
         * @return probe result
         */
        OpenCodeCapabilityProbe.Result probe(OpenCodeAssistantClient openCodeAssistantClient);
    }

    /**
     * Lifecycle abstraction around the OpenCode session server process.
     */
    public interface ServerProcessHandle {

        /**
         * Starts the backing server process.
         */
        void start();

        /**
         * Stops the backing server process.
         */
        void stop();

        /**
         * @return true when the backing server process is alive
         */
        boolean isAlive();

        /**
         * @return session server base URL
         */
        String baseUrl();
    }

    /**
     * Adapter for using {@link OpenCodeSessionServerProcess} as a server handle.
     */
    public static final class ServerProcessAdapter implements ServerProcessHandle {

        private final OpenCodeSessionServerProcess delegate;

        /**
         * @param delegate underlying OpenCode process manager
         */
        public ServerProcessAdapter(OpenCodeSessionServerProcess delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public boolean isAlive() {
            return delegate.isAlive();
        }

        @Override
        public String baseUrl() {
            return delegate.baseUrl();
        }
    }
}
