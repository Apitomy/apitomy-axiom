package io.apitomy.axiom.core.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Runs a subprocess with a hard deadline and bounded output capture.
 * <p>
 * Stdout and stderr are drained concurrently from the moment the process starts, so a full pipe can never block
 * the process and a process that keeps its output open can never block the caller past the deadline. On timeout
 * (or interruption of the calling thread) the whole process tree is terminated: descendants and the process are
 * asked to stop, then killed forcibly after a short grace period. Each stream keeps at most a configurable number
 * of bytes; anything beyond that is read and discarded, and the captured text ends with
 * {@link #TRUNCATION_MARKER}.
 */
public final class BoundedProcessRunner {

    /** Default maximum number of bytes captured per stream (1 MiB). */
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;

    /** Appended to a stream's captured text when output beyond the limit was discarded. */
    public static final String TRUNCATION_MARKER = "\n[Output truncated]";

    private static final Duration KILL_GRACE = Duration.ofSeconds(2);
    private static final Duration READER_JOIN = Duration.ofSeconds(5);

    private BoundedProcessRunner() {
    }

    /**
     * Result of a bounded subprocess run.
     *
     * @param exitCode        the process exit code, or {@code -1} when it timed out
     * @param stdout          captured stdout (UTF-8), possibly truncated
     * @param stderr          captured stderr (UTF-8), possibly truncated; empty if stderr was redirected
     * @param timedOut        true if the process was killed because it exceeded its deadline
     * @param stdoutTruncated true if stdout exceeded the capture limit
     * @param stderrTruncated true if stderr exceeded the capture limit
     * @param durationMs      wall-clock run time in milliseconds
     */
    public record Result(int exitCode, String stdout, String stderr, boolean timedOut,
                         boolean stdoutTruncated, boolean stderrTruncated, long durationMs) {
    }

    /**
     * Starts the process and runs it with the default output limit.
     *
     * @param builder the configured process builder
     * @param timeout maximum run time
     * @return the result
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if interrupted while waiting (the process tree is killed first)
     */
    public static Result run(ProcessBuilder builder, Duration timeout) throws IOException, InterruptedException {
        return run(builder, timeout, DEFAULT_MAX_OUTPUT_BYTES);
    }

    /**
     * Starts the process and runs it with the given output limit.
     *
     * @param builder        the configured process builder
     * @param timeout        maximum run time
     * @param maxOutputBytes maximum bytes captured per stream
     * @return the result
     * @throws IOException          if the process cannot be started
     * @throws InterruptedException if interrupted while waiting (the process tree is killed first)
     */
    public static Result run(ProcessBuilder builder, Duration timeout, int maxOutputBytes)
            throws IOException, InterruptedException {
        return await(builder.start(), timeout, maxOutputBytes);
    }

    /**
     * Supervises an already started process (e.g. one whose stdin the caller feeds) with a deadline counted from
     * this call.
     *
     * @param process        the started process
     * @param timeout        maximum run time
     * @param maxOutputBytes maximum bytes captured per stream
     * @return the result
     * @throws InterruptedException if interrupted while waiting (the process tree is killed first)
     */
    public static Result await(Process process, Duration timeout, int maxOutputBytes)
            throws InterruptedException {
        long start = System.nanoTime();
        CompletableFuture<Captured> stdout = drain(process.getInputStream(), maxOutputBytes, "stdout");
        CompletableFuture<Captured> stderr = drain(process.getErrorStream(), maxOutputBytes, "stderr");
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            killTree(process);
            closeQuietly(process);
            throw e;
        }
        if (!finished) {
            killTree(process);
        }
        Captured out = join(stdout);
        Captured err = join(stderr);
        closeQuietly(process);
        long durationMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        int exitCode = finished ? process.exitValue() : -1;
        return new Result(exitCode, out.text(), err.text(), !finished, out.truncated(), err.truncated(),
                durationMs);
    }

    /**
     * Terminates a process and all of its descendants: gracefully first, then forcibly after a short grace
     * period.
     *
     * @param process the process to terminate
     */
    public static void killTree(Process process) {
        List<ProcessHandle> tree = process.descendants().collect(Collectors.toList());
        tree.add(process.toHandle());
        tree.forEach(ProcessHandle::destroy);
        long deadline = System.nanoTime() + KILL_GRACE.toNanos();
        for (ProcessHandle handle : tree) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try {
                    handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
                } catch (Exception e) {
                    // Fall through to forcible kill.
                }
            }
        }
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        try {
            process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Captured(String text, boolean truncated) {
    }

    private static CompletableFuture<Captured> drain(InputStream in, int maxBytes, String name) {
        CompletableFuture<Captured> future = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            boolean truncated = false;
            byte[] chunk = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(chunk)) != -1) {
                    int room = maxBytes - buffer.size();
                    if (n > room) {
                        truncated = true;
                    }
                    if (room > 0) {
                        buffer.write(chunk, 0, Math.min(n, room));
                    }
                }
            } catch (IOException e) {
                // Stream closed (e.g. after a kill); keep what was captured.
            }
            String text = buffer.toString(StandardCharsets.UTF_8);
            future.complete(new Captured(truncated ? text + TRUNCATION_MARKER : text, truncated));
        }, "bounded-process-" + name);
        reader.setDaemon(true);
        reader.start();
        return future;
    }

    private static Captured join(CompletableFuture<Captured> future) {
        try {
            return future.get(READER_JOIN.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Reader stuck (e.g. an escaped grandchild still holds the pipe); give up on its output.
        }
        return new Captured("", false);
    }

    private static void closeQuietly(Process process) {
        for (java.io.Closeable c : List.of(process.getInputStream(), process.getErrorStream(),
                process.getOutputStream())) {
            try {
                c.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
