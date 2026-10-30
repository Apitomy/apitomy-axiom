package io.apitomy.axiom.app.mcp;

/**
 * Thrown when an MCP server cannot be found.
 */
public class McpServerNotFoundException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param id the id that was not found
     */
    public McpServerNotFoundException(long id) {
        super("MCP server not found: " + id);
    }
}
