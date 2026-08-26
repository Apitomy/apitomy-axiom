package io.apitomy.axiom.agents.claudecode;

/**
 * Parsed result from a Claude Code JSON output.
 *
 * <p>The Claude Code CLI with {@code --output-format json} returns:</p>
 * <pre>
 * {
 *   "result": "the final text output",
 *   "session_id": "uuid",
 *   "total_cost_usd": 0.05,
 *   "usage": { "input_tokens": 1200, "output_tokens": 800 }
 * }
 * </pre>
 */
public record ClaudeCodeResult(
        String result,
        String sessionId,
        Double totalCostUsd,
        Long inputTokens,
        Long outputTokens,
        int exitCode,
        String executionLog,
        String model
) {

    /**
     * Creates a result representing a failed execution.
     *
     * @param errorMessage the error description
     * @param exitCode the process exit code
     * @return a failed result
     */
    public static ClaudeCodeResult failed(String errorMessage, int exitCode) {
        return new ClaudeCodeResult(errorMessage, null, null, null, null, exitCode, null, null);
    }

    /**
     * @return true if the process exited successfully
     */
    public boolean isSuccess() {
        return exitCode == 0;
    }
}
