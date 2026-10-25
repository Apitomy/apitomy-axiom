package io.apitomy.axiom.agents.claudecode;

import io.apitomy.axiom.agents.spi.AgentCheckResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the plugin directory parsing in {@link ClaudeCodeAgent}.
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

    /**
     * A probe that answers as expected reports ok, also when the server
     * environment carries no key: the CLI can authenticate by other means,
     * for example stored credentials.
     */
    @Test
    void successfulProbeIsOkWithoutServerApiKey() {
        AgentCheckResult result = ClaudeCodeAgent.probeResult(true, 0, "AXIOM_OK", false);
        assertEquals("ok", result.status());
    }

    /**
     * With ANTHROPIC_API_KEY set in the server environment a failed probe is
     * conclusive: a real misconfiguration must report an error. The message
     * carries the exit code and the CLI output so the operator sees the
     * cause, and it does not advise setting the key that is already set.
     */
    @Test
    void failedProbeWithServerApiKeyIsAnError() {
        AgentCheckResult result = ClaudeCodeAgent.probeResult(true, 1, "Unknown model", true);
        assertEquals("error", result.status());
        assertTrue(result.message().contains("exit code 1"));
        assertTrue(result.message().contains("Unknown model"));
        assertTrue(result.message().contains("check the key"));
    }

    /**
     * Without a server-side key a failed probe is inconclusive rather than
     * evidence of a broken installation: the CLI may authenticate by other
     * means and agent action types can declare their own key. The check
     * reports a warning that quotes the CLI output, which keeps the UI
     * usable in the 3.x deployment model where secrets are never injected
     * into the server environment.
     */
    @Test
    void failedProbeWithoutServerApiKeyIsAnInconclusiveWarning() {
        AgentCheckResult result = ClaudeCodeAgent.probeResult(true, 1,
                "Invalid API key · Please run /login", false);
        assertEquals("warning", result.status());
        assertTrue(result.message().contains("exit code 1"));
        assertTrue(result.message().contains("Invalid API key · Please run /login"));
        assertTrue(result.message().contains("conclusive"));
    }

    /**
     * A timed-out probe with a server-side key keeps reporting an error.
     */
    @Test
    void timeoutWithServerApiKeyIsAnError() {
        AgentCheckResult result = ClaudeCodeAgent.probeResult(false, -1, "", true);
        assertEquals("error", result.status());
        assertTrue(result.message().contains("timed out"));
    }

    /**
     * A timed-out probe without a server-side key is inconclusive and
     * reports a warning for the same reason as a failed probe.
     */
    @Test
    void timeoutWithoutServerApiKeyIsAnInconclusiveWarning() {
        AgentCheckResult result = ClaudeCodeAgent.probeResult(false, -1, "", false);
        assertEquals("warning", result.status());
        assertTrue(result.message().contains("timed out"));
        assertTrue(result.message().contains("conclusive"));
    }

    /**
     * The output excerpt is flattened to one line, capped at 200 characters,
     * and omitted entirely when the output has no printable content.
     */
    @Test
    void excerptIsFlattenedAndCapped() {
        assertEquals(": \"a b\"", ClaudeCodeAgent.excerptSuffix("a\n  b"));
        String longOutput = "x".repeat(250);
        String suffix = ClaudeCodeAgent.excerptSuffix(longOutput);
        assertTrue(suffix.startsWith(": \"xxxxx"));
        assertTrue(suffix.endsWith("...\""));
        assertEquals(": \"".length() + 200 + "...".length() + "\"".length(), suffix.length());
        assertEquals("", ClaudeCodeAgent.excerptSuffix("   \n  "));
        assertEquals("", ClaudeCodeAgent.excerptSuffix(null));
    }

    /**
     * A CLI that exits 0 but does not answer with the expected token proved
     * nothing: it must not report ok. Without a server-side key this common
     * inconclusive case reports the same warning as any other failed probe,
     * quoting the exit code.
     */
    @Test
    void exitZeroWithoutExpectedAnswerIsNotOk() {
        AgentCheckResult result = ClaudeCodeAgent.probeResult(true, 0,
                "I am not sure what you mean", false);
        assertEquals("warning", result.status());
        assertTrue(result.message().contains("exit code 0"));
    }

    /**
     * The expected token in the output with a non-zero exit code is still a
     * failed probe: the ok branch requires both conditions.
     */
    @Test
    void expectedAnswerWithNonZeroExitIsNotOk() {
        AgentCheckResult result = ClaudeCodeAgent.probeResult(true, 1, "AXIOM_OK", false);
        assertEquals("warning", result.status());
    }
}
