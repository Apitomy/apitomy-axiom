package io.apitomy.axiom.agents.opencode;

import io.apitomy.axiom.agents.spi.AgentMcpManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.Supplier;

/**
 * OpenCode implementation of {@link AgentMcpManager}. Registers MCP servers
 * dynamically via the OpenCode server's HTTP API ({@code POST /mcp}).
 *
 * <p>Unlike Claude Code (which generates per-task config files), OpenCode manages
 * MCP servers at the server level. This manager uses a delegate pattern: the
 * {@code McpConfigGenerator} in the app module provides MCP server details, and
 * this class registers them with the running OpenCode server via HTTP.</p>
 *
 * <p>Servers are registered lazily on the first task that needs them. Registrations are tracked by a
 * fingerprint of their config: an edited server is re-registered (OpenCode replaces a server added under
 * the same name), and a server that was deleted or disabled in Axiom is disconnected. A new OpenCode
 * server process starts with a clean slate. Built-in Axiom servers are registered once per process.</p>
 */
@ApplicationScoped
@Typed(OpenCodeMcpManager.class)
public class OpenCodeMcpManager implements AgentMcpManager {

    private static final Logger LOG = Logger.getLogger(OpenCodeMcpManager.class);

    @Inject
    OpenCodeAgent agent;

    /** Built-in Axiom servers: registered once per OpenCode process, never disconnected. */
    private static final Set<String> BUILT_IN_SERVERS = Set.of("axiom-tools", "axiom-sdk");

    /** Registered server name to config fingerprint, for the current OpenCode server instance. */
    private final Map<String, Integer> registeredServers = new HashMap<>();

    /** The OpenCode server instance the registrations belong to. */
    private Object registeredOn;

    private volatile Supplier<Set<String>> activeServerNames;

    /**
     * Registers and disconnects MCP servers on an OpenCode server. Extracted for testing.
     */
    interface McpRegistrar {
        /**
         * Adds (or replaces) an MCP server.
         *
         * @param name the server name
         * @param config the OpenCode MCP config
         * @throws Exception on failure
         */
        void add(String name, Map<String, Object> config) throws Exception;

        /**
         * Disconnects an MCP server.
         *
         * @param name the server name
         * @throws Exception on failure
         */
        void disconnect(String name) throws Exception;
    }

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
     * @param headers     HTTP headers (remote only; may be null)
     * @param timeout     timeout in milliseconds (may be null)
     */
    public record McpServerConfig(
            String name,
            String type,           // "local" or "remote"
            // Local server fields
            List<String> command,  // e.g. ["node", "/path/to/server.js", "/path/to/tools.json"]
            Map<String, String> environment,
            // Remote server fields
            String url,
            Map<String, String> headers,
            Integer timeout
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
            return new McpServerConfig(name, "local", command, environment, null, null, null);
        }

        /**
         * Creates a remote MCP server configuration.
         *
         * @param name the server name
         * @param url  the remote server URL
         * @return the remote server config
         */
        public static McpServerConfig remote(String name, String url) {
            return new McpServerConfig(name, "remote", null, null, url, null, null);
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

    /**
     * Sets the supplier of names of all user-defined MCP servers that currently exist and are enabled.
     * Registered servers missing from this set are disconnected. If unset, nothing is disconnected.
     *
     * @param activeServerNames the supplier
     */
    public void setActiveServerNames(Supplier<Set<String>> activeServerNames) {
        this.activeServerNames = activeServerNames;
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
            List<McpServerConfig> provided = delegate.getMcpServers(workItemId, environment, allowedTools);
            // Sync even when this task needs no servers, so deleted/disabled ones get disconnected.
            List<McpServerConfig> servers = provided != null ? provided : List.of();

            OpenCodeClient client = agent.getOrStartServer();
            Supplier<Set<String>> active = activeServerNames;
            sync(client, new McpRegistrar() {
                @Override
                public void add(String name, Map<String, Object> config) throws Exception {
                    client.addMcpServer(name, config);
                }

                @Override
                public void disconnect(String name) throws Exception {
                    client.disconnectMcpServer(name);
                }
            }, servers, active != null ? active.get() : null);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to register MCP servers for work item %d", workItemId);
        }

        // OpenCode doesn't use config files -- return null
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public void cleanup(Long workItemId) {
        // Registrations are per OpenCode server process, not per task: stale servers are disconnected
        // on the next configureMcpServers call (see sync), and everything goes away with the process.
    }

    /**
     * Brings the OpenCode server's registrations in line with the given servers.
     *
     * @param serverInstance identifies the OpenCode server process; a new instance resets tracking
     * @param registrar performs the registrations
     * @param servers the servers this task needs
     * @param activeNames all user-defined servers that still exist and are enabled, or {@code null} if unknown
     */
    synchronized void sync(Object serverInstance, McpRegistrar registrar, List<McpServerConfig> servers,
                           Set<String> activeNames) {
        if (registeredOn != serverInstance) {
            registeredServers.clear();
            registeredOn = serverInstance;
        }
        if (activeNames != null) {
            for (String name : new ArrayList<>(registeredServers.keySet())) {
                if (!BUILT_IN_SERVERS.contains(name) && !activeNames.contains(name)) {
                    try {
                        registrar.disconnect(name);
                        LOG.infof("Disconnected MCP server '%s' from OpenCode (deleted or disabled)", name);
                    } catch (Exception e) {
                        LOG.warnf(e, "Failed to disconnect MCP server '%s' from OpenCode", name);
                    }
                    registeredServers.remove(name);
                }
            }
        }
        for (McpServerConfig server : servers) {
            Map<String, Object> config = toOpenCodeConfig(server);
            if (config == null) {
                LOG.warnf("MCP server '%s' has no valid connection config, skipping", server.name());
                continue;
            }
            Integer previous = registeredServers.get(server.name());
            int fingerprint = config.hashCode();
            if (previous != null && (BUILT_IN_SERVERS.contains(server.name()) || previous == fingerprint)) {
                continue;
            }
            try {
                registrar.add(server.name(), config);
                registeredServers.put(server.name(), fingerprint);
                LOG.infof("%s MCP server '%s' (%s) with OpenCode",
                        previous == null ? "Registered" : "Re-registered", server.name(), server.type());
            } catch (Exception e) {
                LOG.errorf(e, "Failed to register MCP server '%s' with OpenCode", server.name());
            }
        }
    }

    /**
     * Builds the OpenCode {@code POST /mcp} config for a server.
     *
     * @param server the server
     * @return the config, or {@code null} if the server has no valid connection details
     */
    private static Map<String, Object> toOpenCodeConfig(McpServerConfig server) {
        Map<String, Object> config = new LinkedHashMap<>();
        if ("remote".equals(server.type()) && server.url() != null) {
            config.put("type", "remote");
            config.put("url", server.url());
            if (server.headers() != null && !server.headers().isEmpty()) {
                config.put("headers", server.headers());
            }
        } else if ("local".equals(server.type()) && server.command() != null) {
            config.put("type", "local");
            config.put("command", server.command());
            if (server.environment() != null && !server.environment().isEmpty()) {
                config.put("environment", server.environment());
            }
        } else {
            return null;
        }
        if (server.timeout() != null) {
            config.put("timeout", server.timeout());
        }
        return config;
    }
}
