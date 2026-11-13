package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.config.AxiomHome;
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
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
    io.apitomy.axiom.app.assistant.AssistantContextBuilder contextBuilder;

    @Inject
    io.apitomy.axiom.app.mcp.McpServerResolver resolver;

    @Inject
    McpConfigGenerator generator;

    @Inject
    io.apitomy.axiom.app.mcp.McpEnvironmentIsolation isolation;

    @Inject
    ObjectMapper objectMapper;

    @BeforeEach
    @Transactional
    void ensureTestToolExists() {
        if (ToolDefinitionEntity.count() == 0) {
            ToolDefinitionEntity tool = new ToolDefinitionEntity();
            tool.name = "test_tool";
            tool.description = "Test tool for MCP config tests";
            tool.parameters = "[]";
            tool.scriptTemplate = "echo test";
            tool.persist();
        }
    }

    // ── Unrestricted config (both servers) ───────────────────────────

    /** OpenCode registration may run outside a transaction (run summaries); DB reads open their own. */
    @Test
    void openCodeServersCanBeComputedOutsideATransaction() {
        assertFalse(io.quarkus.narayana.jta.QuarkusTransaction.isActive());
        List<io.apitomy.axiom.agents.opencode.OpenCodeMcpManager.McpServerConfig> configs =
                generator.getMcpServersForOpenCode(9002L, Map.of(), List.of("mcp__axiom-tools__no-such-tool"));
        assertNotNull(configs);
    }

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
        assertTrue(McpTestSupport.isLaunched(axiomTools), "axiom-tools should start through the env launcher");

        List<String> real = McpTestSupport.realCommand(axiomTools);
        assertEquals(3, real.size(), "real command should be node, tools-server.js and the tools JSON path");
        assertEquals("node", real.get(0));

        String serverJsPath = real.get(1);
        String toolsJsonPath = real.get(2);

        assertTrue(serverJsPath.endsWith("tools-server.js"),
                "First arg should be tools-server.js path");
        assertTrue(toolsJsonPath.contains("/9002/"),
                "Second arg should contain task ID in path");

        assertTrue(Files.exists(Path.of(serverJsPath)), "tools-server.js should exist");
        assertTrue(Files.exists(Path.of(toolsJsonPath)), "tools JSON should exist");
    }

    @Test
    void testAxiomSdkServerConfig() throws Exception {
        Path configFile = generator.generateMcpConfig(9003L, Map.of(), null);
        JsonNode config = objectMapper.readTree(Files.readString(configFile));

        JsonNode axiomSdk = config.get("mcpServers").get("axiom-sdk");
        assertNotNull(axiomSdk, "axiom-sdk server should be present");

        List<String> real = McpTestSupport.realCommand(axiomSdk);
        assertEquals(2, real.size(), "SDK server should run node with only the sdk-server.js path");
        assertEquals("node", real.get(0));

        String sdkServerPath = real.get(1);
        assertTrue(sdkServerPath.endsWith("sdk-server.js"),
                "Arg should be sdk-server.js path");
        assertTrue(Files.exists(Path.of(sdkServerPath)), "sdk-server.js should exist");

        JsonNode env = axiomSdk.get("env");
        assertNotNull(env, "SDK server should have env");
        assertTrue(env.has("AXIOM_API_URL"), "SDK server env should have AXIOM_API_URL");
    }

    @Test
    void testToolsServerGetsOnlyAxiomVariablesOfTheAgentEnvironment() throws Exception {
        Map<String, String> env = Map.of(
                "GH_TOKEN", "ghp_test123",
                "ANTHROPIC_API_KEY", "sk-test",
                "AXIOM_TRACE_ID", "trace-1"
        );

        Path configFile = generator.generateMcpConfig(9004L, env, null);
        JsonNode config = objectMapper.readTree(Files.readString(configFile));

        JsonNode axiomToolsEnv = config.get("mcpServers").get("axiom-tools").get("env");
        assertNotNull(axiomToolsEnv, "axiom-tools env should be present");
        assertFalse(axiomToolsEnv.has("GH_TOKEN"), "agent variables must not reach the tools server");
        assertFalse(axiomToolsEnv.has("ANTHROPIC_API_KEY"), "the model key must not reach the tools server");
        assertEquals("trace-1", axiomToolsEnv.get("AXIOM_TRACE_ID").asText());
        assertTrue(axiomToolsEnv.has("AXIOM_API_URL"));
    }

    // ── Tools JSON file content ──────────────────────────────────────

    @Test
    void testToolsJsonContainsTools() throws Exception {
        Path configFile = generator.generateMcpConfig(9005L, Map.of(), null);
        JsonNode config = objectMapper.readTree(Files.readString(configFile));

        String toolsJsonPath = McpTestSupport.realCommand(config.get("mcpServers").get("axiom-tools")).get(2);
        JsonNode tools = objectMapper.readTree(Files.readString(Path.of(toolsJsonPath)));

        assertTrue(tools.isArray(), "Tools should be a JSON array");
        assertTrue(tools.size() >= 1, "Should have at least 1 script tool");

        boolean hasTestTool = false;
        for (JsonNode tool : tools) {
            String name = tool.get("name").asText();
            if ("test_tool".equals(name)) hasTestTool = true;
        }
        assertTrue(hasTestTool, "Should contain test_tool");
    }

    @Test
    void testToolJsonStructure() throws Exception {
        Path configFile = generator.generateMcpConfig(9006L, Map.of(), null);
        JsonNode config = objectMapper.readTree(Files.readString(configFile));

        String toolsJsonPath = McpTestSupport.realCommand(config.get("mcpServers").get("axiom-tools")).get(2);
        JsonNode tools = objectMapper.readTree(Files.readString(Path.of(toolsJsonPath)));

        JsonNode testTool = null;
        for (JsonNode tool : tools) {
            if ("test_tool".equals(tool.get("name").asText())) {
                testTool = tool;
                break;
            }
        }
        assertNotNull(testTool, "test_tool should exist");
        assertTrue(testTool.has("description"), "Tool should have description");
        assertTrue(testTool.has("scriptTemplate"), "Tool should have scriptTemplate");
    }

    // ── MCP server installation ──────────────────────────────────────

    @Test
    void testMcpServerSubjectInstalled() throws Exception {
        generator.generateMcpConfig(9007L, Map.of(), null);

        // The server is installed under the configured Axiom home (target/axiom-test-home in test runs).
        Path serverDir = AxiomHome.resolve("mcp-server");
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

        List<String> real1 = McpTestSupport.realCommand(cfg1.get("mcpServers").get("axiom-tools"));
        List<String> real2 = McpTestSupport.realCommand(cfg2.get("mcpServers").get("axiom-tools"));
        String serverJs1 = real1.get(1);
        String serverJs2 = real2.get(1);
        assertEquals(serverJs1, serverJs2,
                "Both configs should reference the same tools-server.js");

        String toolsJson1 = real1.get(2);
        String toolsJson2 = real2.get(2);
        assertNotEquals(toolsJson1, toolsJson2,
                "Each task should get its own tools JSON file");
    }

    // ── Server isolation (only script tools → no SDK server) ────────

    @Test
    void testOnlyScriptToolsNoSdkServer() throws Exception {
        Path configFile = generator.generateMcpConfig(9016L, Map.of(),
                List.of("mcp__axiom-tools__test_tool"));
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
                List.of("mcp__axiom-sdk__axiom_list_subjects"));
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
            Path configFile = generator.generateMcpConfig(9010L, Map.of(), List.of("mcp__test-external-stdio__*"));
            JsonNode config = objectMapper.readTree(Files.readString(configFile));

            JsonNode servers = config.get("mcpServers");
            assertTrue(servers.has("test-external-stdio"),
                    "Config should include the external MCP server");

            JsonNode extServer = servers.get("test-external-stdio");
            assertEquals(List.of("npx", "-y", "@test/mcp-server"), McpTestSupport.realCommand(extServer));

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
            Path configFile = generator.generateMcpConfig(9011L, Map.of(), List.of("mcp__test-external-http__*"));
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

    @Test
    @Transactional
    void testInvalidExternalServerIsOmittedAndConfigStaysValidJson() throws Exception {
        McpServerEntity broken = new McpServerEntity();
        broken.name = "test-broken";
        broken.serverCommand = "npx";
        broken.serverArgs = "not-json";
        broken.persist();
        McpServerEntity noTransport = new McpServerEntity();
        noTransport.name = "test-no-transport";
        noTransport.persist();
        McpServerEntity ok = new McpServerEntity();
        ok.name = "test-ok";
        ok.serverCommand = "npx";
        ok.serverArgs = "[\"arg with \\\"quotes\\\"\"]";
        ok.persist();

        try {
            List<String> allowed = List.of("mcp__test-broken__x", "mcp__test-no-transport__x", "mcp__test-ok__x");
            Path configFile = generator.generateMcpConfig(9013L, Map.of(), allowed);
            JsonNode servers = objectMapper.readTree(Files.readString(configFile)).get("mcpServers");
            assertFalse(servers.has("test-broken"));
            assertFalse(servers.has("test-no-transport"));
            assertEquals("arg with \"quotes\"", McpTestSupport.realCommand(servers.get("test-ok")).get(1));
        } finally {
            broken.delete();
            noTransport.delete();
            ok.delete();
        }
    }

    @Test
    @Transactional
    void testTaskAndSessionPathsProduceSameExternalServerConfig() throws Exception {
        McpServerEntity mcp = new McpServerEntity();
        mcp.name = "test-same";
        mcp.serverCommand = "npx";
        mcp.serverArgs = "[\"-y\"]";
        mcp.serverEnv = "{\"K\":\"v\"}";
        mcp.persist();
        try {
            Path configFile = generator.generateMcpConfig(9014L, Map.of(), List.of("mcp__test-same__x"));
            JsonNode taskNode = objectMapper.readTree(Files.readString(configFile)).get("mcpServers").get("test-same");
            String sessionJson = contextBuilder.buildMcpConfig(
                    isolation.isolateAll(resolver.resolveByName(List.of("test-same"))));
            JsonNode sessionNode = objectMapper.readTree(sessionJson).get("mcpServers").get("test-same");
            assertEquals(sessionNode, taskNode);
        } finally {
            mcp.delete();
        }
    }

    @Test
    @Transactional
    void testActiveExternalServerNamesExcludesDisabled() {
        McpServerEntity on = new McpServerEntity();
        on.name = "test-active-on";
        on.serverCommand = "npx";
        on.persist();
        McpServerEntity off = new McpServerEntity();
        off.name = "test-active-off";
        off.serverCommand = "npx";
        off.enabled = false;
        off.persist();
        try {
            java.util.Set<String> names = generator.activeExternalServerNames();
            assertTrue(names.contains("test-active-on"));
            assertFalse(names.contains("test-active-off"));
        } finally {
            on.delete();
            off.delete();
        }
    }

    @Test
    @Transactional
    void testUnrestrictedToolsDoNotLoadExternalServers() throws Exception {
        McpServerEntity mcp = new McpServerEntity();
        mcp.name = "test-implicit";
        mcp.serverCommand = "npx";
        mcp.persist();
        try {
            for (List<String> allowed : java.util.Arrays.asList(null, List.<String>of())) {
                Path configFile = generator.generateMcpConfig(9020L, Map.of(), allowed);
                if (configFile != null) {
                    JsonNode servers = objectMapper.readTree(Files.readString(configFile)).get("mcpServers");
                    assertFalse(servers.has("test-implicit"), "external servers must be selected explicitly");
                }
            }
        } finally {
            mcp.delete();
        }
    }

    @Test
    @Transactional
    void testExternalStdioServerGetsOnlyItsOwnEnvironment() throws Exception {
        McpServerEntity mcp = new McpServerEntity();
        mcp.name = "test-own-env";
        mcp.serverCommand = "npx";
        mcp.serverEnv = "{\"OWN\":\"1\"}";
        mcp.persist();
        try {
            Path configFile = generator.generateMcpConfig(9021L, Map.of("TASK_SECRET", "s", "AXIOM_TRACE_ID", "t"),
                    List.of("mcp__test-own-env__*"));
            JsonNode env = objectMapper.readTree(Files.readString(configFile))
                    .get("mcpServers").get("test-own-env").get("env");
            assertEquals(1, env.size());
            assertEquals("1", env.get("OWN").asText());
        } finally {
            mcp.delete();
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
        assertTrue(config1.toString().contains("/9014/"),
                "Config path should contain task ID");
        assertTrue(config2.toString().contains("/9015/"),
                "Config path should contain task ID");
    }
}
