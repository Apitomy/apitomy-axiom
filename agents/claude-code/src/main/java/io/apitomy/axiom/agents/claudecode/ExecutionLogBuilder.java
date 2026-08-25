package io.apitomy.axiom.agents.claudecode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Accumulates a human-readable execution log during Claude Code subprocess
 * execution. Thread-safe — methods may be called from stdout and stderr
 * reader threads concurrently.
 */
public class ExecutionLogBuilder {

    private final StringBuffer log = new StringBuffer();

    /**
     * Appends the task header section.
     *
     * @param taskId the task ID
     * @param actionType the action type name
     * @param startTime when execution started
     */
    public void header(long taskId, String actionType, Instant startTime) {
        log.append("=== Task ").append(taskId)
                .append(" (").append(actionType).append(")")
                .append(" — Started ").append(startTime)
                .append(" ===\n\n");
    }

    /**
     * Appends the full prompt section.
     *
     * @param prompt the prompt text sent to Claude Code
     */
    public void prompt(String prompt) {
        log.append("=== Prompt ===\n");
        log.append(prompt).append("\n\n");
    }

    /**
     * Appends the system prompt section.
     *
     * @param systemPrompt the system prompt sent to Claude Code
     */
    public void systemPrompt(String systemPrompt) {
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            log.append("=== System Prompt ===\n");
            log.append(systemPrompt).append("\n\n");
        }
    }

    /**
     * Appends the allowed tools section as a numbered list.
     *
     * @param tools the list of allowed tool patterns
     */
    public void allowedTools(List<String> tools) {
        log.append("=== Allowed Tools ===\n");
        if (tools == null || tools.isEmpty()) {
            log.append("(none — no tool restrictions)\n\n");
            return;
        }
        for (int i = 0; i < tools.size(); i++) {
            log.append("  ").append(i + 1).append(". ").append(tools.get(i)).append("\n");
        }
        log.append("\n");
    }

    /**
     * Appends the environment variables section (names only, no values).
     *
     * @param env the environment variable map
     */
    public void environment(Map<String, String> env) {
        log.append("=== Environment Variables ===\n");
        if (env == null || env.isEmpty()) {
            log.append("(none)\n\n");
            return;
        }
        env.keySet().stream().sorted().forEach(key ->
                log.append("  - ").append(key).append("\n"));
        log.append("\n");
    }

    /**
     * Appends the command line section, formatting flags for readability.
     *
     * @param cmdLine the full command line as a list of arguments
     */
    public void command(List<String> cmdLine) {
        log.append("=== Command ===\n");
        if (cmdLine.isEmpty()) {
            log.append("(empty)\n\n");
            return;
        }

        log.append(cmdLine.getFirst()).append(" -p <prompt>");
        int i = 2;
        if (cmdLine.size() > 3) {
            i = 3;
        }
        for (; i < cmdLine.size(); i++) {
            String arg = cmdLine.get(i);
            if (arg.startsWith("--")) {
                log.append("\n  ").append(arg);
            } else {
                log.append(" ").append(truncate(arg, 200));
            }
        }
        log.append("\n\n");
        log.append("=== Execution ===\n");
    }

    /**
     * Appends a tool call entry with timestamp and full input.
     *
     * @param toolName the tool being called
     * @param input the full tool input (typically JSON)
     */
    public void toolCall(String toolName, String input) {
        log.append(timestamp())
                .append(" Tool call: ").append(toolName).append("\n");
        if (input != null && !input.isBlank()) {
            log.append(indent(prettyPrintJson(input), "    ")).append("\n");
        }
    }

    /**
     * Appends a tool result entry with timestamp.
     *
     * @param toolName the tool that completed
     * @param isError whether the tool execution failed
     * @param preview preview of the result or error
     */
    public void toolResult(String toolName, boolean isError, String preview) {
        if (isError) {
            log.append(timestamp())
                    .append(" Tool FAILED: ").append(toolName)
                    .append(" — ").append(truncate(preview, 300))
                    .append("\n");
        } else {
            log.append(timestamp())
                    .append(" Tool completed: ").append(toolName)
                    .append("\n");
        }
    }

    /**
     * Appends a text output entry with timestamp.
     *
     * @param text the full assistant text output
     */
    public void text(String text) {
        log.append(timestamp())
                .append(" Text: ").append(text != null ? text : "")
                .append("\n");
    }

    /**
     * Appends a stderr line.
     *
     * @param line the stderr output line
     */
    public void stderr(String line) {
        log.append(timestamp())
                .append(" [stderr] ").append(truncate(line, 300))
                .append("\n");
    }

    /**
     * Appends the final result event from the Claude Code stream.
     *
     * @param subtype the result subtype (e.g. "success")
     * @param turns number of conversation turns
     * @param costUsd cost in USD
     * @param durationMs execution duration in milliseconds
     */
    public void result(String subtype, int turns, double costUsd, long durationMs) {
        log.append(timestamp())
                .append(" Result: ").append(subtype)
                .append(" (turns=").append(turns)
                .append(", cost=$").append(String.format("%.4f", costUsd))
                .append(", duration=").append(durationMs).append("ms)")
                .append("\n");
    }

    /**
     * Appends the completion footer section.
     *
     * @param status final task status (Completed/Failed)
     * @param costUsd total cost in USD
     * @param inputTokens input tokens consumed
     * @param outputTokens output tokens produced
     * @param duration total wall-clock duration
     */
    public void footer(String status, Double costUsd, Long inputTokens,
                       Long outputTokens, Duration duration) {
        log.append("\n=== ").append(status).append(" ===\n");
        log.append("Status: ").append(status).append("\n");
        if (costUsd != null) {
            log.append("Cost: $").append(String.format("%.4f", costUsd)).append("\n");
        }
        if (inputTokens != null || outputTokens != null) {
            log.append("Tokens: ")
                    .append(inputTokens != null ? inputTokens : 0).append(" in / ")
                    .append(outputTokens != null ? outputTokens : 0).append(" out\n");
        }
        if (duration != null) {
            log.append("Duration: ").append(duration.toSeconds()).append("s\n");
        }
    }

    /**
     * Returns the accumulated log text.
     *
     * @return the complete execution log
     */
    public String build() {
        return log.toString();
    }

    private String timestamp() {
        return "[" + LocalTime.now(ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + "]";
    }

    private String truncate(String s, int maxLength) {
        if (s == null) return "";
        if (s.length() <= maxLength) return s;
        return s.substring(0, maxLength - 3) + "...";
    }

    private String prettyPrintJson(String json) {
        if (json == null || json.isBlank()) return json;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            Object parsed = mapper.readValue(json, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
        } catch (Exception e) {
            return json;
        }
    }

    private String indent(String text, String prefix) {
        if (text == null || text.isEmpty()) return "";
        return text.lines()
                .map(line -> prefix + line)
                .collect(Collectors.joining("\n"));
    }
}
