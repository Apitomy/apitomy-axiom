package io.apitomy.axiom.app.mcp;

/**
 * Thrown when an MCP server operation conflicts with existing state (e.g. duplicate name).
 */
public class McpServerConflictException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message a user-facing description of the conflict
     */
    public McpServerConflictException(String message) {
        super(message);
    }
}
