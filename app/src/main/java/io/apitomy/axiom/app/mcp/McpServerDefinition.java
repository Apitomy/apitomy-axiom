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
 * @param type the transport type: {@code null} for stdio, {@code "http"} or {@code "sse"} for remote
 * @param url the server URL (remote only)
 * @param headers HTTP headers (remote only; never null)
 * @param timeoutMs timeout in milliseconds, or {@code null} for the engine default
 */
public record McpServerDefinition(String command, List<String> args, Map<String, String> env,
                                  String type, String url, Map<String, String> headers, Integer timeoutMs) {

    /**
     * Normalizes null collections.
     */
    public McpServerDefinition {
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : env;
        headers = headers == null ? Map.of() : headers;
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
        return new McpServerDefinition(command, args, env, null, null, Map.of(), null);
    }

    /**
     * Creates an HTTP-transport definition.
     *
     * @param url the server URL
     * @return the definition
     */
    public static McpServerDefinition http(String url) {
        return remote("http", url, Map.of(), null);
    }

    /**
     * Creates a remote-transport definition.
     *
     * @param type {@code "http"} or {@code "sse"}
     * @param url the server URL
     * @param headers HTTP headers
     * @param timeoutMs timeout in milliseconds, or {@code null}
     * @return the definition
     */
    public static McpServerDefinition remote(String type, String url, Map<String, String> headers,
                                             Integer timeoutMs) {
        return new McpServerDefinition(null, List.of(), Map.of(), type, url, headers, timeoutMs);
    }

    /**
     * Returns whether this definition uses a remote (HTTP or SSE) transport.
     *
     * @return true for remote transports
     */
    public boolean isHttpTransport() {
        return "http".equals(type) || "sse".equals(type);
    }
}
