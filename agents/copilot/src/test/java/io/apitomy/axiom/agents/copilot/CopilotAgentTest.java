package io.apitomy.axiom.agents.copilot;

import io.apitomy.axiom.agents.spi.AgentCheckResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the startup check in {@link CopilotAgent}, run against fake CLI scripts.
 */
class CopilotAgentTest {

    private static final Duration SHORT = Duration.ofSeconds(5);

    private static CopilotAgent agentWith(Path executable) {
        CopilotAgent agent = new CopilotAgent();
        agent.executable = executable.toString();
        return agent;
    }

    private static Path fakeCli(Path dir, String body) throws IOException {
        Path script = dir.resolve("copilot");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    /** The expected answer with exit code 0 is ok. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void answeredProbeIsOk(@TempDir Path dir) throws IOException {
        AgentCheckResult result = agentWith(fakeCli(dir, "echo AXIOM_OK")).check(SHORT);
        assertEquals("ok", result.status());
    }

    /**
     * A CLI that writes more than a pipe buffer is not reported as a timeout,
     * and the error quotes the end of its output.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void chattyFailureReportsTheRealError(@TempDir Path dir) throws IOException {
        Path cli = fakeCli(dir, "i=0; while [ $i -lt 20000 ]; do echo progress-line; i=$((i+1)); done\n"
                + "echo 'No authentication information found'; exit 1");
        AgentCheckResult result = agentWith(cli).check(SHORT);
        assertEquals("error", result.status());
        assertTrue(result.message().contains("exit code 1"));
        assertTrue(result.message().contains("No authentication information found"));
        assertFalse(result.message().contains("timed out"));
    }

    /** A hung CLI times out, its output is quoted, and its child processes are killed. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void hungCheckQuotesOutputAndKillsChildren(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("child.pid");
        Path cli = fakeCli(dir, "sleep 60 & echo $! > " + pidFile + "; echo still-connecting; wait");
        AgentCheckResult result = agentWith(cli).check(Duration.ofMillis(800));
        assertEquals("error", result.status());
        assertTrue(result.message().contains("timed out"));
        assertTrue(result.message().contains("still-connecting"));
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                "child process survived the check");
    }

    /** A CLI that waits for stdin sees end-of-input instead of hanging until the deadline. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void stdinIsClosed(@TempDir Path dir) throws IOException {
        AgentCheckResult result = agentWith(fakeCli(dir, "cat >/dev/null; echo AXIOM_OK")).check(SHORT);
        assertEquals("ok", result.status());
    }

    /** A missing CLI is an error with the install hint. */
    @Test
    void missingCliIsAnError(@TempDir Path dir) {
        AgentCheckResult result = agentWith(dir.resolve("no-such-copilot")).check(SHORT);
        assertEquals("error", result.status());
        assertTrue(result.message().contains("not available"));
        assertTrue(result.message().contains("npm install"));
    }
}
