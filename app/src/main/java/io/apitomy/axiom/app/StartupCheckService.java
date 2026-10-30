package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentCheckResult;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.time.Duration;
import io.apitomy.axiom.agents.spi.process.BoundedProcessRunner;

/**
 * Performs startup configuration checks to verify that the application
 * environment is properly configured. Results are exposed via the
 * system config endpoint for the UI to display.
 */
@ApplicationScoped
public class StartupCheckService {

    private static final Logger LOG = Logger.getLogger(StartupCheckService.class);

    @Inject
    AgentRegistry agentRegistry;

    private final List<CheckResult> results = new ArrayList<>();
    private final Map<String, List<CheckResult>> engineResults = new LinkedHashMap<>();

    /**
     * Runs all startup checks when the application starts.
     *
     * @param event the Quarkus startup event
     */
    void onStart(@Observes StartupEvent event) {
        LOG.info("Running startup configuration checks...");
        checkNodeJs();
        checkOptionalRuntimes();
        checkAllEngines();

        long errors = results.stream().filter(r -> "error".equals(r.status())).count();
        long warnings = results.stream().filter(r -> "warning".equals(r.status())).count();
        if (errors > 0 || warnings > 0) {
            LOG.warnf("Startup checks: %d error(s), %d warning(s)", errors, warnings);
        } else {
            LOG.info("All startup checks passed");
        }
    }

    /**
     * Returns the results of all startup checks.
     *
     * @return the list of check results
     */
    public List<CheckResult> getResults() {
        return List.copyOf(results);
    }

    /**
     * Returns the health check results for a specific engine type.
     *
     * @param engineType the engine type identifier
     * @return the list of check results for that engine (may be empty)
     */
    public List<CheckResult> getResultsForEngine(String engineType) {
        return engineResults.getOrDefault(engineType, List.of());
    }

    private void checkNodeJs() {
        try {
            ProcessBuilder pb = new ProcessBuilder("node", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                results.add(CheckResult.required(
                        "Node.js",
                        "error",
                        "Node.js check timed out. Ensure that the 'node' command is "
                                + "installed and on your PATH."
                ));
                LOG.warn("Startup check FAILED: Node.js check timed out");
                return;
            }

            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.exitValue();

            if (exitCode == 0 && output.startsWith("v")) {
                results.add(CheckResult.required(
                        "Node.js",
                        "ok",
                        "Node.js " + output + " is available."
                ));
                LOG.infof("Startup check OK: Node.js %s", output);
            } else {
                results.add(CheckResult.required(
                        "Node.js",
                        "error",
                        "Node.js returned unexpected output (exit code " + exitCode + "). "
                                + "Ensure Node.js v18 or later is installed. "
                                + "Download from https://nodejs.org/"
                ));
                LOG.warnf("Startup check FAILED: Node.js exit code %d, output: %s",
                        exitCode, output);
            }
        } catch (Exception e) {
            results.add(CheckResult.required(
                    "Node.js",
                    "error",
                    "Node.js is not installed or not on your PATH. Node.js is required "
                            + "to run the MCP tool server that provides custom tools to AI agents. "
                            + "Install Node.js v18 or later from https://nodejs.org/"
            ));
            LOG.warnf("Startup check FAILED: Node.js not found: %s", e.getMessage());
        }
    }

    void checkAllEngines() {
        for (Agent agent : agentRegistry.getAllAgents()) {
            LOG.infof("Checking AI engine: %s", agent.getType());
            List<CheckResult> perEngine = new ArrayList<>();
            for (AgentCheckResult engineResult : agent.healthCheck()) {
                // Every list carries the true status; the UI gates on engines[].available.
                CheckResult result = new CheckResult(engineResult.name(),
                        engineResult.status(), engineResult.message());
                perEngine.add(result);
                results.add(result);

                if ("ok".equals(engineResult.status())) {
                    LOG.infof("Startup check OK: %s", engineResult.name());
                } else {
                    LOG.warnf("Startup check %s: %s — %s",
                            engineResult.status().toUpperCase(), engineResult.name(),
                            engineResult.message());
                }
            }
            engineResults.put(agent.getType(), List.copyOf(perEngine));
        }

        if (!anyEngineAvailable()) {
            LOG.error("No AI engines are available — at least one engine must be configured");
        }
    }

