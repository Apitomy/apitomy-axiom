package io.apitomy.axiom.agents.opencode;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of an {@code opencode serve} process. Starts the server
 * on demand, monitors health, and restarts on crash with exponential backoff.
 *
 * <p>Axiom only talks to a server it launched itself. If something else is already
 * answering on the configured port (e.g. an {@code opencode serve} started by a user
 * from an unrelated or deleted directory), a warning is logged and Axiom launches its
 * own server on a free port instead of silently reusing the foreign one.</p>
 */
public class OpenCodeServerManager {

    private static final Logger LOG = Logger.getLogger(OpenCodeServerManager.class);
    private static final int MAX_RESTART_ATTEMPTS = 5;
    private static final long INITIAL_BACKOFF_MS = 1000;

    private final String hostname;
    private final int port;
    private int activePort;
    private boolean axiomLaunched;

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
        this.activePort = port;
    }

    /**
     * Ensures the OpenCode server is running. Starts it if not already running.
     *
     * @return the client connected to the running server
     */
    public synchronized OpenCodeClient ensureRunning() {
        if (client != null && serverProcess != null && serverProcess.isAlive() && client.isHealthy()) {
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
            axiomLaunched = false;
            activePort = selectLaunchPort();
            LOG.infof("Starting OpenCode server on %s:%d", hostname, activePort);
            ProcessBuilder pb = new ProcessBuilder(
                    "opencode", "serve",
                    "--port", String.valueOf(activePort),
                    "--hostname", hostname
            );
            pb.redirectErrorStream(false);
            pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
            // Inherit environment (API keys, etc.)

            serverProcess = pb.start();
            client = new OpenCodeClient(hostname, activePort);

            // Wait for server to become healthy
            waitForHealthy();
            axiomLaunched = true;
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
     * Chooses the port to launch the server on: the configured port, unless a server Axiom
     * did not start is already answering there, in which case a free ephemeral port is used.
     *
     * @return the port to pass to {@code opencode serve}
     * @throws IOException if no free port can be allocated
     */
    int selectLaunchPort() throws IOException {
        if (!isForeignServerRunning(port)) {
            return port;
        }
        int alternate;
        try (ServerSocket socket = new ServerSocket(0)) {
            alternate = socket.getLocalPort();
        }
        LOG.warnf("An OpenCode server that Axiom did not start is already running on %s:%d; "
                + "not reusing it (its working directory and configuration are unknown). "
                + "Starting Axiom's own OpenCode server on port %d instead.", hostname, port, alternate);
        return alternate;
    }

    /**
     * Returns whether a healthy OpenCode server not launched by this manager is listening on the port.
     *
     * @param candidatePort the port to probe
     * @return true if a foreign server answers health checks on the port
     */
    boolean isForeignServerRunning(int candidatePort) {
        if (axiomLaunched && serverProcess != null && serverProcess.isAlive() && candidatePort == activePort) {
            return false;
        }
        return new OpenCodeClient(hostname, candidatePort).isHealthy();
    }

    /**
     * Returns whether the currently connected server was launched by Axiom.
     *
     * @return true if Axiom started the server process the client talks to
     */
    public boolean isAxiomLaunched() {
        return axiomLaunched;
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
            // Check our own process first so a server that already holds the port is never
            // mistaken for the one we just launched.
            if (serverProcess != null && !serverProcess.isAlive()) {
                throw new RuntimeException("OpenCode server process exited with code "
                        + serverProcess.exitValue());
            }
            if (client.isHealthy()) {
                return;
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
        axiomLaunched = false;
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
