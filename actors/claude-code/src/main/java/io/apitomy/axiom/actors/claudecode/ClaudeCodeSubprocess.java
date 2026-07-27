package io.apitomy.axiom.actors.claudecode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Manages a Claude Code CLI subprocess. Handles launching the process,
 * reading NDJSON streaming output, parsing the final result, and
 * enforcing timeouts.
 */
public class ClaudeCodeSubprocess {

    private static final Logger LOG = Logger.getLogger(ClaudeCodeSubprocess.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> command;
    private final java.io.File workingDirectory;
    private final Map<String, String> environment;
    private final Duration timeout;
    private final Consumer<String> streamListener;
    private final ExecutionLogBuilder logBuilder;

    private Process process;

    // Accumulated from stream-json "result" events during execution
    private volatile Double streamCostUsd;
    private volatile Long streamInputTokens;
    private volatile Long streamOutputTokens;
    private volatile String streamSessionId;

    /**
     * Creates a new subprocess wrapper.
     *
     * @param command the command line arguments
     * @param workingDirectory the working directory for the process (may be null)
     * @param environment additional environment variables
     * @param timeout maximum execution duration
     * @param streamListener callback for streaming output lines (may be null)
     * @param logBuilder accumulator for the per-task execution log
     */
    public ClaudeCodeSubprocess(List<String> command, java.io.File workingDirectory,
                                 Map<String, String> environment,
                                 Duration timeout, Consumer<String> streamListener,
                                 ExecutionLogBuilder logBuilder) {
        this.command = command;
        this.workingDirectory = workingDirectory;
        this.environment = environment;
        this.timeout = timeout;
        this.streamListener = streamListener;
        this.logBuilder = logBuilder;
    }

    /**
     * Launches the subprocess and returns a future that completes with the result.
     *
     * @return a future containing the parsed Claude Code result
     */
    public CompletableFuture<ClaudeCodeResult> execute() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doExecute();
            } catch (Exception e) {
                LOG.errorf(e, "Claude Code subprocess failed");
                return ClaudeCodeResult.failed("Subprocess error: " + e.getMessage(), -1);
            }
        });
    }

    /**
     * Kills the running subprocess, if any.
     */
    public void kill() {
        if (process != null && process.isAlive()) {
            LOG.warn("Killing Claude Code subprocess");
            destroyProcessTree();
        }
    }

    private ClaudeCodeResult doExecute() throws IOException, InterruptedException {
        Instant startTime = Instant.now();

        LOG.infof("Launching Claude Code");
        // Log tool-related flags for debugging
        for (int i = 0; i < command.size(); i++) {
            String arg = command.get(i);
            if ("--tools".equals(arg) || "--allowedTools".equals(arg)
                    || "--disallowedTools".equals(arg) || "--permission-mode".equals(arg)) {
                String value = (i + 1 < command.size()) ? command.get(i + 1) : "?";
                LOG.tracef("  [cmd] %s %s", arg, value);
            }
        }

        // Write command to execution log
        logBuilder.command(command);

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

        // Read stdout (NDJSON lines or final JSON)
        StringBuilder lastResultLine = new StringBuilder();
        StringBuilder stderrContent = new StringBuilder();

        // Read stdout in a thread — parse NDJSON for diagnostic logging
        CompletableFuture<Void> stdoutFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastResultLine.setLength(0);
                    lastResultLine.append(line);

                    if (streamListener != null) {
                        streamListener.accept(line);
                    }

                    // Parse NDJSON for diagnostic logging
                    logStreamEvent(line);
                }
            } catch (IOException e) {
                LOG.warnf("Error reading Claude Code stdout: %s", e.getMessage());
            }
        });

        // Read stderr in a thread
        CompletableFuture<Void> stderrFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderrContent.append(line).append("\n");
                    LOG.debugf("Claude Code stderr: %s", line);
                    logBuilder.stderr(line);
                }
            } catch (IOException e) {
                LOG.warnf("Error reading Claude Code stderr: %s", e.getMessage());
            }
        });

        // Wait for process with timeout
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            LOG.warnf("Claude Code subprocess timed out after %s", timeout);
            destroyProcessTree();
            logBuilder.footer("Timed Out", null, null, null,
                    Duration.between(startTime, Instant.now()));
            return new ClaudeCodeResult("Process timed out after " + timeout,
                    null, null, null, null, 124, logBuilder.build());
        }

        // Kill any orphan child processes that may hold pipes open
        destroyProcessTree();

        // Wait for stream readers to finish (bounded to avoid hanging on orphan pipes)
        joinWithTimeout(stdoutFuture, "stdout");
        joinWithTimeout(stderrFuture, "stderr");

        int exitCode = process.exitValue();
        LOG.tracef("Claude Code subprocess exited with code %d", exitCode);

        if (exitCode != 0) {
            String error = stderrContent.toString().trim();
            if (error.isEmpty()) {
                error = "Process exited with code " + exitCode;
            }
            logBuilder.footer("Failed (exit " + exitCode + ")", null, null, null,
                    Duration.between(startTime, Instant.now()));
            return new ClaudeCodeResult(error, null, null, null, null,
                    exitCode, logBuilder.build());
        }

        // Parse the final result line
        ClaudeCodeResult result = parseResult(lastResultLine.toString(), exitCode);

        // Merge: prefer data from the parsed result, fall back to stream-captured data
        String finalResult = result.result();
        String finalSessionId = result.sessionId() != null ? result.sessionId() : streamSessionId;
        Double finalCostUsd = result.totalCostUsd() != null ? result.totalCostUsd() : streamCostUsd;
        Long finalInputTokens = result.inputTokens() != null ? result.inputTokens() : streamInputTokens;
        Long finalOutputTokens = result.outputTokens() != null ? result.outputTokens() : streamOutputTokens;

        logBuilder.footer(result.isSuccess() ? "Completed" : "Failed",
                finalCostUsd, finalInputTokens, finalOutputTokens,
                Duration.between(startTime, Instant.now()));
        return new ClaudeCodeResult(finalResult, finalSessionId,
                finalCostUsd, finalInputTokens, finalOutputTokens,
                result.exitCode(), logBuilder.build());
    }

    /**
     * Parses the final JSON output from Claude Code.
     */
    private ClaudeCodeResult parseResult(String jsonLine, int exitCode) {
        if (jsonLine == null || jsonLine.isEmpty()) {
            return ClaudeCodeResult.failed("No output from Claude Code", exitCode);
        }

        try {
            JsonNode root = MAPPER.readTree(jsonLine);

            // When --json-schema is used, the structured output may be in
            // "structured_output" as a parsed object, or in "result" as a string
            String result;
            JsonNode structuredOutput = root.path("structured_output");
            if (!structuredOutput.isMissingNode() && !structuredOutput.isNull()) {
                result = MAPPER.writeValueAsString(structuredOutput);
            } else {
                result = root.path("result").asText(null);
            }
            String sessionId = root.path("session_id").asText(null);

            Double costUsd = null;
            if (root.has("total_cost_usd")) {
                costUsd = root.get("total_cost_usd").asDouble();
            }

            Long inputTokens = null;
            Long outputTokens = null;
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode()) {
                if (usage.has("input_tokens")) {
                    inputTokens = usage.get("input_tokens").asLong();
                }
                if (usage.has("output_tokens")) {
                    outputTokens = usage.get("output_tokens").asLong();
                }
            }

            return new ClaudeCodeResult(result, sessionId, costUsd, inputTokens,
                    outputTokens, exitCode, null);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse Claude Code output as JSON: %s",
                    jsonLine.substring(0, Math.min(jsonLine.length(), 200)));
            // Fall back to treating the raw output as the result text
            return new ClaudeCodeResult(jsonLine, null, null, null, null, exitCode, null);
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

    /**
     * Parses a stream-json NDJSON line and logs meaningful diagnostic information.
     * This helps trace what Claude Code is doing during execution.
     */
    private void logStreamEvent(String line) {
        try {
            JsonNode node = MAPPER.readTree(line);
            String type = node.path("type").asText("");

            switch (type) {
                case "assistant" -> {
                    // Assistant message — check for tool use
                    JsonNode content = node.path("message").path("content");
                    if (content.isArray()) {
                        for (JsonNode block : content) {
                            String blockType = block.path("type").asText("");
                            if ("tool_use".equals(blockType)) {
                                String toolName = block.path("name").asText("?");
                                String input = block.path("input").toString();
                                String inputPreview = input.length() > 150
                                        ? input.substring(0, 147) + "..."
                                        : input;
                                LOG.tracef("  [claude] Tool call: %s — %s", toolName, inputPreview);
                                logBuilder.toolCall(toolName, input);
                            } else if ("text".equals(blockType)) {
                                String text = block.path("text").asText("");
                                if (!text.isBlank()) {
                                    String preview = text.length() > 120
                                            ? text.substring(0, 117) + "..."
                                            : text;
                                    LOG.tracef("  [claude] Text: %s", preview);
                                    logBuilder.text(text);
                                }
                            }
                        }
                    }
                }
                case "result" -> {
                    String subtype = node.path("subtype").asText("");
                    double cost = node.path("total_cost_usd").asDouble(0);
                    int turns = node.path("num_turns").asInt(0);
                    long durationMs = node.path("duration_ms").asLong(0);
                    LOG.tracef("  [claude] Result: %s (turns=%d, cost=$%.4f, duration=%dms)",
                            subtype, turns, cost, durationMs);
                    logBuilder.result(subtype, turns, cost, durationMs);

                    // Capture cost and token data from the result event
                    if (cost > 0) {
                        streamCostUsd = cost;
                    }
                    if (node.has("session_id")) {
                        streamSessionId = node.path("session_id").asText(null);
                    }
                    // Extract tokens from the result event's usage field (if present)
                    JsonNode resultUsage = node.path("usage");
                    if (!resultUsage.isMissingNode()) {
                        if (resultUsage.has("input_tokens")) {
                            streamInputTokens = resultUsage.get("input_tokens").asLong();
                        }
                        if (resultUsage.has("output_tokens")) {
                            streamOutputTokens = resultUsage.get("output_tokens").asLong();
                        }
                    }
                    // Also try top-level input_tokens/output_tokens
                    if (streamInputTokens == null && node.has("input_tokens")) {
                        streamInputTokens = node.get("input_tokens").asLong();
                    }
                    if (streamOutputTokens == null && node.has("output_tokens")) {
                        streamOutputTokens = node.get("output_tokens").asLong();
                    }
                }
                case "tool_result" -> {
                    String toolName = node.path("name").asText("");
                    boolean isError = node.path("is_error").asBoolean(false);
                    if (isError) {
                        String errorContent = node.path("content").asText(
                                node.path("content").toString());
                        String errorPreview = errorContent.length() > 200
                                ? errorContent.substring(0, 197) + "..."
                                : errorContent;
                        LOG.warnf("  [claude] Tool failed: %s — %s", toolName, errorPreview);
                        logBuilder.toolResult(toolName, true, errorPreview);
                    } else {
                        LOG.tracef("  [claude] Tool completed: %s", toolName);
                        logBuilder.toolResult(toolName, false, null);
                    }
                }
                default -> LOG.tracef("  [claude] Stream event type: %s", type);
            }
        } catch (Exception e) {
            // Not JSON or unparseable — log raw at trace
            LOG.tracef("  [claude] Raw: %s",
                    line.substring(0, Math.min(line.length(), 200)));
        }
    }
}
