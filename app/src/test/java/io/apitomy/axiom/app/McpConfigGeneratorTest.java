package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the MCP configuration generator. Verifies that the generated
 * MCP config JSON and tools JSON files have the correct structure and content.
 *
 * <p>After the SDK/tools server split, the generator produces up to two
 * independent MCP server entries: {@code axiom-sdk} for built-in SDK tools
 * and {@code axiom-tools} for user-defined script tools.</p>
 */
@QuarkusTest
class McpConfigGeneratorTest {

    @Inject
    McpConfigGenerator generator;

    @Inject
    ObjectMapper objectMapper;

    // ── Unrestricted config (both servers) ───────────────────────────

    @Test
    void testUnrestrictedConfigHasBothServers() throws Exception {
        Path configFile = generator.generateMcpConfig(9001L, Map.of(), null);
        assertNotNull(configFile);

        JsonNode config = objectMapper.readTree(Files.readString(configFile));
        JsonNode servers = config.get("mcpServers");

        assertTrue(servers.has("axiom-sdk"),
                "Unrestricted config should have axiom-sdk server");
        assertTrue(servers.has("axiom-tools"),
                "Unrestricted config should have axiom-tools server");
    }

    @Test
    void testAxiomToolsServerConfig() throws Exception {
        Path configFile = generator.generateMcpConfig(9002L, Map.of("GH_TOKEN", "test"), null);
        JsonNode config = objectMapper.readTree(Files.readString(configFile));

        JsonNode axiomTools = config.get("mcpServers").get("axiom-tools");
        assertNotNull(axiomTools, "axiom-tools server should be present");
        assertEquals("node", axiomTools.get("command").asText());

        JsonNode args = axiomTools.get("args");
        assertTrue(args.isArray(), "args should be an array");
        assertEquals(2, args.size(), "args should have tools-server.js path and tools JSON path");

        String serverJsPath = args.get(0).asText();
        String toolsJsonPath = args.get(1).asText();

        assertTrue(serverJsPath.endsWith("tools-server.js"),
                "First arg should be tools-server.js path");
        assertTrue(toolsJsonPath.contains("axiom-tools-9002"),
                "Second arg should contain task ID");

        assertTrue(Files.exists(Path.of(serverJsPath)), "tools-server.js should exist");
        assertTrue(Files.exists(Path.of(toolsJsonPath)), "tools JSON should exist");
    }

    @Test
    void testAxiomSdkServerConfig() throws Exception {
        Path configFile = generator.generateMcpConfig(9003L, Map.of(), null);
        JsonNode config = objectMapper.readTree(Files.readString(configFile));

        JsonNode axiomSdk = config.get("mcpServers").get("axiom-sdk");
        assertNotNull(axiomSdk, "axiom-sdk server should be present");
        assertEquals("node", axiomSdk.get("command").asText());

        JsonNode args = axiomSdk.get("args");
        assertTrue(args.isArray(), "args should be an array");
        assertEquals(1, args.size(), "SDK server args should only have sdk-server.js path");

        String sdkServerPath = args.get(0).asText();
        assertTrue(sdkServerPath.endsWith("sdk-server.js"),
                "Arg should be sdk-server.js path");
        assertTrue(Files.exists(Path.of(sdkServerPath)), "sdk-server.js should exist");

        JsonNode env = axiomSdk.get("env");
        assertNotNull(env, "SDK server should have env");
        assertTrue(env.has("AXIOM_API_URL"), "SDK server env should have AXIOM_API_URL");
    }

    @Test
    void testEnvironmentVariablesInToolsServer() throws Exception {
        Map<String, String> env = Map.of(
                "GH_TOKEN", "ghp_test123",
                "GITHUB_TOKEN", "ghp_test456"
        );

        Path configFile = generator.generateMcpConfig(9004L, env, null);
        JsonNode config = objectMapper.readTree(Files.readString(configFile));

        JsonNode axiomToolsEnv = config.get("mcpServers").get("axiom-tools").get("env");
        assertNotNull(axiomToolsEnv, "axiom-tools env should be present");
        assertEquals("ghp_test123", axiomToolsEnv.get("GH_TOKEN").asText());
        assertEquals("ghp_test456", axiomToolsEnv.get("GITHUB_TOKEN").asText());
    }

