package io.apitomy.axiom.app.assistant.runtime.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.AssistantContextBuilder.McpServerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * Builds and writes the per-session OpenCode configuration file. The file is passed to
 * {@code opencode serve} via the {@code OPENCODE_CONFIG} environment variable and contains the
 * session's MCP servers and, when the session has allowed tools, its permission rules.
 */
public final class OpenCodeConfigWriter {

    /** File name of the generated config inside the session directory. */
    public static final String CONFIG_FILE_NAME = "opencode.json";

    private static final String SCHEMA_URL = "https://opencode.ai/config.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenCodeConfigWriter() {
    }

    /**
     * Builds an OpenCode config document containing an {@code mcp} block for the given servers.
     *
     * @param servers MCP servers keyed by server name; may be null
     * @return the config document
     */
    public static ObjectNode buildConfig(Map<String, McpServerConfig> servers) {
        return buildConfig(servers, null);
    }

    /**
     * Builds an OpenCode config document containing an {@code mcp} block for the given servers and,
     * when non-null, a {@code permission} block.
     *
     * @param servers MCP servers keyed by server name; may be null
     * @param permission OpenCode permission block; may be null
     * @return the config document
     */
    public static ObjectNode buildConfig(Map<String, McpServerConfig> servers, ObjectNode permission) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("$schema", SCHEMA_URL);
        ObjectNode mcp = root.putObject("mcp");
        if (servers != null) {
            servers.forEach((String name, McpServerConfig config) -> mcp.set(name, toServerNode(config)));
        }
        if (permission != null) {
            root.set("permission", permission);
        }
        return root;
    }

    /**
     * Writes {@value #CONFIG_FILE_NAME} into the session directory when there are MCP servers.
     * The file may contain secrets (server environment), so it is made owner-readable only where
     * POSIX permissions are supported.
     *
     * @param sessionDirectory Axiom session directory
     * @param servers MCP servers keyed by server name; may be null
     * @return path of the written file, or {@code null} if there were no servers
     * @throws IOException if the file cannot be written
     */
    public static Path writeConfig(Path sessionDirectory, Map<String, McpServerConfig> servers)
            throws IOException {
        return writeConfig(sessionDirectory, servers, null);
    }

    /**
     * Writes {@value #CONFIG_FILE_NAME} into the session directory when there are MCP servers or a
     * permission block. The file may contain secrets (server environment), so it is made
     * owner-readable only where POSIX permissions are supported.
     *
     * @param sessionDirectory Axiom session directory
     * @param servers MCP servers keyed by server name; may be null
     * @param permission OpenCode permission block; may be null
     * @return path of the written file, or {@code null} if there were no servers and no permission block
     * @throws IOException if the file cannot be written
     */
    public static Path writeConfig(Path sessionDirectory, Map<String, McpServerConfig> servers,
                                   ObjectNode permission) throws IOException {
        if ((servers == null || servers.isEmpty()) && permission == null) {
            return null;
        }
        Path file = sessionDirectory.resolve(CONFIG_FILE_NAME);
        Files.writeString(file, buildConfig(servers, permission).toPrettyString());
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem; leave default permissions.
        }
        return file;
    }

    private static ObjectNode toServerNode(McpServerConfig config) {
        ObjectNode node = MAPPER.createObjectNode();
        if (config.isHttpTransport()) {
            node.put("type", "remote");
            node.put("url", config.url());
        } else {
            node.put("type", "local");
            ArrayNode command = node.putArray("command");
            command.add(config.command());
            config.args().forEach(command::add);
            if (config.env() != null && !config.env().isEmpty()) {
                ObjectNode environment = node.putObject("environment");
                config.env().forEach(environment::put);
            }
        }
        node.put("enabled", true);
        return node;
    }
}
