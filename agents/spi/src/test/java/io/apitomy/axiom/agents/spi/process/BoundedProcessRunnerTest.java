package io.apitomy.axiom.agents.spi.process;

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
        assertTrue(result.stdout().startsWith("a"));
        assertTrue(result.stdout().contains(BoundedProcessRunner.TRUNCATION_MARKER));
        assertEquals(1024 + BoundedProcessRunner.TRUNCATION_MARKER.length(), result.stdout().length());
        assertTrue(result.stderrTruncated());
        assertTrue(result.stderr().length() < 3000);
    }

    @Test
    void truncationKeepsTheTailSoAFinalJsonResultSurvives() throws Exception {
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                bash("head -c 3000000 /dev/zero | tr '\\0' 'l'; echo; echo '{\"ok\":true}'"),
                Duration.ofSeconds(30));
        assertTrue(result.stdoutTruncated());
        assertTrue(result.stdout().startsWith("lll"));
        assertTrue(result.stdout().contains(BoundedProcessRunner.TRUNCATION_MARKER));
        assertTrue(result.stdout().endsWith("\n{\"ok\":true}\n"), "tail lost");
        assertTrue(result.stdout().length() <= BoundedProcessRunner.DEFAULT_MAX_OUTPUT_BYTES
                + BoundedProcessRunner.TRUNCATION_MARKER.length());
    }

    @Test
    void truncationDoesNotSplitMultibyteCharacters() throws Exception {
        // "\u00e9" is two bytes in UTF-8; an odd limit forces boundaries inside a character.
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                bash("for i in $(seq 1 500); do printf '\\xc3\\xa9'; done"), Duration.ofSeconds(10), 101);
        assertTrue(result.stdoutTruncated());
        String text = result.stdout().replace(BoundedProcessRunner.TRUNCATION_MARKER, "");
        assertFalse(text.contains("\uFFFD"), text);
        assertTrue(text.chars().allMatch(c -> c == '\u00e9'), text);
    }

    @Test
    void stdinIsClosedSoReadersSeeEndOfInput() throws Exception {
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(bash("cat; echo done"),
                Duration.ofSeconds(10));
        assertFalse(result.timedOut());
        assertEquals("done\n", result.stdout());
    }

    @Test
    void backgroundChildHoldingStdoutIsKilledAfterNormalExit(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("child.pid");
        long start = System.nanoTime();
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                bash("sleep 60 & echo $! > " + pidFile + "; echo hi; sleep 0.5"), Duration.ofSeconds(30));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
        assertEquals("hi\n", result.stdout());
        assertTrue(elapsedMs < 10_000, "took " + elapsedMs + "ms");
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        Optional<ProcessHandle> child = ProcessHandle.of(childPid);
        assertTrue(child.isEmpty() || !child.get().isAlive(), "background sleep still alive");
    }

    @Test
    void escapedProcessHoldingStdoutDoesNotLoseCapturedOutput(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("escaped.pid");
        // The subshell exits at once, so the sleep is re-parented before it can be seen as a descendant.
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                bash("(sleep 60 & echo $! > " + pidFile + "); echo captured"), Duration.ofSeconds(30));
        try {
            assertFalse(result.timedOut());
            assertEquals("captured\n", result.stdout());
        } finally {
            ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim()))
                    .ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    void killTreeKillsGrandchildren(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("grandchild.pid");
        Process process = bash("bash -c 'sleep 60 & echo $! > " + pidFile + "; wait' & wait").start();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.exists(pidFile) || Files.readString(pidFile).isBlank()) {
            assertTrue(System.nanoTime() < deadline, "grandchild never started");
            Thread.sleep(20);
        }
        BoundedProcessRunner.killTree(process);
        assertFalse(process.isAlive());
        Optional<ProcessHandle> grandchild = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim()));
        assertTrue(grandchild.isEmpty() || !grandchild.get().isAlive(), "grandchild still alive");
    }

    @Test
    void outputUnderLimitIsNotTruncated() throws Exception {
        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                bash("printf '%s' abc"), Duration.ofSeconds(10), 3);
        assertEquals("abc", result.stdout());
        assertFalse(result.stdoutTruncated());
    }
}
