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
 * Creates the working directory structure for an assistant session, including
 * the {@code CLAUDE.md} system prompt and {@code mcp-config.json} for the
 * Axiom Assistant MCP server.
 */
@ApplicationScoped
public class AssistantContextBuilder {

    private static final Logger LOG = Logger.getLogger(AssistantContextBuilder.class);

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "9090")
    int httpPort;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Creates the full working directory for an assistant session.
     *
     * @param sessionId the unique session identifier
     * @param templateId the template this session was created from
     * @param systemPrompt markdown content to write to CLAUDE.md
     * @param mcpConfig the MCP configuration JSON string, or null
     * @return the path to the created working directory
     * @throws IOException if directory creation fails
     */
    public Path createWorkingDirectory(String sessionId, String templateId,
                                        String systemPrompt, String mcpConfig)
            throws IOException {
        Path axiomHome = Path.of(System.getProperty("user.home"), ".axiom");
        Path sessionsRoot = axiomHome.resolve("assistant-sessions");
        Path sessionDir = sessionsRoot.resolve(sessionId);

        Files.createDirectories(sessionDir);

        // Config Assistant needs item subdirectories
        if ("axiom-config-assistant".equals(templateId)) {
            Files.createDirectories(sessionDir.resolve("tools"));
            Files.createDirectories(sessionDir.resolve("action-types"));
            Files.createDirectories(sessionDir.resolve("report-definitions"));
        }

        Files.writeString(sessionDir.resolve("CLAUDE.md"), systemPrompt);

        if (mcpConfig != null) {
            Files.writeString(sessionDir.resolve("mcp-config.json"), mcpConfig);
        }

        LOG.infof("Created assistant working directory: %s", sessionDir);
        return sessionDir;
    }

    /**
     * Deletes the working directory and all its contents.
     *
     * @param workingDirectory the directory to delete
     */
    public void deleteWorkingDirectory(Path workingDirectory) {
        if (workingDirectory == null || !Files.exists(workingDirectory)) {
            return;
        }
        try {
            try (var walk = Files.walk(workingDirectory)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                LOG.warnf("Failed to delete: %s", p);
                            }
                        });
            }
            LOG.infof("Deleted assistant working directory: %s", workingDirectory);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to clean up working directory: %s", workingDirectory);
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
            serverNode.put("command", config.command());
            ArrayNode argsNode = serverNode.putArray("args");
            config.args().forEach(argsNode::add);
            if (!config.env().isEmpty()) {
                ObjectNode envNode = serverNode.putObject("env");
                config.env().forEach(envNode::put);
            }
        }
        return root.toPrettyString();
    }

    /**
     * Resolved MCP server configuration ready for the mcp-config.json file.
     */
    public record McpServerConfig(String command, List<String> args,
                                    Map<String, String> env) {
    }
}
