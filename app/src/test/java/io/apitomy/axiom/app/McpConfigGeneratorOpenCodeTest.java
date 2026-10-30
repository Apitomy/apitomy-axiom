package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.opencode.OpenCodeMcpManager.McpServerConfig;
import io.apitomy.axiom.app.mcp.McpServerDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for converting resolved definitions to OpenCode task-path MCP server configs.
 */
class McpConfigGeneratorOpenCodeTest {

    @Test
    void remoteKeepsHeadersAndTimeout() {
        McpServerConfig cfg = McpConfigGenerator.toOpenCodeConfig("r",
                McpServerDefinition.remote("http", "https://x.io/mcp", Map.of("Authorization", "Bearer t"), 9000));
        assertEquals("remote", cfg.type());
        assertEquals(Map.of("Authorization", "Bearer t"), cfg.headers());
        assertEquals(9000, cfg.timeout());
    }

    @Test
    void localCombinesCommandAndArgsAndKeepsTimeout() {
        McpServerConfig cfg = McpConfigGenerator.toOpenCodeConfig("l",
                new McpServerDefinition("npx", List.of("-y", "p"), Map.of("K", "v"), null, null, Map.of(), 1000));
        assertEquals("local", cfg.type());
        assertEquals(List.of("npx", "-y", "p"), cfg.command());
        assertEquals(Map.of("K", "v"), cfg.environment());
        assertEquals(1000, cfg.timeout());
    }
}
