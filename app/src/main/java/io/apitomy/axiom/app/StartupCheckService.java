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
                results.add(new CheckResult(
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
                results.add(new CheckResult(
                        "Node.js",
                        "ok",
                        "Node.js " + output + " is available."
                ));
                LOG.infof("Startup check OK: Node.js %s", output);
            } else {
                results.add(new CheckResult(
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
            results.add(new CheckResult(
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

    /**
     * Result of a single startup check.
     */
    public record CheckResult(String name, String status, String message) {
    }
}
