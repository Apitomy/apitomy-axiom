package io.apitomy.axiom.app.assistant.runtime.opencode;

import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of a per-session {@code opencode serve} process.
 */
public final class OpenCodeSessionServerProcess {

    private static final Logger LOG = Logger.getLogger(OpenCodeSessionServerProcess.class);

    private final String executable;
    private final String hostname;
    private final int configuredPort;
    private final int startupTimeoutSeconds;

    private volatile Process process;
    private volatile int resolvedPort;
    private volatile OpenCodeAssistantClient client;

    /**
     * Creates a per-session OpenCode server process manager.
     *
     * @param executable OpenCode executable name or path
     * @param hostname host to bind
     * @param configuredPort explicit port or {@code 0} for ephemeral
     * @param startupTimeoutSeconds startup timeout in seconds
     */
    public OpenCodeSessionServerProcess(String executable,
                                        String hostname,
                                        int configuredPort,
                                        int startupTimeoutSeconds) {
        this.executable = executable;
        this.hostname = hostname;
        this.configuredPort = configuredPort;
        this.startupTimeoutSeconds = startupTimeoutSeconds;
    }

    /**
     * Starts the OpenCode session-scoped server and blocks until healthy.
     */
    public synchronized void start() {
        if (isAlive()) {
            return;
        }

        resolvedPort = configuredPort == 0 ? resolveEphemeralPort() : configuredPort;

        ProcessBuilder processBuilder = new ProcessBuilder(
                executable,
                "serve",
                "--hostname", hostname,
                "--port", String.valueOf(resolvedPort)
        );
        processBuilder.redirectErrorStream(true);

        try {
            process = processBuilder.start();
            drainOutput(process);
            client = new OpenCodeAssistantClient(baseUrl());
            waitForHealthy();
        } catch (IOException e) {
            stop();
            throw new IllegalStateException("Failed to start OpenCode server process", e);
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    /**
     * Stops the OpenCode session-scoped server process.
     */
    public synchronized void stop() {
        Process localProcess = process;
        process = null;
        client = null;

        if (localProcess == null || !localProcess.isAlive()) {
            return;
        }

        localProcess.destroy();
        try {
            if (!localProcess.waitFor(10, TimeUnit.SECONDS)) {
                localProcess.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            localProcess.destroyForcibly();
        }
    }

    /**
     * Returns the OpenCode server base URL.
     *
     * @return base URL
     */
    public String baseUrl() {
        if (resolvedPort <= 0) {
            throw new IllegalStateException("Server has not been started");
        }
        return "http://" + hostname + ":" + resolvedPort;
    }

    /**
     * Returns whether the underlying process is alive.
     *
     * @return true if alive
     */
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    private int resolveEphemeralPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to resolve ephemeral port", e);
        }
    }

    private void waitForHealthy() {
        OpenCodeAssistantClient localClient = client;
        Process localProcess = process;
        if (localClient == null || localProcess == null) {
            throw new IllegalStateException("OpenCode server process was not initialized");
        }

        Instant deadline = Instant.now().plusSeconds(startupTimeoutSeconds);
        while (Instant.now().isBefore(deadline)) {
            if (!localProcess.isAlive()) {
                throw new IllegalStateException(
                        "OpenCode server exited during startup with code " + localProcess.exitValue());
            }

            OpenCodeAssistantClient.HealthStatus health = localClient.health();
            if (health.healthy()) {
                return;
            }

            try {
                Thread.sleep(Duration.ofMillis(250));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for OpenCode health", e);
            }
        }

        throw new IllegalStateException("OpenCode server did not become healthy within "
                + startupTimeoutSeconds + " seconds");
    }

    private void drainOutput(Process localProcess) {
        Thread.ofVirtual().name("opencode-serve-output").start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(localProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.debugf("opencode serve: %s", line);
                }
            } catch (IOException e) {
                LOG.debugf("OpenCode output stream closed: %s", e.getMessage());
            }
        });
    }
}
