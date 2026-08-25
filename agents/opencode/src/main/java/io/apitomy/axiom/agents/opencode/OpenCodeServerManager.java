package io.apitomy.axiom.agents.opencode;

import org.jboss.logging.Logger;

import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of an {@code opencode serve} process. Starts the server
 * on demand, monitors health, and restarts on crash with exponential backoff.
 */
public class OpenCodeServerManager {

    private static final Logger LOG = Logger.getLogger(OpenCodeServerManager.class);
    private static final int MAX_RESTART_ATTEMPTS = 5;
    private static final long INITIAL_BACKOFF_MS = 1000;

    private final String hostname;
    private final int port;

    private Process serverProcess;
    private OpenCodeClient client;
    private int restartAttempts = 0;

    /**
     * Creates a new server manager for the given host and port.
     *
     * @param hostname the hostname to bind the server to
     * @param port     the port to bind the server to
     */
    public OpenCodeServerManager(String hostname, int port) {
        this.hostname = hostname;
        this.port = port;
    }

    /**
     * Ensures the OpenCode server is running. Starts it if not already running.
     *
     * @return the client connected to the running server
     */
    public synchronized OpenCodeClient ensureRunning() {
        if (client != null && client.isHealthy()) {
            return client;
        }

        // Server not healthy -- try to start or restart
        if (serverProcess != null && serverProcess.isAlive()) {
            LOG.warn("OpenCode server process alive but not healthy, killing and restarting");
            serverProcess.destroyForcibly();
        }

        start();
        return client;
    }

    /**
     * Starts the OpenCode server process.
     */
    private void start() {
        if (restartAttempts >= MAX_RESTART_ATTEMPTS) {
            throw new IllegalStateException(
                    "OpenCode server failed to start after " + MAX_RESTART_ATTEMPTS + " attempts");
        }

        try {
            LOG.infof("Starting OpenCode server on %s:%d", hostname, port);
            ProcessBuilder pb = new ProcessBuilder(
                    "opencode", "serve",
                    "--port", String.valueOf(port),
                    "--hostname", hostname
            );
            pb.redirectErrorStream(false);
            pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
            // Inherit environment (API keys, etc.)

            serverProcess = pb.start();
            client = new OpenCodeClient(hostname, port);

            // Wait for server to become healthy
            waitForHealthy();
            restartAttempts = 0;
            LOG.infof("OpenCode server started (PID: %d, version: %s)",
                    serverProcess.pid(), client.getVersion());

        } catch (Exception e) {
            restartAttempts++;
            long backoff = INITIAL_BACKOFF_MS * (1L << (restartAttempts - 1));
            LOG.errorf(e, "Failed to start OpenCode server (attempt %d/%d), retrying in %dms",
                    restartAttempts, MAX_RESTART_ATTEMPTS, backoff);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            start(); // recursive retry with backoff
        }
    }

    /**
     * Waits for the server to become healthy, polling every 500ms up to 30 seconds.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    private void waitForHealthy() throws InterruptedException {
        int maxWaitMs = 30_000;
        int intervalMs = 500;
        int waited = 0;

        while (waited < maxWaitMs) {
            if (client.isHealthy()) {
                return;
            }
            if (serverProcess != null && !serverProcess.isAlive()) {
                throw new RuntimeException("OpenCode server process exited with code "
                        + serverProcess.exitValue());
            }
            Thread.sleep(intervalMs);
            waited += intervalMs;
        }
        throw new RuntimeException("OpenCode server did not become healthy within "
                + (maxWaitMs / 1000) + " seconds");
    }

    /**
     * Stops the server process.
     */
    public synchronized void stop() {
        if (serverProcess != null && serverProcess.isAlive()) {
            LOG.info("Stopping OpenCode server");
            serverProcess.destroy();
            try {
                if (!serverProcess.waitFor(10, TimeUnit.SECONDS)) {
                    serverProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                serverProcess.destroyForcibly();
            }
        }
        serverProcess = null;
        client = null;
    }

    /**
     * Checks if the OpenCode CLI binary is available on PATH.
     *
     * @return true if {@code opencode --version} succeeds
     */
    public static boolean isOpenCodeAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("opencode", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Gets the OpenCode CLI version string.
     *
     * @return the CLI version string, or null if unavailable
     */
    public static String getCliVersion() {
        try {
            ProcessBuilder pb = new ProcessBuilder("opencode", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            if (completed && process.exitValue() == 0) {
                return new String(process.getInputStream().readAllBytes()).trim();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * Returns the current client instance.
     *
     * @return the OpenCode client, or null if the server has not been started
     */
    public OpenCodeClient getClient() {
        return client;
    }
}