    // ── Tools JSON file content ──────────────────────────────────────

    @Test
    void testToolsJsonContainsSeededTools() throws Exception {
        Path configFile = generator.generateMcpConfig(9005L, Map.of(), null);
        JsonNode config = objectMapper.readTree(Files.readString(configFile));

        String toolsJsonPath = config.get("mcpServers").get("axiom-tools")
                .get("args").get(1).asText();
        JsonNode tools = objectMapper.readTree(Files.readString(Path.of(toolsJsonPath)));

        assertTrue(tools.isArray(), "Tools should be a JSON array");
        assertTrue(tools.size() >= 4, "Should have at least 4 seeded script tools");

        boolean hasPostComment = false;
        boolean hasListLabels = false;
        boolean hasApplyLabels = false;
        boolean hasCreatePr = false;
        for (JsonNode tool : tools) {
            String name = tool.get("name").asText();
            if ("post_github_comment".equals(name)) hasPostComment = true;
            if ("list_github_labels".equals(name)) hasListLabels = true;
            if ("apply_github_labels".equals(name)) hasApplyLabels = true;
            if ("create_github_pr".equals(name)) hasCreatePr = true;
        }
        assertTrue(hasPostComment, "Should contain post_github_comment tool");
        assertTrue(hasListLabels, "Should contain list_github_labels tool");
        assertTrue(hasApplyLabels, "Should contain apply_github_labels tool");
        assertTrue(hasCreatePr, "Should contain create_github_pr tool");
    }

    @Test
    void testToolJsonStructure() throws Exception {
        Path configFile = generator.generateMcpConfig(9006L, Map.of(), null);
        JsonNode config = objectMapper.readTree(Files.readString(configFile));

        String toolsJsonPath = config.get("mcpServers").get("axiom-tools")
                .get("args").get(1).asText();
        JsonNode tools = objectMapper.readTree(Files.readString(Path.of(toolsJsonPath)));

        JsonNode postCommentTool = null;
        for (JsonNode tool : tools) {
            if ("post_github_comment".equals(tool.get("name").asText())) {
                postCommentTool = tool;
                break;
            }
        }
        assertNotNull(postCommentTool, "post_github_comment tool should exist");
        assertTrue(postCommentTool.has("description"), "Tool should have description");
        assertTrue(postCommentTool.has("scriptTemplate"), "Tool should have scriptTemplate");
        assertTrue(postCommentTool.has("parameters"), "Tool should have parameters");

        JsonNode params = postCommentTool.get("parameters");
        assertTrue(params.isArray(), "Parameters should be an array");
        assertTrue(params.size() >= 3, "post_github_comment should have at least 3 parameters");

        for (JsonNode param : params) {
            assertTrue(param.has("name"), "Parameter should have name");
            assertTrue(param.has("type"), "Parameter should have type");
            assertTrue(param.has("description"), "Parameter should have description");
            assertTrue(param.has("required"), "Parameter should have required flag");
        }
    }

    // ── MCP server installation ──────────────────────────────────────

    @Test
    void testMcpServerProjectInstalled() throws Exception {
        generator.generateMcpConfig(9007L, Map.of(), null);

        Path serverDir = Path.of(System.getProperty("user.home"), ".axiom", "mcp-server");
        assertTrue(Files.exists(serverDir), "MCP server directory should exist");
        assertTrue(Files.exists(serverDir.resolve("package.json")), "package.json should exist");
        assertTrue(Files.exists(serverDir.resolve("sdk-server.js")), "sdk-server.js should exist");
        assertTrue(Files.exists(serverDir.resolve("tools-server.js")), "tools-server.js should exist");
        assertTrue(Files.exists(serverDir.resolve("node_modules")),
                "node_modules should exist after npm install");
    }

