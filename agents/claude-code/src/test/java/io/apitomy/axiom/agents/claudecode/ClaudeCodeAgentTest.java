package io.apitomy.axiom.agents.claudecode;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
