package io.apitomy.axiom.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the MCP server templates against regressions to the pre-3.0 {@code /actors} endpoint.
 */
class McpTemplateAgentsEndpointTest {

    private static String read(String path) throws IOException {
        try (InputStream in = McpTemplateAgentsEndpointTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void sdkServerListsAgentsViaAgentsEndpoint() throws IOException {
        String js = read("templates/axiom-mcp-server/sdk-server.js");
        assertTrue(js.contains("axiomApi(\"GET\", \"/agents\")"));
        assertFalse(js.contains("\"/actors"));
    }

    @Test
    void assistantServerExposesListAgentsViaAgentsEndpoint() throws IOException {
        String js = read("templates/axiom-assistant-mcp/server.js");
        assertTrue(js.contains("name: \"axiom_list_agents\""));
        assertTrue(js.contains("axiomApi(\"GET\", \"/agents\")"));
        assertFalse(js.contains("list_actors"));
        assertFalse(js.contains("\"/actors"));
    }

    @Test
    void configAssistantAllowsListAgents() throws IOException {
        String json = read("templates/assistant-templates/axiom-config-assistant.json");
        assertTrue(json.contains("mcp__axiom__axiom_list_agents"));
        assertFalse(json.contains("list_actors"));
    }
}
