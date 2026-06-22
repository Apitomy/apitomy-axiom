package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.api.beans.ImportResult;
import io.apitomy.axiom.app.ImportExportService;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Manages the lifecycle of interactive AI assistant sessions. Enforces a
 * configurable maximum session count, installs the assistant MCP server on
 * first use, and cleans up all sessions on application shutdown.
 */
@ApplicationScoped
public class AssistantSessionManager {

    private static final Logger LOG = Logger.getLogger(AssistantSessionManager.class);

    private static final String ASSISTANT_MCP_DIR_NAME = "assistant-mcp-server";
    private static final String[] MCP_TEMPLATE_FILES = { "package.json", "server.js" };

    @ConfigProperty(name = "axiom.assistant.max-sessions", defaultValue = "3")
    int maxSessions;

    @ConfigProperty(name = "axiom.assistant.idle-timeout-seconds", defaultValue = "3600")
    int idleTimeoutSeconds;

    @ConfigProperty(name = "axiom.ai-engine", defaultValue = "claude-code")
    String aiEngine;

    @ConfigProperty(name = "axiom.claude-code.executable", defaultValue = "claude")
    String claudeExecutable;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "9090")
    int httpPort;

    @Inject
    AssistantContextBuilder contextBuilder;

    @Inject
    AssistantItemValidator itemValidator;

    @Inject
    ImportExportService importExportService;

    @Inject
    ObjectMapper objectMapper;

    private final Map<String, AssistantSession> sessions = new ConcurrentHashMap<>();

    private volatile Path assistantMcpServerDir;

    /**
     * Creates and starts a new assistant session.
     *
     * @param name the user-visible session name
     * @return the started session
     * @throws IOException if the session cannot be created
     * @throws IllegalStateException if the AI engine is not Claude Code or the
     *         session limit has been reached
     */
    public AssistantSession createSession(String name) throws IOException {
        if (!"claude-code".equals(aiEngine)) {
            throw new IllegalStateException(
                    "The AI Assistant requires Claude Code as the active AI engine. "
                            + "Current engine: " + aiEngine);
        }

        if (sessions.size() >= maxSessions) {
            throw new SessionLimitReachedException(
                    "Maximum number of assistant sessions reached (" + maxSessions + ")");
        }

        Path mcpServerDir = ensureAssistantMcpServerInstalled();

        // Create a temporary session to get the ID, then build the working dir
        String sessionName = name != null && !name.isBlank() ? name : "Assistant Session";
        AssistantSession session = new AssistantSession(sessionName, null, List.of());

        Path workDir = contextBuilder.createWorkingDirectory(session.getId(), mcpServerDir);

        List<String> command = buildCommand(workDir);
        session = new AssistantSession(sessionName, workDir, command);
        session.start();

        // Wire validation listener: when Claude writes/edits files in item
        // subdirectories, validate and send errors back if needed
        session.addListener(createValidationListener(session));

        sessions.put(session.getId(), session);
        LOG.infof("Created assistant session %s (%s)", session.getId(), sessionName);
        return session;
    }

    /**
     * Returns an active session by ID.
     *
     * @param sessionId the session identifier
     * @return the session, or null if not found
     */
    public AssistantSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Returns all active sessions.
     *
     * @return list of active sessions
     */
    public List<AssistantSession> listSessions() {
        return List.copyOf(sessions.values());
    }

    /**
     * Destroys a session: kills the subprocess and deletes the working directory.
     *
     * @param sessionId the session to destroy
     */
    public void destroySession(String sessionId) {
        AssistantSession session = sessions.remove(sessionId);
        if (session != null) {
            session.destroy();
            contextBuilder.deleteWorkingDirectory(session.getWorkingDirectory());
            LOG.infof("Destroyed assistant session %s", sessionId);
        }
    }

    /**
     * Lists generated items in a session's working directory.
     *
     * @param sessionId the session identifier
     * @return list of item descriptors with validation status
     * @throws IOException if the directory cannot be read
     */
    public List<AssistantItem> listItems(String sessionId) throws IOException {
        AssistantSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }

        List<AssistantItem> items = new ArrayList<>();
        Path workDir = session.getWorkingDirectory();

        collectItems(workDir, "tools", items);
        collectItems(workDir, "action-types", items);
        collectItems(workDir, "report-definitions", items);

        return items;
    }

    /**
     * Returns the full content of a specific generated item.
     *
     * @param sessionId the session identifier
     * @param itemType the item type directory (tools, action-types, report-definitions)
     * @param itemName the item file name (without .json extension)
     * @return the parsed JSON content
     * @throws IOException if the file cannot be read
     */
    public JsonNode getItemContent(String sessionId, String itemType, String itemName)
            throws IOException {
        AssistantSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }

        Path file = session.getWorkingDirectory().resolve(itemType).resolve(itemName + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        return objectMapper.readTree(Files.readString(file));
    }

    /**
     * Validates all items and imports them as a Configuration Pack.
     *
     * @param sessionId the session identifier
     * @return the import result
     * @throws IOException if files cannot be read
     */
    public ImportResult applySession(String sessionId) throws IOException {
        AssistantSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }

        List<AssistantItem> items = listItems(sessionId);

        // Check for validation errors (warnings don't block apply)
        List<String> allErrors = new ArrayList<>();
        for (AssistantItem item : items) {
            if (!item.errors().isEmpty()) {
                allErrors.add(item.type() + "/" + item.name() + ": "
                        + String.join("; ", item.errors()));
            }
        }
        if (!allErrors.isEmpty()) {
            throw new ValidationException("Items have validation errors", allErrors);
        }

        // Build configuration pack
        JsonNode pack = buildConfigPack(session, items);

        // Import via the existing service
        ImportResult result = importExportService.importPack(pack);

        // Destroy session on success
        destroySession(sessionId);

        return result;
    }

    /**
     * Returns whether the AI engine supports the assistant feature.
     *
     * @return true if Claude Code is the active engine
     */
    public boolean isAvailable() {
        return "claude-code".equals(aiEngine);
    }

    /**
     * Cleans up all sessions on application shutdown.
     *
     * @param event the shutdown event
     */
    void onShutdown(@Observes ShutdownEvent event) {
        LOG.info("Shutting down — destroying all assistant sessions");
        for (String sessionId : new ArrayList<>(sessions.keySet())) {
            destroySession(sessionId);
        }
    }

    private List<String> buildCommand(Path workDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(claudeExecutable);
        cmd.add("--print");
        cmd.add("--input-format");
        cmd.add("stream-json");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");
        cmd.add("--permission-prompt-tool");
        cmd.add("stdio");
        cmd.add("--mcp-config");
        cmd.add(workDir.resolve("mcp-config.json").toAbsolutePath().toString());

        // Auto-approve safe tools; everything else prompts the user via
        // --permission-prompt-tool stdio (forwarded to the chat UI).
        cmd.add("--allowedTools");
        cmd.add(String.join(" ",
                "Read(*)", "Write(*)", "Edit(*)",
                "Bash(ls *)", "Bash(cat *)",
                "mcp__axiom__axiom_list_tools",
                "mcp__axiom__axiom_get_tool",
                "mcp__axiom__axiom_list_action_types",
                "mcp__axiom__axiom_get_action_type",
                "mcp__axiom__axiom_list_report_definitions",
                "mcp__axiom__axiom_get_report_definition",
                "mcp__axiom__axiom_list_mcp_servers",
                "mcp__axiom__axiom_list_toolsets"
        ));

        return cmd;
    }

    private java.util.function.Consumer<SseEvent> createValidationListener(
            AssistantSession session) {
        return event -> {
            // Listen for tool_result events — they fire after Write/Edit completes.
            // We check the working directory for changed JSON files.
            if (!"tool_result".equals(event.type())) {
                return;
            }

            // After any tool result, re-validate all item files in the workdir.
            // This is simpler and more reliable than trying to extract the
            // specific file path from event data.
            Path workDir = session.getWorkingDirectory();
            try {
                validateAndFeedback(workDir, "tools", session);
                validateAndFeedback(workDir, "action-types", session);
                validateAndFeedback(workDir, "report-definitions", session);
            } catch (Exception e) {
                LOG.warnf(e, "Validation listener error in session %s",
                        session.getId());
            }
        };
    }

    private void validateAndFeedback(Path workDir, String subdir,
                                      AssistantSession session) throws IOException {
        Path dir = workDir.resolve(subdir);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.toString().endsWith(".json"))
                    .forEach(f -> {
                        AssistantItemValidator.ValidationResult result =
                                itemValidator.validate(f, subdir, workDir);
                        List<String> allMessages = result.allMessages();
                        if (!allMessages.isEmpty()) {
                            String filePath = workDir.relativize(f).toString();
                            StringBuilder feedback = new StringBuilder();
                            feedback.append("Validation issues in ").append(filePath).append(":\n");
                            for (String err : result.errors()) {
                                feedback.append("- [ERROR] ").append(err).append("\n");
                            }
                            for (String warn : result.warnings()) {
                                feedback.append("- [WARNING] ").append(warn).append("\n");
                            }
                            if (!result.errors().isEmpty()) {
                                feedback.append("\nPlease fix the errors above.");
                            }
                            try {
                                session.sendMessage(feedback.toString());
                                LOG.infof("Sent validation feedback for %s in session %s",
                                        filePath, session.getId());
                            } catch (IOException e) {
                                LOG.warnf(e, "Failed to send validation feedback");
                            }
                        }
                    });
        }
    }

    private void collectItems(Path workDir, String subdir, List<AssistantItem> items)
            throws IOException {
        Path dir = workDir.resolve(subdir);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.toString().endsWith(".json"))
                    .forEach(f -> {
                        String name = f.getFileName().toString()
                                .replaceFirst("\\.json$", "");
                        AssistantItemValidator.ValidationResult result =
                                itemValidator.validate(f, subdir, workDir);
                        items.add(new AssistantItem(subdir, name,
                                result.errors(), result.warnings()));
                    });
        }
    }

    private JsonNode buildConfigPack(AssistantSession session, List<AssistantItem> items)
            throws IOException {
        ObjectNode pack = objectMapper.createObjectNode();

        ObjectNode metadata = pack.putObject("metadata");
        metadata.put("name", "AI Assistant Session - " + session.getName());
        metadata.put("description", "Generated by AI Assistant on "
                + Instant.now().toString());
        metadata.put("version", "2.0");
        metadata.put("exportedAt", Instant.now().toString());

        ArrayNode toolsArr = pack.putArray("tools");
        ArrayNode actionTypesArr = pack.putArray("actionTypes");
        ArrayNode reportDefsArr = pack.putArray("reportDefinitions");

        for (AssistantItem item : items) {
            Path file = session.getWorkingDirectory()
                    .resolve(item.type()).resolve(item.name() + ".json");
            JsonNode content = objectMapper.readTree(Files.readString(file));

            switch (item.type()) {
                case "tools" -> toolsArr.add(content);
                case "action-types" -> actionTypesArr.add(content);
                case "report-definitions" -> reportDefsArr.add(content);
            }
        }

        return pack;
    }

    /**
     * Ensures the assistant MCP server Node.js project is installed at
     * {@code ~/.axiom/assistant-mcp-server/}.
     */
    private Path ensureAssistantMcpServerInstalled() throws IOException {
        if (assistantMcpServerDir != null
                && Files.exists(assistantMcpServerDir.resolve("node_modules"))) {
            return assistantMcpServerDir;
        }

        synchronized (this) {
            if (assistantMcpServerDir != null
                    && Files.exists(assistantMcpServerDir.resolve("node_modules"))) {
                return assistantMcpServerDir;
            }

            Path axiomHome = Path.of(System.getProperty("user.home"), ".axiom");
            Path serverDir = axiomHome.resolve(ASSISTANT_MCP_DIR_NAME);
            Files.createDirectories(serverDir);

            for (String fileName : MCP_TEMPLATE_FILES) {
                String resourcePath = "templates/axiom-assistant-mcp/" + fileName;
                try (InputStream is = getClass().getClassLoader()
                        .getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        throw new IOException("Template resource not found: " + resourcePath);
                    }
                    Files.copy(is, serverDir.resolve(fileName),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
            LOG.infof("Copied assistant MCP server template to %s", serverDir);

            LOG.info("Running npm install for assistant MCP server...");
            ProcessBuilder pb = new ProcessBuilder("npm", "install", "--production")
                    .directory(serverDir.toFile())
                    .redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("npm install interrupted", e);
            }

            if (exitCode != 0) {
                throw new IOException(
                        "npm install failed (exit code " + exitCode + "): " + output);
            }
            LOG.infof("npm install completed for assistant MCP server in %s", serverDir);

            assistantMcpServerDir = serverDir;
            return assistantMcpServerDir;
        }
    }

    /**
     * Describes a generated configuration item with its validation status.
     *
     * @param type the item type directory (tools, action-types, report-definitions)
     * @param name the item name (file name without .json)
     * @param validationErrors list of validation errors (empty if valid)
     */
    /**
     * Describes a generated configuration item with its validation status.
     *
     * @param type the item type directory (tools, action-types, report-definitions)
     * @param name the item name (file name without .json)
     * @param errors validation errors that block apply
     * @param warnings advisory messages that don't block apply
     */
    public record AssistantItem(String type, String name,
                                 List<String> errors, List<String> warnings) {

        /**
         * Returns whether this item has no errors (warnings are OK).
         */
        public boolean isValid() {
            return errors.isEmpty();
        }

        /**
         * Returns whether this item has any issues (errors or warnings).
         */
        public boolean hasIssues() {
            return !errors.isEmpty() || !warnings.isEmpty();
        }
    }

    /**
     * Thrown when the maximum session limit has been reached.
     */
    public static class SessionLimitReachedException extends RuntimeException {
        /**
         * @param message the error message
         */
        public SessionLimitReachedException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when items fail validation during apply.
     */
    public static class ValidationException extends RuntimeException {
        private final List<String> errors;

        /**
         * @param message the error message
         * @param errors the list of validation errors
         */
        public ValidationException(String message, List<String> errors) {
            super(message);
            this.errors = errors;
        }

        /**
         * @return the list of validation errors
         */
        public List<String> getErrors() {
            return errors;
        }
    }
}
