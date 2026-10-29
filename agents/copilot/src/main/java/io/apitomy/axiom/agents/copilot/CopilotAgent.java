package io.apitomy.axiom.agents.copilot;

import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentCheckResult;
import io.apitomy.axiom.agents.spi.AgentRequest;
import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.agents.spi.process.CliRun;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified agent implementation for the GitHub Copilot CLI ({@code copilot}).
 * Merges the former CopilotActor (task-shaped) and CopilotEngine (prompt-shaped)
 * into a single {@link Agent} implementation.
 *
 * <p>Each invocation launches a Copilot CLI subprocess scoped to the request's
 * working directory. The subprocess streams NDJSON events which are parsed by
 * {@link CopilotSubprocess} to extract the final answer, session metadata,
 * and token counts.</p>
 */
@ApplicationScoped
public class CopilotAgent implements Agent {

    private static final Logger LOG = Logger.getLogger(CopilotAgent.class);

    private static final String CHECK_NAME = "GitHub Copilot CLI";
    private static final String INSTALL_HINT = "npm install -g @github/copilot";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    @ConfigProperty(name = "axiom.agent.copilot.executable", defaultValue = "copilot")
    String executable;

    @ConfigProperty(name = "axiom.agent.copilot.model")
    Optional<String> model;

    @ConfigProperty(name = "axiom.agent.copilot.timeout-seconds", defaultValue = "600")
    int defaultTimeoutSeconds;

    @ConfigProperty(name = "axiom.agent.copilot.available-models", defaultValue = "")
    String availableModels;

    @Inject
    CopilotMcpManager mcpManager;

    private final Map<String, CopilotSubprocess> runningProcesses = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     *
     * @return {@code "copilot"}
     */
    @Override
    public String getType() {
        return "copilot";
    }

    /** {@inheritDoc} */
    @Override
    public String getLabel() {
        return "GitHub Copilot CLI";
    }

