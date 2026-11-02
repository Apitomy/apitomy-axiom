package io.apitomy.axiom.app;

import io.apitomy.axiom.core.config.AxiomHome;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.agents.claudecode.ClaudeCodeMcpManager;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.agents.copilot.CopilotMcpManager;
import io.apitomy.axiom.agents.opencode.OpenCodeMcpManager;
import io.apitomy.axiom.agents.opencode.OpenCodeMcpManager.McpServerConfig;
import io.apitomy.axiom.app.mcp.ClaudeMcpConfigWriter;
import io.apitomy.axiom.app.mcp.McpEnvironmentIsolation;
import io.apitomy.axiom.app.mcp.McpServerDefinition;
import io.apitomy.axiom.app.mcp.McpServerResolver;
import io.apitomy.axiom.agents.spi.process.BoundedProcessRunner;
import io.apitomy.axiom.core.services.EnvironmentResolver;
import io.apitomy.axiom.core.services.MissingSecretException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates MCP configuration files for AI engine subprocesses.
 *
 * <p>For each task execution, this generator:</p>
 * <ol>
 *   <li>Ensures the Axiom MCP server Node.js subject is installed (once)</li>
 *   <li>For "script" tools: writes a per-task tools JSON file and references the
 *       pre-built server in the MCP config</li>
 *   <li>For "mcp-server" tools: includes them directly in the config</li>
 *   <li>Writes the MCP config JSON file</li>
 * </ol>
 *
 * <p>The MCP server subject is a Node.js application bundled as a classpath resource
 * at {@code templates/axiom-mcp-server/}. On first use it is copied to
 * {@code ~/.axiom/mcp-server/} and {@code npm install} is run once to resolve
 * dependencies. Subsequent invocations reuse the installed subject.</p>
 *
 * <p>This class also implements {@link io.apitomy.axiom.agents.spi.AgentMcpManager}
 * for the Claude Code and Copilot engines, via the {@link io.apitomy.axiom.agents.claudecode.ClaudeCodeMcpManager}
 * / {@link io.apitomy.axiom.agents.copilot.CopilotMcpManager} delegate pattern. On
 * initialization, it registers itself as the MCP config delegate for both engines
 * (both consume the same {@code mcpServers} JSON config format).</p>
 */
@ApplicationScoped
public class McpConfigGenerator {

    private static final Logger LOG = Logger.getLogger(McpConfigGenerator.class);

    /**
     * Upper bound for {@code npm install} of a bundled MCP server (network fetches can be slow); shared with
     * {@code AssistantSessionManager}.
     */
    public static final java.time.Duration NPM_INSTALL_TIMEOUT = java.time.Duration.ofMinutes(5);