    @Test
    void testMultipleCallsReuseInstalledServer() throws Exception {
        Path config1 = generator.generateMcpConfig(9008L, Map.of(), null);
        Path config2 = generator.generateMcpConfig(9009L, Map.of(), null);

        JsonNode cfg1 = objectMapper.readTree(Files.readString(config1));
        JsonNode cfg2 = objectMapper.readTree(Files.readString(config2));

        String serverJs1 = cfg1.get("mcpServers").get("axiom-tools").get("args").get(0).asText();
        String serverJs2 = cfg2.get("mcpServers").get("axiom-tools").get("args").get(0).asText();
        assertEquals(serverJs1, serverJs2,
                "Both configs should reference the same tools-server.js");

        String toolsJson1 = cfg1.get("mcpServers").get("axiom-tools").get("args").get(1).asText();
        String toolsJson2 = cfg2.get("mcpServers").get("axiom-tools").get("args").get(1).asText();
        assertNotEquals(toolsJson1, toolsJson2,
                "Each task should get its own tools JSON file");
    }

    // ── Server isolation (only script tools → no SDK server) ────────

    @Test
    void testOnlyScriptToolsNoSdkServer() throws Exception {
        Path configFile = generator.generateMcpConfig(9016L, Map.of(),
                List.of("mcp__axiom-tools__post_github_comment"));
        assertNotNull(configFile);

        JsonNode config = objectMapper.readTree(Files.readString(configFile));
        JsonNode servers = config.get("mcpServers");

        assertTrue(servers.has("axiom-tools"),
                "Should have axiom-tools when script tools are requested");
        assertFalse(servers.has("axiom-sdk"),
                "Should NOT have axiom-sdk when only script tools are requested");
    }

    @Test
    void testOnlySdkToolsNoToolsServer() throws Exception {
        Path configFile = generator.generateMcpConfig(9017L, Map.of(),
                List.of("mcp__axiom-sdk__axiom_list_projects"));
        assertNotNull(configFile);

        JsonNode config = objectMapper.readTree(Files.readString(configFile));
        JsonNode servers = config.get("mcpServers");

        assertTrue(servers.has("axiom-sdk"),
                "Should have axiom-sdk when SDK tools are requested");
        assertFalse(servers.has("axiom-tools"),
                "Should NOT have axiom-tools when only SDK tools are requested");
    }

    // ── External MCP server tools ────────────────────────────────────

    @Test
    @Transactional
    void testExternalMcpServerStdioInConfig() throws Exception {
        McpServerEntity mcpTool = new McpServerEntity();
        mcpTool.name = "test-external-stdio";
        mcpTool.description = "Test external MCP server (stdio)";
        mcpTool.serverCommand = "npx";
        mcpTool.serverArgs = "[\"-y\", \"@test/mcp-server\"]";
        mcpTool.serverEnv = "{\"API_KEY\": \"test-key\"}";
        mcpTool.persist();

        try {
            Path configFile = generator.generateMcpConfig(9010L, Map.of(), null);
            JsonNode config = objectMapper.readTree(Files.readString(configFile));

            JsonNode servers = config.get("mcpServers");
            assertTrue(servers.has("test-external-stdio"),
                    "Config should include the external MCP server");

            JsonNode extServer = servers.get("test-external-stdio");
            assertEquals("npx", extServer.get("command").asText());

            JsonNode args = extServer.get("args");
            assertTrue(args.isArray());
            assertEquals("-y", args.get(0).asText());
            assertEquals("@test/mcp-server", args.get(1).asText());

            JsonNode env = extServer.get("env");
            assertEquals("test-key", env.get("API_KEY").asText());
        } finally {
            mcpTool.delete();
        }
    }

