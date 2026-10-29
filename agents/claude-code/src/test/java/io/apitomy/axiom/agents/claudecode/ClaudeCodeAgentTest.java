package io.apitomy.axiom.agents.claudecode;

import io.apitomy.axiom.agents.spi.AgentCheckResult;
import io.apitomy.axiom.agents.spi.process.CliRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the plugin directory parsing and the startup check in
 * {@link ClaudeCodeAgent}.
 */
class ClaudeCodeAgentTest {

    /**
     * A missing or blank configuration must yield an empty list, so no
     * {@code --plugin-dir} flag is produced (the default configuration).
     */
    @Test
    void blankConfigYieldsNoPluginDirs() {
        assertEquals(List.of(), ClaudeCodeAgent.parsePluginDirs(null));
        assertEquals(List.of(), ClaudeCodeAgent.parsePluginDirs(""));
        assertEquals(List.of(), ClaudeCodeAgent.parsePluginDirs("   "));
    }

    /**
     * Entries are trimmed and blank entries between commas are dropped, in
     * configured order.
     */
    @Test
    void trimsEntriesAndDropsBlankOnes() {
        List<String> dirs = ClaudeCodeAgent.parsePluginDirs(
                " /plugins/one , , /plugins/two ,");
        assertEquals(List.of("/plugins/one", "/plugins/two"), dirs);
    }

    /**
     * A relative entry must be skipped (the CLI would resolve it against each
     * task's working directory), while absolute entries survive.
     */
    @Test
    void skipsRelativeEntries() {
        List<String> dirs = ClaudeCodeAgent.parsePluginDirs(
                "plugins/cve-finder, /plugins/absolute");
        assertEquals(List.of("/plugins/absolute"), dirs);
    }

    /**
     * An absolute entry pointing at a missing path is still passed through. The
     * CLI ignores a missing {@code --plugin-dir} without any diagnostic, so the
     * parser's warning is the only signal an operator gets.
     */
    @Test
    void keepsAbsoluteEntryForMissingDirectory() {
        String missing = Path.of("/").resolve("axiom-no-such-plugin-dir").toString();
        assertEquals(List.of(missing), ClaudeCodeAgent.parsePluginDirs(missing));
    }

    /**
     * An existing absolute directory passes through unchanged.
     */
    @Test
    void keepsAbsoluteEntryForExistingDirectory() {
        String existing = System.getProperty("java.io.tmpdir");
        assertEquals(List.of(existing), ClaudeCodeAgent.parsePluginDirs(existing));
    }

    /**
     * An entry that cannot be parsed as a path at all is skipped with a
     * warning instead of throwing out of bean creation.
     */
    @Test
    void skipsUnparseableEntries() {
        assertEquals(List.of(), ClaudeCodeAgent.parsePluginDirs("bad\u0000dir"));
    }

    /** Generous for fake CLIs that exit at once, short enough to fail fast on a hang. */
    private static final Duration SHORT = Duration.ofSeconds(3);

    private static CliRun completed(int exitCode, String output) {
        return new CliRun(CliRun.Outcome.COMPLETED, exitCode, output, null);
    }

    private static ClaudeCodeAgent agentWith(Path executable) {
        ClaudeCodeAgent agent = new ClaudeCodeAgent();
        agent.executable = executable.toString();
        return agent;
    }

