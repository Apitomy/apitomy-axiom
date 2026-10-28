package io.apitomy.axiom.agents.claudecode;

import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentCheckResult;
import io.apitomy.axiom.agents.spi.AgentRequest;
import io.apitomy.axiom.agents.spi.AgentResult;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Unified agent implementation for Claude Code. Executes prompts by launching
 * the Claude Code CLI as a subprocess. Merges the functionality of the former
 * {@code ClaudeCodeActor} and {@code ClaudeCodeEngine}.
 */
@ApplicationScoped
public class ClaudeCodeAgent implements Agent {

    private static final Logger LOG = Logger.getLogger(ClaudeCodeAgent.class);

    @ConfigProperty(name = "axiom.agent.claude-code.executable", defaultValue = "claude")
    String executable;

    @ConfigProperty(name = "axiom.agent.claude-code.model")
    Optional<String> defaultModel;

    @ConfigProperty(name = "axiom.agent.claude-code.max-steps", defaultValue = "50")
    int defaultMaxSteps;

    @ConfigProperty(name = "axiom.agent.claude-code.max-budget-usd", defaultValue = "5.0")
    double defaultMaxBudgetUsd;

    @ConfigProperty(name = "axiom.agent.claude-code.timeout-seconds", defaultValue = "600")
    int defaultTimeoutSeconds;

    @ConfigProperty(name = "axiom.agent.claude-code.available-models", defaultValue = "")
    String availableModels;

    /*
     * Optional rather than defaultValue = "": MicroProfile Config treats an
     * empty string as a missing value, so an empty default makes the property
     * required and fails bean creation when it is not configured.
     */
    @ConfigProperty(name = "axiom.agent.claude-code.plugin-dirs")
    Optional<String> pluginDirs;

    @Inject
    ClaudeCodeMcpManager mcpManager;

    private List<String> configuredPluginDirs = List.of();

    private final Map<String, ClaudeCodeSubprocess> runningProcesses = new ConcurrentHashMap<>();

    /** {@inheritDoc} */
    @Override
    public String getType() {
        return "claude-code";
    }

