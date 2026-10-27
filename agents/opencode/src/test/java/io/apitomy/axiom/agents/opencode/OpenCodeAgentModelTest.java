package io.apitomy.axiom.agents.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.agents.spi.AgentResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that {@link OpenCodeAgent} records the model OpenCode reports it actually used.
 */
class OpenCodeAgentModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void recordsProviderAndModelFromResponse() throws Exception {
        JsonNode response = MAPPER.readTree("""
                {"info": {"providerID": "github-copilot", "modelID": "claude-opus-5"},
                 "parts": [{"type": "text", "text": "done"}]}
                """);

        AgentResult result = new OpenCodeAgent().parseResponse(response, "s1", "github-copilot/claude-haiku-4.5");

        assertEquals("github-copilot/claude-opus-5", result.model());
        assertEquals("done", result.output());
    }

    @Test
    void recordsModelIdWithoutProvider() throws Exception {
        JsonNode info = MAPPER.readTree("{\"modelID\": \"gpt-5\"}");
        assertEquals("gpt-5", OpenCodeAgent.reportedModel(info));
    }

    @Test
    void fallsBackToRequestedModelWhenNoneReported() throws Exception {
        JsonNode response = MAPPER.readTree("{\"info\": {}, \"parts\": []}");

        AgentResult result = new OpenCodeAgent().parseResponse(response, "s1", "anthropic/claude-haiku");

        assertEquals("anthropic/claude-haiku", result.model());
    }
}
