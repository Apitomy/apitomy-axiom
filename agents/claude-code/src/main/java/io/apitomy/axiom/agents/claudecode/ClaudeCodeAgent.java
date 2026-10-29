package io.apitomy.axiom.agents.claudecode;

import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentCheckResult;
import io.apitomy.axiom.agents.spi.AgentRequest;
import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.agents.spi.process.CliRun;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified agent implementation for Claude Code. Executes prompts by launching
 * the Claude Code CLI as a subprocess. Merges the functionality of the former
 * {@code ClaudeCodeActor} and {@code ClaudeCodeEngine}.
 */
@ApplicationScoped
public class ClaudeCodeAgent implements Agent {

    private static final Logger LOG = Logger.getLogger(ClaudeCodeAgent.class);

    private static final String CHECK_NAME = "Claude Code CLI";
    private static final String STATUS_OK = "ok";
    private static final String STATUS_WARNING = "warning";
    private static final String STATUS_ERROR = "error";
    private static final String INSTALL_HINT = "npm install -g @anthropic-ai/claude-code";
    private static final String PROBE_ADVICE =
            "Check the API key, the configured model, and network access.";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Switches for the providers that {@code --bare} lets use their own
     * credentials, as of CLI 2.1.306. A provider added later just takes the
     * {@code --version} path, which is harmless, so the list need not be
     * exhaustive.
     */
    private static final List<String> THIRD_PARTY_PROVIDER_SWITCHES = List.of(
            "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY",
            "CLAUDE_CODE_USE_ANTHROPIC_AWS", "CLAUDE_CODE_USE_ANTHROPIC_GOOGLE_CLOUD",
            "CLAUDE_CODE_USE_MANTLE");

    /** The values the CLI reads as "on" for a boolean environment switch. */
    private static final List<String> ENABLED_VALUES = List.of("1", "true", "yes", "on");

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
        return List.of(check(System.getenv(), PROBE_TIMEOUT, VERSION_TIMEOUT));
    }

    /**
     * Checks the CLI against the given server environment. Agent runs always
     * pass {@code --bare}, which authenticates strictly from
     * {@code ANTHROPIC_API_KEY} (or a third-party provider's own credentials)
     * and never reads stored logins. Without such credentials in the server
     * environment a test prompt can only fail, so the check does not send one:
     * it confirms that the CLI starts ({@code --version}) and reports a
     * warning that the runs' credentials, which each action type declares in
     * its own Environment, cannot be verified here. With credentials it sends
     * the test prompt, and any failure is a misconfiguration.
     *
     * @param env the server environment
     * @param probeTimeout the time limit for the test prompt
     * @param versionTimeout the time limit for {@code --version}
     * @return the check result
     */
    AgentCheckResult check(Map<String, String> env, Duration probeTimeout,
            Duration versionTimeout) {
        boolean credentials = hasServerCredentials(env);
        CliRun run = credentials
                ? CliRun.run(List.of(executable, "-p", "Reply with exactly: AXIOM_OK", "--bare",
                        "--output-format", "text", "--max-turns", "1"), probeTimeout)
                : CliRun.run(List.of(executable, "--version"), versionTimeout);
        AgentCheckResult notRun = notRunResult(run);
        if (notRun != null) {
            return notRun;
        }
        return credentials ? probeResult(run, probeTimeout) : versionResult(run);
    }

    /**
     * Returns true when the environment lets a {@code --bare} CLI
     * authenticate: a non-blank {@code ANTHROPIC_API_KEY}, or one of the
     * third-party provider switches, which use their own credentials.
     */
    static boolean hasServerCredentials(Map<String, String> env) {
        String apiKey = env.get("ANTHROPIC_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            return true;
        }
        return THIRD_PARTY_PROVIDER_SWITCHES.stream().anyMatch(name -> isEnabled(env.get(name)));
    }

    private static boolean isEnabled(String value) {
        return value != null && ENABLED_VALUES.contains(value.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Builds the result of a test prompt sent with server credentials, where
     * any failure is conclusive. The run completed or timed out.
     */
    static AgentCheckResult probeResult(CliRun run, Duration timeout) {
        if (run.outcome() == CliRun.Outcome.TIMED_OUT) {
            return new AgentCheckResult(CHECK_NAME, STATUS_ERROR,
                    "Claude Code CLI check timed out after " + timeout.toSeconds() + " seconds"
                            + run.excerptSuffix() + ". " + PROBE_ADVICE);
        }
        if (run.succeededWith("AXIOM_OK")) {
            return new AgentCheckResult(CHECK_NAME, STATUS_OK,
                    "Claude Code CLI is available and working.");
        }
        return new AgentCheckResult(CHECK_NAME, STATUS_ERROR,
                "Claude Code CLI probe failed with exit code " + run.exitCode()
                        + run.excerptSuffix() + ". " + PROBE_ADVICE);
    }

    /**
     * Builds the result of the {@code --version} check that replaces the test
     * prompt when the server environment has no credentials. The run
     * completed or timed out. {@code --version} makes no network call, so a
     * timeout means a broken install (for example a Node.js problem or a
     * wrapper waiting on input) and reports an error, like a non-zero exit.
     */
    static AgentCheckResult versionResult(CliRun run) {
        if (run.outcome() == CliRun.Outcome.TIMED_OUT) {
            return new AgentCheckResult(CHECK_NAME, STATUS_ERROR,
                    "'claude --version' did not finish in time" + run.excerptSuffix()
                            + ". Check the Claude Code installation: " + INSTALL_HINT);
        }
        if (run.exitCode() != 0) {
            return new AgentCheckResult(CHECK_NAME, STATUS_ERROR,
                    "'claude --version' failed with exit code " + run.exitCode()
                            + run.excerptSuffix() + ". Reinstall Claude Code: "
                            + INSTALL_HINT);
        }
        return new AgentCheckResult(CHECK_NAME, STATUS_WARNING,
                "Claude Code CLI starts" + run.excerptSuffix() + ". No test prompt was "
                        + "sent: the server environment has no ANTHROPIC_API_KEY and no "
                        + "third-party provider switch such as CLAUDE_CODE_USE_BEDROCK. Agent "
                        + "runs authenticate with the ANTHROPIC_API_KEY their action type "
                        + "declares in its Environment, which the startup check cannot verify. "
                        + "This is expected when keys are configured per action type.");
    }

    /**
     * Maps the outcomes where the CLI did not run to completion or timeout,
     * shared by both checks: a CLI that cannot start is an error, while an
     * interrupted check says nothing about the CLI and reports a warning.
     * Returns {@code null} otherwise.
     */
    static AgentCheckResult notRunResult(CliRun run) {
        return switch (run.outcome()) {
            case NOT_STARTED -> new AgentCheckResult(CHECK_NAME, STATUS_ERROR,
                    "Claude Code CLI is not available: " + run.failure() + ". Install Claude "
                            + "Code: " + INSTALL_HINT);
            case INTERRUPTED -> new AgentCheckResult(CHECK_NAME, STATUS_WARNING,
                    "Claude Code CLI check was interrupted before it finished.");
            default -> null;
        };
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
