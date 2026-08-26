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

    /**
     * Returns true if there are any errors in the startup checks.
     *
     * @return true if any check has status "error"
     */
    public boolean hasErrors() {
        return results.stream().anyMatch(r -> "error".equals(r.status()));
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

    private void checkAllEngines() {
        // First pass: collect per-engine results with their true status
        Map<String, List<AgentCheckResult>> rawResults = new LinkedHashMap<>();
        for (Agent agent : agentRegistry.getAllAgents()) {
            LOG.infof("Checking AI engine: %s", agent.getType());
            rawResults.put(agent.getType(), agent.healthCheck());
        }

        // Determine if at least one engine is fully healthy (no errors)
        boolean anyEngineHealthy = rawResults.values().stream()
                .anyMatch(checks -> checks.stream()
                        .noneMatch(c -> "error".equals(c.status())));

        // Second pass: store per-engine results as-is, but downgrade errors
        // to warnings in the system-level list when another engine is healthy
        for (var entry : rawResults.entrySet()) {
            String engineType = entry.getKey();
            List<AgentCheckResult> agentChecks = entry.getValue();
            boolean thisEngineHealthy = agentChecks.stream()
                    .noneMatch(c -> "error".equals(c.status()));

            List<CheckResult> perEngine = new ArrayList<>();
            for (AgentCheckResult engineResult : agentChecks) {
                // Per-engine results always reflect the true status
                CheckResult perEngineResult = new CheckResult(engineResult.name(),
                        engineResult.status(), engineResult.message());
                perEngine.add(perEngineResult);

                // System-level: downgrade to warning if this engine failed
                // but at least one other engine is healthy
                String systemStatus = engineResult.status();
                if ("error".equals(systemStatus) && anyEngineHealthy && !thisEngineHealthy) {
                    systemStatus = "warning";
                }
                results.add(new CheckResult(engineResult.name(),
                        systemStatus, engineResult.message()));

                if ("ok".equals(engineResult.status())) {
                    LOG.infof("Startup check OK: %s", engineResult.name());
                } else {
                    LOG.warnf("Startup check %s: %s — %s",
                            engineResult.status().toUpperCase(), engineResult.name(),
                            engineResult.message());
                }
            }
            engineResults.put(engineType, List.copyOf(perEngine));
        }

        if (!anyEngineHealthy) {
            LOG.error("No AI engines are available — at least one engine must be configured");
        }
    }

    /**
     * Result of a single startup check.
     */
    public record CheckResult(String name, String status, String message) {
    }
}
