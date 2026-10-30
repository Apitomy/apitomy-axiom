package io.apitomy.axiom.app.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Serializes MCP server definitions to the {@code {"mcpServers": {...}}} JSON format consumed by
 * Claude Code ({@code --mcp-config}) and GitHub Copilot CLI.
 */
public final class ClaudeMcpConfigWriter {

    private ClaudeMcpConfigWriter() {
    }

    /**
     * Builds the config JSON.
     *
     * @param mapper the object mapper
     * @param servers server name to definition, in output order
     * @return the pretty-printed JSON, or {@code null} if there are no servers
     */
    public static String toJson(ObjectMapper mapper, Map<String, McpServerDefinition> servers) {
        if (servers == null || servers.isEmpty()) {
            return null;
        }
        ObjectNode root = mapper.createObjectNode();
        ObjectNode serversNode = root.putObject("mcpServers");
        servers.forEach((name, def) -> {
            ObjectNode node = serversNode.putObject(name);
            if (def.isHttpTransport()) {
                node.put("type", def.type());
                node.put("url", def.url());
                if (!def.headers().isEmpty()) {
                    ObjectNode headers = node.putObject("headers");
                    def.headers().forEach(headers::put);
                }
            } else {
                node.put("command", def.command());
                ArrayNode args = node.putArray("args");
                def.args().forEach(args::add);
                if (!def.env().isEmpty()) {
                    ObjectNode env = node.putObject("env");
                    def.env().forEach(env::put);
                }
            }
        });
        return root.toPrettyString();
    }
}
