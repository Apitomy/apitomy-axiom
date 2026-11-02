package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.claudecode.ClaudeCodeMcpManager;
import io.apitomy.axiom.agents.copilot.CopilotMcpManager;
import io.apitomy.axiom.agents.opencode.OpenCodeMcpManager;
import io.apitomy.axiom.agents.opencode.OpenCodeMcpManager.McpServerConfig;
import io.apitomy.axiom.agents.spi.AgentMcpManager;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link AgentRegistry} hands out each engine's per-task MCP manager (issue #625: OpenCode tasks
 * never registered their MCP servers because the registry had no managers).
 */
@QuarkusTest
class AgentMcpManagerRegistryTest {

    private static final String TOOL_NAME = "registry_probe_tool";

    @Inject
    AgentRegistry agentRegistry;

    @Inject
    McpConfigGenerator generator;

    /**
     * Creates the script tool the OpenCode task allows.
     */
    @BeforeEach
    @Transactional
    void ensureToolExists() {
        if (ToolDefinitionEntity.count("name", TOOL_NAME) == 0) {
            ToolDefinitionEntity tool = new ToolDefinitionEntity();
            tool.name = TOOL_NAME;
            tool.description = "Probe tool for the agent MCP manager registry test";
            tool.parameters = "[]";
            tool.scriptTemplate = "echo probe";
            tool.persist();
        }
    }

    /**
     * Removes the probe tool so other tests see the tool table as they left it.
     */
    @AfterEach
    @Transactional
    void removeTool() {
        ToolDefinitionEntity.delete("name", TOOL_NAME);
    }

    /**
     * The OpenCode engine resolves to the OpenCode MCP manager, not the no-op fallback.
     */
    @Test
    void openCodeResolvesToOpenCodeMcpManager() {
        assertInstanceOf(OpenCodeMcpManager.class, agentRegistry.getMcpManager("opencode"));
    }

    /**
     * Claude Code and Copilot read the generated config file, so they get a no-op manager and never borrow
     * another engine's manager.
     */
    @Test
    void fileBasedEnginesGetNoOpManager() {
        for (String type : List.of("claude-code", "copilot")) {
            AgentMcpManager manager = agentRegistry.getMcpManager(type);
            assertFalse(manager instanceof OpenCodeMcpManager, type);
            assertFalse(manager instanceof ClaudeCodeMcpManager, type);
            assertFalse(manager instanceof CopilotMcpManager, type);
            assertNull(manager.configureMcpServers(1L, Map.of(), List.of()), type);
        }
    }

    /**
     * An OpenCode task allowing a script tool gets the {@code axiom-tools} server registered.
     */
    @Test
    void openCodeTaskRegistersAxiomTools() {
        List<McpServerConfig> servers = generator.getMcpServersForOpenCode(62501L, Map.of(),
                List.of("mcp__axiom-tools__" + TOOL_NAME));
        assertTrue(servers.stream().anyMatch(s -> "axiom-tools".equals(s.name())),
                "Expected axiom-tools in " + servers);
    }
}