    /** {@inheritDoc} */
    @Override
    public List<String> getAvailableModels() {
        if (availableModels == null || availableModels.isBlank()) {
            return List.of();
        }
        return Arrays.stream(availableModels.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Launches a Copilot CLI subprocess with the given prompt and configuration.
     * If the request includes a system prompt, it is prepended to the user prompt.
     * The configured model (from the request or the global default) is forwarded
     * to the CLI.</p>
     */
    @Override
    public CompletableFuture<AgentResult> execute(AgentRequest request) {
        return executeInternal(request, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Kills the running Copilot CLI subprocess associated with the given
     * execution identifier, if one exists.</p>
     */
    @Override
    public void cancel(String executionId) {
        CopilotSubprocess subprocess = runningProcesses.remove(executionId);
        if (subprocess != null) {
            LOG.infof("Cancelling execution %s", executionId);
            subprocess.kill();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Verifies that the Copilot CLI executable is available and responsive
     * by running a trivial prompt and checking for the expected output.</p>
     */
    @Override
    public List<AgentCheckResult> healthCheck() {
        return List.of(check(PROBE_TIMEOUT));
    }

    /**
     * Sends the test prompt and maps the run to a check result. On failure
     * the message quotes the end of the CLI's own output, which usually
     * names the actual problem (for example a missing login).
     *
     * @param timeout the time limit for the test prompt
     * @return the check result
     */
    AgentCheckResult check(Duration timeout) {
        CliRun run = CliRun.run(List.of(executable, "-p", "Reply with exactly: AXIOM_OK",
                "--allow-all-tools", "--disable-builtin-mcps", "--output-format", "text", "-s"),
                timeout);
        return switch (run.outcome()) {
            case NOT_STARTED -> new AgentCheckResult(CHECK_NAME, "error",
                    "GitHub Copilot CLI is not available: " + run.failure() + ". "
                            + "Install GitHub Copilot CLI: " + INSTALL_HINT);
            case INTERRUPTED -> new AgentCheckResult(CHECK_NAME, "warning",
                    "GitHub Copilot CLI check was interrupted before it finished.");
            case TIMED_OUT -> new AgentCheckResult(CHECK_NAME, "error",
                    "GitHub Copilot CLI check timed out after " + timeout.toSeconds() + " seconds"
                            + run.excerptSuffix() + ". Ensure that you are logged in "
                            + "(run 'copilot login') and have network access.");
            case COMPLETED -> run.succeededWith("AXIOM_OK")
                    ? new AgentCheckResult(CHECK_NAME, "ok", "GitHub Copilot CLI is available and working.")
                    : new AgentCheckResult(CHECK_NAME, "error",
                            "GitHub Copilot CLI returned exit code " + run.exitCode()
                                    + run.excerptSuffix() + ". Ensure that 'copilot' is installed "
                                    + "and that you are authenticated (run 'copilot login'). "
                                    + "Install: " + INSTALL_HINT);
        };
    }

    /**
     * Returns the MCP manager for configuring MCP servers for Copilot invocations.
     *
     * @return the Copilot MCP manager instance
     */
    public CopilotMcpManager getMcpManager() {
        return mcpManager;
    }

    /**
     * Core execution logic of {@link #execute(AgentRequest)}.
     */
    private CompletableFuture<AgentResult> executeInternal(AgentRequest request,
                                                            String jsonSchema) {
        LOG.infof("Executing Copilot CLI request (executionId: %s)", request.getExecutionId());

        // Build the effective prompt: system prompt + user prompt + optional schema instructions
        String effectivePrompt = request.getPrompt();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            effectivePrompt = request.getSystemPrompt() + "\n\n" + effectivePrompt;
        }

        if (jsonSchema != null) {
            effectivePrompt = effectivePrompt
                    + "\n\nIMPORTANT: You MUST respond with ONLY a valid JSON object "
                    + "matching this JSON schema. No other text, no markdown formatting, "
                    + "no code fences — just the raw JSON.\n\nJSON Schema:\n" + jsonSchema;
        }

        // Build the command line from the request
        CopilotCommandBuilder cmdBuilder = CopilotCommandBuilder
                .fromRequest(request)
                .prompt(effectivePrompt)
                .executable(executable);

        // Per-request model takes priority over the global default
        String resolvedModel;
        if (request.getModel() != null && !request.getModel().isBlank()) {
            resolvedModel = request.getModel();
            cmdBuilder.model(resolvedModel);
        } else {
            resolvedModel = model.orElse(null);
            model.ifPresent(cmdBuilder::model);
        }

        if (request.getSessionId() != null) {
            cmdBuilder.sessionId(request.getSessionId());
        }

        List<String> command = cmdBuilder.build();

        java.io.File workDir = request.getWorkingDirectory() != null
                ? request.getWorkingDirectory().toFile() : null;

        int timeoutSeconds = request.getTimeoutSeconds() > 0
                ? request.getTimeoutSeconds() : defaultTimeoutSeconds;

        CopilotSubprocess subprocess = new CopilotSubprocess(
                command,
                workDir,
                request.getEnvironment() != null ? request.getEnvironment() : Map.of(),
                Duration.ofSeconds(timeoutSeconds)
        );

        // Track running subprocess for cancellation
        String executionId = request.getExecutionId();
        if (executionId != null) {
            runningProcesses.put(executionId, subprocess);
        }

        return subprocess.execute()
                .thenApply(result -> {
                    if (executionId != null) {
                        runningProcesses.remove(executionId);
                    }
                    return toAgentResult(result, resolvedModel);
                })
                .exceptionally(throwable -> {
                    if (executionId != null) {
                        runningProcesses.remove(executionId);
                    }
                    LOG.errorf(throwable, "Copilot CLI execution failed (executionId: %s)",
                            executionId);
                    return AgentResult.failure("Execution error: " + throwable.getMessage());
                });
    }

    /**
     * Converts a Copilot-specific result into the unified {@link AgentResult}.
     */
    private AgentResult toAgentResult(CopilotResult result, String resolvedModel) {
        String actualModel = result.model() != null ? result.model() : resolvedModel;
        return new AgentResult(
                result.isSuccess(),
                result.result(),
                result.isSuccess() ? null : result.result(),
                result.executionLog(),
                result.sessionId(),
                result.costUsd(),
                result.inputTokens(),
                result.outputTokens(),
                getType(),
                actualModel
        );
    }
}
