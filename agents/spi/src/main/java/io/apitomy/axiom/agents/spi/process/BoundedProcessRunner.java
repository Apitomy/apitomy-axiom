package io.apitomy.axiom.agents.spi.process;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs a subprocess with a hard deadline and bounded output capture.
 * <p>
 * Stdout and stderr are drained concurrently from the moment the process starts, so a full pipe can never block
 * the process and a process that keeps its output open can never block the caller past the deadline. On timeout
 * (or interruption of the calling thread) the whole process tree is terminated: descendants (deepest first) and
 * the process are asked to stop, then killed forcibly after a short grace period.
 * <p>
 * Each stream keeps at most a configurable number of bytes: the first quarter and the last three quarters of the
 * output, joined by {@link #TRUNCATION_MARKER}. Keeping the tail matters because scripts report their result as
 * the last JSON object on stdout. Truncation boundaries never split a UTF-8 character.
 * <p>
 * When the process exits normally, descendants observed while it ran that are still alive after a short grace
 * period are killed, so a forgotten background job cannot keep the output pipes open. A process that escaped
 * the tree before it could be observed (double fork, {@code setsid}/{@code nohup} detached and re-parented) is
 * not killed; the caller still gets the output captured so far after a bounded wait, but the reader thread
 * blocked on the pipe that process holds lives until that process exits or closes the pipe.
 */
public final class BoundedProcessRunner {

    /** Default maximum number of bytes captured per stream (1 MiB). */
    public static final int DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;

    /** Inserted into a stream's captured text where output beyond the limit was discarded. */
    public static final String TRUNCATION_MARKER = "\n[Output truncated]\n";

    /** Value of {@link Result#exitCode()} when the process timed out and has no exit code. */
    public static final int NO_EXIT_CODE = -1;

    /** Exit code callers report to users for a process that was killed after its timeout. */
    public static final int TIMEOUT_EXIT_CODE = 1;

    private static final Duration KILL_GRACE = Duration.ofSeconds(2);
    private static final Duration ORPHAN_GRACE = Duration.ofMillis(500);
    private static final Duration READER_JOIN = Duration.ofSeconds(5);
    private static final long DESCENDANT_POLL_MS = 200;
    private static final int MAX_KILL_ROUNDS = 5;

    private BoundedProcessRunner() {
    }

    /**
     * Result of a bounded subprocess run.
     *
     * @param exitCode        the process exit code, or {@link #NO_EXIT_CODE} when it timed out
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
     * Starts the process (closing its stdin, see {@link #start(ProcessBuilder)}) and runs it with the given output
     * limit.
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
        return await(start(builder), timeout, maxOutputBytes);
    }

    /**
     * Starts the process and, when its stdin is a pipe, closes that pipe at once so a process that reads stdin
     * sees end-of-input instead of waiting forever.
     *
     * @param builder the configured process builder
     * @return the started process
     * @throws IOException if the process cannot be started
     */
    public static Process start(ProcessBuilder builder) throws IOException {
        Process process = builder.start();
        if (builder.redirectInput() == ProcessBuilder.Redirect.PIPE) {
            try {
                process.getOutputStream().close();
            } catch (IOException e) {
                // The process may already have exited; nothing to close.
            }
        }
        return process;
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
        Capture stdout = drain(process.getInputStream(), maxOutputBytes, "stdout-" + process.pid());
        Capture stderr = drain(process.getErrorStream(), maxOutputBytes, "stderr-" + process.pid());
        Set<ProcessHandle> seen = new LinkedHashSet<>();
        boolean finished;
        try {
            finished = waitObservingDescendants(process, timeout, seen);
        } catch (InterruptedException e) {
            killTree(process);
            closeQuietly(process);
            throw e;
        }
        if (finished) {
            killLeftovers(seen);
        } else {
            killTree(process);
        }
        Captured out = stdout.await();
        Captured err = stderr.await();
        closeQuietly(process);
        long durationMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        int exitCode = finished ? process.exitValue() : NO_EXIT_CODE;
        return new Result(exitCode, out.text(), err.text(), !finished, out.truncated(), err.truncated(),
                durationMs);
    }

    /**
     * Terminates a process and all of its descendants: descendants are signalled deepest first (so none is
     * re-parented out of the tree before it is seen), the tree is re-collected until no new descendants appear
     * (bounded number of rounds), then the root is signalled. Survivors are killed forcibly after a short grace
     * period. Processes that detached themselves from the tree ({@code setsid}/{@code nohup} plus a double fork,
     * or anything re-parented before it was collected) can escape.
     *
     * @param process the process to terminate
     */
    public static void killTree(Process process) {
        List<ProcessHandle> killed = new ArrayList<>();
        Set<ProcessHandle> signalled = new LinkedHashSet<>();
        for (int round = 0; round < MAX_KILL_ROUNDS; round++) {
            List<ProcessHandle> fresh = deepestFirst(process.toHandle()).stream()
                    .filter(h -> !signalled.contains(h))
                    .toList();
            if (fresh.isEmpty()) {
                break;
            }
            fresh.forEach(ProcessHandle::destroy);
            signalled.addAll(fresh);
            killed.addAll(fresh);
        }
        process.destroy();
        killed.add(process.toHandle());
        awaitExit(killed, KILL_GRACE);
        // Children forked while the tree was dying, then everything left, deepest first.
        List<ProcessHandle> remaining = new ArrayList<>(deepestFirst(process.toHandle()));
        remaining.addAll(killed);
        remaining.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        try {
            process.waitFor(KILL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean waitObservingDescendants(Process process, Duration timeout, Set<ProcessHandle> seen)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            process.descendants().forEach(seen::add);
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs <= 0) {
                return !process.isAlive() || process.waitFor(0, TimeUnit.MILLISECONDS);
            }
            if (process.waitFor(Math.min(remainingMs, DESCENDANT_POLL_MS), TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
    }

    private static void killLeftovers(Set<ProcessHandle> seen) {
        List<ProcessHandle> alive = seen.stream().filter(ProcessHandle::isAlive).toList();
        if (alive.isEmpty()) {
            return;
        }
        awaitExit(alive, ORPHAN_GRACE);
        List<ProcessHandle> leftovers = new ArrayList<>();
        for (ProcessHandle handle : alive) {
            if (handle.isAlive()) {
                leftovers.addAll(deepestFirst(handle));
                leftovers.add(handle);
            }
        }
        if (leftovers.isEmpty()) {
            return;
        }
        leftovers.forEach(ProcessHandle::destroy);
        awaitExit(leftovers, KILL_GRACE);
        leftovers.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    /** Descendants of {@code root} (excluding it) ordered so that every process comes before its parent. */
    private static List<ProcessHandle> deepestFirst(ProcessHandle root) {
        List<ProcessHandle> breadthFirst = new ArrayList<>();
        Deque<ProcessHandle> queue = new ArrayDeque<>();
        root.children().forEach(queue::add);
        while (!queue.isEmpty()) {
            ProcessHandle handle = queue.poll();
            breadthFirst.add(handle);
            handle.children().forEach(queue::add);
        }
        Collections.reverse(breadthFirst);
        return breadthFirst;
    }

    private static void awaitExit(List<ProcessHandle> handles, Duration grace) {
        long deadline = System.nanoTime() + grace.toNanos();
        for (ProcessHandle handle : handles) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            try {
                handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Not gone yet; the caller kills it forcibly.
            }
        }
    }

    private record Captured(String text, boolean truncated) {
    }

    /**
     * Head-and-tail capture of one stream, shared between the reader thread and the caller under a lock so the
     * caller can take a snapshot when the reader does not finish in time.
     */
    private static final class Capture {

        private final byte[] head;
        private final byte[] tail;
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private int headSize;
        private int tailPos;
        private long tailWritten;

        Capture(int maxBytes) {
            int headCapacity = maxBytes / 4;
            this.head = new byte[headCapacity];
            this.tail = new byte[maxBytes - headCapacity];
        }

        synchronized void append(byte[] chunk, int length) {
            int offset = 0;
            int toHead = Math.min(length, head.length - headSize);
            if (toHead > 0) {
                System.arraycopy(chunk, 0, head, headSize, toHead);
                headSize += toHead;
                offset = toHead;
            }
            int remaining = length - offset;
            if (remaining <= 0 || tail.length == 0) {
                tailWritten += Math.max(remaining, 0);
                return;
            }
            if (remaining >= tail.length) {
                // Only the last tail.length bytes of this chunk can survive.
                System.arraycopy(chunk, length - tail.length, tail, 0, tail.length);
                tailPos = 0;
            } else {
                int firstPart = Math.min(remaining, tail.length - tailPos);
                System.arraycopy(chunk, offset, tail, tailPos, firstPart);
                System.arraycopy(chunk, offset + firstPart, tail, 0, remaining - firstPart);
                tailPos = (tailPos + remaining) % tail.length;
            }
            tailWritten += remaining;
        }

        synchronized Captured snapshot() {
            int tailSize = (int) Math.min(tailWritten, tail.length);
            byte[] tailBytes = new byte[tailSize];
            int first = tailWritten < tail.length ? 0 : tailPos;
            for (int i = 0; i < tailSize; i++) {
                tailBytes[i] = tail[(first + i) % tail.length];
            }
            boolean truncated = tailWritten > tail.length;
            if (!truncated) {
                byte[] all = new byte[headSize + tailSize];
                System.arraycopy(head, 0, all, 0, headSize);
                System.arraycopy(tailBytes, 0, all, headSize, tailSize);
                return new Captured(new String(all, StandardCharsets.UTF_8), false);
            }
            int headEnd = completeUtf8Prefix(head, headSize);
            int tailFrom = firstCharStart(tailBytes);
            String text = new String(head, 0, headEnd, StandardCharsets.UTF_8) + TRUNCATION_MARKER
                    + new String(tailBytes, tailFrom, tailSize - tailFrom, StandardCharsets.UTF_8);
            return new Captured(text, true);
        }

        Captured await() {
            try {
                done.get(READER_JOIN.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // Reader still blocked (an escaped process holds the pipe); return what was captured so far.
            }
            return snapshot();
        }
    }

    /** Length of the longest prefix of {@code bytes[0..length)} that does not end inside a UTF-8 sequence. */
    static int completeUtf8Prefix(byte[] bytes, int length) {
        for (int i = length - 1; i >= Math.max(0, length - 4); i--) {
            int b = bytes[i] & 0xFF;
            if ((b & 0xC0) == 0x80) {
                continue;
            }
            int needed = b >= 0xF0 ? 4 : b >= 0xE0 ? 3 : b >= 0xC0 ? 2 : 1;
            return i + needed <= length ? length : i;
        }
        return length;
    }

    /** Index of the first byte that is not a UTF-8 continuation byte (at most 3 are skipped). */
    static int firstCharStart(byte[] bytes) {
        int i = 0;
        while (i < bytes.length && i < 3 && (bytes[i] & 0xC0) == 0x80) {
            i++;
        }
        return i;
    }

    private static Capture drain(InputStream in, int maxBytes, String name) {
        Capture capture = new Capture(maxBytes);
        Thread reader = new Thread(() -> {
            byte[] chunk = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(chunk)) != -1) {
                    capture.append(chunk, n);
                }
            } catch (IOException e) {
                // Stream closed (e.g. after a kill); keep what was captured.
            } finally {
                capture.done.complete(null);
            }
        }, "bounded-process-" + name);
        reader.setDaemon(true);
        reader.start();
        return capture;
    }

    private static void closeQuietly(Process process) {
        for (Closeable c : List.of(process.getInputStream(), process.getErrorStream(),
                process.getOutputStream())) {
            try {
                c.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