    /**
     * Returns true if at least one engine passed its startup checks without errors. This is the same rule
     * as {@code engines[].available} in the system config, which the UI uses to decide whether Axiom is
     * usable.
     *
     * @return true if any engine has no error results
     */
    public boolean anyEngineAvailable() {
        return engineResults.values().stream().anyMatch(StartupCheckService::hasNoErrors);
    }

    private static boolean hasNoErrors(List<CheckResult> checks) {
        return checks.stream().noneMatch(c -> "error".equals(c.status()));
    }

    // ── Optional MCP server runtimes ─────────────────────────────────

    /** Timeout for each runtime check command. */
    private static final Duration RUNTIME_CHECK_TIMEOUT = Duration.ofSeconds(10);

    /** Check names of the optional runtimes. */
    static final String NPX = "npx";
    static final String UVX = "uvx";
    static final String DOCKER = "Docker";

    /**
     * Output of a check command.
     *
     * @param exitCode the exit code
     * @param output combined stdout and stderr
     * @param timedOut whether the command was killed after the timeout
     */
    record CommandResult(int exitCode, String output, boolean timedOut) {
    }

    /** Runs a check command. Throws {@link IOException} when the command cannot be started. */
    @FunctionalInterface
    interface CommandRunner {
        /**
         * Runs a command.
         *
         * @param command the command and arguments
         * @return the result
         * @throws IOException if the command cannot be started (e.g. not installed)
         * @throws InterruptedException if interrupted
         */
        CommandResult run(List<String> command) throws IOException, InterruptedException;
    }

    /** Runs check commands; replaceable in tests. */
    CommandRunner commandRunner = command -> {
        BoundedProcessRunner.Result r = BoundedProcessRunner.run(
                new ProcessBuilder(command).redirectErrorStream(true), RUNTIME_CHECK_TIMEOUT);
        return new CommandResult(r.exitCode(), r.stdout(), r.timedOut());
    };

    /**
     * Checks the optional runtimes that MCP servers are started with: {@code npx} (Node.js packages),
     * {@code uvx} (Python packages) and Docker (container images). A missing runtime is a warning, never an
     * error: only MCP servers that use it are affected.
     */
    void checkOptionalRuntimes() {
        checkTool(NPX, List.of("npx", "--version"),
                "MCP servers that run with npx (Node.js packages), such as Slack, Notion or Playwright",
                "npx comes with Node.js; install Node.js v18 or later from https://nodejs.org/");
        checkTool(UVX, List.of("uvx", "--version"),
                "MCP servers that run with uvx (Python packages), such as Jira & Confluence, PostgreSQL or Git",
                "Install uv: https://docs.astral.sh/uv/getting-started/installation/");
        checkDocker();
    }

