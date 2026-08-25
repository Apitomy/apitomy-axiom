package io.apitomy.axiom.agents.copilot;

import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentCheckResult;
import io.apitomy.axiom.agents.spi.AgentRequest;
import io.apitomy.axiom.agents.spi.AgentResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    @ConfigProperty(name = "axiom.agent.copilot.executable", defaultValue = "copilot")
    String executable;

    @ConfigProperty(name = "axiom.agent.copilot.model")
    Optional<String> model;

    @ConfigProperty(name = "axiom.agent.copilot.timeout-seconds", defaultValue = "600")
    int defaultTimeoutSeconds;

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
     * <p>Copilot CLI does not have a native {@code --json-schema} flag, so structured
     * output is achieved by appending schema instructions to the prompt, instructing
     * the model to respond with only a valid JSON object conforming to the schema.</p>
     */
    @Override
    public CompletableFuture<AgentResult> executeWithSchema(AgentRequest request,
                                                             String jsonSchema) {
        return executeInternal(request, jsonSchema);
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
        List<AgentCheckResult> results = new ArrayList<>();

        try {
            ProcessBuilder pb = new ProcessBuilder(executable, "-p",
                    "Reply with exactly: AXIOM_OK", "--allow-all-tools",
                    "--disable-builtin-mcps", "--output-format", "text", "-s");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                results.add(new AgentCheckResult(
                        "GitHub Copilot CLI",
                        "error",
                        "GitHub Copilot CLI check timed out after 30 seconds. "
                                + "Ensure that the 'copilot' command is on your PATH and that "
                                + "you are logged in (run 'copilot login')."
                ));
                return results;
            }

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.exitValue();

            if (exitCode == 0 && output.contains("AXIOM_OK")) {
                results.add(new AgentCheckResult(
                        "GitHub Copilot CLI",
                        "ok",
                        "GitHub Copilot CLI is available and working."
                ));
            } else {
                results.add(new AgentCheckResult(
                        "GitHub Copilot CLI",
                        "error",
                        "GitHub Copilot CLI returned exit code " + exitCode + ". "
                                + "Ensure that 'copilot' is installed, on your PATH, and that "
                                + "you are authenticated (run 'copilot login'). "
                                + "Install: npm install -g @github/copilot"
                ));
            }
        } catch (Exception e) {
            results.add(new AgentCheckResult(
                    "GitHub Copilot CLI",
                    "error",
                    "GitHub Copilot CLI is not available: " + e.getMessage() + ". "
                            + "Install GitHub Copilot CLI: npm install -g @github/copilot"
            ));
        }

        return results;
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
     * Core execution logic shared by {@link #execute(AgentRequest)} and
     * {@link #executeWithSchema(AgentRequest, String)}.
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
        if (request.getModel() != null && !request.getModel().isBlank()) {
            cmdBuilder.model(request.getModel());
        } else {
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
                    return toAgentResult(result);
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
    private static AgentResult toAgentResult(CopilotResult result) {
        return new AgentResult(
                result.isSuccess(),
                result.result(),
                result.isSuccess() ? null : result.result(),
                result.executionLog(),
                result.sessionId(),
                result.costUsd(),
                result.inputTokens(),
                result.outputTokens()
        );
    }
}
