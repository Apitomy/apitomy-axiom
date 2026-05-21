package io.apicurio.axiom.engine.bob;

import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages an IBM Bob CLI subprocess. Handles launching the process,
 * reading plain-text output, and enforcing timeouts.
 */
public class BobSubprocess {

    private static final Logger LOG = Logger.getLogger(BobSubprocess.class);

    private final List<String> command;
    private final java.io.File workingDirectory;
    private final Map<String, String> environment;
    private final Duration timeout;

    private Process process;

    public BobSubprocess(List<String> command, java.io.File workingDirectory,
                         Map<String, String> environment, Duration timeout) {
        this.command = command;
        this.workingDirectory = workingDirectory;
        this.environment = environment;
        this.timeout = timeout;
    }

    public CompletableFuture<BobResult> execute() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doExecute();
            } catch (Exception e) {
                LOG.errorf(e, "Bob subprocess failed");
                return BobResult.failed("Subprocess error: " + e.getMessage(), -1);
            }
        });
    }

    public void kill() {
        if (process != null && process.isAlive()) {
            LOG.warn("Killing Bob subprocess");
            process.destroyForcibly();
        }
    }

    private BobResult doExecute() throws IOException, InterruptedException {
        LOG.infof("Launching Bob: %s", String.join(" ",
                command.subList(0, Math.min(command.size(), 5))) + "...");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));

        if (workingDirectory != null) {
            pb.directory(workingDirectory);
        }

        if (environment != null) {
            pb.environment().putAll(environment);
        }

        process = pb.start();

        StringBuilder stdoutContent = new StringBuilder();
        StringBuilder stderrContent = new StringBuilder();

        CompletableFuture<Void> stdoutFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!stdoutContent.isEmpty()) stdoutContent.append("\n");
                    stdoutContent.append(line);
                }
            } catch (IOException e) {
                LOG.warnf("Error reading Bob stdout: %s", e.getMessage());
            }
        });

        CompletableFuture<Void> stderrFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderrContent.append(line).append("\n");
                    LOG.debugf("Bob stderr: %s", line);
                }
            } catch (IOException e) {
                LOG.warnf("Error reading Bob stderr: %s", e.getMessage());
            }
        });

        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            LOG.warnf("Bob subprocess timed out after %s", timeout);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return new BobResult("Process timed out after " + timeout, 124);
        }

        stdoutFuture.join();
        stderrFuture.join();

        int exitCode = process.exitValue();
        LOG.infof("Bob subprocess exited with code %d", exitCode);

        if (exitCode != 0) {
            String error = stderrContent.toString().trim();
            if (error.isEmpty()) {
                error = stdoutContent.toString().trim();
            }
            if (error.isEmpty()) {
                error = "Process exited with code " + exitCode;
            }
            return new BobResult(error, exitCode);
        }

        return new BobResult(stdoutContent.toString(), exitCode);
    }
}