    private static final String MCP_SERVER_DIR_NAME = "mcp-server";
    private static final String[] TEMPLATE_FILES = {
            "package.json", "sdk-server.js", "tools-server.js", "trace-helper.js" };

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "9090")
    int httpPort;

    @Inject
    McpServerResolver mcpServerResolver;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    McpEnvironmentIsolation environmentIsolation;

    @Inject
    EnvironmentResolver environmentResolver;

    @Inject
    ClaudeCodeMcpManager claudeCodeMcpManager;

    @Inject
    OpenCodeMcpManager openCodeMcpManager;

    @Inject
    CopilotMcpManager copilotMcpManager;

    /** Lazily resolved path to the installed MCP server subject directory. */
    private volatile Path mcpServerDir;

    /**
     * Registers this McpConfigGenerator as the delegate for both engine MCP managers.
     */
    @PostConstruct
    void init() {
        claudeCodeMcpManager.setDelegate(this::generateMcpConfig);
        LOG.info("Registered McpConfigGenerator as ClaudeCodeMcpManager delegate");

        openCodeMcpManager.setDelegate(this::getMcpServersForOpenCode);
        openCodeMcpManager.setActiveServerNames(this::activeExternalServerNames);
        LOG.info("Registered McpConfigGenerator as OpenCodeMcpManager delegate");

        copilotMcpManager.setDelegate(this::generateMcpConfig);
        LOG.info("Registered McpConfigGenerator as CopilotMcpManager delegate");
    }

    /** Prefix for script tools provided by the axiom-tools MCP server. */
    private static final String AXIOM_TOOLS_PREFIX = "mcp__axiom-tools__";

    /** Prefix for SDK tools provided by the axiom-sdk MCP server. */
    private static final String AXIOM_SDK_PREFIX = "mcp__axiom-sdk__";

    /**
     * Generates the MCP config file for a task, filtered by the action type's
     * allowed tools list. Only MCP tools that appear in the allowed list are
     * included in the config. If the allowed list is null or empty, all built-in
     * Axiom tools are included, but no user-defined MCP servers: those are only
     * loaded when referenced ({@code mcp__<name>__...}).
     *
     * @param taskId the task ID (for unique temp file naming)
     * @param environment the calling agent's environment; only its Axiom-provided {@code AXIOM_*} variables
     *                    reach the axiom-tools server, and no MCP server inherits the rest
     * @param allowedTools the action type's allowed tools list (may be null)
     * @return path to the generated MCP config file, or null if no tools match
     * @throws io.apitomy.axiom.core.services.MissingSecretException if an included MCP server or script tool
     *         references a secret that does not exist
     */
    public Path generateMcpConfig(Long taskId, Map<String, String> environment,
                                   List<String> allowedTools) {
        Map<String, String> env = axiomToolsEnvironment(environment);

        boolean hasRestrictions = allowedTools != null && !allowedTools.isEmpty();

        // Filter script tools: include only those whose MCP name is in the allowed list
        List<ToolDefinitionEntity> scriptTools = ToolDefinitionEntity.<ToolDefinitionEntity>listAll()
                .stream()
                .filter(t -> !hasRestrictions
                        || allowedTools.contains(AXIOM_TOOLS_PREFIX + t.name))
                .toList();

        // External MCP servers referenced by the allowed tools (never implicit). External servers get only
        // their own configured env, never the task environment.
        Map<String, McpServerDefinition> externalServers = mcpServerResolver.resolveForAllowedTools(allowedTools);

        // Check if any SDK tools are in the allowed list
        boolean hasSdkTools = !hasRestrictions
                || allowedTools.stream().anyMatch(a -> a.startsWith(AXIOM_SDK_PREFIX));

        if (scriptTools.isEmpty() && externalServers.isEmpty() && !hasSdkTools) {
            LOG.debugf("No MCP tools matched allowed list for task %d, skipping MCP config", taskId);
            return null;
        }

        // Resolve each tool's own environment up front so a missing secret fails the task
        String toolsJsonContent = scriptTools.isEmpty() ? null : buildToolsJson(scriptTools);

        try {
            Map<String, McpServerDefinition> servers = new java.util.LinkedHashMap<>();
            Path serverDir = (hasSdkTools || !scriptTools.isEmpty())
                    ? ensureMcpServerInstalled() : null;

            // SDK tools → axiom-sdk server (no tools JSON, no script execution)
            if (hasSdkTools && serverDir != null) {
                servers.put("axiom-sdk", McpServerDefinition.stdio("node",
                        List.of(serverDir.resolve("sdk-server.js").toAbsolutePath().toString()),
                        Map.of("AXIOM_API_URL", env.get("AXIOM_API_URL"))));
            }

            // Script tools → axiom-tools server (loads tools from JSON file)
            if (!scriptTools.isEmpty() && serverDir != null) {
                Path toolsJson = writeToolsJson(toolsJsonContent, scriptTools.size(), taskId);
                servers.put("axiom-tools", McpServerDefinition.stdio("node",
                        List.of(serverDir.resolve("tools-server.js").toAbsolutePath().toString(),
                                toolsJson.toAbsolutePath().toString()),
                        env));
            }

            externalServers.forEach(servers::putIfAbsent);

            // Write config file to ~/.axiom/tasks/{taskId}/
            Path taskDir = getTaskDirectory(taskId);
            Path configFile = taskDir.resolve("mcp-config.json");
            Files.writeString(configFile, ClaudeMcpConfigWriter.toJson(objectMapper,
                    environmentIsolation.isolateAll(servers)));
            restrictToOwner(configFile);
            LOG.infof("Generated MCP config for task %d: %s (%d script tools, %d MCP servers)",
                    taskId, configFile, scriptTools.size(), externalServers.size());
            return configFile;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to generate MCP config for task %d", taskId);
            return null;
        }
    }

    /**
     * Cleans up the MCP config directory for a given task ID.
     *
     * @param taskId the task ID used when generating the files
     */
    public void cleanupTempFiles(Long taskId) {
        Path taskDir = AxiomHome.resolve("tasks", String.valueOf(taskId));
        if (!Files.isDirectory(taskDir)) return;
        try (var stream = Files.walk(taskDir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            LOG.warnf("Failed to delete: %s", p);
                        }
                    });
            LOG.debugf("Cleaned up task directory: %s", taskDir);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to clean up task directory for task %d", taskId);
        }
    }

    /**
     * Returns the task-specific directory under {@code ~/.axiom/tasks/{taskId}/},
     * creating it if it does not exist.
     */
    private Path getTaskDirectory(Long taskId) throws IOException {
        Path taskDir = AxiomHome.resolve("tasks", String.valueOf(taskId));
        Files.createDirectories(taskDir);
        return taskDir;
    }

    /**
     * Ensures the Axiom MCP server Node.js subject is installed and dependencies
     * are resolved. On first call, copies the template subject from classpath
     * resources to {@code ~/.axiom/mcp-server/} and runs {@code npm install}.
     * Subsequent calls return the cached path. Used by both the task execution
     * path and the assistant session path.
     *
     * @return path to the installed MCP server subject directory
     * @throws IOException if the subject cannot be installed
     */
    public Path ensureMcpServerInstalled() throws IOException {
        if (mcpServerDir != null && Files.exists(mcpServerDir.resolve("node_modules"))
                && Files.exists(mcpServerDir.resolve("sdk-server.js"))) {
            return mcpServerDir;
        }

        synchronized (this) {
            if (mcpServerDir != null && Files.exists(mcpServerDir.resolve("node_modules"))
                    && Files.exists(mcpServerDir.resolve("sdk-server.js"))) {
                return mcpServerDir;
            }

            Path axiomHome = AxiomHome.path();
            Path serverDir = axiomHome.resolve(MCP_SERVER_DIR_NAME);
            Files.createDirectories(serverDir);

            // Copy template files from classpath resources
            for (String fileName : TEMPLATE_FILES) {
                String resourcePath = "templates/axiom-mcp-server/" + fileName;
                try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        throw new IOException("Template resource not found: " + resourcePath);
                    }
                    Files.copy(is, serverDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            LOG.infof("Copied MCP server template to %s", serverDir);

            // Run npm install to resolve dependencies
            LOG.info("Running npm install for Axiom MCP server...");
            ProcessBuilder pb = new ProcessBuilder("npm", "install", "--production")
                    .directory(serverDir.toFile())
                    .redirectErrorStream(true);
            BoundedProcessRunner.Result result;
            try {
                result = BoundedProcessRunner.run(pb, NPM_INSTALL_TIMEOUT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("npm install interrupted", e);
            }
            String output = result.stdout();
            if (result.timedOut()) {
                throw new IOException("npm install timed out after " + NPM_INSTALL_TIMEOUT.toMinutes()
                        + " minutes: " + output);
            }
            int exitCode = result.exitCode();

            if (exitCode != 0) {
                throw new IOException("npm install failed (exit code " + exitCode + "): " + output);
            }
            LOG.infof("npm install completed successfully in %s", serverDir);

            mcpServerDir = serverDir;
            return mcpServerDir;
        }
    }

    /**
     * Writes the tool definitions for a task to a JSON file, readable by the owner only: it contains each
     * tool's resolved environment. The generated MCP server reads this file at startup to discover available
     * tools.
     *
     * @param json the tool definitions JSON (see {@link #buildToolsJson})
     * @param toolCount the number of tools, for logging
     * @param taskId the task ID (for unique file naming)
     * @return path to the written tools JSON file
     * @throws IOException if the file cannot be written
     */
    private Path writeToolsJson(String json, int toolCount, Long taskId) throws IOException {
        Path taskDir = getTaskDirectory(taskId);
        Path toolsFile = taskDir.resolve("tools.json");
        Files.writeString(toolsFile, json);
        restrictToOwner(toolsFile);
        LOG.debugf("Wrote tools JSON for task %d: %s (%d tools)", (Object) taskId, toolsFile, toolCount);
        return toolsFile;
    }

    /**
     * Builds the environment of the axiom-tools server from a calling agent's environment: only the
     * Axiom-provided {@code AXIOM_*} variables, plus {@code AXIOM_API_URL}. Script tools declare everything
     * else they need themselves.
     *
     * @param agentEnvironment the calling agent's environment (may be null)
     * @return the axiom-tools server environment
     */
    public Map<String, String> axiomToolsEnvironment(Map<String, String> agentEnvironment) {
        Map<String, String> env = McpEnvironmentIsolation.axiomVariables(agentEnvironment);
        env.putIfAbsent("AXIOM_API_URL", "http://localhost:" + httpPort + "/api/v1");
        return env;
    }

    /**
     * Builds the JSON array of tool definitions for the MCP server, including each tool's own environment
     * with {@code ${secret:NAME}} references resolved. Used by both the task execution path and the
     * assistant session path. The result may contain secret values: write it to an owner-only file.
     *
     * @param tools the tool definition entities to serialize
     * @return JSON string representing the tool definitions array
     * @throws MissingSecretException if a tool's environment references a secret that does not exist
     */
    public String buildToolsJson(List<ToolDefinitionEntity> tools) {
        try {
            List<Map<String, Object>> toolsList = new ArrayList<>();
            for (ToolDefinitionEntity tool : tools) {
                Map<String, Object> toolMap = new java.util.LinkedHashMap<>();
                toolMap.put("name", tool.name);
                toolMap.put("description", tool.description);
                toolMap.put("scriptTemplate", tool.scriptTemplate);

                if (tool.parameters != null) {
                    List<Map<String, Object>> params = objectMapper.readValue(
                            tool.parameters, new TypeReference<>() {});
                    toolMap.put("parameters", params);
                }

                if (tool.environment != null && !tool.environment.isBlank()) {
                    try {
                        toolMap.put("environment", environmentResolver.resolve(tool.environment));
                    } catch (MissingSecretException e) {
                        throw new MissingSecretException("Tool '" + tool.name + "': " + e.getMessage());
                    }
                }

                toolsList.add(toolMap);
            }
            return objectMapper.writeValueAsString(toolsList);
        } catch (MissingSecretException e) {
            throw e;
        } catch (Exception e) {
            LOG.warnf(e, "Failed to serialize tool definitions");
            return "[]";
        }
    }

    /**
     * Provides MCP server configurations for the OpenCode engine. This method
     * is called by {@link OpenCodeMcpManager} via the delegate pattern to
     * discover which MCP servers need to be registered with the OpenCode server.
     *
     * @param taskId       the task ID
     * @param environment  environment variables (e.g. GitHub tokens)
     * @param allowedTools the allowed tools list for filtering
     * @return list of MCP server configurations
     */
    List<McpServerConfig> getMcpServersForOpenCode(Long taskId, Map<String, String> environment,
                                                    List<String> allowedTools) {
        boolean hasRestrictions = allowedTools != null && !allowedTools.isEmpty();

        Map<String, McpServerDefinition> servers = new java.util.LinkedHashMap<>();

        // SDK tools → axiom-sdk local MCP server
        boolean hasSdkTools = !hasRestrictions
                || allowedTools.stream().anyMatch(a -> a.startsWith(AXIOM_SDK_PREFIX));

        if (hasSdkTools) {
            try {
                Path serverDir = ensureMcpServerInstalled();
                servers.put("axiom-sdk", McpServerDefinition.stdio("node",
                        List.of(serverDir.resolve("sdk-server.js").toAbsolutePath().toString()),
                        Map.of("AXIOM_API_URL", "http://localhost:" + httpPort + "/api/v1")));
            } catch (Exception e) {
                LOG.errorf(e, "Failed to prepare axiom-sdk MCP server for task %d", taskId);
            }
        }

        // Script tools → axiom-tools local MCP server
        List<ToolDefinitionEntity> scriptTools = ToolDefinitionEntity.<ToolDefinitionEntity>listAll()
                .stream()
                .filter(t -> !hasRestrictions
                        || allowedTools.contains(AXIOM_TOOLS_PREFIX + t.name))
                .toList();

        if (!scriptTools.isEmpty()) {
            String toolsJsonContent = buildToolsJson(scriptTools);
            try {
                Path serverDir = ensureMcpServerInstalled();
                Path toolsJson = writeToolsJson(toolsJsonContent, scriptTools.size(), taskId);
                servers.put("axiom-tools", McpServerDefinition.stdio("node",
                        List.of(serverDir.resolve("tools-server.js").toAbsolutePath().toString(),
                                toolsJson.toAbsolutePath().toString()),
                        axiomToolsEnvironment(environment)));
            } catch (Exception e) {
                LOG.errorf(e, "Failed to prepare axiom-tools MCP server for task %d", taskId);
            }
        }

        // External MCP servers
        mcpServerResolver.resolveForAllowedTools(allowedTools).forEach(servers::putIfAbsent);

        List<McpServerConfig> configs = new ArrayList<>();
        environmentIsolation.isolateAll(servers).forEach((name, def) -> configs.add(toOpenCodeConfig(name, def)));
        return configs;
    }

    /**
     * Returns the names of user-defined MCP servers that exist and are enabled.
     *
     * @return the names
     */
    java.util.Set<String> activeExternalServerNames() {
        return io.quarkus.narayana.jta.QuarkusTransaction.joiningExisting().call(() ->
                io.apitomy.axiom.core.entities.McpServerEntity.<io.apitomy.axiom.core.entities.McpServerEntity>stream(
                                "enabled", true)
                        .map(e -> e.name)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    /**
     * Restricts a file to owner read/write; it may contain resolved secrets.
     *
     * @param file the file
     */
    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            LOG.debugf("Could not restrict permissions on %s: %s", file, e.getMessage());
        }
    }

    /**
     * Converts a resolved definition to the OpenCode task MCP manager's config type.
     *
     * @param name the server name
     * @param def the resolved definition
     * @return the OpenCode config
     */
    static McpServerConfig toOpenCodeConfig(String name, McpServerDefinition def) {
        if (def.isHttpTransport()) {
            return new McpServerConfig(name, "remote", null, null, def.url(), def.headers(), def.timeoutMs());
        }
        List<String> command = new ArrayList<>();
        command.add(def.command());
        command.addAll(def.args());
        return new McpServerConfig(name, "local", command, def.env(), null, null, def.timeoutMs());
    }
}
