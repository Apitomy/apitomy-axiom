package io.apitomy.axiom.agents.claudecode;

import io.apitomy.axiom.agents.spi.AgentRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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

    /**
     * The read-only fallback ("Read", "Bash(grep *)", "Bash(find *)") must collapse
     * to the base tools Read and Bash in {@code --tools}, because
     * {@link ClaudeCodeCommandBuilder#deriveBaseToolNames(List)} strips the
     * parenthesized pattern suffixes, while {@code --allowedTools} keeps both
     * Bash patterns verbatim.
     */
    @Test
    void derivesBaseToolsFromReadOnlyFallback() {
        List<String> cmd = ClaudeCodeCommandBuilder.fromRequest(AgentRequest.builder()
                        .prompt("hello")
                        .allowedTools(List.of("Read", "Bash(grep *)", "Bash(find *)"))
                        .build())
                .build();

        int toolsIdx = cmd.indexOf("--tools");
        assertEquals("Read,Bash", cmd.get(toolsIdx + 1),
                "Bash patterns must reduce to the base tool name Bash");

        int allowedIdx = cmd.indexOf("--allowedTools");
        assertEquals("Read Bash(grep *) Bash(find *)", cmd.get(allowedIdx + 1),
                "the allowed tools must keep the two Bash patterns");
    }

    /**
     * With both an MCP config file and plugin dirs configured, {@code --mcp-config}
     * must come before the {@code --plugin-dir} flags (the current flag order).
     */
    @Test
    void placesMcpConfigBeforePluginDirFlags() {
        List<String> cmd = builderForPrompt("hello")
                .mcpConfigFile(Path.of("/tmp/axiom/mcp.json"))
                .pluginDirs(List.of("/opt/axiom/plugins/cve-finder"))
                .build();

        int mcpIdx = cmd.indexOf("--mcp-config");
        int pluginIdx = cmd.indexOf("--plugin-dir");
        assertTrue(mcpIdx >= 0, "--mcp-config is expected when an MCP config file is set");
        assertTrue(pluginIdx >= 0, "--plugin-dir is expected when plugin dirs are set");
        assertTrue(pluginIdx > mcpIdx, "--plugin-dir must come after --mcp-config");
    }

    private static ClaudeCodeCommandBuilder builderForPrompt(String prompt) {
        return ClaudeCodeCommandBuilder.fromRequest(AgentRequest.builder().prompt(prompt).build());
    }
}
