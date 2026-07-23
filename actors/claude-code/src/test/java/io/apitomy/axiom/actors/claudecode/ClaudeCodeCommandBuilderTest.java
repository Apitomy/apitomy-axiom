package io.apitomy.axiom.actors.claudecode;

import io.apitomy.axiom.actors.spi.ActorContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClaudeCodeCommandBuilder. These are pure unit tests that don't
 * require Claude Code to be installed.
 */
class ClaudeCodeCommandBuilderTest {

    @Test
    void testMinimalCommand() {
        ActorContext context = ActorContext.builder()
                .workingDirectory(Path.of("/tmp/workspace"))
                .build();

        List<String> cmd = ClaudeCodeCommandBuilder
                .fromContext("Hello", context)
                .build();

        assertTrue(cmd.contains("claude"));
        assertTrue(cmd.contains("-p"));
        assertTrue(cmd.contains("Hello"));
        assertTrue(cmd.contains("--bare"));
        assertFalse(cmd.contains("--cwd"), "Working dir is set via ProcessBuilder, not CLI flag");
        assertTrue(cmd.contains("--permission-mode"));
        assertTrue(cmd.contains("acceptEdits"));
    }

    @Test
    void testStreamJsonDefault() {
        ActorContext context = ActorContext.builder().build();
        List<String> cmd = ClaudeCodeCommandBuilder.fromContext("test", context).build();

        int fmtIndex = cmd.indexOf("--output-format");
        assertTrue(fmtIndex >= 0);
        assertEquals("stream-json", cmd.get(fmtIndex + 1));
    }

    @Test
    void testJsonOutputMode() {
        ActorContext context = ActorContext.builder().build();
        List<String> cmd = ClaudeCodeCommandBuilder.fromContext("test", context)
                .streamJson(false)
                .build();

        int fmtIndex = cmd.indexOf("--output-format");
        assertTrue(fmtIndex >= 0);
        assertEquals("json", cmd.get(fmtIndex + 1));
    }

    @Test
    void testModelFlag() {
        ActorContext context = ActorContext.builder().build();
        List<String> cmd = ClaudeCodeCommandBuilder.fromContext("test", context)
                .model("claude-sonnet-4-6")
                .build();

        int modelIndex = cmd.indexOf("--model");
        assertTrue(modelIndex >= 0);
        assertEquals("claude-sonnet-4-6", cmd.get(modelIndex + 1));
    }

    @Test
    void testAllowedToolsThreeLayers() {
        ActorContext context = ActorContext.builder()
                .allowedTools(List.of("Read", "Edit", "Bash"))
                .build();

        List<String> cmd = ClaudeCodeCommandBuilder.fromContext("test", context).build();

        // Layer 1: --tools (hard availability restriction with base names)
        int hardToolsIndex = cmd.indexOf("--tools");
        assertTrue(hardToolsIndex >= 0, "Should have --tools flag");
        String hardToolsArg = cmd.get(hardToolsIndex + 1);
        assertTrue(hardToolsArg.contains("Read"));
        assertTrue(hardToolsArg.contains("Edit"));
        assertTrue(hardToolsArg.contains("Bash"));

        // Layer 2: --allowedTools (auto-approve patterns, space-separated single arg)
        int allowedIndex = cmd.indexOf("--allowedTools");
        assertTrue(allowedIndex >= 0, "Should have --allowedTools flag");
        String allowedArg = cmd.get(allowedIndex + 1);
        assertTrue(allowedArg.contains("Read"));
        assertTrue(allowedArg.contains("Edit"));
        assertTrue(allowedArg.contains("Bash"));

        // Layer 3: --permission-mode dontAsk
        int modeIndex = cmd.indexOf("--permission-mode");
        assertTrue(modeIndex >= 0);
        assertEquals("dontAsk", cmd.get(modeIndex + 1));
    }

    @Test
    void testDisallowedTools() {
        ActorContext context = ActorContext.builder()
                .disallowedTools(List.of("Write"))
                .build();

        List<String> cmd = ClaudeCodeCommandBuilder.fromContext("test", context).build();

        int toolsIndex = cmd.indexOf("--disallowedTools");
        assertTrue(toolsIndex >= 0);
        String toolsArg = cmd.get(toolsIndex + 1);
        assertTrue(toolsArg.contains("Write"));
    }

    @Test
    void testMaxTurnsAndBudget() {
        ActorContext context = ActorContext.builder().build();
        List<String> cmd = ClaudeCodeCommandBuilder.fromContext("test", context)
                .maxTurns(10)
                .maxBudgetUsd(2.5)
                .build();

        int turnsIndex = cmd.indexOf("--max-turns");
        assertTrue(turnsIndex >= 0);
        assertEquals("10", cmd.get(turnsIndex + 1));

        int budgetIndex = cmd.indexOf("--max-budget-usd");
        assertTrue(budgetIndex >= 0);
        assertEquals("2.5", cmd.get(budgetIndex + 1));
    }

    @Test
    void testSessionId() {
        ActorContext context = ActorContext.builder().build();
        List<String> cmd = ClaudeCodeCommandBuilder.fromContext("test", context)
                .sessionId("axiom-task-42")
                .build();

        int sessionIndex = cmd.indexOf("--session-id");
        assertTrue(sessionIndex >= 0);
        assertEquals("axiom-task-42", cmd.get(sessionIndex + 1));
    }

