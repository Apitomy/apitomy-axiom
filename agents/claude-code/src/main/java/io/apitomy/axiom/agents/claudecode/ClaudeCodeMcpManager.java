package io.apitomy.axiom.agents.claudecode;

import io.apitomy.axiom.agents.spi.AgentMcpManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Claude Code implementation of {@link AgentMcpManager}. Delegates to the
 * app module's {@code McpConfigGenerator} to produce {@code --mcp-config}
 * JSON files for Claude Code subprocesses.
 */
@ApplicationScoped
@Typed(ClaudeCodeMcpManager.class)
public class ClaudeCodeMcpManager implements AgentMcpManager {

    private static final Logger LOG = Logger.getLogger(ClaudeCodeMcpManager.class);

    /**
     * Functional interface for the MCP config generation, allowing the app module's
     * McpConfigGenerator to be injected without creating a circular dependency.
     */
    @FunctionalInterface
    public interface McpConfigDelegate {
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
        LOG.infof("MCP config delegate set on ClaudeCodeMcpManager instance@%d",
                System.identityHashCode(this));
    }

    /** {@inheritDoc} */
    @Override
    public Path configureMcpServers(Long workItemId, Map<String, String> environment,
                                     List<String> allowedTools) {
        if (delegate != null) {
            Path result = delegate.generateMcpConfig(workItemId, environment, allowedTools);
            LOG.debugf("MCP config for work item %d: %s", workItemId, result);
            return result;
        }
        LOG.warnf("MCP config delegate not set — cannot generate MCP config for work item %d",
                workItemId);
        return null;
    }
}
