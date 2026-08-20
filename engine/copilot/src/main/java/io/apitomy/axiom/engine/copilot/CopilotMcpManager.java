package io.apitomy.axiom.engine.copilot;

import io.apitomy.axiom.engine.spi.AiEngineMcpManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Copilot CLI implementation of {@link AiEngineMcpManager}. Delegates to the
 * existing {@code io.apitomy.axiom.app.McpConfigGenerator} to produce the
 * {@code mcpServers} JSON config passed to the {@code copilot} CLI via
 * {@code --additional-mcp-config @<file>}. The config format is identical to
 * Claude Code's {@code --mcp-config} file, so the same generator is reused
 * unchanged.
 *
 * <p>Note: This is a thin adapter. The actual MCP config generation logic
 * remains in {@code McpConfigGenerator} (in the app module) and is injected
 * here. This class exists to satisfy the engine SPI contract so that
 * consumers can use {@link AiEngineMcpManager} without coupling to
 * Copilot-specific code.</p>
 */
@ApplicationScoped
@Typed(CopilotMcpManager.class)
public class CopilotMcpManager implements AiEngineMcpManager {

    private static final Logger LOG = Logger.getLogger(CopilotMcpManager.class);

    /**
     * Functional interface for the MCP config generation, allowing the app module's
     * McpConfigGenerator to be injected without creating a circular dependency.
     */
    @FunctionalInterface
    public interface McpConfigDelegate {
        Path generateMcpConfig(Long taskId, Map<String, String> environment,
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

    @Override
    public Path configureMcpServers(Long taskId, Map<String, String> environment,
                                     List<String> allowedTools) {
        if (delegate != null) {
            Path result = delegate.generateMcpConfig(taskId, environment, allowedTools);
            LOG.debugf("MCP config for task %d: %s", taskId, result);
            return result;
        }
        LOG.warnf("MCP config delegate not set on CopilotMcpManager instance@%d — "
                + "cannot generate MCP config for task %d",
                System.identityHashCode(this), taskId);
        return null;
    }
}
