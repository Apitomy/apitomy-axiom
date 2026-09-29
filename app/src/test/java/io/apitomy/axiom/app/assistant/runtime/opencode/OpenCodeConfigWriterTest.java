package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.assistant.AssistantContextBuilder.McpServerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeConfigWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildsLocalServerWithCommandArgsAndEnvironment() {
        Map<String, McpServerConfig> servers = Map.of("axiom",
                McpServerConfig.stdio("node", List.of("/home/u/.axiom/assistant-mcp-server/server.js"),
                        Map.of("AXIOM_API_URL", "http://localhost:9090/api/v1")));

        JsonNode config = OpenCodeConfigWriter.buildConfig(servers);

        JsonNode axiom = config.path("mcp").path("axiom");
        assertEquals("local", axiom.path("type").asText());
        assertEquals("node", axiom.path("command").get(0).asText());
        assertEquals("/home/u/.axiom/assistant-mcp-server/server.js", axiom.path("command").get(1).asText());
        assertEquals(2, axiom.path("command").size());
        assertEquals("http://localhost:9090/api/v1", axiom.path("environment").path("AXIOM_API_URL").asText());
        assertTrue(axiom.path("enabled").asBoolean());
        assertEquals("https://opencode.ai/config.json", config.path("$schema").asText());
    }

    @Test
    void omitsEnvironmentWhenEmpty() {
        JsonNode config = OpenCodeConfigWriter.buildConfig(
                Map.of("plain", McpServerConfig.stdio("npx", List.of("-y", "some-mcp"), Map.of())));

        assertFalse(config.path("mcp").path("plain").has("environment"));
    }

    @Test
    void buildsRemoteServerForHttpTransport() {
        JsonNode config = OpenCodeConfigWriter.buildConfig(
                Map.of("remote", McpServerConfig.http("http://127.0.0.1:8000/mcp")));

        JsonNode remote = config.path("mcp").path("remote");
        assertEquals("remote", remote.path("type").asText());
        assertEquals("http://127.0.0.1:8000/mcp", remote.path("url").asText());
        assertTrue(remote.path("enabled").asBoolean());
        assertFalse(remote.has("command"));
    }

    @Test
    void writesConfigFileToSessionDirectory(@TempDir Path sessionDir) throws Exception {
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        servers.put("remote", McpServerConfig.http("http://127.0.0.1:8000/mcp"));

        Path written = OpenCodeConfigWriter.writeConfig(sessionDir, servers);

        assertEquals(sessionDir.resolve("opencode.json"), written);
        JsonNode parsed = MAPPER.readTree(Files.readString(written));
        assertEquals("remote", parsed.path("mcp").path("remote").path("type").asText());
    }

    @Test
    void writesNothingWhenNoServers(@TempDir Path sessionDir) throws Exception {
        assertNull(OpenCodeConfigWriter.writeConfig(sessionDir, Map.of()));
        assertNull(OpenCodeConfigWriter.writeConfig(sessionDir, null));
        assertFalse(Files.exists(sessionDir.resolve("opencode.json")));
    }
}
