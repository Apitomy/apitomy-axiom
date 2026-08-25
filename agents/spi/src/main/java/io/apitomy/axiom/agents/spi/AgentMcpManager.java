package io.apitomy.axiom.agents.spi;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * SPI interface for agent-specific MCP (Model Context Protocol) server management.
 * Each agent implementation handles MCP configuration differently:
 * <ul>
 *   <li>Claude Code: generates a JSON config file passed via {@code --mcp-config}</li>
 *   <li>OpenCode: registers servers via HTTP API</li>
 *   <li>Copilot: generates a JSON config file passed via {@code --additional-mcp-config}</li>
 * </ul>
 */
public interface AgentMcpManager {

    /**
     * Configures MCP servers for a work item execution. The returned path may be
     * used as an agent-specific MCP configuration reference (e.g. a config file
     * path for Claude Code), or null if the agent handles MCP setup differently.
     *
     * @param workItemId   the work item ID (for unique naming/isolation)
     * @param environment  environment variables to pass to MCP servers
     * @param allowedTools the action type's allowed tools list (may be empty)
     * @return path to the generated MCP config, or null if not applicable
     */
    Path configureMcpServers(Long workItemId, Map<String, String> environment,
                              List<String> allowedTools);

    /**
     * Cleans up MCP configuration after work item completion. Implementations
     * may delete temp files, deregister servers, etc.
     *
     * @param workItemId the work item ID whose MCP config should be cleaned up
     */
    default void cleanup(Long workItemId) {
    }
}
