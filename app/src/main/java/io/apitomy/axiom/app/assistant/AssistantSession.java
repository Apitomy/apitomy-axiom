package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Wraps an interactive Claude Code subprocess using stream-json I/O.
 *
 * <p>Unlike the one-shot {@code ClaudeCodeSubprocess}, this class maintains
 * a long-lived process with bidirectional stdin/stdout communication. User
 * messages and permission responses are written to stdin as JSON lines;
 * NDJSON events are read from stdout and dispatched to registered listeners.</p>
 *
 * <p>All emitted events are buffered so that reconnecting SSE clients can
 * replay the full session history.</p>
 */
public class AssistantSession {

    private static final Logger LOG = Logger.getLogger(AssistantSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Possible session states. */
    public enum Status { STARTING, RUNNING, STOPPED, ERROR }

    private final String id;
    private volatile String name;
    private final String templateId;
    private final Path sessionDirectory;
    private final Path workingDirectory;
    private final List<String> command;
    private final Map<String, String> environment;
    private final AssistantEventParser parser;

    private volatile Process process;
    private volatile OutputStream stdin;
    private volatile Status status;
    private volatile Instant lastActivityAt;
    private final Instant createdAt;
    private final AtomicReference<String> errorMessage = new AtomicReference<>();

    private volatile double totalCostUsd;
    private volatile long totalInputTokens;
    private volatile long totalOutputTokens;
    private volatile long totalDurationMs;
    private volatile int turnCount;

    private final CopyOnWriteArrayList<SseEvent> eventHistory = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<SseEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Object eventLock = new Object();
    private final CopyOnWriteArrayList<AutoApprovalRule> autoApprovalRules = new CopyOnWriteArrayList<>();
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
     * @param workingDirectory the Claude Code working directory
     * @param command the full command line for the Claude Code subprocess
     * @param environment resolved environment variables to inject into the subprocess
     * @param projectId optional project ID if session is scoped to a project
     * @param projectName optional project name if session is scoped to a project
     */
    public AssistantSession(String name, String templateId, Path sessionDirectory,
                             Path workingDirectory, List<String> command,
                             Map<String, String> environment, Long projectId,
                             String projectName) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.templateId = templateId;
        this.sessionDirectory = sessionDirectory;
        this.workingDirectory = workingDirectory;
        this.command = command;
        this.environment = environment;
        this.parser = new AssistantEventParser();
        this.status = Status.STARTING;
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
        this.projectId = projectId;
        this.projectName = projectName;
    }

    /**
     * Starts the Claude Code subprocess and begins reading its output.
     *
     * @throws IOException if the process cannot be started
     */
    public void start() throws IOException {
        LOG.infof("Starting assistant session %s in %s", id, workingDirectory);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory.toFile());
        pb.redirectErrorStream(false);
        if (environment != null && !environment.isEmpty()) {
            pb.environment().putAll(environment);
        }

        process = pb.start();
        stdin = process.getOutputStream();

        // Read stdout (NDJSON) on a virtual thread
        Thread.ofVirtual().name("assistant-stdout-" + id).start(this::readStdout);

        // Read stderr on a virtual thread (logging only)
        Thread.ofVirtual().name("assistant-stderr-" + id).start(this::readStderr);

        // Monitor process exit on a virtual thread
        Thread.ofVirtual().name("assistant-monitor-" + id).start(this::monitorProcess);

