package io.apitomy.axiom.app.mcp;

/**
 * Thrown when an MCP server definition is invalid.
 */
public class McpServerValidationException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message a user-facing description of the problem
     */
    public McpServerValidationException(String message) {
        super(message);
    }
}
