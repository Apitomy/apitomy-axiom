package io.apitomy.axiom.app.mcp;

import java.util.List;
import java.util.Map;

/**
 * Engine-neutral, fully resolved MCP server configuration. Produced by {@link McpServerResolver} (for
 * user-defined servers) or directly by callers (for built-in servers), and serialized per engine by
 * {@link ClaudeMcpConfigWriter}, the OpenCode config writer, or the OpenCode task MCP manager.
 *
 * @param command the stdio command (stdio only)
 * @param args the stdio arguments (never null)
 * @param env the stdio environment (never null)
 * @param type the transport type: {@code null} for stdio, {@code "http"} for HTTP
 * @param url the server URL (HTTP only)
 */
public record McpServerDefinition(String command, List<String> args, Map<String, String> env,
                                  String type, String url) {

    /**
     * Normalizes null collections.
     */
    public McpServerDefinition {
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : env;
    }

    /**
     * Creates a stdio-transport definition.
     *
     * @param command the command
     * @param args the arguments
     * @param env the environment variables
     * @return the definition
     */
    public static McpServerDefinition stdio(String command, List<String> args, Map<String, String> env) {
        return new McpServerDefinition(command, args, env, null, null);
    }

    /**
     * Creates an HTTP-transport definition.
     *
     * @param url the server URL
     * @return the definition
     */
    public static McpServerDefinition http(String url) {
        return new McpServerDefinition(null, List.of(), Map.of(), "http", url);
    }

    /**
     * Returns whether this definition uses HTTP transport.
     *
     * @return true for HTTP transport
     */
    public boolean isHttpTransport() {
        return "http".equals(type);
    }
}