        status = Status.RUNNING;
        lastActivityAt = Instant.now();
    }

    /**
     * Sends a user message to the Claude Code subprocess via stdin. The message
     * is also recorded in the event history so it can be replayed on reconnect.
     *
     * @param message the user's message text
     * @throws IOException if the message cannot be written
     */
    public void sendMessage(String message) throws IOException {
        // Record the user message in event history for replay
        ObjectNode userData = MAPPER.createObjectNode();
        userData.put("content", message);
        addEvent(new SseEvent("user_message", userData));

        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "user");
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", "user");
        msg.put("content", message);
        root.set("message", msg);
        writeLine(MAPPER.writeValueAsString(root));
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
     * Responds to a permission prompt from Claude Code.
     *
     * @param permissionId the permission request ID
     * @param allow whether to allow (true) or deny (false) the tool call
     * @param toolInput the original tool input to echo back as updatedInput
     *                  (required by Claude Code when allowing)
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

        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "control_response");
        ObjectNode response = MAPPER.createObjectNode();
        response.put("subtype", "success");
        response.put("request_id", permissionId);
        ObjectNode innerResponse = MAPPER.createObjectNode();
        if (allow) {
            innerResponse.put("behavior", "allow");
            if (toolInput != null) {
                innerResponse.set("updatedInput", toolInput);
            }
        } else {
            innerResponse.put("behavior", "deny");
            innerResponse.put("message", "User denied permission");
        }
        response.set("response", innerResponse);
        root.set("response", response);
        writeLine(MAPPER.writeValueAsString(root));
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
     * @param listener the event consumer to register
     * @param sinceId the last event index the client received, or -1 to replay all
     * @return the event history snapshot to replay (events after sinceId)
     */
    public List<SseEvent> addListenerWithHistory(Consumer<SseEvent> listener, long sinceId) {
        synchronized (eventLock) {
            int fromIndex = (int) Math.min(sinceId + 1, eventHistory.size());
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
     * Sends SIGINT to the Claude Code subprocess to interrupt the current
     * turn, equivalent to pressing ESC in the CLI. The session remains
     * alive for further interaction.
     */
    public void interrupt() {
        if (process != null && process.isAlive()) {
            long pid = process.pid();
            LOG.infof("Interrupting assistant session %s (SIGINT to pid %d)", id, pid);
            try {
                new ProcessBuilder("kill", "-INT", String.valueOf(pid))
                        .start().waitFor();
            } catch (Exception e) {
                LOG.warnf(e, "Failed to send SIGINT to session %s", id);
            }
        }
    }

    /**
     * Kills the subprocess and marks the session as stopped.
     */
    public void destroy() {
        LOG.infof("Destroying assistant session %s", id);
        status = Status.STOPPED;
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    /**
     * Returns whether the subprocess is still alive.
     *
     * @return true if the subprocess is running
     */
    public boolean isAlive() {
        return process != null && process.isAlive();
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

    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public String getErrorMessage() {
        return errorMessage.get();
    }

    /** Returns the accumulated cost in USD across all turns. */
    public double getTotalCostUsd() {
        return totalCostUsd;
    }

    /** Returns the accumulated input token count across all turns. */
    public long getTotalInputTokens() {
        return totalInputTokens;
    }

    /** Returns the accumulated output token count across all turns. */
    public long getTotalOutputTokens() {
        return totalOutputTokens;
    }

    /** Returns the accumulated duration in milliseconds across all turns. */
    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    /** Returns the number of completed turns. */
    public int getTurnCount() {
        return turnCount;
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

    private void writeLine(String json) throws IOException {
        if (stdin == null) {
            throw new IOException("Session stdin is not available");
        }
        synchronized (stdin) {
            stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }
    }

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                List<SseEvent> events = parser.parse(line);
                for (SseEvent event : events) {
                    if (handleAutoApproval(event)) {
                        continue;
                    }
                    if ("turn_complete".equals(event.type())) {
                        accumulateCost(event);
                    }
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
            }
        } catch (IOException e) {
            if (status == Status.RUNNING) {
                LOG.warnf("Error reading stdout for session %s: %s", id, e.getMessage());
            }
        }
    }

    private boolean handleAutoApproval(SseEvent event) {
        if (!"permission_request".equals(event.type())) {
            return false;
        }
        String toolName = event.data().path("toolName").asText("");
        JsonNode toolInput = event.data().path("toolInput");
        if (!checkAutoApproval(toolName, toolInput)) {
            return false;
        }
        String requestId = event.data().path("requestId").asText("");
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
        totalCostUsd += event.data().path("costUsd").asDouble(0);
        totalInputTokens += event.data().path("inputTokens").asLong(0);
        totalOutputTokens += event.data().path("outputTokens").asLong(0);
        totalDurationMs += event.data().path("durationMs").asLong(0);
        turnCount++;
    }

    private void readStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOG.debugf("Session %s stderr: %s", id, line);
            }
        } catch (IOException e) {
            if (status == Status.RUNNING) {
                LOG.warnf("Error reading stderr for session %s: %s", id, e.getMessage());
            }
        }
    }

    private void monitorProcess() {
        try {
            int exitCode = process.waitFor();
            if (status == Status.RUNNING) {
                LOG.infof("Assistant session %s process exited with code %d", id, exitCode);
                if (exitCode != 0) {
                    status = Status.ERROR;
                    errorMessage.set("Process exited with code " + exitCode);
                } else {
                    status = Status.STOPPED;
                }

                ObjectNode data = MAPPER.createObjectNode();
                data.put("exitCode", exitCode);
                data.put("status", status.name());
                if (exitCode != 0) {
                    data.put("message", "Process exited with code " + exitCode);
                }
                addEvent(new SseEvent("session_ended", data));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
