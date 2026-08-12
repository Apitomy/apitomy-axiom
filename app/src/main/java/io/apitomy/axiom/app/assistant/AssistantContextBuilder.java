package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Creates the directory structure for an assistant session. Each session has
 * two directories: a session directory (always under {@code ~/.axiom/assistant/sessions/})
 * that holds session metadata like {@code mcp-config.json}, and a working
 * directory where Claude Code runs. If the template does not specify a
 * working directory, one is created inside the session directory.
 */
@ApplicationScoped
public class AssistantContextBuilder {

    private static final Logger LOG = Logger.getLogger(AssistantContextBuilder.class);

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "9090")
    int httpPort;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Creates the session directory for an assistant session. The session
     * directory lives at {@code ~/.axiom/assistant/sessions/<sessionId>/}
     * and holds session-level files like {@code mcp-config.json}.
     *
     * @param sessionId the unique session identifier
     * @param mcpConfig the MCP configuration JSON string, or null
     * @return the path to the created session directory
     * @throws IOException if directory creation fails
     */
    public Path createSessionDirectory(String sessionId, String mcpConfig) throws IOException {
        Path axiomHome = Path.of(System.getProperty("user.home"), ".axiom");
        Path sessionsRoot = axiomHome.resolve("assistant").resolve("sessions");
        Path sessionDir = sessionsRoot.resolve(sessionId);

        Files.createDirectories(sessionDir);

        if (mcpConfig != null) {
            Files.writeString(sessionDir.resolve("mcp-config.json"), mcpConfig);
        }

        LOG.infof("Created session directory: %s", sessionDir);
        return sessionDir;
    }

    /**
     * Creates the working directory inside the session directory. Used when the
     * template does not specify an external working directory.
     *
     * @param sessionDir the session directory
     * @param templateId the template this session was created from
     * @return the path to the created working directory
     * @throws IOException if directory creation fails
     */
    public Path createWorkingDirectory(Path sessionDir, String templateId) throws IOException {
        Path workDir = sessionDir.resolve("workDir");
        Files.createDirectories(workDir);

        // Config Assistant needs item subdirectories
        if ("axiom-config-assistant".equals(templateId)) {
            Files.createDirectories(workDir.resolve("tools"));
            Files.createDirectories(workDir.resolve("action-types"));
            Files.createDirectories(workDir.resolve("report-definitions"));
            Files.createDirectories(workDir.resolve("toolsets"));
            Files.createDirectories(workDir.resolve("session-templates"));
            Files.createDirectories(workDir.resolve("event-sources"));
            Files.createDirectories(workDir.resolve("scheduled-jobs"));
        }

        LOG.infof("Created working directory: %s", workDir);
        return workDir;
    }

    /**
     * Deletes the session directory and all its contents.
     *
     * @param sessionDirectory the session directory to delete
     */
    public void deleteSessionDirectory(Path sessionDirectory) {
        if (sessionDirectory == null || !Files.exists(sessionDirectory)) {
            return;
        }
        try {
            try (var walk = Files.walk(sessionDirectory)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                LOG.warnf("Failed to delete: %s", p);
                            }
                        });
            }
            LOG.infof("Deleted session directory: %s", sessionDirectory);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to clean up session directory: %s", sessionDirectory);
        }
    }

    /**
     * Builds an MCP configuration JSON string from resolved MCP server entities
     * and optional built-in server entries.
     *
     * @param resolvedServers map of server name to MCP server config objects
     * @return the JSON config string
     */
    public String buildMcpConfig(Map<String, McpServerConfig> resolvedServers) {
        if (resolvedServers.isEmpty()) {
            return null;
        }
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode servers = root.putObject("mcpServers");
        for (var entry : resolvedServers.entrySet()) {
            ObjectNode serverNode = servers.putObject(entry.getKey());
            McpServerConfig config = entry.getValue();
            if (config.isHttpTransport()) {
                serverNode.put("type", config.type());
                serverNode.put("url", config.url());
            } else {
                serverNode.put("command", config.command());
                ArrayNode argsNode = serverNode.putArray("args");
                config.args().forEach(argsNode::add);
                if (!config.env().isEmpty()) {
                    ObjectNode envNode = serverNode.putObject("env");
                    config.env().forEach(envNode::put);
                }
            }
        }
        return root.toPrettyString();
    }

    /**
     * Resolved MCP server configuration ready for the mcp-config.json file.
     * Supports both stdio transport (command + args) and HTTP transport (url).
     */
    public record McpServerConfig(String command, List<String> args,
                                    Map<String, String> env,
                                    String type, String url) {

        /**
         * Creates a stdio-transport MCP server configuration.
         */
        public static McpServerConfig stdio(String command, List<String> args,
                                             Map<String, String> env) {
            return new McpServerConfig(command, args, env, null, null);
        }

        /**
         * Creates an HTTP-transport MCP server configuration.
         */
        public static McpServerConfig http(String url) {
            return new McpServerConfig(null, List.of(), Map.of(), "http", url);
        }

        /**
         * Returns whether this config uses HTTP transport.
         */
        public boolean isHttpTransport() {
            return "http".equals(type);
        }
    }
}
