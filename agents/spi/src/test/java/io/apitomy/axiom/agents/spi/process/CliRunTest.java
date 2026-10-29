package io.apitomy.axiom.agents.spi.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CliRun}.
 */
class CliRunTest {

    private static final Duration SHORT = Duration.ofSeconds(5);

    /** Output far beyond the pipe buffer completes instead of blocking until the deadline. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void largeOutputCompletesAndKeepsTheTail() {
        CliRun run = CliRun.run(List.of("sh", "-c",
                "i=0; while [ $i -lt 20000 ]; do echo line-$i; i=$((i+1)); done; echo THE-END; exit 2"),
                SHORT);
        assertEquals(CliRun.Outcome.COMPLETED, run.outcome());
        assertEquals(2, run.exitCode());
        assertTrue(run.output().contains("THE-END"));
        assertTrue(run.excerptSuffix().contains("THE-END"));
    }

    /** A hung CLI is killed with its children, and the output printed so far is kept. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void timeoutKillsTheProcessTreeAndKeepsOutput(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("child.pid");
        CliRun run = CliRun.run(List.of("sh", "-c",
                "sleep 60 & echo $! > " + pidFile + "; echo still-connecting; wait"), Duration.ofMillis(800));
        assertEquals(CliRun.Outcome.TIMED_OUT, run.outcome());
        assertTrue(run.output().contains("still-connecting"));
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        Optional<ProcessHandle> child = ProcessHandle.of(childPid);
        assertFalse(child.isPresent() && child.get().isAlive(), "child process survived the check");
    }

    /** A CLI that does not read stdin sees end-of-input instead of waiting. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void stdinIsClosed() {
        CliRun run = CliRun.run(List.of("sh", "-c", "cat; echo done"), SHORT);
        assertEquals(CliRun.Outcome.COMPLETED, run.outcome());
        assertTrue(run.succeededWith("done"));
    }

    /** A missing executable is reported, not thrown. */
    @Test
    void missingExecutableIsNotStarted(@TempDir Path dir) {
        CliRun run = CliRun.run(List.of(dir.resolve("no-such-cli").toString()), SHORT);
        assertEquals(CliRun.Outcome.NOT_STARTED, run.outcome());
        assertTrue(run.failure() != null && !run.failure().isBlank());
    }

    /** An interrupted run is reported and the interrupt flag is restored. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void interruptionIsReportedAndRestored() {
        Thread.currentThread().interrupt();
        try {
            CliRun run = CliRun.run(List.of("sleep", "30"), SHORT);
            assertEquals(CliRun.Outcome.INTERRUPTED, run.outcome());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /** Success needs both exit code 0 and the token. */
    @Test
    void succeededWithNeedsExitZeroAndToken() {
        assertTrue(new CliRun(CliRun.Outcome.COMPLETED, 0, "x AXIOM_OK", null).succeededWith("AXIOM_OK"));
        assertFalse(new CliRun(CliRun.Outcome.COMPLETED, 1, "AXIOM_OK", null).succeededWith("AXIOM_OK"));
        assertFalse(new CliRun(CliRun.Outcome.COMPLETED, 0, "nope", null).succeededWith("AXIOM_OK"));
        assertFalse(new CliRun(CliRun.Outcome.TIMED_OUT, -1, "AXIOM_OK", null).succeededWith("AXIOM_OK"));
    }

    /** The excerpt is flattened, keeps the end of long output, and is empty for blank output. */
    @Test
    void excerptIsFlattenedAndKeepsTheTail() {
        assertEquals(": \"a b\"", CliRun.excerptSuffix("a\n  b", 200));
        String suffix = CliRun.excerptSuffix("banner ".repeat(100) + "the real error", 20);
        assertEquals(": \"...anner the real error\"", suffix);
        assertEquals("", CliRun.excerptSuffix("   \n  ", 200));
        assertEquals("", CliRun.excerptSuffix(null, 200));
    }
}
