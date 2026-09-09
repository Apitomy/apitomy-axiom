package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.SessionCompatibilityException;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final String sessionTitle;
    private final String model;
    private final JsonNode tools;

    private final AtomicBoolean turnInFlight = new AtomicBoolean(false);
    private final AtomicReference<String> errorMessage = new AtomicReference<>();

    private volatile OpenCodeAssistantClient client;
    private volatile String openCodeSessionId;
    private volatile AssistantSession.Status status;

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
     */
    public OpenCodeInteractiveSessionDriver(ServerProcessHandle serverProcess,
                                            CapabilityProbe capabilityProbe,
                                            OpenCodeEventNormalizer normalizer,
                                            Consumer<SseEvent> eventSink,
                                            Consumer<SseEvent> autoApprovalSink,
                                            String sessionTitle,
                                            String model,
                                            JsonNode tools) {
        this.serverProcess = Objects.requireNonNull(serverProcess, "serverProcess");
        this.capabilityProbe = Objects.requireNonNull(capabilityProbe, "capabilityProbe");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.autoApprovalSink = Objects.requireNonNull(autoApprovalSink, "autoApprovalSink");
        this.sessionTitle = sessionTitle;
        this.model = model;
        this.tools = tools;
        this.status = AssistantSession.Status.STARTING;
    }

    @Override
    public synchronized void start() throws IOException {
        if (status == AssistantSession.Status.RUNNING) {
            return;
        }

        try {
            serverProcess.start();
            client = new OpenCodeAssistantClient(serverProcess.baseUrl());
            OpenCodeCapabilityProbe.Result result = capabilityProbe.probe(client);
            if (!result.compatible()) {
                status = AssistantSession.Status.ERROR;
                errorMessage.set(result.message());
                throw new SessionCompatibilityException(result.code(), result.message(), result.details());
            }

            openCodeSessionId = client.createSession(sessionTitle);
            if (openCodeSessionId == null || openCodeSessionId.isBlank()) {
                status = AssistantSession.Status.ERROR;
                errorMessage.set("OpenCode session creation did not return an id");
                throw new IOException("OpenCode session creation did not return an id");
            }

            client.connectEvents(raw -> handleRawEvent(raw.eventName(), raw.payload()));
            status = AssistantSession.Status.RUNNING;
        } catch (SessionCompatibilityException e) {
            safeStopServer();
            throw e;
        } catch (RuntimeException e) {
            status = AssistantSession.Status.ERROR;
            errorMessage.set(e.getMessage());
            safeStopServer();
            throw new IOException("Failed to start OpenCode interactive session", e);
        }
    }

    @Override
    public void sendUserMessage(String message) throws IOException {
        ensureRunning();
        if (!turnInFlight.compareAndSet(false, true)) {
            throw new IllegalStateException("A turn is already in flight");
        }

        try {
            client.sendPromptAsync(openCodeSessionId, message, model, tools);
        } catch (RuntimeException e) {
            turnInFlight.set(false);
            throw new IOException("Failed to submit OpenCode prompt", e);
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
            turnInFlight.set(false);
        } catch (RuntimeException e) {
            status = AssistantSession.Status.ERROR;
            errorMessage.set(e.getMessage());
            LOG.warnf(e, "Failed to interrupt OpenCode session %s", localSessionId);
        }
    }

    @Override
    public synchronized void destroy() {
        turnInFlight.set(false);
        openCodeSessionId = null;
        safeStopServer();
        if (status != AssistantSession.Status.ERROR) {
            status = AssistantSession.Status.STOPPED;
        }
    }

    @Override
    public boolean isAlive() {
        return serverProcess.isAlive();
    }

    @Override
    public AssistantSession.Status getStatus() {
        return status;
    }

    @Override
    public String getErrorMessage() {
        return errorMessage.get();
    }

    private void handleRawEvent(String eventName, JsonNode payload) {
        if (!isCurrentSessionEvent(payload)) {
            return;
        }
        List<SseEvent> normalizedEvents = normalizer.normalize(eventName, payload);
        for (SseEvent normalizedEvent : normalizedEvents) {
            if ("turn_complete".equals(normalizedEvent.type())) {
                turnInFlight.set(false);
            }
            if ("permission_request".equals(normalizedEvent.type())) {
                autoApprovalSink.accept(normalizedEvent);
            } else {
                eventSink.accept(normalizedEvent);
            }
        }
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
            return true;
        }
        return currentSessionId.equals(eventSessionId);
    }

    private void ensureRunning() {
        if (status != AssistantSession.Status.RUNNING || client == null || openCodeSessionId == null) {
            throw new IllegalStateException("OpenCode interactive session is not running");
        }
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
