package io.apitomy.axiom.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the Claude Code / Copilot {@code mcpServers} JSON format.
 */
class ClaudeMcpConfigWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void writesStdioAndHttpServersWithEscaping() throws Exception {
        Map<String, McpServerDefinition> servers = new LinkedHashMap<>();
        servers.put("gh", McpServerDefinition.stdio("npx", List.of("a \"quoted\" arg"), Map.of("K", "v\\x")));
        servers.put("remote", McpServerDefinition.http("https://x.io/mcp"));

        JsonNode root = mapper.readTree(ClaudeMcpConfigWriter.toJson(mapper, servers));

        JsonNode gh = root.path("mcpServers").path("gh");
        assertEquals("npx", gh.path("command").asText());
        assertEquals("a \"quoted\" arg", gh.path("args").get(0).asText());
        assertEquals("v\\x", gh.path("env").path("K").asText());
        JsonNode remote = root.path("mcpServers").path("remote");
        assertEquals("http", remote.path("type").asText());
        assertEquals("https://x.io/mcp", remote.path("url").asText());
        assertFalse(remote.has("command"));
    }

    @Test
    void emptyServerMapProducesNull() {
        assertNull(ClaudeMcpConfigWriter.toJson(mapper, Map.of()));
    }
}
