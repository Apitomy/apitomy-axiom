package io.apitomy.axiom.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedProcessRunnerTest {

    private static ProcessBuilder bash(String script) {
        return new ProcessBuilder("/bin/bash", "-c", script);
    }

    @Test
    void capturesNormalExitWithSeparateStreams() throws Exception {
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                bash("echo out; echo err >&2; exit 3"), Duration.ofSeconds(10));
        assertFalse(result.timedOut());
        assertEquals(3, result.exitCode());
        assertEquals("out\n", result.stdout());
        assertEquals("err\n", result.stderr());
        assertFalse(result.stdoutTruncated());
        assertFalse(result.stderrTruncated());
    }

    @Test
    void timeoutKillsSleepingProcessAndItsChildHoldingStdout(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("child.pid");
        // The child sleep inherits stdout, so a read-before-wait implementation would block for 60s.
        long start = System.nanoTime();
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                bash("sleep 60 & echo $! > " + pidFile + "; wait"), Duration.ofMillis(500));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(result.timedOut());
        assertTrue(elapsedMs < 10_000, "took " + elapsedMs + "ms");
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        Optional<ProcessHandle> child = ProcessHandle.of(childPid);
        assertTrue(child.isEmpty() || !child.get().isAlive(), "child sleep still alive");
    }

    @Test
    void largeOutputIsTruncatedWithoutDeadlock() throws Exception {
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                bash("head -c 5000000 /dev/zero | tr '\\0' 'a'; head -c 3000 /dev/zero | tr '\\0' 'b' >&2"),
                Duration.ofSeconds(30), 1024);
        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdoutTruncated());
        assertTrue(result.stdout().startsWith("a".repeat(1024)));
        assertTrue(result.stdout().contains(BoundedProcessRunner.TRUNCATION_MARKER));
        assertTrue(result.stderrTruncated());
        assertTrue(result.stderr().length() < 3000);
    }

    @Test
    void outputUnderLimitIsNotTruncated() throws Exception {
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                bash("printf '%s' abc"), Duration.ofSeconds(10), 3);
        assertEquals("abc", result.stdout());
        assertFalse(result.stdoutTruncated());
    }
}
