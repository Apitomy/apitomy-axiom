package io.apitomy.axiom.app.assistant.runtime.opencode;

import io.apitomy.axiom.app.mcp.McpServerDefinition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        Map<String, McpServerDefinition> servers = Map.of("axiom",
                McpServerDefinition.stdio("node", List.of("/home/u/.axiom/assistant-mcp-server/server.js"),
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
                Map.of("plain", McpServerDefinition.stdio("npx", List.of("-y", "some-mcp"), Map.of())));

        assertFalse(config.path("mcp").path("plain").has("environment"));
    }

    @Test
    void buildsRemoteServerForHttpTransport() {
        JsonNode config = OpenCodeConfigWriter.buildConfig(
                Map.of("remote", McpServerDefinition.http("http://127.0.0.1:8000/mcp")));

        JsonNode remote = config.path("mcp").path("remote");
        assertEquals("remote", remote.path("type").asText());
        assertEquals("http://127.0.0.1:8000/mcp", remote.path("url").asText());
        assertTrue(remote.path("enabled").asBoolean());
        assertFalse(remote.has("command"));
    }

    @Test
    void remoteServerIncludesHeadersAndTimeout() {
        JsonNode config = OpenCodeConfigWriter.buildConfig(Map.of("remote",
                McpServerDefinition.remote("sse", "https://x.io/sse", Map.of("Authorization", "Bearer t"), 7000)));

        JsonNode remote = config.path("mcp").path("remote");
        assertEquals("remote", remote.path("type").asText());
        assertEquals("Bearer t", remote.path("headers").path("Authorization").asText());
        assertEquals(7000, remote.path("timeout").asInt());
    }

    @Test
    void localServerIncludesTimeout() {
        JsonNode config = OpenCodeConfigWriter.buildConfig(Map.of("l",
                new McpServerDefinition("npx", List.of(), Map.of(), null, null, Map.of(), 3000)));
        assertEquals(3000, config.path("mcp").path("l").path("timeout").asInt());
    }

    @Test
    void writesConfigFileToSessionDirectory(@TempDir Path sessionDir) throws Exception {
        Map<String, McpServerDefinition> servers = new LinkedHashMap<>();
        servers.put("remote", McpServerDefinition.http("http://127.0.0.1:8000/mcp"));

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

    @Test
    void writesPermissionBlockEvenWithoutMcpServers(@TempDir Path sessionDir) throws Exception {
        ObjectNode permission = OpenCodeSessionPermissions.fromAllowedTools(List.of("Read(*)", "Bash(ls *)"));

        Path written = OpenCodeConfigWriter.writeConfig(sessionDir, Map.of(), permission);

        JsonNode parsed = MAPPER.readTree(Files.readString(written));
        assertEquals("ask", parsed.path("permission").path("*").asText());
        assertEquals("allow", parsed.path("permission").path("read").asText());
        assertEquals("allow", parsed.path("permission").path("bash").path("ls *").asText());
    }

    @Test
    void omitsPermissionBlockWhenNull() {
        assertFalse(OpenCodeConfigWriter.buildConfig(
                Map.of("remote", McpServerDefinition.http("http://x/mcp")), null).has("permission"));
    }
}
