package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the assistant MCP server script and the configuration assistant template expose the validation tool.
 */
class AssistantConfigToolResourcesTest {

    private static String resource(String path) throws IOException {
        try (InputStream in = AssistantConfigToolResourcesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "Missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void serverJsDefinesValidateTool() throws IOException {
        String serverJs = resource("/templates/axiom-assistant-mcp/server.js");
        assertTrue(serverJs.contains("name: \"axiom_validate_item\""));
        assertTrue(serverJs.contains("AXIOM_ASSISTANT_SESSION_ID"));
        assertTrue(serverJs.contains("INVALID \u2014 item is not valid JSON: "));
    }

    @Test
    void configAssistantTemplateAllowsValidateTool() throws IOException {
        String text = resource("/templates/assistant-templates/axiom-config-assistant.json");
        JsonNode template = new ObjectMapper().readTree(text);
        List<String> allowedTools = new ArrayList<>();
        template.path("allowedTools").forEach(t -> allowedTools.add(t.asText()));
        assertTrue(allowedTools.contains("mcp__axiom__axiom_validate_item"), allowedTools.toString());
        assertTrue(allowedTools.contains("mcp__axiom__axiom_list_agents"), allowedTools.toString());
        assertFalse(text.contains("axiom_list_actors"));
    }
}
