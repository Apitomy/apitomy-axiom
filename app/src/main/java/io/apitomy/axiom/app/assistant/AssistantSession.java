package io.apitomy.axiom.app.assistant;

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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
    private final String name;
    private final String templateId;
    private final Path workingDirectory;
    private final List<String> command;
    private final AssistantEventParser parser;

    private volatile Process process;
    private volatile OutputStream stdin;
    private volatile Status status;
    private volatile Instant lastActivityAt;
    private final Instant createdAt;
    private final AtomicReference<String> errorMessage = new AtomicReference<>();

    private final CopyOnWriteArrayList<SseEvent> eventHistory = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<SseEvent>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Creates a new assistant session.
     *
     * @param name the user-visible session name
     * @param templateId the template this session was created from
     * @param workingDirectory the session's working directory
     * @param command the full command line for the Claude Code subprocess
     */
    public AssistantSession(String name, String templateId, Path workingDirectory, List<String> command) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.templateId = templateId;
        this.workingDirectory = workingDirectory;
        this.command = command;
        this.parser = new AssistantEventParser();
        this.status = Status.STARTING;
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
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
     * Sends a user message to the Claude Code subprocess via stdin.
     *
     * @param message the user's message text
     * @throws IOException if the message cannot be written
     */
    public void sendMessage(String message) throws IOException {
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
     * Returns the template ID this session was created from.
     *
     * @return the template identifier
     */
    public String getTemplateId() {
        return templateId;
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
        } catch (IOException e) {
            if (status == Status.RUNNING) {
                LOG.warnf("Error reading stdout for session %s: %s", id, e.getMessage());
            }
        }
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
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
