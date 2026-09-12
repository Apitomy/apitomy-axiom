package io.apitomy.axiom.agents.opencode;

import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Discovers OpenCode models by executing {@code opencode models} and caches the
 * results for a configurable duration.
 */
final class OpenCodeModelDiscovery {

    private static final Logger LOG = Logger.getLogger(OpenCodeModelDiscovery.class);

    private final String executable;
    private final boolean enabled;
    private final Duration timeout;
    private final Duration cacheTtl;
    private final Clock clock;
    private final CommandRunner commandRunner;

    private volatile CacheEntry cacheEntry;

    /**
     * Creates a model discovery service with a default process runner.
     *
     * @param executable the OpenCode executable name/path
     * @param enabled whether dynamic discovery is enabled
     * @param timeoutSeconds timeout for the {@code opencode models} command
     * @param cacheSeconds cache TTL in seconds
     */
    OpenCodeModelDiscovery(String executable, boolean enabled,
                           int timeoutSeconds, int cacheSeconds) {
        this(executable, enabled, timeoutSeconds, cacheSeconds,
                Clock.systemUTC(), OpenCodeModelDiscovery::runCommand);
    }

    OpenCodeModelDiscovery(String executable, boolean enabled,
                           int timeoutSeconds, int cacheSeconds,
                           Clock clock, CommandRunner commandRunner) {
        this.executable = executable;
        this.enabled = enabled;
        this.timeout = Duration.ofSeconds(Math.max(timeoutSeconds, 1));
        this.cacheTtl = Duration.ofSeconds(Math.max(cacheSeconds, 1));
        this.clock = clock;
        this.commandRunner = commandRunner;
    }

    /**
     * Discovers models, using cache when available.
     *
     * @return discovered models in provider/model format, or an empty list
     */
    synchronized List<String> discoverModels() {
        if (!enabled) {
            return List.of();
        }

        Instant now = clock.instant();
        CacheEntry cached = cacheEntry;
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.models();
        }

        try {
            CommandResult result = commandRunner.run(executable, timeout);
            if (result.exitCode() != 0) {
                LOG.warnf("OpenCode model discovery failed (exit=%d)", result.exitCode());
                return List.of();
            }

            List<String> models = parseModels(result.output());
            if (models.isEmpty()) {
                LOG.warn("OpenCode model discovery returned no models");
                return List.of();
            }

            cacheEntry = new CacheEntry(models, now.plus(cacheTtl));
            return models;
        } catch (Exception e) {
            LOG.warnf(e, "OpenCode model discovery failed");
            return List.of();
        }
    }

    /**
     * Refreshes models by bypassing cache and replacing it with newly
     * discovered values when available.
     *
     * @return refreshed models, or an empty list when discovery fails
     */
    synchronized List<String> refreshModels() {
        cacheEntry = null;
        return discoverModels();
    }

    static List<String> parseModels(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }

        Set<String> deduped = new LinkedHashSet<>();
        String[] lines = output.split("\\R");
        for (String line : lines) {
            String model = line.trim();
            if (model.isEmpty() || !model.contains("/")) {
                continue;
            }
            deduped.add(model);
        }
        return new ArrayList<>(deduped);
    }

    private static CommandResult runCommand(String executable, Duration timeout)
            throws java.io.IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(executable, "models");
        pb.redirectErrorStream(true);
        Process process = pb.start();

        boolean completed = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            return new CommandResult(-1, "");
        }
        String output = new String(process.getInputStream().readAllBytes());
        return new CommandResult(process.exitValue(), output);
    }

    @FunctionalInterface
    interface CommandRunner {
        CommandResult run(String executable, Duration timeout) throws Exception;
    }

    record CommandResult(int exitCode, String output) {
    }

    private record CacheEntry(List<String> models, Instant expiresAt) {
    }
}
