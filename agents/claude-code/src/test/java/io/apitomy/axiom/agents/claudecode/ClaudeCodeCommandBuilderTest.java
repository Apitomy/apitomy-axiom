package io.apitomy.axiom.agents.claudecode;

import io.apitomy.axiom.agents.spi.AgentRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ClaudeCodeCommandBuilder}.
 */
class ClaudeCodeCommandBuilderTest {

    /**
     * Without configured plugin directories the command line must not contain
     * any {@code --plugin-dir} flag (the default configuration).
     */
    @Test
    void omitsPluginDirFlagWhenNoPluginDirsConfigured() {
        List<String> cmd = builderForPrompt("hello").build();

        assertFalse(cmd.contains("--plugin-dir"),
                "no --plugin-dir flag is expected when no plugin dirs are configured");
    }

    /**
     * Each configured plugin directory must produce its own {@code --plugin-dir}
     * flag (the CLI flag is repeatable) in the configured order, and the command
     * must keep running with {@code --bare}.
     */
    @Test
    void appendsOnePluginDirFlagPerConfiguredDirInOrder() {
        List<String> cmd = builderForPrompt("hello")
                .pluginDirs(List.of("/opt/axiom/plugins/cve-finder", "/opt/axiom/plugins/extra"))
                .build();

        assertTrue(cmd.contains("--bare"), "the command must keep the --bare flag");

        long flagCount = cmd.stream().filter("--plugin-dir"::equals).count();
        assertEquals(2, flagCount, "exactly two --plugin-dir flags are expected");

        int firstFlag = cmd.indexOf("--plugin-dir");
        assertEquals("/opt/axiom/plugins/cve-finder", cmd.get(firstFlag + 1),
                "the first flag must carry the first configured dir");
        assertEquals("/opt/axiom/plugins/extra", cmd.get(firstFlag + 3),
                "the second flag must carry the second configured dir");
    }

    private static ClaudeCodeCommandBuilder builderForPrompt(String prompt) {
        return ClaudeCodeCommandBuilder.fromRequest(AgentRequest.builder().prompt(prompt).build());
    }
}