    /** {@inheritDoc} */
    @Override
    public String getLabel() {
        return "Claude Code";
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

    /** {@inheritDoc} */
    @Override
    public boolean supportsInteractiveSessions() {
        return true;
    }

    /**
     * Parses the configured plugin directories once per bean so tasks neither
     * re-parse the config nor re-log misconfiguration warnings per execution.
     */
    @PostConstruct
    void initPluginDirs() {
        configuredPluginDirs = parsePluginDirs(pluginDirs.orElse(null));
        if (!configuredPluginDirs.isEmpty()) {
            LOG.infof("Claude Code plugin dirs: %s", configuredPluginDirs);
        }
    }

    /**
     * Parses the raw comma-separated plugin path configuration. Blank entries
     * are ignored. Entries that are not absolute paths are logged and skipped:
     * the CLI would otherwise resolve them against each task's working
     * directory, silently loading a different plugin per task. Entries that do
     * not exist are logged and still passed through: the CLI ignores a missing
     * {@code --plugin-dir} without any diagnostic of its own, so this warning
     * is the only signal an operator gets. Entries that cannot be parsed as
     * paths are logged and skipped rather than failing bean creation.
     *
     * @param config the raw comma-separated configuration value
     * @return the plugin paths in configured order, never {@code null}
     */
    static List<String> parsePluginDirs(String config) {
        if (config == null || config.isBlank()) {
            return List.of();
        }
        List<String> dirs = new ArrayList<>();
        for (String dir : config.split(",")) {
            String trimmed = dir.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path path;
            try {
                path = Path.of(trimmed);
            } catch (InvalidPathException e) {
                LOG.warnf("Skipping configured plugin path that cannot be parsed as a path: %s", trimmed);
                continue;
            }
            if (!path.isAbsolute()) {
                LOG.warnf("Skipping configured plugin path that is not absolute: %s", trimmed);
                continue;
            }
            if (!Files.exists(path)) {
                LOG.warnf("Configured plugin path does not exist: %s", trimmed);
            }
            dirs.add(trimmed);
        }
        return dirs;
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<AgentResult> execute(AgentRequest request) {
        return executeInternal(request, null);
    }

    /** {@inheritDoc} */
    @Override
    public void cancel(String executionId) {
        ClaudeCodeSubprocess subprocess = runningProcesses.remove(executionId);
        if (subprocess != null) {
            LOG.infof("Cancelling execution %s", executionId);
            subprocess.kill();
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<AgentCheckResult> healthCheck() {
        List<AgentCheckResult> results = new ArrayList<>();

        try {
            ProcessBuilder pb = new ProcessBuilder(executable, "-p", "Reply with exactly: AXIOM_OK",
                    "--bare", "--output-format", "text", "--max-turns", "1");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                results.add(new AgentCheckResult(
                        "Claude Code CLI",
                        "error",
                        "Claude Code CLI check timed out after 30 seconds. "
                                + "Ensure that the 'claude' command is on your PATH and that "
                                + "your Anthropic API key is configured correctly."
                ));
                return results;
            }

            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.exitValue();

            if (exitCode == 0 && output.contains("AXIOM_OK")) {
                results.add(new AgentCheckResult(
                        "Claude Code CLI",
                        "ok",
                        "Claude Code CLI is available and working."
                ));
            } else {
                results.add(new AgentCheckResult(
                        "Claude Code CLI",
                        "error",
                        "Claude Code CLI returned exit code " + exitCode + ". "
                                + "Ensure that the 'claude' command is installed, on your PATH, "
                                + "and that ANTHROPIC_API_KEY is set in your environment. "
                                + "Install Claude Code: npm install -g @anthropic-ai/claude-code"
                ));
            }
        } catch (Exception e) {
            results.add(new AgentCheckResult(
                    "Claude Code CLI",
                    "error",
                    "Claude Code CLI is not available: " + e.getMessage() + ". "
                            + "Install Claude Code: npm install -g @anthropic-ai/claude-code"
            ));
        }

        return results;
    }

    private CompletableFuture<AgentResult> executeInternal(AgentRequest request,
                                                            String jsonSchema) {
        LOG.infof("Executing via Claude Code (executionId=%s)", request.getExecutionId());
        LOG.debugf("Plugin dirs applied to this execution: %s", configuredPluginDirs);

        ExecutionLogBuilder logBuilder = new ExecutionLogBuilder();
        logBuilder.header(request.getTaskId(), request.getActionType(), Instant.now());
        if (request.getSystemPrompt() != null) {
            logBuilder.systemPrompt(request.getSystemPrompt());
        }
        logBuilder.prompt(request.getPrompt());
        logBuilder.allowedTools(request.getAllowedTools());
        if (request.getEnvironment() != null && !request.getEnvironment().isEmpty()) {
            logBuilder.environment(request.getEnvironment());
        }

        int maxSteps = request.getMaxSteps() > 0 ? request.getMaxSteps() : defaultMaxSteps;
        Double maxBudget = request.getMaxBudgetUsd() != null
                ? request.getMaxBudgetUsd() : defaultMaxBudgetUsd;
        int timeoutSecs = request.getTimeoutSeconds() > 0
                ? request.getTimeoutSeconds() : defaultTimeoutSeconds;

        ClaudeCodeCommandBuilder cmdBuilder = ClaudeCodeCommandBuilder
                .fromRequest(request)
                .executable(executable)
                .streamJson(true)
                .maxTurns(maxSteps)
                .maxBudgetUsd(maxBudget)
                .pluginDirs(configuredPluginDirs);

        String resolvedModel;
        if (request.getModel() != null && !request.getModel().isBlank()) {
            resolvedModel = request.getModel();
            cmdBuilder.model(resolvedModel);
        } else {
            resolvedModel = defaultModel.orElse(null);
            defaultModel.ifPresent(cmdBuilder::model);
        }

        if (request.getSessionId() != null) {
            cmdBuilder.sessionId(request.getSessionId());
        }

        List<String> command = cmdBuilder.build();

        if (jsonSchema != null) {
            command.add("--json-schema");
            command.add(jsonSchema);
        }

        java.io.File workDir = request.getWorkingDirectory() != null
                ? request.getWorkingDirectory().toFile() : null;

        ClaudeCodeSubprocess subprocess = new ClaudeCodeSubprocess(
                command, workDir,
                request.getEnvironment() != null ? request.getEnvironment() : Map.of(),
                Duration.ofSeconds(timeoutSecs),
                null, logBuilder
        );

        if (request.getExecutionId() != null) {
            runningProcesses.put(request.getExecutionId(), subprocess);
        }

        return subprocess.execute()
                .thenApply(result -> {
                    if (request.getExecutionId() != null) {
                        runningProcesses.remove(request.getExecutionId());
                    }
                    return toAgentResult(result, resolvedModel);
                })
                .exceptionally(throwable -> {
                    if (request.getExecutionId() != null) {
                        runningProcesses.remove(request.getExecutionId());
                    }
                    LOG.errorf(throwable, "Execution %s failed", request.getExecutionId());
                    return AgentResult.failure("Execution error: " + throwable.getMessage());
                });
    }

    private AgentResult toAgentResult(ClaudeCodeResult result, String resolvedModel) {
        String actualModel = result.model() != null ? result.model() : resolvedModel;
        return new AgentResult(
                result.isSuccess(),
                result.result(),
                result.isSuccess() ? null : result.result(),
                result.executionLog(),
                result.sessionId(),
                result.totalCostUsd(),
                result.inputTokens(),
                result.outputTokens(),
                getType(),
                actualModel
        );
    }
}