    /** Writes an executable shell script standing in for the CLI. */
    private static Path fakeCli(Path dir, String body) throws IOException {
        Path script = dir.resolve("claude");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    /**
     * With a server-side key the test prompt is conclusive: the expected
     * answer is ok.
     */
    @Test
    void answeredProbeIsOk() {
        AgentCheckResult result = ClaudeCodeAgent.probeResult(completed(0, "AXIOM_OK"), SHORT);
        assertEquals("ok", result.status());
    }

    /**
     * With a server-side key a failed probe is a misconfiguration: an error
     * that carries the exit code and the CLI output.
     */
    @Test
    void failedProbeIsAnError() {
        AgentCheckResult result = ClaudeCodeAgent.probeResult(completed(1, "Unknown model"), SHORT);
        assertEquals("error", result.status());
        assertTrue(result.message().contains("exit code 1"));
        assertTrue(result.message().contains("Unknown model"));
    }

    /**
     * The expected token with a non-zero exit, or exit 0 without the token,
     * proves nothing: both are errors.
     */
    @Test
    void probeNeedsBothExitZeroAndTheToken() {
        assertEquals("error", ClaudeCodeAgent.probeResult(completed(1, "AXIOM_OK"), SHORT).status());
        assertEquals("error", ClaudeCodeAgent.probeResult(completed(0, "not sure"), SHORT).status());
    }

    /**
     * Only a non-blank ANTHROPIC_API_KEY or a third-party provider switch the
     * CLI reads as on (1, true, yes or on) lets a --bare CLI authenticate.
     */
    @Test
    void serverCredentialsAreTheKeyOrAThirdPartyProvider() {
        assertFalse(ClaudeCodeAgent.hasServerCredentials(Map.of()));
        assertFalse(ClaudeCodeAgent.hasServerCredentials(Map.of("ANTHROPIC_API_KEY", "  ")));
        assertTrue(ClaudeCodeAgent.hasServerCredentials(Map.of("ANTHROPIC_API_KEY", "k")));
        assertTrue(ClaudeCodeAgent.hasServerCredentials(Map.of("CLAUDE_CODE_USE_BEDROCK", "1")));
        assertTrue(ClaudeCodeAgent.hasServerCredentials(Map.of("CLAUDE_CODE_USE_VERTEX", "true")));
        assertTrue(ClaudeCodeAgent.hasServerCredentials(Map.of("CLAUDE_CODE_USE_MANTLE", " ON ")));
        assertTrue(ClaudeCodeAgent.hasServerCredentials(
                Map.of("CLAUDE_CODE_USE_ANTHROPIC_AWS", "yes")));
        assertFalse(ClaudeCodeAgent.hasServerCredentials(Map.of("CLAUDE_CODE_USE_FOUNDRY", "0")));
        assertFalse(ClaudeCodeAgent.hasServerCredentials(Map.of("CLAUDE_CODE_USE_BEDROCK", "false")));
        // the CLI reads only 1/true/yes/on as on, so these leave the provider off
        assertFalse(ClaudeCodeAgent.hasServerCredentials(Map.of("CLAUDE_CODE_USE_BEDROCK", "off")));
        assertFalse(ClaudeCodeAgent.hasServerCredentials(Map.of("CLAUDE_CODE_USE_VERTEX", "no")));
    }

    /**
     * Without server credentials no test prompt is sent: the check runs
     * {@code --version} and reports a warning that names the version and
     * says the runs' own keys cannot be verified.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void withoutCredentialsTheCheckRunsVersionAndWarns(@TempDir Path dir) throws IOException {
        Path cli = fakeCli(dir, "[ \"$1\" = --version ] && echo '2.1.306 (Claude Code)' && exit 0\n"
                + "echo prompt-was-sent; exit 1");
        AgentCheckResult result = agentWith(cli).check(Map.of(), SHORT, SHORT);
        assertEquals("warning", result.status());
        assertTrue(result.message().contains("2.1.306 (Claude Code)"));
        assertTrue(result.message().contains("No test prompt was sent"));
        assertTrue(result.message().contains("expected when keys are configured per action type"));
    }

    /**
     * A CLI whose --version fails is broken and reports an error.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void failingVersionIsAnError(@TempDir Path dir) throws IOException {
        Path cli = fakeCli(dir, "echo 'cannot load module'; exit 3");
        AgentCheckResult result = agentWith(cli).check(Map.of(), SHORT, SHORT);
        assertEquals("error", result.status());
        assertTrue(result.message().contains("exit code 3"));
        assertTrue(result.message().contains("cannot load module"));
    }

    /**
     * {@code --version} makes no network call, so a CLI that does not answer
     * it in time is broken and reports an error, quoting what it printed.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void hungVersionIsAnError(@TempDir Path dir) throws IOException {
        Path cli = fakeCli(dir, "echo waiting-for-input; exec sleep 30");
        AgentCheckResult result = agentWith(cli).check(Map.of(), SHORT, Duration.ofMillis(500));
        assertEquals("error", result.status());
        assertTrue(result.message().contains("did not finish in time"));
        assertTrue(result.message().contains("waiting-for-input"));
    }

    /**
     * With server credentials the test prompt is sent, and its answer
     * decides the status.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void withCredentialsTheProbeIsSent(@TempDir Path dir) throws IOException {
        Path cli = fakeCli(dir, "[ \"$1\" = -p ] && echo AXIOM_OK && exit 0\nexit 1");
        AgentCheckResult result = agentWith(cli)
                .check(Map.of("ANTHROPIC_API_KEY", "k"), SHORT, SHORT);
        assertEquals("ok", result.status());
    }

    /**
     * A timed-out probe keeps the output written so far in its message.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void timeoutQuotesTheOutputSoFar(@TempDir Path dir) throws IOException {
        Path cli = fakeCli(dir, "echo still-connecting; exec sleep 30");
        AgentCheckResult result = agentWith(cli)
                .check(Map.of("ANTHROPIC_API_KEY", "k"), Duration.ofMillis(500), SHORT);
        assertEquals("error", result.status());
        assertTrue(result.message().contains("timed out"));
        assertTrue(result.message().contains("still-connecting"));
    }

    /**
     * A CLI that cannot be started at all is an error with the install hint.
     */
    @Test
    void missingCliIsAnError(@TempDir Path dir) {
        AgentCheckResult result = agentWith(dir.resolve("no-such-claude"))
                .check(Map.of(), SHORT, SHORT);
        assertEquals("error", result.status());
        assertTrue(result.message().contains("not available"));
        assertTrue(result.message().contains("npm install"));
    }

    /**
     * An interrupted check says nothing about the CLI and reports a warning,
     * and the interrupt flag is restored for the caller.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void interruptedCheckIsAWarning(@TempDir Path dir) throws IOException {
        Path cli = fakeCli(dir, "exec sleep 30");
        Thread.currentThread().interrupt();
        try {
            AgentCheckResult result = agentWith(cli).check(Map.of(), SHORT, SHORT);
            assertEquals("warning", result.status());
            assertTrue(result.message().contains("interrupted"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * A probe that writes far more than a pipe buffer still completes, and
     * the failure message quotes the end of the output, where the error is.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void chattyFailingProbeQuotesTheEndOfItsOutput(@TempDir Path dir) throws IOException {
        Path cli = fakeCli(dir, "i=0; while [ $i -lt 20000 ]; do echo banner-line; i=$((i+1)); done\n"
                + "echo 'Not logged in'; exit 1");
        AgentCheckResult result = agentWith(cli)
                .check(Map.of("ANTHROPIC_API_KEY", "k"), SHORT, SHORT);
        assertEquals("error", result.status());
        assertTrue(result.message().contains("exit code 1"));
        assertTrue(result.message().contains("Not logged in"));
    }

    /**
     * A hung CLI is killed together with the processes it started.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void hungCheckKillsTheChildProcesses(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("child.pid");
        Path cli = fakeCli(dir, "sleep 60 & echo $! > " + pidFile + "; wait");
        AgentCheckResult result = agentWith(cli).check(Map.of(), SHORT, Duration.ofMillis(800));
        assertEquals("error", result.status());
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                "child process survived the check");
    }
}
