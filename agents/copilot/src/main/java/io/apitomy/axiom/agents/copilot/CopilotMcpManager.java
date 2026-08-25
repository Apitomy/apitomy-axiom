package io.apitomy.axiom.agents.copilot;

import io.apitomy.axiom.agents.spi.AgentMcpManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Copilot CLI implementation of {@link AgentMcpManager}. Delegates to the
 * existing {@code io.apitomy.axiom.app.McpConfigGenerator} to produce the
 * {@code mcpServers} JSON config passed to the {@code copilot} CLI via
 * {@code --additional-mcp-config @<file>}. The config format is identical to
 * Claude Code's {@code --mcp-config} file, so the same generator is reused
 * unchanged.
 *
 * <p>Note: This is a thin adapter. The actual MCP config generation logic
 * remains in {@code McpConfigGenerator} (in the app module) and is injected
 * here. This class exists to satisfy the agent SPI contract so that
 * consumers can use {@link AgentMcpManager} without coupling to
 * Copilot-specific code.</p>
 */
@ApplicationScoped
@Typed(CopilotMcpManager.class)
public class CopilotMcpManager implements AgentMcpManager {

    private static final Logger LOG = Logger.getLogger(CopilotMcpManager.class);

    /**
     * Functional interface for the MCP config generation, allowing the app module's
     * McpConfigGenerator to be injected without creating a circular dependency.
     */
    @FunctionalInterface
    public interface McpConfigDelegate {

        /**
         * Generates an MCP configuration file for the given work item.
         *
         * @param workItemId   the work item ID
         * @param environment  environment variables for MCP servers
         * @param allowedTools the allowed tools list
         * @return path to the generated MCP config file
         */
        Path generateMcpConfig(Long workItemId, Map<String, String> environment,
                               List<String> allowedTools);
    }

    private volatile McpConfigDelegate delegate;

    /**
     * Sets the delegate that performs the actual MCP config generation.
     * Called by the app module during initialization.
     *
     * @param delegate the MCP config generation delegate
     */
    public void setDelegate(McpConfigDelegate delegate) {
        this.delegate = delegate;
        LOG.infof("MCP config delegate set on CopilotMcpManager instance@%d",
                System.identityHashCode(this));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Path configureMcpServers(Long workItemId, Map<String, String> environment,
                                     List<String> allowedTools) {
        if (delegate != null) {
            Path result = delegate.generateMcpConfig(workItemId, environment, allowedTools);
            LOG.debugf("MCP config for work item %d: %s", workItemId, result);
            return result;
        }
        LOG.warnf("MCP config delegate not set on CopilotMcpManager instance@%d — "
                + "cannot generate MCP config for work item %d",
                System.identityHashCode(this), workItemId);
        return null;
    }
}
