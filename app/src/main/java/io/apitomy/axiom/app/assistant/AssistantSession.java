package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import io.apitomy.axiom.app.assistant.runtime.ClaudeInteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Manages an interactive Assistant session runtime and replayable event history.
 *
 * <p>The session can run either with a direct runtime implementation (legacy Claude subprocess mode)
 * or by delegating runtime operations to an injected {@link InteractiveSessionDriver}. All emitted
 * events are buffered so reconnecting SSE clients can replay full history.</p>
 */
public class AssistantSession {

    private static final Logger LOG = Logger.getLogger(AssistantSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RAW_EVENTS_FILE = "raw-events.jsonl";

    /** Possible session states. */
    public enum Status { STARTING, RUNNING, STOPPED, ERROR }

    private final String id;
    private volatile String name;
    private final String templateId;
    private final Path sessionDirectory;
    private final Path workingDirectory;
    private final List<String> command;
    private final Map<String, String> environment;
    private final InteractiveSessionDriver driver;

    private volatile Instant lastActivityAt;
    private final Instant createdAt;

    private final DoubleAdder totalCostUsd = new DoubleAdder();
    private final AtomicLong totalInputTokens = new AtomicLong();
    private final AtomicLong totalOutputTokens = new AtomicLong();
    private final AtomicLong totalDurationMs = new AtomicLong();
    private final AtomicInteger turnCount = new AtomicInteger();

    private final CopyOnWriteArrayList<SseEvent> eventHistory = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<SseEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Object eventLock = new Object();
    private final CopyOnWriteArrayList<AutoApprovalRule> autoApprovalRules = new CopyOnWriteArrayList<>();
    private volatile boolean allowAll;
    private final Set<String> subagentAllowAll = ConcurrentHashMap.newKeySet();
    private final Map<String, String> subagentTaskToToolUseId = new ConcurrentHashMap<>();
    private final Long projectId;
    private final String projectName;

    /**
     * A session-scoped rule for automatically approving tool permissions.
     *
     * @param id unique identifier for management
     * @param toolName the tool this rule applies to
     * @param fieldName the input field to match against (e.g., "command", "file_path"), or null for tool-name-only matching
     * @param pattern regex pattern string to match against the field value
     * @param compiledPattern the compiled regex
     * @param createdAt when the rule was created
     */
    public record AutoApprovalRule(String id, String toolName, String fieldName,
                                    String pattern, Pattern compiledPattern,
                                    Instant createdAt) {
    }

    /**
     * Creates a new assistant session.
     *
     * @param name the user-visible session name
     * @param templateId the template this session was created from
     * @param sessionDirectory the Axiom-managed session directory (always deleted on end)
     * @param workingDirectory the assistant runtime working directory
     * @param command the legacy command line for direct subprocess mode
     * @param environment resolved environment variables for direct subprocess mode
     * @param projectId optional project ID if session is scoped to a project
     * @param projectName optional project name if session is scoped to a project
     */
    public AssistantSession(String name, String templateId, Path sessionDirectory,
                             Path workingDirectory, List<String> command,
                             Map<String, String> environment, Long projectId,
                             String projectName) {
        this(name, templateId, sessionDirectory, workingDirectory, command,
                environment, projectId, projectName, null);
    }

    /**
     * Creates a new assistant session with an injected runtime driver.
     *
     * @param name the user-visible session name
     * @param templateId the template this session was created from
     * @param sessionDirectory the Axiom-managed session directory (always deleted on end)
     * @param workingDirectory the assistant working directory
     * @param command the legacy command line used by Claude runtime mode
     * @param environment resolved environment variables used by Claude runtime mode
     * @param projectId optional project ID if session is scoped to a project
     * @param projectName optional project name if session is scoped to a project
     * @param driver runtime driver used for interactive session I/O
     */
    public AssistantSession(String name, String templateId, Path sessionDirectory,
                             Path workingDirectory, List<String> command,
                             Map<String, String> environment, Long projectId,
                             String projectName, InteractiveSessionDriver driver) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.templateId = templateId;
        this.sessionDirectory = sessionDirectory;
        this.workingDirectory = workingDirectory;
        this.command = command;
        this.environment = environment;
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
        this.projectId = projectId;
        this.projectName = projectName;
        AssistantEventParser eventParser = new AssistantEventParser();
        this.driver = driver != null
                ? driver
                : new ClaudeInteractiveSessionDriver(
                        workingDirectory,
                        sessionDirectory,
                        command,
                        environment,
                        eventParser,
                        this::handleDriverEvent,
                        this::handlePermissionEvent
                );
    }

