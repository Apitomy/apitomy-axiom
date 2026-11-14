package io.apitomy.axiom.app.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.McpConfigGenerator;
import io.apitomy.axiom.app.McpServerInstaller;
import io.apitomy.axiom.app.mcp.ClaudeMcpConfigWriter;
import io.apitomy.axiom.app.mcp.McpEnvironmentIsolation;
import io.apitomy.axiom.app.mcp.McpServerDefinition;
import io.apitomy.axiom.core.config.AxiomHome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Installs and describes the read-only {@code axiom-run-summary} MCP server. */
@ApplicationScoped
public class RunSummaryMcp {

    private static final Logger LOG = Logger.getLogger(RunSummaryMcp.class);

    /** MCP server name. */
    public static final String SERVER_NAME = "axiom-run-summary";

    /** The only tools a summary agent may use. */
    public static final List<String> ALLOWED_TOOLS = List.of(
            "mcp__axiom-run-summary__get_run",
            "mcp__axiom-run-summary__get_task",
            "mcp__axiom-run-summary__get_task_log",
            "mcp__axiom-run-summary__get_trace");

    /**
     * Built-in tools denied to OpenCode summary agents, as raw OpenCode tool names (the permission mapper passes
     * lowercase names through unchanged). OpenCode treats the allowed tools as extra permissions rather than an
     * exclusive list, so its built-ins must be denied explicitly (see #681). {@code read} is not denied:
     * OpenCode 1.18 never answers a prompt whose tools disable it, so OpenCode summary agents can read local
     * files (documented). Tools of other MCP servers registered on the shared OpenCode server are not denied
     * either; OpenCode's per-prompt {@code tools} map is not known to support wildcards.
     */
    public static final List<String> OPENCODE_DENIED_TOOLS = List.of(
            "bash", "write", "edit", "multiedit", "apply_patch", "patch", "webfetch", "websearch", "task",
            "todowrite", "todoread", "glob", "grep", "list", "skill", "lsp", "codesearch");

    /**
     * Copilot CLI tools denied to summary agents. The CLI always runs with {@code --allow-all-tools} in
     * non-interactive mode; {@code --deny-tool} rules take precedence over it. {@code shell}, {@code write} and
     * {@code url} cover shell commands, file modification and web access.
     */
    public static final List<String> COPILOT_DENIED_TOOLS = List.of("shell", "write", "url");

    private static final String INSTALL_DIR = "run-summary-mcp-server";
    private static final String RESOURCE_DIR = "templates/axiom-run-summary-mcp/";

    @Inject
    McpServerInstaller installer;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    McpEnvironmentIsolation environmentIsolation;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "9090")
    int httpPort;

    /**
     * Returns the stdio server definition, installing the server on first use.
     *
     * @return the server definition
     * @throws IOException if installation fails
     */
    public McpServerDefinition serverDefinition() throws IOException {
        Path dir = installer.ensureInstalled(INSTALL_DIR, RESOURCE_DIR, "package.json", "server.js");
        return McpServerDefinition.stdio("node", List.of(dir.resolve("server.js").toAbsolutePath().toString()),
                Map.of("AXIOM_API_URL", "http://localhost:" + httpPort + "/api/v1"));
    }

    /**
     * Writes a Claude/Copilot MCP config containing only this server.
     *
     * @param runId the run (names the directory)
     * @return the config file
     * @throws IOException on failure
     */
    public Path writeConfigFile(long runId) throws IOException {
        Path dir = runDirectory(runId);
        Files.createDirectories(dir);
        Path file = dir.resolve("mcp-config.json");
        Map<String, McpServerDefinition> servers = Map.of(SERVER_NAME, serverDefinition());
        Files.writeString(file, ClaudeMcpConfigWriter.toJson(objectMapper, environmentIsolation.isolateAll(servers)));
        McpConfigGenerator.restrictToOwner(file);
        return file;
    }

    /**
     * Deletes the per-run config directory (best effort).
     *
     * @param runId the run
     */
    public void cleanup(long runId) {
        Path dir = runDirectory(runId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.debugf("Failed to delete %s: %s", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.debugf("Failed to clean up %s: %s", dir, e.getMessage());
        }
    }

    private static Path runDirectory(long runId) {
        return AxiomHome.path().resolve("run-summaries").resolve(Long.toString(runId));
    }
}
