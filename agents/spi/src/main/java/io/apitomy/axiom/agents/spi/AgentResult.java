package io.apitomy.axiom.agents.spi;

/**
 * Unified result of an AI agent invocation. All agent implementations map
 * their internal result types to this record before returning to callers.
 * Replaces both {@code TaskResult} and {@code AiEngineResult}.
 *
 * @param success      true if the invocation completed successfully
 * @param output       the output text produced by the agent
 * @param errorMessage the error message if the invocation failed, or null
 * @param executionLog human-readable execution transcript, or null if not captured
 * @param sessionId    agent-specific session identifier (for potential resumption)
 * @param costUsd      cost in USD, or null if not tracked
 * @param inputTokens  number of input tokens consumed, or null if not tracked
 * @param outputTokens number of output tokens produced, or null if not tracked
 * @param engine       the agent engine type that handled the invocation (e.g. "claude-code"), or null
 * @param model        the AI model that was actually used (e.g. "claude-sonnet-4-6"), or null
 */
public record AgentResult(
        boolean success,
        String output,
        String errorMessage,
        String executionLog,
        String sessionId,
        Double costUsd,
        Long inputTokens,
        Long outputTokens,
        String engine,
        String model
) {

    /**
     * Creates a successful result with only the output text.
     *
     * @param output the output text
     * @return a successful AgentResult
     */
    public static AgentResult success(String output) {
        return new AgentResult(true, output, null, null, null, null, null, null, null, null);
    }

    /**
     * Creates a failed result with an error message.
     *
     * @param errorMessage the error message
     * @return a failed AgentResult
     */
    public static AgentResult failure(String errorMessage) {
        return new AgentResult(false, null, errorMessage, null, null, null, null, null, null, null);
    }
}
