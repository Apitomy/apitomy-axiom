package io.apitomy.axiom.engine.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Manages a GitHub Copilot CLI ({@code copilot}) subprocess. Handles launching
 * the process, reading NDJSON streaming output (one JSON object per line,
 * {@code --output-format json}), extracting the final answer, and enforcing
 * timeouts.
 */
public class CopilotSubprocess {

    private static final Logger LOG = Logger.getLogger(CopilotSubprocess.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> command;
    private final java.io.File workingDirectory;
    private final Map<String, String> environment;
    private final Duration timeout;

    private volatile Process process;

    // Accumulated from the NDJSON event stream during execution
    private volatile String lastAssistantMessage;
    private volatile Long lastOutputTokens;
    private volatile String resultSessionId;
    private volatile int resultExitCode = -1;
    private volatile boolean sawResultEvent;

    private final StringBuilder log = new StringBuilder();

    public CopilotSubprocess(List<String> command, java.io.File workingDirectory,
                              Map<String, String> environment, Duration timeout) {
        this.command = command;
        this.workingDirectory = workingDirectory;
        this.environment = environment;
        this.timeout = timeout;
    }

    /**
     * Launches the subprocess and returns a future that completes with the result.
     *
     * @return a future containing the parsed Copilot result
     */
    public CompletableFuture<CopilotResult> execute() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doExecute();
            } catch (Exception e) {
                LOG.errorf(e, "Copilot CLI subprocess failed");
                return CopilotResult.failed("Subprocess error: " + e.getMessage(), -1);
            }
        });
    }

    /**
     * Kills the running subprocess, if any.
     */
    public void kill() {
        if (process != null && process.isAlive()) {
            LOG.warn("Killing Copilot CLI subprocess");
            destroyProcessTree();
        }
    }

    private CopilotResult doExecute() throws IOException, InterruptedException {
        Instant startTime = Instant.now();
        LOG.infof("Launching Copilot CLI");

        log.append("=== Command ===\n").append(String.join(" ",
                command.stream().map(a -> a.contains(" ") ? "\"" + a + "\"" : a).toList()))
                .append("\n\n=== Execution ===\n");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));

        if (workingDirectory != null) {
            pb.directory(workingDirectory);
        }

        if (environment != null) {
            pb.environment().putAll(environment);
        }

        process = pb.start();

        StringBuilder stderrContent = new StringBuilder();

        CompletableFuture<Void> stdoutFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logStreamEvent(line);
                }
            } catch (IOException e) {
                LOG.warnf("Error reading Copilot CLI stdout: %s", e.getMessage());
            }
        });

        CompletableFuture<Void> stderrFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderrContent.append(line).append("\n");
                    LOG.debugf("Copilot CLI stderr: %s", line);
                    log.append(timestamp()).append(" [stderr] ").append(line).append("\n");
                }
            } catch (IOException e) {
                LOG.warnf("Error reading Copilot CLI stderr: %s", e.getMessage());
            }
        });

        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            LOG.warnf("Copilot CLI subprocess timed out after %s", timeout);
            destroyProcessTree();
            log.append("\n=== Timed Out ===\n");
            return new CopilotResult("Process timed out after " + timeout,
                    null, null, null, null, 124, log.toString());
        }

        destroyProcessTree();

        joinWithTimeout(stdoutFuture, "stdout");
        joinWithTimeout(stderrFuture, "stderr");

        int exitCode = process.exitValue();
        LOG.tracef("Copilot CLI subprocess exited with code %d", exitCode);

        Duration duration = Duration.between(startTime, Instant.now());

        if (exitCode != 0) {
            String error = stderrContent.toString().trim();
            if (error.isEmpty()) {
                error = lastAssistantMessage != null ? lastAssistantMessage
                        : "Process exited with code " + exitCode;
            }
            log.append("\n=== Failed (exit ").append(exitCode).append(") ===\n");
            return new CopilotResult(error, resultSessionId, null, null, lastOutputTokens,
                    exitCode, log.toString());
        }

        String result = lastAssistantMessage != null ? lastAssistantMessage : "";
        // If the process reported success but a "result" event carried a
        // non-zero exit code, prefer that as the authoritative status.
        int finalExitCode = sawResultEvent && resultExitCode >= 0 ? resultExitCode : exitCode;

        log.append("\n=== Completed ===\nStatus: ")
                .append(finalExitCode == 0 ? "Completed" : "Failed")
                .append("\nDuration: ").append(duration.toSeconds()).append("s\n");

        return new CopilotResult(result, resultSessionId, null, null, lastOutputTokens,
                finalExitCode, log.toString());
    }

    /**
     * Parses an NDJSON event line from the Copilot CLI stream and accumulates
     * diagnostic information plus the final answer text.
     */
    private void logStreamEvent(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        try {
            JsonNode node = MAPPER.readTree(line);
            String type = node.path("type").asText("");

            switch (type) {
                case "assistant.message" -> {
                    JsonNode data = node.path("data");
                    String content = data.path("content").asText(null);
                    if (content != null) {
                        lastAssistantMessage = content;
                        String preview = content.length() > 150
                                ? content.substring(0, 147) + "..." : content;
                        LOG.tracef("  [copilot] Message: %s", preview);
                        log.append(timestamp()).append(" Message: ").append(content).append("\n");
                    }
                    if (data.has("outputTokens")) {
                        lastOutputTokens = data.get("outputTokens").asLong();
                    }
                }
                case "session.tool_call", "tool.call" -> {
                    JsonNode data = node.path("data");
                    String toolName = data.path("name").asText(data.path("toolName").asText("?"));
                    LOG.tracef("  [copilot] Tool call: %s", toolName);
                    log.append(timestamp()).append(" Tool call: ").append(toolName).append("\n");
                }
                case "result" -> {
                    // Unlike other event types, "result" carries exitCode and
                    // sessionId at the top level rather than under "data".
                    sawResultEvent = true;
                    resultExitCode = node.path("exitCode").asInt(0);
                    resultSessionId = node.path("sessionId").asText(null);
                    LOG.tracef("  [copilot] Result: exitCode=%d, sessionId=%s",
                            resultExitCode, resultSessionId);
                    log.append(timestamp()).append(" Result: exitCode=")
                            .append(resultExitCode).append("\n");
                }
                case "session.info" -> {
                    String message = node.path("data").path("message").asText(null);
                    if (message != null) {
                        LOG.tracef("  [copilot] Info: %s", message);
                    }
                }
                default -> LOG.tracef("  [copilot] Stream event type: %s", type);
            }
        } catch (Exception e) {
            LOG.tracef("  [copilot] Raw: %s",
                    line.substring(0, Math.min(line.length(), 200)));
        }
    }

    private void destroyProcessTree() {
        process.descendants().forEach(ph -> {
            LOG.tracef("Killing descendant process %d", ph.pid());
            ph.destroyForcibly();
        });
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void joinWithTimeout(CompletableFuture<Void> future, String name) {
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOG.warnf("%s reader did not finish within 30s after process exit, cancelling", name);
            future.cancel(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
        } catch (ExecutionException e) {
            LOG.warnf(e.getCause(), "Error in %s reader", name);
        }
    }

    private String timestamp() {
        return "[" + LocalTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "]";
    }
}
