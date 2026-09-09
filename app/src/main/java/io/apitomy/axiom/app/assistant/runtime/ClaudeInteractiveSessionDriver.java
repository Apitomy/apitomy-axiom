package io.apitomy.axiom.app.assistant.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.AssistantEventParser;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import io.apitomy.axiom.app.assistant.AssistantSession;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Claude Code interactive session driver that owns subprocess lifecycle and NDJSON I/O.
 */
public final class ClaudeInteractiveSessionDriver implements InteractiveSessionDriver {

    private static final Logger LOG = Logger.getLogger(ClaudeInteractiveSessionDriver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RAW_EVENTS_FILE = "raw-events.jsonl";

    private final Path workingDirectory;
    private final Path sessionDirectory;
    private final List<String> command;
    private final Map<String, String> environment;
    private final AssistantEventParser parser;
    private final Consumer<SseEvent> eventSink;
    private final Consumer<SseEvent> autoApprovalSink;

    private volatile Process process;
    private volatile OutputStream stdin;
    private volatile AssistantSession.Status status;
    private final AtomicReference<String> errorMessage = new AtomicReference<>();
    private volatile BufferedWriter rawEventsWriter;

    /**
     * Creates a Claude interactive driver.
     *
     * @param workingDirectory assistant runtime working directory
     * @param sessionDirectory session directory where raw events are logged
     * @param command Claude command invocation
     * @param environment environment variables for the Claude process
     * @param parser NDJSON event parser
     * @param eventSink sink for parsed non-permission events
     * @param autoApprovalSink sink for parsed permission_request events
     */
    public ClaudeInteractiveSessionDriver(Path workingDirectory,
                                          Path sessionDirectory,
                                          List<String> command,
                                          Map<String, String> environment,
                                          AssistantEventParser parser,
                                          Consumer<SseEvent> eventSink,
                                          Consumer<SseEvent> autoApprovalSink) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.sessionDirectory = Objects.requireNonNull(sessionDirectory, "sessionDirectory");
        this.command = Objects.requireNonNull(command, "command");
        this.environment = environment;
        this.parser = Objects.requireNonNull(parser, "parser");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.autoApprovalSink = Objects.requireNonNull(autoApprovalSink, "autoApprovalSink");
        this.status = AssistantSession.Status.STARTING;
    }

    @Override
    public void start() throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory.toFile());
        processBuilder.redirectErrorStream(false);
        if (environment != null && !environment.isEmpty()) {
            processBuilder.environment().putAll(environment);
        }

        process = processBuilder.start();
        stdin = process.getOutputStream();

        try {
            rawEventsWriter = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(sessionDirectory.resolve(RAW_EVENTS_FILE).toFile()),
                    StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.warnf(e, "Failed to open raw events log in %s; raw logging disabled", sessionDirectory);
            rawEventsWriter = null;
        }

        Thread.ofVirtual().name("assistant-stdout").start(this::readStdout);
        Thread.ofVirtual().name("assistant-stderr").start(this::readStderr);
        Thread.ofVirtual().name("assistant-monitor").start(this::monitorProcess);

        status = AssistantSession.Status.RUNNING;
    }

    @Override
    public void sendUserMessage(String message) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "user");
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", "user");
        msg.put("content", message);
        root.set("message", msg);
        writeLine(MAPPER.writeValueAsString(root));
    }

    @Override
    public void respondToPermission(String permissionId, boolean allow, JsonNode toolInput)
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
    }

    @Override
    public void interrupt() {
        if (process != null && process.isAlive()) {
            long pid = process.pid();
            try {
                new ProcessBuilder("kill", "-INT", String.valueOf(pid)).start().waitFor();
            } catch (Exception e) {
                LOG.warnf(e, "Failed to send SIGINT to Claude runtime pid %d", pid);
            }
        }
    }

    @Override
    public void destroy() {
        status = AssistantSession.Status.STOPPED;
        closeQuietly(rawEventsWriter);
        rawEventsWriter = null;
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    @Override
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    @Override
    public AssistantSession.Status getStatus() {
        return status;
    }

    @Override
    public String getErrorMessage() {
        return errorMessage.get();
    }

    void handleStdoutLine(String line) {
        writeRawEvent(line);
        List<SseEvent> events = parser.parse(line);
        for (SseEvent event : events) {
            if ("permission_request".equals(event.type())) {
                autoApprovalSink.accept(event);
            } else {
                eventSink.accept(event);
            }
        }
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
        try {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    handleStdoutLine(line);
                }
            } catch (IOException e) {
                if (status == AssistantSession.Status.RUNNING) {
                    LOG.warnf("Error reading Claude stdout: %s", e.getMessage());
                }
            }
        } finally {
            closeQuietly(rawEventsWriter);
            rawEventsWriter = null;
        }
    }

    private void readStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOG.debugf("Claude stderr: %s", line);
            }
        } catch (IOException e) {
            if (status == AssistantSession.Status.RUNNING) {
                LOG.warnf("Error reading Claude stderr: %s", e.getMessage());
            }
        }
    }

    private void monitorProcess() {
        try {
            int exitCode = process.waitFor();
            if (status == AssistantSession.Status.RUNNING) {
                if (exitCode != 0) {
                    status = AssistantSession.Status.ERROR;
                    errorMessage.set("Process exited with code " + exitCode);
                } else {
                    status = AssistantSession.Status.STOPPED;
                }

                ObjectNode data = MAPPER.createObjectNode();
                data.put("exitCode", exitCode);
                data.put("status", status.name());
                if (exitCode != 0) {
                    data.put("message", "Process exited with code " + exitCode);
                }
                eventSink.accept(new SseEvent("session_ended", data));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeRawEvent(String line) {
        BufferedWriter writer = rawEventsWriter;
        if (writer == null) {
            return;
        }
        try {
            writer.write("{\"ts\":\"");
            writer.write(Instant.now().toString());
            writer.write("\",\"raw\":");
            writer.write(line);
            writer.write("}");
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            LOG.warnf(e, "Failed to write Claude raw event; disabling raw logging");
            rawEventsWriter = null;
            closeQuietly(writer);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}
