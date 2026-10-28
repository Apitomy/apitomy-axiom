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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.TimeUnit;

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
    private static final String OUTPUT_FILE_PREFIX = "axiom-claude-code-check-";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration STOP_WAIT = Duration.ofSeconds(2);
    /** Far more than the 200-character excerpt a check message quotes. */
    private static final int MAX_OUTPUT_BYTES = 4 * 1024;

    /**
     * Switches for the providers that {@code --bare} lets use their own
     * credentials, as the CLI checks them (2.1.306).
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
        return List.of(check(System.getenv(), PROBE_TIMEOUT, VERSION_TIMEOUT, null));
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
     * @param tempDir the directory for the CLI output file, or {@code null}
     *                for the default temporary directory
     * @return the check result
     */
    AgentCheckResult check(Map<String, String> env, Duration probeTimeout,
            Duration versionTimeout, Path tempDir) {
        boolean credentials = hasServerCredentials(env);
        CliRun run = credentials
                ? runCli(List.of(executable, "-p", "Reply with exactly: AXIOM_OK", "--bare",
                        "--output-format", "text", "--max-turns", "1"), probeTimeout, tempDir)
                : runCli(List.of(executable, "--version"), versionTimeout, tempDir);
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

    /** How a CLI invocation for the startup check ended. */
    enum Outcome { COMPLETED, TIMED_OUT, INTERRUPTED, NOT_STARTED, NO_OUTPUT_FILE }

    /**
     * One CLI invocation for the startup check.
     *
     * @param outcome how the invocation ended
     * @param exitCode the exit code, meaningful only when completed
     * @param output the combined output read so far, possibly empty
     * @param failure the cause when the CLI or its output file could not be set up
     */
    record CliRun(Outcome outcome, int exitCode, String output, String failure) {
    }

    /**
     * Runs the CLI with its combined output redirected to a temporary file,
     * so a chatty CLI can never block on a full pipe. The output is read
     * (bounded) also after a timeout, and the file is always removed; a
     * failed removal is logged and does not change the result.
     */
    static CliRun runCli(List<String> command, Duration timeout, Path tempDir) {
        Path outputFile;
        try {
            outputFile = tempDir == null
                    ? Files.createTempFile(OUTPUT_FILE_PREFIX, ".txt")
                    : Files.createTempFile(tempDir, OUTPUT_FILE_PREFIX, ".txt");
        } catch (IOException | RuntimeException e) {
            return new CliRun(Outcome.NO_OUTPUT_FILE, -1, "", e.getMessage());
        }
        try {
            Process process;
            try {
                process = start(command, outputFile);
            } catch (IOException | RuntimeException e) {
                return new CliRun(Outcome.NOT_STARTED, -1, "", e.getMessage());
            }
            return await(process, timeout, outputFile);
        } finally {
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException e) {
                LOG.debugf("Could not delete the startup check output file %s: %s",
                        outputFile, e.getMessage());
            }
        }
    }

    /**
     * Starts the CLI with its combined output going to the file and with
     * end-of-input on stdin: otherwise {@code -p} waits 3 s for piped input
     * and prints a warning about it into the output the check quotes.
     */
    private static Process start(List<String> command, Path outputFile) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(outputFile.toFile());
        Process process = pb.start();
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            LOG.debugf("Could not close the startup check's stdin: %s", e.getMessage());
        }
        return process;
    }

    /**
     * Waits for the CLI and reads its output, also after a timeout. On
     * interruption the process is stopped first, while the interrupt flag is
     * still clear so the stop can wait, and the flag is restored afterwards.
     */
    private static CliRun await(Process process, Duration timeout, Path outputFile) {
        try {
            if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return new CliRun(Outcome.COMPLETED, process.exitValue(),
                        readBounded(outputFile), null);
            }
            stop(process);
            return new CliRun(Outcome.TIMED_OUT, -1, readBounded(outputFile), null);
        } catch (InterruptedException e) {
            stop(process);
            Thread.currentThread().interrupt();
            return new CliRun(Outcome.INTERRUPTED, -1, "", null);
        }
    }

    /**
     * Kills the process and waits briefly for it to exit, so the output file
     * is no longer held open when it is read and deleted (Windows refuses to
     * delete a file that a running process holds).
     */
    private static void stop(Process process) {
        process.destroyForcibly();
        try {
            process.waitFor(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readBounded(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes(MAX_OUTPUT_BYTES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Builds the result of a test prompt sent with server credentials, where
     * any failure is conclusive. The run completed or timed out.
     */
    static AgentCheckResult probeResult(CliRun run, Duration timeout) {
        if (run.outcome() == Outcome.TIMED_OUT) {
            return new AgentCheckResult(CHECK_NAME, STATUS_ERROR,
                    "Claude Code CLI check timed out after " + timeout.toSeconds() + " seconds"
                            + excerptSuffix(run.output()) + ". " + PROBE_ADVICE);
        }
        if (run.exitCode() == 0 && run.output().contains("AXIOM_OK")) {
            return new AgentCheckResult(CHECK_NAME, STATUS_OK,
                    "Claude Code CLI is available and working.");
        }
        return new AgentCheckResult(CHECK_NAME, STATUS_ERROR,
                "Claude Code CLI probe failed with exit code " + run.exitCode()
                        + excerptSuffix(run.output()) + ". " + PROBE_ADVICE);
    }

    /**
     * Builds the result of the {@code --version} check that replaces the test
     * prompt when the server environment has no credentials. The run
     * completed or timed out.
     */
    static AgentCheckResult versionResult(CliRun run) {
        if (run.outcome() == Outcome.TIMED_OUT) {
            return new AgentCheckResult(CHECK_NAME, STATUS_WARNING,
                    "'claude --version' did not finish in time" + excerptSuffix(run.output())
                            + ", so the startup check could not confirm that the CLI starts.");
        }
        if (run.exitCode() != 0) {
            return new AgentCheckResult(CHECK_NAME, STATUS_ERROR,
                    "'claude --version' failed with exit code " + run.exitCode()
                            + excerptSuffix(run.output()) + ". Reinstall Claude Code: "
                            + INSTALL_HINT);
        }
        return new AgentCheckResult(CHECK_NAME, STATUS_WARNING,
                "Claude Code CLI starts" + excerptSuffix(run.output()) + ", but the startup "
                        + "check sent no test prompt: the server environment has no "
                        + "ANTHROPIC_API_KEY and no third-party provider switch such as "
                        + "CLAUDE_CODE_USE_BEDROCK. Agent "
                        + "runs authenticate only with the ANTHROPIC_API_KEY their action type "
                        + "declares in its Environment, which this check cannot verify. Set "
                        + "ANTHROPIC_API_KEY in the server environment and restart Axiom to "
                        + "verify the CLI at startup.");
    }

    /**
     * Maps the outcomes where the CLI did not run to completion or timeout,
     * shared by both checks: a CLI that cannot start is an error, while a
     * missing output file or an interrupted check says nothing about the CLI
     * and reports a warning. Returns {@code null} otherwise.
     */
    static AgentCheckResult notRunResult(CliRun run) {
        return switch (run.outcome()) {
            case NOT_STARTED -> new AgentCheckResult(CHECK_NAME, STATUS_ERROR,
                    "Claude Code CLI is not available: " + run.failure() + ". Install Claude "
                            + "Code: " + INSTALL_HINT);
            case NO_OUTPUT_FILE -> new AgentCheckResult(CHECK_NAME, STATUS_WARNING,
                    "Claude Code CLI check did not run: could not create a temporary file "
                            + "for its output (" + run.failure() + ").");
            case INTERRUPTED -> new AgentCheckResult(CHECK_NAME, STATUS_WARNING,
                    "Claude Code CLI check was interrupted before it finished.");
            default -> null;
        };
    }

    /**
     * Formats the CLI output as a quoted, single-line excerpt for a check
     * message, or an empty string when the output has no printable content.
     * The excerpt is capped so a chatty failure cannot flood the check list.
     * It quotes the CLI's own output verbatim and is operator-visible in the
     * server log and the UI, so it must stay an operator-facing diagnostic.
     */
    static String excerptSuffix(String output) {
        if (output == null) {
            return "";
        }
        String flat = output.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return "";
        }
        String excerpt = flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
        return ": \"" + excerpt + "\"";
    }

    private CompletableFuture<AgentResult> executeInternal(AgentRequest request,
                                                            String jsonSchema) {
        LOG.infof("Executing via Claude Code (executionId=%s)", request.getExecutionId());
        LOG.debugf("Plugin dirs applied to this execution: %s", configuredPluginDirs);

        ExecutionLogBuilder logBuilder = new ExecutionLogBuilder();
        logBuilder.header(0, "agent", Instant.now());
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