    private void checkTool(String name, List<String> command, String usedBy, String installHint) {
        try {
            CommandResult r = commandRunner.run(command);
            if (r.timedOut()) {
                optionalWarning(name, "'" + String.join(" ", command) + "' timed out. " + usedBy
                        + " may not start.");
            } else if (r.exitCode() == 0) {
                String version = versionOf(r.output());
                optionalOk(name, name + " " + version + " is available for " + usedBy + ".");
            } else {
                optionalWarning(name, "'" + String.join(" ", command) + "' failed (exit code " + r.exitCode()
                        + "): " + firstLine(r.output()) + ". " + usedBy + " won't start. " + installHint);
            }
        } catch (IOException e) {
            optionalWarning(name, name + " not found on the PATH: " + usedBy + " won't start. " + installHint);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void checkDocker() {
        String usedBy = "MCP servers that run as Docker containers (e.g. from the MCP Registry)";
        String installHint = "Install Docker from https://docs.docker.com/get-docker/ "
                + "(with Podman, provide a 'docker' command, e.g. the podman-docker package)";
        String clientVersion;
        try {
            CommandResult client = commandRunner.run(List.of("docker", "--version"));
            if (client.timedOut() || client.exitCode() != 0) {
                optionalWarning(DOCKER, "'docker --version' failed: " + usedBy + " won't start. " + installHint);
                return;
            }
            clientVersion = versionOf(client.output());
            CommandResult server = commandRunner.run(List.of("docker", "info", "--format", "{{.ServerVersion}}"));
            if (server.timedOut()) {
                optionalWarning(DOCKER, "Docker " + clientVersion + " is installed, but 'docker info' timed out. "
                        + "Check that the Docker daemon is running.");
            } else if (server.exitCode() != 0) {
                optionalWarning(DOCKER, "Docker " + clientVersion + " is installed, but the Docker daemon is not "
                        + "reachable: " + firstLine(server.output()) + ". " + usedBy + " won't start until it runs.");
            } else {
                optionalOk(DOCKER, "Docker " + clientVersion + " (daemon " + firstLine(server.output())
                        + ") is available for " + usedBy + ".");
            }
        } catch (IOException e) {
            optionalWarning(DOCKER, "docker not found on the PATH: " + usedBy + " won't start. " + installHint);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void optionalOk(String name, String message) {
        results.add(CheckResult.optional(name, "ok", message));
        LOG.infof("Startup check OK: %s", message);
    }

    private void optionalWarning(String name, String message) {
        results.add(CheckResult.optional(name, "warning", message));
        LOG.warnf("Startup check WARNING (optional): %s — %s", name, message);
    }

    /**
     * Returns which MCP runtimes passed their startup checks.
     *
     * @return {@code node}, {@code uv} and {@code docker} availability, or an empty map if the checks have not run
     */
    public Map<String, Boolean> runtimeAvailability() {
        Map<String, Boolean> availability = new LinkedHashMap<>();
        Map<String, String> keys = Map.of(NPX, "node", UVX, "uv", DOCKER, "docker");
        for (String name : List.of(NPX, UVX, DOCKER)) {
            results.stream().filter(r -> r.name().equals(name) && Boolean.FALSE.equals(r.required())).findFirst()
                    .ifPresent(r -> availability.put(keys.get(name), "ok".equals(r.status())));
        }
        return availability;
    }

    private static String firstLine(String output) {
        String trimmed = output == null ? "" : output.strip();
        int nl = trimmed.indexOf('\n');
        return nl < 0 ? trimmed : trimmed.substring(0, nl).strip();
    }

    /** Returns the first version-like number in a tool's output (e.g. {@code 0.12.3} from {@code uvx 0.12.3 (...)}). */
    private static String versionOf(String output) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+\\.\\d+[\\w.-]*)").matcher(output);
        return m.find() ? m.group(1) : firstLine(output);
    }

    /**
     * Result of a single startup check.
     *
     * @param name the check name
     * @param status {@code ok}, {@code warning} or {@code error}
     * @param message details or instructions
     * @param required for prerequisites: whether Axiom needs it ({@code true}) or only some features do
     *                 ({@code false}); {@code null} for engine checks
     */
    public record CheckResult(String name, String status, String message, Boolean required) {

        /**
         * Creates an engine check result (not a prerequisite).
         *
         * @param name the check name
         * @param status the status
         * @param message the message
         */
        public CheckResult(String name, String status, String message) {
            this(name, status, message, null);
        }

        static CheckResult required(String name, String status, String message) {
            return new CheckResult(name, status, message, true);
        }

        static CheckResult optional(String name, String status, String message) {
            return new CheckResult(name, status, message, false);
        }
    }
}
