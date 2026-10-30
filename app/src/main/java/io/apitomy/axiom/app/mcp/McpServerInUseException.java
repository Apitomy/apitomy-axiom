package io.apitomy.axiom.app.mcp;

import java.util.List;

/**
 * Thrown when deleting an MCP server that is still referenced.
 */
public class McpServerInUseException extends RuntimeException {

    private final transient List<McpServerReferences.Usage> usages;

    /**
     * Creates the exception.
     *
     * @param serverName the server name
     * @param usages the references
     */
    public McpServerInUseException(String serverName, List<McpServerReferences.Usage> usages) {
        super("MCP server '" + serverName + "' is in use by " + usages.size()
                + " item(s); remove those references before deleting it");
        this.usages = usages;
    }

    /**
     * @return the references
     */
    public List<McpServerReferences.Usage> usages() {
        return usages;
    }
}
