package io.apitomy.axiom.engine.copilot;

import io.apitomy.axiom.actors.spi.ActorContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for CopilotCommandBuilder. These are pure unit tests that don't
 * require the Copilot CLI to be installed.
 */
class CopilotCommandBuilderTest {

    @Test
    void testMinimalCommand() {
        ActorContext context = ActorContext.builder()
                .workingDirectory(Path.of("/tmp/workspace"))
                .build();

        List<String> cmd = CopilotCommandBuilder
                .fromContext("Hello", context)
                .build();

        assertTrue(cmd.contains("copilot"));
        assertTrue(cmd.contains("-p"));
        assertTrue(cmd.contains("Hello"));
        assertTrue(cmd.contains("--allow-all-tools"),
                "Non-interactive mode requires blanket tool approval");
        assertTrue(cmd.contains("--disable-builtin-mcps"));
        assertFalse(cmd.contains("-C"), "Working dir is set via ProcessBuilder, not CLI flag");
    }

    @Test
    void testOutputFormatJson() {
        ActorContext context = ActorContext.builder().build();
        List<String> cmd = CopilotCommandBuilder.fromContext("test", context).build();

        int fmtIndex = cmd.indexOf("--output-format");
        assertTrue(fmtIndex >= 0);
        assertEquals("json", cmd.get(fmtIndex + 1));
    }

    @Test
    void testCustomExecutable() {
        ActorContext context = ActorContext.builder().build();
        List<String> cmd = CopilotCommandBuilder.fromContext("test", context)
                .executable("/usr/local/bin/copilot")
                .build();

        assertEquals("/usr/local/bin/copilot", cmd.getFirst());
    }

    @Test
    void testModelFlag() {
        ActorContext context = ActorContext.builder().build();
        List<String> cmd = CopilotCommandBuilder.fromContext("test", context)
                .model("gpt-5.4")
                .build();

        int modelIndex = cmd.indexOf("--model");
        assertTrue(modelIndex >= 0);
        assertEquals("gpt-5.4", cmd.get(modelIndex + 1));
    }

    @Test
    void testAllowedToolsForwardedAsBaseNames() {
        ActorContext context = ActorContext.builder()
                .allowedTools(List.of("Read", "Bash(git log *)", "Bash(git diff *)"))
                .build();

        List<String> cmd = CopilotCommandBuilder.fromContext("test", context).build();

        // --allow-tool should appear once per deduplicated base tool name
        long allowToolCount = cmd.stream().filter("--allow-tool"::equals).count();
        assertEquals(2, allowToolCount);
        assertTrue(cmd.contains("Read"));
        assertTrue(cmd.contains("Bash"));
        // --allow-all-tools is still present to avoid blocking on unmapped names
        assertTrue(cmd.contains("--allow-all-tools"));
    }

    @Test
    void testDisallowedTools() {
        ActorContext context = ActorContext.builder()
                .disallowedTools(List.of("bash", "edit"))
                .build();

        List<String> cmd = CopilotCommandBuilder.fromContext("test", context).build();

        long denyToolCount = cmd.stream().filter("--deny-tool"::equals).count();
        assertEquals(2, denyToolCount);
        assertTrue(cmd.contains("bash"));
        assertTrue(cmd.contains("edit"));
    }

    @Test
    void testSessionId() {
        ActorContext context = ActorContext.builder().build();
        List<String> cmd = CopilotCommandBuilder.fromContext("test", context)
                .sessionId("axiom-task-42")
                .build();

        int sessionIndex = cmd.indexOf("--session-id");
        assertTrue(sessionIndex >= 0);
        assertEquals("axiom-task-42", cmd.get(sessionIndex + 1));
    }

    @Test
    void testMcpConfig() {
        ActorContext context = ActorContext.builder().build();
        List<String> cmd = CopilotCommandBuilder.fromContext("test", context)
                .mcpConfigFile(Path.of("/tmp/mcp.json"))
                .build();

        int mcpIndex = cmd.indexOf("--additional-mcp-config");
        assertTrue(mcpIndex >= 0);
        assertTrue(cmd.get(mcpIndex + 1).startsWith("@"));
        assertTrue(cmd.get(mcpIndex + 1).endsWith("/tmp/mcp.json"));
    }

    @Test
    void testNoAllowedToolsOnlyEmitsAllowAllTools() {
        ActorContext context = ActorContext.builder()
                .allowedTools(List.of())
                .build();

        List<String> cmd = CopilotCommandBuilder.fromContext("test", context).build();

        assertFalse(cmd.contains("--allow-tool"),
                "Empty allowedTools should not produce --allow-tool flags");
        assertTrue(cmd.contains("--allow-all-tools"));
    }

    @Test
    void testDeriveBaseToolNamesWithPatterns() {
        String result = CopilotCommandBuilder.deriveBaseToolNames(List.of(
                "Read", "Bash(git log *)", "Bash(git diff *)"));
        assertEquals("Read,Bash", result);
    }
}
