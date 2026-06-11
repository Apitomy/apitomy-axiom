package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that starts the generated Axiom MCP server as a subprocess
 * and communicates with it over the MCP stdio protocol (JSON-RPC 2.0).
 *
 * <p>This test verifies the full round-trip: Java generates the MCP server
 * project via {@link McpConfigGenerator}, then the test starts the server,
 * lists available tools, calls a "hello_world" tool, and verifies the result.</p>
 */
@QuarkusTest
class McpServerIntegrationTest {

    @Inject
    McpConfigGenerator generator;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Generates the MCP server, starts it, and verifies that a hello_world
     * tool can be listed and called successfully.
     */
    @Test
    @Transactional
    void testHelloWorldToolEndToEnd() throws Exception {
        // Create a simple hello_world tool
        ToolDefinitionEntity helloTool = new ToolDefinitionEntity();
        helloTool.name = "hello_world";
        helloTool.description = "Says hello to someone";

        helloTool.scriptTemplate = "echo Hello, {{name}}!";
        helloTool.parameters = "[{\"name\":\"name\",\"type\":\"string\","
                + "\"description\":\"Who to greet\",\"required\":true}]";
        helloTool.persist();

        Process serverProcess = null;
        try {
            // Generate MCP config — this installs the server and writes the tools JSON
            Path mcpConfig = generator.generateMcpConfig(88888L, Map.of(), null);
            assertNotNull(mcpConfig, "MCP config should be generated");

            // Parse the config to extract server.js path and tools JSON path
            JsonNode config = objectMapper.readTree(Files.readString(mcpConfig));
            JsonNode args = config.get("mcpServers").get("axiom-tools").get("args");
            String serverJsPath = args.get(0).asText();
            String toolsJsonPath = args.get(1).asText();

            assertTrue(Files.exists(Path.of(serverJsPath)), "tools-server.js should exist");
            assertTrue(Files.exists(Path.of(toolsJsonPath)), "tools JSON should exist");

            // Start the MCP server subprocess
            ProcessBuilder pb = new ProcessBuilder("node", serverJsPath, toolsJsonPath);
            pb.redirectErrorStream(false);
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            serverProcess = pb.start();

            OutputStream stdin = serverProcess.getOutputStream();
            BufferedReader stdout = new BufferedReader(
                    new InputStreamReader(serverProcess.getInputStream(), StandardCharsets.UTF_8));

            // ── Step 1: Initialize the MCP session ──────────────────────────
            sendJsonRpc(stdin, objectMapper, Map.of(
                    "jsonrpc", "2.0",
                    "id", 1,
                    "method", "initialize",
                    "params", Map.of(
                            "protocolVersion", "2024-11-05",
                            "capabilities", Map.of(),
                            "clientInfo", Map.of(
                                    "name", "axiom-test",
                                    "version", "1.0.0"
                            )
                    )
            ));

            JsonNode initResponse = readJsonRpcWithTimeout(stdout, objectMapper, 10000);
            assertNotNull(initResponse.get("result"), "Initialize should return a result");
            assertNotNull(initResponse.get("result").get("serverInfo"),
                    "Initialize result should include serverInfo");

            // Send initialized notification (no response expected)
            sendJsonRpc(stdin, objectMapper, Map.of(
                    "jsonrpc", "2.0",
                    "method", "notifications/initialized"
            ));

            // Small delay to let the server process the notification
            Thread.sleep(100);

            // ── Step 2: List tools ──────────────────────────────────────────
            sendJsonRpc(stdin, objectMapper, Map.of(
                    "jsonrpc", "2.0",
                    "id", 2,
                    "method", "tools/list",
                    "params", Map.of()
            ));

            JsonNode listResponse = readJsonRpcWithTimeout(stdout, objectMapper, 5000);
            assertNotNull(listResponse.get("result"), "tools/list should return a result");
            JsonNode tools = listResponse.get("result").get("tools");
            assertTrue(tools.isArray(), "tools should be an array");
            assertTrue(tools.size() >= 1, "Should have at least 1 tool");

            // Find the hello_world tool
            boolean foundHelloWorld = false;
            for (JsonNode tool : tools) {
                if ("hello_world".equals(tool.get("name").asText())) {
                    foundHelloWorld = true;
                    assertEquals("Says hello to someone", tool.get("description").asText());

                    // Verify input schema
                    JsonNode inputSchema = tool.get("inputSchema");
                    assertNotNull(inputSchema, "Tool should have inputSchema");
                    assertTrue(inputSchema.get("properties").has("name"),
                            "Schema should have 'name' property");
                    assertTrue(inputSchema.get("required").toString().contains("name"),
                            "Schema should mark 'name' as required");
                }
            }
            assertTrue(foundHelloWorld, "hello_world tool should be listed");

            // ── Step 3: Call the hello_world tool ───────────────────────────
            sendJsonRpc(stdin, objectMapper, Map.of(
                    "jsonrpc", "2.0",
                    "id", 3,
                    "method", "tools/call",
                    "params", Map.of(
                            "name", "hello_world",
                            "arguments", Map.of("name", "Axiom")
                    )
            ));

            JsonNode callResponse = readJsonRpcWithTimeout(stdout, objectMapper, 10000);
            assertNotNull(callResponse.get("result"), "tools/call should return a result");
            JsonNode content = callResponse.get("result").get("content");
            assertTrue(content.isArray() && content.size() > 0,
                    "Result should have content array");
            String text = content.get(0).get("text").asText();
            assertTrue(text.contains("Hello, Axiom!"),
                    "Tool output should contain 'Hello, Axiom!' but was: " + text);
            assertNull(callResponse.get("result").get("isError"),
                    "Result should not be an error");

        } finally {
            if (serverProcess != null && serverProcess.isAlive()) {
                serverProcess.destroyForcibly();
                serverProcess.waitFor(5, TimeUnit.SECONDS);
            }
            helloTool.delete();
        }
    }

