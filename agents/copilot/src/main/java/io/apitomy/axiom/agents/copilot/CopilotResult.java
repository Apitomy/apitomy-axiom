package io.apitomy.axiom.agents.copilot;

/**
 * Parsed result from a GitHub Copilot CLI invocation.
 *
 * <p>The {@code copilot} CLI with {@code --output-format json} streams NDJSON
 * events; the final answer text is extracted from the last
 * {@code assistant.message} event, and the session identifier and exit code
 * come from the terminal {@code result} event.</p>
 *
 * <p>Copilot CLI does not currently expose a USD cost figure (it reports
 * "premium requests" and AI-credit usage instead), so {@code costUsd} is
 * always {@code null} for this engine.</p>
 */
public record CopilotResult(
        String result,
        String sessionId,
        Double costUsd,
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
     * @param exitCode     the process exit code
     * @return a failed result
     */
    public static CopilotResult failed(String errorMessage, int exitCode) {
        return new CopilotResult(errorMessage, null, null, null, null, exitCode, null, null);
    }

    /**
     * Returns {@code true} if the process exited successfully.
     *
     * @return true if the exit code is zero
     */
    public boolean isSuccess() {
        return exitCode == 0;
    }
}