    /**
     * Starts the session runtime.
     *
     * @throws IOException if runtime startup fails
     */
    public void start() throws IOException {
        driver.start();
        lastActivityAt = Instant.now();
    }

    /**
     * Sends a user message to the runtime. The message is recorded in event history so it can be
     * replayed on reconnect.
     *
     * @param message the user's message text
     * @throws IOException if the message cannot be written
     */
    public void sendMessage(String message) throws IOException {
        // Record the user message in event history for replay
        ObjectNode userData = MAPPER.createObjectNode();
        userData.put("content", message);
        addEvent(new SseEvent("user_message", userData));

        driver.sendUserMessage(message);
        lastActivityAt = Instant.now();
    }

    /**
     * Adds a synthetic event to the event history and dispatches it to listeners.
     * Used for events not produced by the Claude Code subprocess (e.g., user
     * messages, welcome messages).
     *
     * @param event the event to add
     */
    public void addEvent(SseEvent event) {
        synchronized (eventLock) {
            eventHistory.add(event);
            lastActivityAt = Instant.now();
            for (Consumer<SseEvent> listener : listeners) {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    LOG.warnf(e, "SSE listener error in session %s", id);
                }
            }
        }
    }

    /**
     * Responds to a runtime permission prompt.
     *
     * @param permissionId the permission request ID
     * @param allow whether to allow (true) or deny (false) the tool call
     * @param toolInput runtime tool input payload associated with the permission request
     * @throws IOException if the response cannot be written
     */
    public void respondToPermission(String permissionId, boolean allow,
                                      com.fasterxml.jackson.databind.JsonNode toolInput)
            throws IOException {
        // Record the resolution in event history for replay
        ObjectNode resolvedData = MAPPER.createObjectNode();
        resolvedData.put("permissionId", permissionId);
        resolvedData.put("allow", allow);
        addEvent(new SseEvent("permission_resolved", resolvedData));

        driver.respondToPermission(permissionId, allow, toolInput);
        lastActivityAt = Instant.now();
    }

    /**
     * Registers a listener that will receive all new SSE events.
     *
     * @param listener the event consumer
     */
    public void addListener(Consumer<SseEvent> listener) {
        listeners.add(listener);
    }

    /**
     * Atomically snapshots the event history and registers a listener so that
     * no events are lost between replay and live streaming.
     *
     * @param listener the event consumer to register
     * @return the event history snapshot to replay
     */
    public List<SseEvent> addListenerWithHistory(Consumer<SseEvent> listener) {
        return addListenerWithHistory(listener, -1);
    }

    /**
     * Atomically snapshots the event history since a given event index and
     * registers a listener. Used for SSE reconnect with {@code Last-Event-Id}.
     *
     * <p>If {@code sinceId} exceeds the current history size (e.g., because the
     * history was cleared by a {@code conversation_reset}), the full current
     * history is returned so the client can catch up from the reset.</p>
     *
     * @param listener the event consumer to register
     * @param sinceId the last event index the client received, or -1 to replay all
     * @return the event history snapshot to replay (events after sinceId)
     */
    public List<SseEvent> addListenerWithHistory(Consumer<SseEvent> listener, long sinceId) {
        synchronized (eventLock) {
            int fromIndex = (sinceId + 1 > eventHistory.size())
                    ? 0
                    : (int) (sinceId + 1);
            if (fromIndex < 0) {
                fromIndex = 0;
            }
            List<SseEvent> snapshot = List.copyOf(
                    eventHistory.subList(fromIndex, eventHistory.size()));
            listeners.add(listener);
            return snapshot;
        }
    }

    /**
     * Removes a previously registered listener.
     *
     * @param listener the event consumer to remove
     */
    public void removeListener(Consumer<SseEvent> listener) {
        listeners.remove(listener);
    }

    /**
     * Returns the buffered event history for replay on SSE reconnect.
     *
     * @return an unmodifiable snapshot of all events emitted so far
     */
    public List<SseEvent> getEventHistory() {
        return List.copyOf(eventHistory);
    }

    /**
     * Interrupts the current runtime turn while keeping the session alive for further interaction.
     */
    public void interrupt() {
        driver.interrupt();
    }

    /**
     * Destroys the session runtime and marks the session as stopped.
     */
    public void destroy() {
        driver.destroy();
    }

    /**
     * Returns whether the session runtime is still alive.
     *
     * @return true if the runtime is running
     */
    public boolean isAlive() {
        return driver.isAlive();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /**
     * Renames this session.
     *
     * @param name the new session name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the template ID this session was created from.
     *
     * @return the template identifier
     */
    public String getTemplateId() {
        return templateId;
    }

    /**
     * Returns the Axiom-managed session directory. This directory is always
     * deleted when the session ends.
     *
     * @return the session directory path
     */
    public Path getSessionDirectory() {
        return sessionDirectory;
    }

    /**
     * Returns the path to the raw NDJSON event log file.
     *
     * @return the raw events log file path
     */
    public Path getRawEventsFile() {
        return sessionDirectory.resolve(RAW_EVENTS_FILE);
    }

    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    public Status getStatus() {
        return driver.getStatus();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public String getErrorMessage() {
        return driver.getErrorMessage();
    }

    /** Returns the accumulated cost in USD across all turns. */
    public double getTotalCostUsd() {
        return totalCostUsd.sum();
    }

    /** Returns the accumulated input token count across all turns. */
    public long getTotalInputTokens() {
        return totalInputTokens.get();
    }

    /** Returns the accumulated output token count across all turns. */
    public long getTotalOutputTokens() {
        return totalOutputTokens.get();
    }

    /** Returns the accumulated duration in milliseconds across all turns. */
    public long getTotalDurationMs() {
        return totalDurationMs.get();
    }

    /** Returns the number of completed turns. */
    public int getTurnCount() {
        return turnCount.get();
    }

    /**
     * @return the project ID if this session is scoped to a project, null otherwise
     */
    public Long getProjectId() {
        return projectId;
    }

    /**
     * @return the project name if this session is scoped to a project, null otherwise
     */
    public String getProjectName() {
        return projectName;
    }

    /**
     * @return the number of events in the session's event history
     */
    public int getEventCount() {
        return eventHistory.size();
    }

    /**
     * Adds an auto-approval rule for matching tool permissions.
     *
     * @param toolName the tool name to match
     * @param fieldName the input field to match against, or null for tool-name-only
     * @param pattern the regex pattern string
     * @return the created rule
     * @throws PatternSyntaxException if the pattern is invalid
     */
    public AutoApprovalRule addAutoApprovalRule(String toolName, String fieldName,
                                                 String pattern) {
        Pattern compiled = (pattern != null && !pattern.isBlank())
                ? Pattern.compile(pattern) : null;
        AutoApprovalRule rule = new AutoApprovalRule(
                UUID.randomUUID().toString(), toolName, fieldName,
                pattern, compiled, Instant.now());
        autoApprovalRules.add(rule);
        LOG.infof("Added auto-approval rule %s for %s (field=%s, pattern=%s)",
                rule.id(), toolName, fieldName, pattern);
        return rule;
    }

    /**
     * Removes an auto-approval rule by ID.
     *
     * @param ruleId the rule identifier
     * @return true if the rule was found and removed
     */
    public boolean removeAutoApprovalRule(String ruleId) {
        return autoApprovalRules.removeIf(r -> r.id().equals(ruleId));
    }

    /**
     * Returns all active auto-approval rules.
     *
     * @return unmodifiable list of rules
     */
    public List<AutoApprovalRule> getAutoApprovalRules() {
        return List.copyOf(autoApprovalRules);
    }

    /**
     * Returns whether Allow All mode is active for this session.
     *
     * @return true if all permissions are auto-approved
     */
    public boolean isAllowAll() {
        return allowAll;
    }

    /**
     * Enables or disables Allow All mode for this session.
     *
     * @param allowAll true to auto-approve all permissions
     */
    public void setAllowAll(boolean allowAll) {
        this.allowAll = allowAll;
    }

    public boolean isSubagentAllowAll(String subagentToolUseId) {
        return subagentAllowAll.contains(subagentToolUseId);
    }

    public void setSubagentAllowAll(String subagentToolUseId, boolean enabled) {
        if (enabled) {
            subagentAllowAll.add(subagentToolUseId);
        } else {
            subagentAllowAll.remove(subagentToolUseId);
        }
    }

    /**
     * Checks whether a tool permission request matches any auto-approval rule.
     *
     * @param toolName the tool name from the permission request
     * @param toolInput the tool input from the permission request
     * @return true if a matching rule was found
     */
    public boolean checkAutoApproval(String toolName, JsonNode toolInput) {
        for (AutoApprovalRule rule : autoApprovalRules) {
            if (!rule.toolName().equals(toolName)) {
                continue;
            }
            if (rule.fieldName() == null || rule.compiledPattern() == null) {
                return true;
            }
            String fieldValue = toolInput != null
                    ? toolInput.path(rule.fieldName()).asText(null) : null;
            if (fieldValue != null && rule.compiledPattern().matcher(fieldValue).find()) {
                return true;
            }
        }
        return false;
    }

    private void handleDriverEvent(SseEvent event) {
        if ("subagent_started".equals(event.type())) {
            String taskId = event.data().path("taskId").asText("");
            String toolUseId = event.data().path("toolUseId").asText("");
            if (!taskId.isEmpty() && !toolUseId.isEmpty()) {
                subagentTaskToToolUseId.put(taskId, toolUseId);
            }
        }
        if ("turn_complete".equals(event.type())) {
            accumulateCost(event);
        }
        synchronized (eventLock) {
            if ("conversation_reset".equals(event.type())) {
                eventHistory.clear();
            }
            eventHistory.add(event);
            lastActivityAt = Instant.now();
            for (Consumer<SseEvent> listener : listeners) {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    LOG.warnf(e, "SSE listener error in session %s", id);
                }
            }
        }
    }

    private void handlePermissionEvent(SseEvent event) {
        if (handleAutoApproval(event)) {
            return;
        }
        handleDriverEvent(event);
    }

    private boolean handleAutoApproval(SseEvent event) {
        if (!"permission_request".equals(event.type())) {
            return false;
        }
        String toolName = event.data().path("toolName").asText("");
        JsonNode toolInput = event.data().path("toolInput");
        String requestId = event.data().path("requestId").asText("");

        // Allow All short-circuit: auto-approve everything except AskUserQuestion,
        // which requires the user to provide answers rather than just approval.
        if (allowAll && !"AskUserQuestion".equals(toolName)) {
            LOG.infof("Auto-approving %s (allow-all) in session %s", toolName, id);
            try {
                addEvent(event);
                respondToPermission(requestId, true, toolInput);
            } catch (IOException e) {
                LOG.warnf(e, "Failed to auto-approve %s in session %s", toolName, id);
                return false;
            }
            return true;
        }

        // Subagent-scoped allow-all: check subagentToolUseId directly,
        // or resolve agentId (taskId) to toolUseId via the mapping
        String subagentId = event.data().path("subagentToolUseId").asText("");
        if (subagentId.isEmpty()) {
            String agentId = event.data().path("agentId").asText("");
            if (!agentId.isEmpty()) {
                subagentId = subagentTaskToToolUseId.getOrDefault(agentId, "");
            }
        }
        if (!subagentId.isEmpty() && subagentAllowAll.contains(subagentId)
                && !"AskUserQuestion".equals(toolName)) {
            LOG.infof("Auto-approving %s (subagent allow-all for %s) in session %s",
                    toolName, subagentId, id);
            try {
                addEvent(event);
                respondToPermission(requestId, true, toolInput);
            } catch (IOException e) {
                LOG.warnf(e, "Failed to auto-approve %s in session %s", toolName, id);
                return false;
            }
            return true;
        }

        if (!checkAutoApproval(toolName, toolInput)) {
            return false;
        }
        LOG.infof("Auto-approving %s (rule matched) in session %s", toolName, id);
        try {
            respondToPermission(requestId, true, toolInput);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to auto-approve %s in session %s", toolName, id);
            return false;
        }
        return true;
    }

    private void accumulateCost(SseEvent event) {
        totalCostUsd.add(event.data().path("costUsd").asDouble(0));
        totalInputTokens.addAndGet(event.data().path("inputTokens").asLong(0));
        totalOutputTokens.addAndGet(event.data().path("outputTokens").asLong(0));
        totalDurationMs.addAndGet(event.data().path("durationMs").asLong(0));
        turnCount.incrementAndGet();
    }

}
