package io.apitomy.axiom.agents.spi;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * SPI interface for AI agent implementations. Each implementation (Claude Code,
 * OpenCode, Copilot) provides a CDI bean that handles the provider-specific
 * invocation details.
 *
 * <p>This interface replaces both the Actor SPI (task-shaped) and the AiEngine
 * SPI (prompt-shaped) with a single unified abstraction.</p>
 */
public interface Agent {

    /**
     * Returns the agent type identifier (e.g. "claude-code", "opencode", "copilot").
     *
     * @return the agent type string
     */
    String getType();

    /**
     * Executes a prompt and returns the result asynchronously.
     *
     * @param request the agent request containing prompt, config, and environment
     * @return a future that completes with the agent result
     */
    CompletableFuture<AgentResult> execute(AgentRequest request);

    /**
     * Executes a prompt with a JSON schema constraint for structured output.
     * The agent enforces that the response conforms to the given JSON schema.
     *
     * @param request    the agent request containing prompt, config, and environment
     * @param jsonSchema the JSON schema that the response must conform to
     * @return a future that completes with the structured agent result
     */
    CompletableFuture<AgentResult> executeWithSchema(AgentRequest request, String jsonSchema);

    /**
     * Best-effort cancellation of a running execution.
     *
     * @param executionId the execution identifier assigned by the caller
     */
    void cancel(String executionId);

    /**
     * Performs agent-specific startup health checks.
     *
     * @return a list of check results (name, status, message)
     */
    List<AgentCheckResult> healthCheck();
}
