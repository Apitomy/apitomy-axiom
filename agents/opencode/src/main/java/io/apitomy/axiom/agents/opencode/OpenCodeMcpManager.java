package io.apitomy.axiom.agents.opencode;

import io.apitomy.axiom.agents.spi.AgentMcpManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenCode implementation of {@link AgentMcpManager}. Registers MCP servers
 * dynamically via the OpenCode server's HTTP API ({@code POST /mcp}).
 *
 * <p>Unlike Claude Code (which generates per-task config files), OpenCode manages
 * MCP servers at the server level. This manager uses a delegate pattern: the
 * {@code McpConfigGenerator} in the app module provides MCP server details, and
 * this class registers them with the running OpenCode server via HTTP.</p>
 *
 * <p>Servers are registered lazily on first task that needs them, and are tracked
 * to avoid re-registering on subsequent tasks.</p>
 */
@ApplicationScoped
@Typed(OpenCodeMcpManager.class)
public class OpenCodeMcpManager implements AgentMcpManager {

    private static final Logger LOG = Logger.getLogger(OpenCodeMcpManager.class);

    @Inject
    OpenCodeAgent agent;

    /** Tracks which MCP servers have been registered with the OpenCode server. */
    private final Set<String> registeredServers = ConcurrentHashMap.newKeySet();

    /**
     * Functional interface for obtaining MCP server configurations from the app module.
     * The delegate is set by {@code McpConfigGenerator} during initialization.
     */
    @FunctionalInterface
    public interface McpServerProvider {
        /**
         * Returns a list of MCP server configurations that should be registered
         * for the given task.
         *
         * @param taskId       the task ID
         * @param environment  environment variables (e.g. GitHub tokens)
         * @param allowedTools the allowed tools list for filtering
         * @return list of server configs, each containing: name, type (local/remote),
         *         and connection details
         */
        List<McpServerConfig> getMcpServers(Long taskId, Map<String, String> environment,
                                             List<String> allowedTools);
    }

    /**
     * Configuration for a single MCP server to register.
     *
     * @param name        the MCP server name
     * @param type        the server type: "local" or "remote"
     * @param command     the command to start the server (local only)
     * @param environment environment variables for the server process (local only)
     * @param url         the remote server URL (remote only)
     */
    public record McpServerConfig(
            String name,
            String type,           // "local" or "remote"
            // Local server fields
            List<String> command,  // e.g. ["node", "/path/to/server.js", "/path/to/tools.json"]
            Map<String, String> environment,
            // Remote server fields
            String url
    ) {
        /**
         * Creates a local MCP server configuration.
         *
         * @param name        the server name
         * @param command     the command to start the server
         * @param environment environment variables
         * @return the local server config
         */
        public static McpServerConfig local(String name, List<String> command,
                                             Map<String, String> environment) {
            return new McpServerConfig(name, "local", command, environment, null);
        }

        /**
         * Creates a remote MCP server configuration.
         *
         * @param name the server name
         * @param url  the remote server URL
         * @return the remote server config
         */
        public static McpServerConfig remote(String name, String url) {
            return new McpServerConfig(name, "remote", null, null, url);
        }
    }

    private volatile McpServerProvider delegate;

    /**
     * Sets the delegate that provides MCP server configurations.
     * Called by {@code McpConfigGenerator} in the app module during initialization.
     *
     * @param delegate the MCP server provider delegate
     */
    public void setDelegate(McpServerProvider delegate) {
        this.delegate = delegate;
    }

    /** {@inheritDoc} */
    @Override
    public Path configureMcpServers(Long workItemId, Map<String, String> environment,
                                     List<String> allowedTools) {
        if (delegate == null) {
            LOG.debugf("No MCP server provider delegate set, skipping MCP registration for work item %d",
                    workItemId);
            return null;
        }

        try {
            List<McpServerConfig> servers = delegate.getMcpServers(workItemId, environment, allowedTools);
            if (servers == null || servers.isEmpty()) {
                LOG.debugf("No MCP servers needed for work item %d", workItemId);
                return null;
            }

            OpenCodeClient client = agent.getOrStartServer();

            for (McpServerConfig server : servers) {
                // Skip if already registered (servers persist at the OpenCode server level)
                if (registeredServers.contains(server.name())) {
                    LOG.debugf("MCP server '%s' already registered, skipping", server.name());
                    continue;
                }

                registerServer(client, server);
                registeredServers.add(server.name());
            }

        } catch (Exception e) {
            LOG.errorf(e, "Failed to register MCP servers for work item %d", workItemId);
        }

        // OpenCode doesn't use config files -- return null
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public void cleanup(Long workItemId) {
        // MCP servers persist at the OpenCode server level, no per-task cleanup needed.
        // They are cleaned up when the server shuts down.
    }

    /**
     * Registers a single MCP server with the OpenCode server via POST /mcp.
     */
    private void registerServer(OpenCodeClient client, McpServerConfig server) {
        try {
            Map<String, Object> config = new java.util.LinkedHashMap<>();

            if ("remote".equals(server.type()) && server.url() != null) {
                config.put("type", "remote");
                config.put("url", server.url());
            } else if ("local".equals(server.type()) && server.command() != null) {
                config.put("type", "local");
                config.put("command", server.command());
                if (server.environment() != null && !server.environment().isEmpty()) {
                    config.put("environment", server.environment());
                }
            } else {
                LOG.warnf("MCP server '%s' has no valid connection config, skipping", server.name());
                return;
            }

            client.addMcpServer(server.name(), config);
            LOG.infof("Registered MCP server '%s' (%s) with OpenCode", server.name(), server.type());

        } catch (Exception e) {
            LOG.errorf(e, "Failed to register MCP server '%s' with OpenCode", server.name());
        }
    }
}