    @Test
    void testSystemPrompt() {
        ActorContext context = ActorContext.builder()
                .systemPrompt("You are working on project Foo")
                .build();

        List<String> cmd = ClaudeCodeCommandBuilder.fromContext("test", context).build();

        int promptIndex = cmd.indexOf("--append-system-prompt");
        assertTrue(promptIndex >= 0);
        assertEquals("You are working on project Foo", cmd.get(promptIndex + 1));
    }

    @Test
    void testMcpConfig() {
        ActorContext context = ActorContext.builder().build();
        List<String> cmd = ClaudeCodeCommandBuilder.fromContext("test", context)
                .mcpConfigFile(Path.of("/tmp/mcp.json"))
                .build();

        int mcpIndex = cmd.indexOf("--mcp-config");
        assertTrue(mcpIndex >= 0);
    }

    @Test
    void testBareDisabled() {
        ActorContext context = ActorContext.builder().build();
        List<String> cmd = ClaudeCodeCommandBuilder.fromContext("test", context)
                .bare(false)
                .build();

        assertFalse(cmd.contains("--bare"));
    }

    @Test
    void testPermissionModeDontAskWithAllowedTools() {
        ActorContext context = ActorContext.builder()
                .allowedTools(List.of("Read", "Glob"))
                .build();
        List<String> cmd = ClaudeCodeCommandBuilder.fromContext("test", context).build();
        int modeIndex = cmd.indexOf("--permission-mode");
        assertTrue(modeIndex >= 0);
        assertEquals("dontAsk", cmd.get(modeIndex + 1),
                "Should use dontAsk when allowedTools is set");
    }

    @Test
    void testPermissionModeAcceptEditsWithoutAllowedTools() {
        ActorContext contextNoTools = ActorContext.builder().build();
        List<String> cmd = ClaudeCodeCommandBuilder.fromContext("test", contextNoTools).build();
        int modeIndex = cmd.indexOf("--permission-mode");
        assertTrue(modeIndex >= 0);
        assertEquals("acceptEdits", cmd.get(modeIndex + 1),
                "Should use acceptEdits when no allowedTools set");
    }

    @Test
    void testWildcardPatternsDerivesBaseToolNames() {
        ActorContext context = ActorContext.builder()
                .allowedTools(List.of(
                        "Read", "Glob", "Grep",
                        "Bash(git log *)", "Bash(git diff *)",
                        "Bash(gh issue *)", "Bash(gh pr *)"
                ))
                .build();

        List<String> cmd = ClaudeCodeCommandBuilder.fromContext("test", context).build();

        // --tools should have deduplicated base names
        int hardToolsIndex = cmd.indexOf("--tools");
        assertTrue(hardToolsIndex >= 0);
        String hardToolsArg = cmd.get(hardToolsIndex + 1);
        assertTrue(hardToolsArg.contains("Read"));
        assertTrue(hardToolsArg.contains("Glob"));
        assertTrue(hardToolsArg.contains("Grep"));
        assertTrue(hardToolsArg.contains("Bash"));
        // Should NOT contain the wildcard patterns — just base names
        assertFalse(hardToolsArg.contains("("),
                "Base tool names should not contain patterns: " + hardToolsArg);

        // --allowedTools should have the full patterns in a space-separated single arg
        int allowedIndex = cmd.indexOf("--allowedTools");
        assertTrue(allowedIndex >= 0);
        String allowedArg = cmd.get(allowedIndex + 1);
        assertTrue(allowedArg.contains("Bash(git log *)"));
        assertTrue(allowedArg.contains("Bash(gh issue *)"));
        assertTrue(allowedArg.contains("Bash(gh pr *)"));
    }

    @Test
    void testDeriveBaseToolNamesWithPatterns() {
        String result = ClaudeCodeCommandBuilder.deriveBaseToolNames(List.of(
                "Read", "Bash(git log *)", "Bash(git diff *)",
                "mcp__axiom__axiom_project_get_details"
        ));
        assertEquals("Read,Bash,mcp__axiom__axiom_project_get_details", result);
    }

    @Test
    void testDeriveBaseToolNamesEmptyList() {
        String result = ClaudeCodeCommandBuilder.deriveBaseToolNames(List.of());
        assertEquals("", result);
    }

    @Test
    void testDeriveBaseToolNamesNoPatterns() {
        String result = ClaudeCodeCommandBuilder.deriveBaseToolNames(List.of(
                "Read", "Write", "Edit"
        ));
        assertEquals("Read,Write,Edit", result);
    }

    @Test
    void testAllowedToolsEmptyListFallsBackToAcceptEdits() {
        ActorContext context = ActorContext.builder()
                .allowedTools(List.of())
                .build();

        List<String> cmd = ClaudeCodeCommandBuilder.fromContext("test", context).build();

        assertFalse(cmd.contains("--allowedTools"),
                "Empty allowedTools should not produce --allowedTools flag");
        int modeIndex = cmd.indexOf("--permission-mode");
        assertEquals("acceptEdits", cmd.get(modeIndex + 1));
    }
}