    /**
     * Verifies that calling an unknown tool returns an error response.
     */
    @Test
    @Transactional
    void testUnknownToolReturnsError() throws Exception {
        Path mcpConfig = generator.generateMcpConfig(88889L, Map.of(), null);
        assertNotNull(mcpConfig);

        JsonNode config = objectMapper.readTree(Files.readString(mcpConfig));
        JsonNode args = config.get("mcpServers").get("axiom-tools").get("args");

        Process serverProcess = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("node",
                    args.get(0).asText(), args.get(1).asText());
            serverProcess = pb.start();

            OutputStream stdin = serverProcess.getOutputStream();
            BufferedReader stdout = new BufferedReader(
                    new InputStreamReader(serverProcess.getInputStream(), StandardCharsets.UTF_8));

            // Initialize
            initializeMcpSession(stdin, stdout, objectMapper);

            // Call a tool that doesn't exist
            sendJsonRpc(stdin, objectMapper, Map.of(
                    "jsonrpc", "2.0",
                    "id", 10,
                    "method", "tools/call",
                    "params", Map.of(
                            "name", "nonexistent_tool",
                            "arguments", Map.of()
                    )
            ));

            JsonNode callResponse = readJsonRpcWithTimeout(stdout, objectMapper, 5000);
            JsonNode result = callResponse.get("result");
            assertNotNull(result, "Should get a result even for unknown tools");
            assertTrue(result.get("isError").asBoolean(),
                    "Unknown tool should return isError=true");
            assertTrue(result.get("content").get(0).get("text").asText()
                            .contains("Unknown tool"),
                    "Error message should mention unknown tool");

        } finally {
            if (serverProcess != null && serverProcess.isAlive()) {
                serverProcess.destroyForcibly();
                serverProcess.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Verifies that the {{param_file}} placeholder writes the value to a temp
     * file and substitutes the file path into the command.
     */
    @Test
    @Transactional
    void testFileParameterSubstitution() throws Exception {
        // Create a tool that uses {{body_file}} placeholder
        ToolDefinitionEntity fileTool = new ToolDefinitionEntity();
        fileTool.name = "file_echo";
        fileTool.description = "Echoes content from a file parameter";
        fileTool.scriptTemplate = "cat {{body_file}}";
        fileTool.parameters = "[{\"name\":\"body\",\"type\":\"string\","
                + "\"description\":\"Content to echo\",\"required\":true}]";
        fileTool.persist();

        Process serverProcess = null;
        try {
            Path mcpConfig = generator.generateMcpConfig(88890L, Map.of(), null);
            assertNotNull(mcpConfig);

            JsonNode config = objectMapper.readTree(Files.readString(mcpConfig));
            JsonNode args = config.get("mcpServers").get("axiom-tools").get("args");

            ProcessBuilder pb = new ProcessBuilder("node",
                    args.get(0).asText(), args.get(1).asText());
            serverProcess = pb.start();

            OutputStream stdin = serverProcess.getOutputStream();
            BufferedReader stdout = new BufferedReader(
                    new InputStreamReader(serverProcess.getInputStream(), StandardCharsets.UTF_8));

            initializeMcpSession(stdin, stdout, objectMapper);

            // Call with a body that contains special characters
            String bodyContent = "Hello from file!\nLine 2 with 'quotes' and \"double quotes\"";
            sendJsonRpc(stdin, objectMapper, Map.of(
                    "jsonrpc", "2.0",
                    "id", 20,
                    "method", "tools/call",
                    "params", Map.of(
                            "name", "file_echo",
                            "arguments", Map.of("body", bodyContent)
                    )
            ));

            JsonNode callResponse = readJsonRpcWithTimeout(stdout, objectMapper, 10000);
            JsonNode result = callResponse.get("result");
            assertNotNull(result, "Should get a result");
            assertNull(result.get("isError"),
                    "Should not be an error");

            String output = result.get("content").get(0).get("text").asText();
            assertTrue(output.contains("Hello from file!"),
                    "Output should contain the file content but was: " + output);
            assertTrue(output.contains("Line 2"),
                    "Output should contain second line");

        } finally {
            if (serverProcess != null && serverProcess.isAlive()) {
                serverProcess.destroyForcibly();
                serverProcess.waitFor(5, TimeUnit.SECONDS);
            }
            fileTool.delete();
        }
    }

    // ── MCP protocol helpers ─────────────────────────────────────────

    /**
     * Performs the MCP initialization handshake (initialize + initialized).
     */
    private void initializeMcpSession(OutputStream stdin, BufferedReader stdout,
                                       ObjectMapper mapper) throws Exception {
        sendJsonRpc(stdin, mapper, Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of(
                                "name", "axiom-test",
                                "version", "1.0.0"
                        )
                )
        ));
        JsonNode initResponse = readJsonRpcWithTimeout(stdout, mapper, 10000);
        assertNotNull(initResponse.get("result"), "Initialize should succeed");

        sendJsonRpc(stdin, mapper, Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized"
        ));
        Thread.sleep(100);
    }

    /**
     * Sends a JSON-RPC message to the server via stdin.
     */
    private void sendJsonRpc(OutputStream out, ObjectMapper mapper,
                              Map<String, Object> message) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(message);
        out.write(bytes);
        out.write('\n');
        out.flush();
    }

    /**
     * Reads a JSON-RPC response from the server's stdout with a timeout.
     * Skips any notification lines (messages without an "id" field) that
     * the server may send asynchronously.
     */
    private JsonNode readJsonRpcWithTimeout(BufferedReader reader, ObjectMapper mapper,
                                             long timeoutMs) throws Exception {
        CompletableFuture<JsonNode> future = CompletableFuture.supplyAsync(() -> {
            try {
                while (true) {
                    String line = reader.readLine();
                    if (line == null) {
                        throw new RuntimeException("Server closed stdout unexpectedly");
                    }
                    JsonNode node = mapper.readTree(line);
                    // Skip notifications (no "id" field) — only return responses
                    if (node.has("id")) {
                        return node;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