    @Test
    @Transactional
    void testExternalMcpServerHttpInConfig() throws Exception {
        McpServerEntity mcpTool = new McpServerEntity();
        mcpTool.name = "test-external-http";
        mcpTool.description = "Test external MCP server (HTTP)";
        mcpTool.serverUrl = "http://localhost:8080/mcp";
        mcpTool.persist();

        try {
            Path configFile = generator.generateMcpConfig(9011L, Map.of(), null);
            JsonNode config = objectMapper.readTree(Files.readString(configFile));

            JsonNode servers = config.get("mcpServers");
            assertTrue(servers.has("test-external-http"),
                    "Config should include the HTTP MCP server");

            JsonNode extServer = servers.get("test-external-http");
            assertEquals("http", extServer.get("type").asText());
            assertEquals("http://localhost:8080/mcp", extServer.get("url").asText());
        } finally {
            mcpTool.delete();
        }
    }

    // ── Edge cases ───────────────────────────────────────────────────

    @Test
    @Transactional
    void testNoToolsReturnsNull() throws Exception {
        long originalCount = ToolDefinitionEntity.count();
        List<ToolDefinitionEntity> saved = ToolDefinitionEntity.<ToolDefinitionEntity>listAll()
                .stream().toList();

        ToolDefinitionEntity.deleteAll();
        try {
            Path configFile = generator.generateMcpConfig(9012L, Map.of(), List.of("Bash"));
            assertNull(configFile, "Should return null when no MCP tools match");
        } finally {
            for (ToolDefinitionEntity tool : saved) {
                ToolDefinitionEntity restored = new ToolDefinitionEntity();
                restored.name = tool.name;
                restored.description = tool.description;
                restored.parameters = tool.parameters;
                restored.scriptTemplate = tool.scriptTemplate;
                restored.persist();
            }
        }
    }

    @Test
    @Transactional
    void testOnlyExternalMcpServersNoAxiomServers() throws Exception {
        List<ToolDefinitionEntity> saved = ToolDefinitionEntity.<ToolDefinitionEntity>listAll()
                .stream().toList();
        ToolDefinitionEntity.deleteAll();

        McpServerEntity mcpOnly = new McpServerEntity();
        mcpOnly.name = "test-mcp-only";
        mcpOnly.description = "Only MCP server, no script tools";
        mcpOnly.serverUrl = "http://localhost:9999/mcp";
        mcpOnly.persist();

        try {
            Path configFile = generator.generateMcpConfig(9013L, Map.of(),
                    List.of("mcp__test-mcp-only__some_tool"));
            assertNotNull(configFile, "Should generate config for MCP server tools");

            JsonNode config = objectMapper.readTree(Files.readString(configFile));
            JsonNode servers = config.get("mcpServers");

            assertFalse(servers.has("axiom-tools"),
                    "Should NOT have axiom-tools when no script tools requested");
            assertFalse(servers.has("axiom-sdk"),
                    "Should NOT have axiom-sdk when no SDK tools requested");
            assertTrue(servers.has("test-mcp-only"),
                    "Should have the external MCP server");
        } finally {
            mcpOnly.delete();
            for (ToolDefinitionEntity tool : saved) {
                ToolDefinitionEntity restored = new ToolDefinitionEntity();
                restored.name = tool.name;
                restored.description = tool.description;
                restored.parameters = tool.parameters;
                restored.scriptTemplate = tool.scriptTemplate;
                restored.persist();
            }
        }
    }

    @Test
    void testUniqueConfigFilePerTask() throws Exception {
        Path config1 = generator.generateMcpConfig(9014L, Map.of(), null);
        Path config2 = generator.generateMcpConfig(9015L, Map.of(), null);

        assertNotEquals(config1, config2, "Each task should get a unique config file");
        assertTrue(config1.getFileName().toString().contains("9014"),
                "Config filename should contain task ID");
        assertTrue(config2.getFileName().toString().contains("9015"),
                "Config filename should contain task ID");
    }
}
