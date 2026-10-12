package io.apitomy.axiom.app.assistant;

import io.apitomy.axiom.core.config.AxiomHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.apitomy.axiom.app.ImportExportService;
import io.apitomy.axiom.app.McpConfigGenerator;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriverFactory;
import io.apitomy.axiom.app.assistant.runtime.SessionCompatibilityException;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.app.validation.ConfigProblem;
import io.apitomy.axiom.app.validation.ConfigValidationException;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.SystemConfigEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.entities.ToolsetEntity;
import io.apitomy.axiom.core.services.EnvironmentResolver;
import io.apitomy.axiom.core.services.WorkspaceService;
import jakarta.enterprise.event.Event;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
    private static final String WORKBENCH_MCP_DIR_NAME = "workbench-mcp-server";
    private static final String COMPOSITION_MARKER = "composition";
    private static final String[] MCP_TEMPLATE_FILES = { "package.json", "server.js" };

    @ConfigProperty(name = "axiom.assistant.max-sessions", defaultValue = "3")
    int maxSessions;

    @ConfigProperty(name = "axiom.agent.claude-code.executable", defaultValue = "claude")
    String claudeExecutable;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "9090")
    int httpPort;

    @Inject
    AgentRegistry agentRegistry;

    @Inject
    SessionTemplateService templateService;

    @Inject
    AssistantContextBuilder contextBuilder;

    @Inject
    AssistantItemValidator itemValidator;

    @Inject
    AssistantReferenceGenerator referenceGenerator;

    @Inject
    ImportExportService importExportService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EnvironmentResolver environmentResolver;

    @Inject
    WorkspaceService workspaceService;

    @Inject
    McpConfigGenerator mcpConfigGenerator;

    @Inject
    Event<io.apitomy.axiom.core.events.SseEvent> sseEventEmitter;

    @Inject
    InteractiveSessionDriverFactory interactiveSessionDriverFactory;

    private final Map<String, AssistantSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, HistoryJournal> journals = new ConcurrentHashMap<>();
    private final AtomicInteger sessionCount = new AtomicInteger();

    private final Map<String, Path> installedMcpServers = new ConcurrentHashMap<>();

    /**
     * Creates and starts a new assistant session.
     *
     * @param name the user-visible session name
     * @param templateId the template to create the session from
     * @param subjectId optional subject ID to scope the session to
     * @return the started session
     * @throws IOException if the session cannot be created
     * @throws IllegalStateException if the AI engine is not Claude Code or the
     *         session limit has been reached
     */
    public AssistantSession createSession(String name, String templateId,
                                          Long subjectId) throws IOException {
        return createSession(name, templateId, subjectId, null, List.of());
    }

    /**
     * Creates and starts a new assistant session, optionally bound to a workbench composition and seeded with
     * earlier history.
     *
     * @param name the user-visible session name
     * @param templateId the template to create the session from
     * @param subjectId optional subject ID to scope the session to
     * @param compositionId optional composition ID, passed to the workbench MCP server
     * @param seedHistory events restored into the history (and journal) before the session starts
     * @return the started session
     * @throws IOException if the session cannot be created
     * @throws IllegalStateException if the AI engine does not support interactive sessions or the
     *         session limit has been reached
     */
    public AssistantSession createSession(String name, String templateId, Long subjectId,
                                          Long compositionId, List<SseEvent> seedHistory) throws IOException {
        List<SseEvent> seed = seedHistory != null ? seedHistory : List.of();
        SessionTemplateService.SessionTemplate template = templateService.getTemplate(templateId);
        if (template == null) {
            throw new IllegalArgumentException("Template not found: " + templateId);
        }

        Agent agent = resolveSessionAgent(template);
        if (!agent.supportsInteractiveSessions()) {
            throw new IllegalStateException(
                    "The AI Assistant requires an engine that supports interactive sessions. "
                            + "Current engine (" + agent.getLabel()
                            + ") does not support this feature.");
        }

        // Atomically reserve a session slot before doing any setup work.
        if (sessionCount.incrementAndGet() > maxSessions) {
            sessionCount.decrementAndGet();
            throw new SessionLimitReachedException(
                    "Maximum number of assistant sessions reached (" + maxSessions + ")");
        }

        try {
            // Look up subject if scoped
            SubjectEntity subject = null;
            if (subjectId != null) {
                subject = SubjectEntity.findById(subjectId);
                if (subject == null) {
                    throw new IllegalArgumentException("Subject not found: " + subjectId);
                }
            }

            // Resolve environment variables early — MCP server resolution needs
            // them (e.g. @axiom-tools passes env to the tools MCP server).
            Map<String, String> resolvedEnv = new LinkedHashMap<>();
            if (environmentResolver.hasCustomEnvironment(template.environment())) {
                resolvedEnv.putAll(environmentResolver.resolve(template.environment()));
            }

            // Inject subject environment variables
            if (subject != null) {
                resolvedEnv.put("AXIOM_SUBJECT_ID", String.valueOf(subject.id));
                resolvedEnv.put("AXIOM_SUBJECT_NAME", subject.name);
                resolvedEnv.put("AXIOM_REF", subject.ref);
                resolvedEnv.put("AXIOM_REPOSITORY", subject.repository != null ? subject.repository : "");
            }

            // Create session directory first — MCP server resolution may need
            // to write files there (e.g. tools.json for @axiom-tools).
            String sessionId = UUID.randomUUID().toString();
            String mcpToken = AssistantSession.newToken();
            Path sessionDir = contextBuilder.createSessionDirectory(sessionId, null);

            try {
            if (compositionId != null) {
                Files.writeString(sessionDir.resolve(COMPOSITION_MARKER), String.valueOf(compositionId));
            }

            // Resolve allowed tools first — MCP server resolution needs them
            // to auto-include servers implied by tool names.
            List<String> resolvedAllowedTools = resolveAllowedTools(template);

            // Resolve MCP servers from template, augmented with any servers
            // implied by the allowed tools list.
            Map<String, AssistantContextBuilder.McpServerConfig> mcpConfigs =
                    resolveMcpServers(sessionId, mcpToken, template, subject, compositionId, sessionDir,
                            resolvedEnv, resolvedAllowedTools);

            // Build and write MCP config JSON to session directory
            String mcpConfig = contextBuilder.buildMcpConfig(mcpConfigs);
            if (mcpConfig != null) {
                Files.writeString(sessionDir.resolve("mcp-config.json"), mcpConfig);
            }

            // Determine working directory
            Path workDir;
            if (template.workingDirectory() != null && !template.workingDirectory().isBlank()) {
                workDir = Path.of(template.workingDirectory());
                if (!Files.isDirectory(workDir)) {
                    throw new IOException("Template working directory does not exist: "
                            + template.workingDirectory());
                }
            } else if (subject != null) {
                Path subjectWorkspace = workspaceService.getWorkspacePath(subject);
                if (Files.isDirectory(subjectWorkspace)) {
                    workDir = subjectWorkspace;
                } else {
                    workDir = contextBuilder.createWorkingDirectory(sessionDir, template.capabilities());
                }
            } else {
                workDir = contextBuilder.createWorkingDirectory(sessionDir, template.capabilities());
            }

            // Run init script if configured
            if (template.initScript() != null && !template.initScript().isBlank()) {
                runInitScript(workDir, sessionDir, template.initScript(),
                        template.initScriptType());
            }

            // Build system prompt, augmenting with subject context if applicable
            String systemPrompt = buildSystemPrompt(template, subject);

            // Build welcome message, substituting subject placeholders
            String welcomeMessage = template.welcomeMessage();
            if (subject != null && welcomeMessage != null) {
                welcomeMessage = welcomeMessage.replace("{{subjectName}}", subject.name);
            }

            String sessionName = name != null && !name.isBlank() ? name : "Assistant Session";
            String subjectName = subject != null ? subject.name : null;

            List<String> command = buildCommand(workDir, sessionDir, systemPrompt,
                    template.model(), resolvedAllowedTools, mcpConfig != null);

            AtomicReference<AssistantSession> sessionRef = new AtomicReference<>();
            Consumer<AssistantEventParser.SseEvent> eventSink = event -> {
                AssistantSession current = sessionRef.get();
                if (current != null) {
                    current.handleDriverEvent(event);
                }
            };
            Consumer<AssistantEventParser.SseEvent> autoApprovalSink = event -> {
                AssistantSession current = sessionRef.get();
                if (current != null) {
                    current.handlePermissionEvent(event);
                }
            };
            InteractiveSessionDriver driver = interactiveSessionDriverFactory.createDriver(
                    new InteractiveSessionDriverFactory.DriverRequest(
                            agent.getType(),
                            templateId,
                            sessionDir,
                            workDir,
                            command,
                            resolvedEnv,
                            subjectId,
                            subjectName,
                            eventSink,
                            autoApprovalSink,
                            template.model(),
                            sessionName,
                            mcpConfigs,
                            systemPrompt,
                            resolvedAllowedTools));

            AssistantSession session = new AssistantSession(sessionId, sessionName, templateId, sessionDir,
                    workDir, command, resolvedEnv, agent.getType(), subjectId, subjectName, driver);
            session.setCapabilities(template.capabilities());
            session.setMcpToken(mcpToken);
            session.seedHistory(seed);
            sessionRef.set(session);
            // Persist every event (including the welcome message and startup events) to history.jsonl.
            // Seeded events are written directly so the new directory holds the complete history.
            HistoryJournal journal = new HistoryJournal(sessionDir.resolve(HistoryJournal.FILE_NAME), objectMapper);
            journals.put(sessionId, journal);
            seed.forEach(journal::accept);
            session.addListener(journal);
            session.start();

            // Add welcome message to event history so it replays on reconnect
            if (welcomeMessage != null && !welcomeMessage.isBlank()) {
                ObjectNode welcomeData = objectMapper.createObjectNode();
                welcomeData.put("text", welcomeMessage);
                session.addEvent(new AssistantEventParser.SseEvent("assistant_text", welcomeData));
            }

            // CDI bridge: broadcast assistant events to the global SSE channel
            session.addListener(event -> {
                try {
                    int eventIndex = session.getEventCount() - 1;
                    sseEventEmitter.fire(
                            io.apitomy.axiom.core.events.SseEvent.assistantSessionEvent(
                                    session.getId(), event.type(),
                                    event.toJson(), eventIndex));
                } catch (Exception e) {
                    LOG.debugf("Failed to broadcast assistant event: %s", e.getMessage());
                }
            });

            // Config Assistant validation listener
            if (session.hasCapability(SessionCapability.VALIDATION_FEEDBACK)) {
                session.addListener(createValidationListener(session));
            }

            sessions.put(session.getId(), session);
            LOG.infof("Created assistant session %s (%s) from template %s",
                    session.getId(), sessionName, templateId);

            // Send initial user message if configured
            String initialMessage = template.initialMessage();
            if (subject != null && initialMessage != null) {
                initialMessage = initialMessage.replace("{{subjectName}}", subject.name);
            }
            if (initialMessage != null && !initialMessage.isBlank()) {
                session.sendMessage(initialMessage);
            }

            return session;
            } catch (SessionCompatibilityException e) {
                closeJournal(sessionId);
                contextBuilder.deleteSessionDirectory(sessionDir);
                throw e;
            } catch (Exception e) {
                // Clean up the journal and session directory if setup fails after creation
                closeJournal(sessionId);
                contextBuilder.deleteSessionDirectory(sessionDir);
                throw e;
            }
        } catch (Exception e) {
            sessionCount.decrementAndGet();
            throw e;
        }
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
     * Validates a draft configuration item in the context of a session: items already written to the
     * session's working directory count as existing.
     *
     * @param sessionId the session identifier
     * @param type the item type (JSON name, e.g. {@code workflow})
     * @param item the item JSON
     * @return the problems found (empty when valid)
     * @throws IllegalArgumentException if the session does not exist
     */
    public List<ConfigProblem> validateItem(String sessionId, String type, JsonNode item) {
        AssistantSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Assistant session not found: " + sessionId);
        }
        return itemValidator.validateItem(ConfigItemType.fromName(type), item, session.getWorkingDirectory());
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
     * Returns the history journal of a live session, for tests that need to flush it.
     *
     * @param sessionId the session identifier
     * @return the journal, or null if the session has none
     */
    HistoryJournal journal(String sessionId) {
        return journals.get(sessionId);
    }

    private void closeJournal(String sessionId) {
        HistoryJournal journal = journals.remove(sessionId);
        if (journal != null) {
            journal.close();
        }
    }

    /**
     * Returns the number of open history journals, for tests.
     *
     * @return the number of journals currently tracked
     */
    int journalCount() {
        return journals.size();
    }

    /**
     * Destroys a session: kills the subprocess and deletes the session directory.
     * If the working directory is inside the session directory (auto-created),
     * it is deleted as well. User-specified working directories are left untouched.
     *
     * @param sessionId the session to destroy
     */
    @jakarta.transaction.Transactional
    public void destroySession(String sessionId) {
        destroySession(sessionId, false);
    }

    /**
     * Destroys a session: kills the subprocess, closes its journal and records usage. The session directory
     * is deleted unless {@code keepDirectory} is true.
     *
     * @param sessionId the session to destroy
     * @param keepDirectory whether to keep the session directory on disk
     */
    @jakarta.transaction.Transactional
    public void destroySession(String sessionId, boolean keepDirectory) {
        AssistantSession session = sessions.remove(sessionId);
        if (session != null) {
            sessionCount.decrementAndGet();
            session.destroy();
            try {
                recordUsage(session);
            } catch (Exception e) {
                LOG.debugf("Could not record usage for session %s: %s",
                        sessionId, e.getMessage());
            }
            closeJournal(sessionId);
            if (!keepDirectory) {
                contextBuilder.deleteSessionDirectory(session.getSessionDirectory());
            }
            LOG.infof("Destroyed assistant session %s (cost=$%.4f, turns=%d)",
                    sessionId, session.getTotalCostUsd(), session.getTurnCount());
        }
    }

    private void runInitScript(Path workDir, Path sessionDir, String script,
                                String scriptType) throws IOException {
        String type = scriptType != null ? scriptType : "bash";
        String fileName = "bash".equals(type) ? "init.sh" : "init.js";
        Path scriptFile = sessionDir.resolve(fileName);
        Files.writeString(scriptFile, script);

        String interpreter = "bash".equals(type) ? "bash" : "node";
        LOG.infof("Running init script (%s) in %s", type, workDir);

        ProcessBuilder pb = new ProcessBuilder(interpreter,
                scriptFile.toAbsolutePath().toString())
                .directory(workDir.toFile())
                .redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());

        boolean finished;
        try {
            finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Init script interrupted");
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Init script timed out after 60 seconds");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            LOG.warnf("Init script failed (exit %d): %s", exitCode, output);
            throw new IOException("Init script failed (exit code " + exitCode + "): "
                    + output.substring(0, Math.min(output.length(), 500)));
        }

        LOG.infof("Init script completed successfully");
        try {
            Files.deleteIfExists(scriptFile);
        } catch (IOException e) {
            LOG.warnf("Failed to delete init script file: %s", scriptFile);
        }
    }

    private void recordUsage(AssistantSession session) {
        if (session.getTurnCount() == 0) {
            return;
        }
        AiUsageEntity usage = new AiUsageEntity();
        usage.invocationType = "assistant-session";
        usage.actionType = "assistant-session";
        usage.engine = session.getEngineType();
        SessionTemplateService.SessionTemplate template =
                templateService.getTemplate(session.getTemplateId());
        if (template != null && template.model() != null && !template.model().isBlank()) {
            usage.model = template.model();
        }
        usage.costUsd = session.getTotalCostUsd();
        usage.inputTokens = session.getTotalInputTokens();
        usage.outputTokens = session.getTotalOutputTokens();
        usage.durationMs = session.getTotalDurationMs();
        usage.createdOn = Instant.now();
        usage.persist();
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
        collectItems(workDir, "toolsets", items);
        collectItems(workDir, "session-templates", items);
        collectItems(workDir, "event-sources", items);
        collectItems(workDir, "subscriptions", items);
        collectItems(workDir, "workflows", items);

        return items;
    }

    /**
     * Returns the full content of a specific generated item.
     *
     * @param sessionId the session identifier
     * @param itemType the item type directory (tools, action-types, toolsets, ...)
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
     * Validates all items and applies them as a Configuration Pack.
     * Items with names matching existing entities are updated in place;
     * new names are created.
     *
     * @param sessionId the session identifier
     * @return the upsert result with created and updated counts
     * @throws IOException if files cannot be read
     */
    public ImportExportService.UpsertResult applySession(String sessionId) throws IOException {
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

        // Refuse to "succeed" with nothing: the working directory may have been removed externally.
        Path workDir = session.getWorkingDirectory();
        if (workDir == null || !Files.isDirectory(workDir)) {
            throw new ValidationException("Nothing to apply", List.of("Nothing to apply: the session's working "
                    + "directory no longer exists (" + workDir + "). The session was kept; ask the assistant to "
                    + "write the items again."));
        }

        // Build configuration pack from the working directory and apply it (validated, transactional)
        ObjectNode pack = AssistantPackBuilder.fromDirectory(workDir, objectMapper);
        if (AssistantPackBuilder.itemCount(pack) == 0) {
            throw new ValidationException("Nothing to apply", List.of("Nothing to apply: the session has no items. "
                    + "Ask the assistant to create or update configuration items first."));
        }
        ObjectNode metadata = pack.putObject("metadata");
        metadata.put("name", "AI Assistant Session - " + session.getName());
        ImportExportService.UpsertResult result;
        try {
            result = importExportService.importOrUpdatePack(pack);
        } catch (ConfigValidationException e) {
            List<String> errors = new ArrayList<>();
            e.problemsByItem().forEach((key, problems) -> problems.forEach(
                    p -> errors.add(key + " " + p.path() + ": " + p.message())));
            throw new ValidationException("Items have validation errors", errors);
        }

        // Destroy session on success
        destroySession(sessionId);

        return result;
    }

    /**
     * Returns whether any registered engine supports interactive sessions.
     *
     * @return true if at least one engine supports interactive sessions
     */
    public boolean isAvailable() {
        return agentRegistry.getAllAgents().stream()
                .anyMatch(Agent::supportsInteractiveSessions);
    }

    private Agent resolveSessionAgent(SessionTemplateService.SessionTemplate template) {
        String templateEngine = trimToNull(template.engine());
        if (templateEngine != null) {
            return resolveAgentByType(templateEngine, "template");
        }
        String globalDefaultEngine = trimToNull(resolveGlobalDefaultEngineType());
        if (globalDefaultEngine != null) {
            return resolveAgentByType(globalDefaultEngine, "global default");
        }
        return agentRegistry.getDefaultAgent();
    }

    private Agent resolveAgentByType(String engineType, String sourceLabel) {
        Agent agent = agentRegistry.getAllAgents().stream()
                .filter(candidate -> engineType.equals(candidate.getType()))
                .findFirst()
                .orElse(null);
        if (agent == null) {
            throw new IllegalArgumentException(
                    "Unknown " + sourceLabel + " engine type: " + engineType);
        }
        return agent;
    }

    private String resolveGlobalDefaultEngineType() {
        SystemConfigEntity systemConfig =
                SystemConfigEntity.<SystemConfigEntity>findAll().firstResult();
        if (systemConfig != null && systemConfig.defaultEngine != null
                && !systemConfig.defaultEngine.isBlank()) {
            return systemConfig.defaultEngine;
        }
        return agentRegistry.getDefaultAgentType();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Cleans up stale session directories left behind by a previous crash.
     * No sessions are active at startup, so any directories under
     * {@code ~/.axiom/assistant/sessions/} are orphaned.
     *
     * @param event the startup event
     */
    void onStartup(@Observes StartupEvent event) {
        Path sessionsRoot = AxiomHome.resolve("assistant", "sessions");
        if (!Files.isDirectory(sessionsRoot)) {
            return;
        }
        Instant now = Instant.now();
        try (Stream<Path> dirs = Files.list(sessionsRoot)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                if (Files.exists(dir.resolve(COMPOSITION_MARKER))) {
                    LOG.infof("Keeping composition session directory %s", dir.getFileName());
                    return;
                }
                if (!isStale(dir, now, STALE_SESSION_GRACE)) {
                    LOG.warnf("Keeping session directory %s: it changed within the last %d minutes, so another "
                            + "Axiom instance may be using it", dir.getFileName(), STALE_SESSION_GRACE.toMinutes());
                    return;
                }
                contextBuilder.deleteSessionDirectory(dir);
                LOG.warnf("Removed stale session directory %s left behind by a previous run", dir.getFileName());
            });
        } catch (IOException e) {
            LOG.warnf(e, "Failed to clean up stale session directories");
        }
    }

    /** Session directories with activity more recent than this are never removed at startup. */
    static final Duration STALE_SESSION_GRACE = Duration.ofMinutes(10);

    /**
     * Whether a session directory has had no activity within {@code grace}: the newest modification time of
     * the directory or anything under it is older than {@code now - grace}.
     *
     * @param dir   the session directory
     * @param now   the current time
     * @param grace how long after its last change a directory is protected
     * @return true if it can be removed
     */
    static boolean isStale(Path dir, Instant now, Duration grace) {
        Instant cutoff = now.minus(grace);
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.noneMatch(p -> {
                try {
                    return Files.getLastModifiedTime(p).toInstant().isAfter(cutoff);
                } catch (IOException e) {
                    return true;
                }
            });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Cleans up all sessions on application shutdown.
     *
     * @param event the shutdown event
     */
    void onShutdown(@Observes ShutdownEvent event) {
        LOG.info("Shutting down — destroying all assistant sessions");
        for (AssistantSession session : new ArrayList<>(sessions.values())) {
            destroySession(session.getId(), session.hasCapability(SessionCapability.COMPOSITION));
        }
    }

    /**
     * Returns the directory of an assistant session.
     *
     * @param sessionId the session identifier
     * @return {@code ${axiom.home}/assistant/sessions/<sessionId>}
     */
    static Path sessionDirectory(String sessionId) {
        return AxiomHome.resolve("assistant", "sessions", sessionId);
    }

    /**
     * Builds the system prompt: replaces {@code {{reference}}} with the generated configuration reference and
     * augments the template prompt with subject context if a subject is specified.
     */
    private String buildSystemPrompt(SessionTemplateService.SessionTemplate template,
                                     SubjectEntity subject) {
        String prompt = template.systemPrompt() != null ? template.systemPrompt() : "";
        if (prompt.contains("{{reference}}")) {
            prompt = prompt.replace("{{reference}}", referenceGenerator.generate());
        }

        if (subject == null) {
            return prompt;
        }

        StringBuilder context = new StringBuilder();
        context.append("\n\n## Subject Context\n\n");
        context.append("This session is scoped to the following subject:\n\n");
        context.append("- **Name**: ").append(subject.name).append("\n");
        if (subject.body != null && !subject.body.isBlank()) {
            context.append("- **Description**: ").append(subject.body).append("\n");
        }
        context.append("- **Type**: ").append(subject.type).append("\n");
        context.append("- **Active**: ").append(SubjectEntity.isActive(subject.id)).append("\n");
        if (subject.refSource != null && !subject.refSource.isBlank()) {
            context.append("- **Source**: ").append(subject.refSource).append("\n");
        }
        context.append("- **Reference**: ").append(subject.ref).append("\n");
        if (subject.repository != null && !subject.repository.isBlank()) {
            context.append("- **Repository**: ").append(subject.repository).append("\n");
        }
        if (subject.labels != null && !subject.labels.isEmpty()) {
            context.append("- **Labels**: ").append(String.join(", ", subject.labels)).append("\n");
        }
        context.append("\nUse the subject-scoped MCP tools (prefixed with `axiom_subject_`) to ")
                .append("access subject tasks, discussion thread, events, and traces.\n");

        String subjectContext = context.toString();

        // Substitute {{subjectContext}} placeholder if present, otherwise append
        if (prompt.contains("{{subjectContext}}")) {
            prompt = prompt.replace("{{subjectContext}}", subjectContext);
        } else {
            prompt = prompt + subjectContext;
        }

        // Substitute {{subjectName}} placeholder
        prompt = prompt.replace("{{subjectName}}", subject.name);

        return prompt;
    }

    private List<String> buildCommand(Path workDir, Path sessionDir, String systemPrompt,
                                       String model, List<String> allowedTools,
                                       boolean hasMcpConfig) {
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

        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }

        cmd.add("--append-system-prompt");
        cmd.add(systemPrompt);

        if (hasMcpConfig) {
            cmd.add("--mcp-config");
            cmd.add(sessionDir.resolve("mcp-config.json").toAbsolutePath().toString());
        }

        if (!allowedTools.isEmpty()) {
            cmd.add("--allowedTools");
            cmd.add(String.join(" ", allowedTools));
        }

        return cmd;
    }

    private java.util.function.Consumer<SseEvent> createValidationListener(
            AssistantSession session) {
        ValidationFeedbackTracker tracker = new ValidationFeedbackTracker();
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
                validateAndFeedback(workDir, "tools", session, tracker);
                validateAndFeedback(workDir, "action-types", session, tracker);
                validateAndFeedback(workDir, "toolsets", session, tracker);
                validateAndFeedback(workDir, "session-templates", session, tracker);
                validateAndFeedback(workDir, "event-sources", session, tracker);
                validateAndFeedback(workDir, "subscriptions", session, tracker);
                validateAndFeedback(workDir, "workflows", session, tracker);
                if (tracker.transitionedToAllValid()) {
                    session.sendMessage("All configuration items in this session are valid.");
                }
            } catch (Exception e) {
                LOG.warnf(e, "Validation listener error in session %s",
                        session.getId());
            }
        };
    }

    private void validateAndFeedback(Path workDir, String subdir,
                                      AssistantSession session,
                                      ValidationFeedbackTracker tracker) throws IOException {
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
                            if (!tracker.shouldSend(f, feedback.toString())) {
                                return;
                            }
                            try {
                                session.sendMessage(feedback.toString());
                                LOG.infof("Sent validation feedback for %s in session %s",
                                        filePath, session.getId());
                            } catch (IOException e) {
                                LOG.warnf(e, "Failed to send validation feedback");
                            }
                        } else {
                            tracker.markValid(f);
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

    private Map<String, AssistantContextBuilder.McpServerConfig> resolveMcpServers(
            String sessionId, String mcpToken, SessionTemplateService.SessionTemplate template,
            SubjectEntity subject, Long compositionId, Path sessionDir,
            Map<String, String> sessionEnv,
            List<String> allowedTools) throws IOException {
        Map<String, AssistantContextBuilder.McpServerConfig> configs = new LinkedHashMap<>();

        String apiUrl = "http://localhost:" + httpPort + "/api/v1";

        // Build the effective server list: start with the template's explicit
        // mcpServers, then auto-include any servers implied by the allowed
        // tools list (mirroring the task-execution path in McpConfigGenerator).
        List<String> serverNames = new ArrayList<>(template.mcpServers());
        augmentServersFromAllowedTools(serverNames, allowedTools);

        for (String serverName : serverNames) {
            if ("@axiom-assistant".equals(serverName)) {
                // Built-in MCP server for subject-scoped assistant tools
                Path mcpServerDir = ensureMcpServerInstalled(ASSISTANT_MCP_DIR_NAME,
                        "templates/axiom-assistant-mcp/");
                String serverJsPath = mcpServerDir.resolve("server.js")
                        .toAbsolutePath().toString();
                Map<String, String> env = new LinkedHashMap<>();
                env.put("AXIOM_API_URL", apiUrl);
                if (subject != null) {
                    env.put("AXIOM_SUBJECT_ID", String.valueOf(subject.id));
                }
                env.put("AXIOM_ASSISTANT_SESSION_ID", sessionId);
                env.put("AXIOM_SESSION_TOKEN", mcpToken);
                configs.put("axiom", AssistantContextBuilder.McpServerConfig.stdio(
                        "node", List.of(serverJsPath), env));

            } else if ("@axiom-workbench".equals(serverName)) {
                // Built-in MCP server for workbench composition tools
                Path serverDir = ensureMcpServerInstalled(WORKBENCH_MCP_DIR_NAME, "templates/axiom-workbench-mcp/");
                Map<String, String> env = new LinkedHashMap<>();
                env.put("AXIOM_API_URL", apiUrl);
                if (compositionId != null) {
                    env.put("AXIOM_COMPOSITION_ID", String.valueOf(compositionId));
                }
                env.put("AXIOM_SESSION_TOKEN", mcpToken);
                configs.put("workbench", AssistantContextBuilder.McpServerConfig.stdio("node",
                        List.of(serverDir.resolve("server.js").toAbsolutePath().toString()), env));

            } else if ("@axiom-tools".equals(serverName)) {
                // Built-in MCP server for user-defined script tools.
                // Same server subject as the task execution path — loads
                // tool definitions from a generated tools.json file.
                List<ToolDefinitionEntity> scriptTools =
                        ToolDefinitionEntity.listAll();
                if (!scriptTools.isEmpty()) {
                    Path serverDir = mcpConfigGenerator.ensureMcpServerInstalled();
                    String toolsJson = mcpConfigGenerator.buildToolsJson(scriptTools);
                    Path toolsFile = sessionDir.resolve("tools.json");
                    Files.writeString(toolsFile, toolsJson);
                    Map<String, String> env = new LinkedHashMap<>(sessionEnv);
                    env.putIfAbsent("AXIOM_API_URL", apiUrl);
                    configs.put("axiom-tools",
                            AssistantContextBuilder.McpServerConfig.stdio("node",
                                    List.of(serverDir.resolve("tools-server.js")
                                                    .toAbsolutePath().toString(),
                                            toolsFile.toAbsolutePath().toString()),
                                    env));
                } else {
                    LOG.infof("No script tools defined — skipping @axiom-tools server");
                }

            } else if ("@axiom-sdk".equals(serverName)) {
                // Built-in MCP server for Axiom SDK tools (subject/task management)
                Path serverDir = mcpConfigGenerator.ensureMcpServerInstalled();
                Map<String, String> env = new LinkedHashMap<>();
                env.put("AXIOM_API_URL", apiUrl);
                configs.put("axiom-sdk",
                        AssistantContextBuilder.McpServerConfig.stdio("node",
                                List.of(serverDir.resolve("sdk-server.js")
                                        .toAbsolutePath().toString()),
                                env));

            } else {
                // Resolve from database
                McpServerEntity entity = McpServerEntity.find("name", serverName).firstResult();
                if (entity != null) {
                    if (entity.serverUrl != null && !entity.serverUrl.isBlank()) {
                        // HTTP transport
                        configs.put(serverName,
                                AssistantContextBuilder.McpServerConfig.http(entity.serverUrl));
                    } else if (entity.serverCommand != null) {
                        // Stdio transport
                        List<String> args = new ArrayList<>();
                        if (entity.serverArgs != null && !entity.serverArgs.isBlank()) {
                            JsonNode argsNode = objectMapper.readTree(entity.serverArgs);
                            if (argsNode.isArray()) {
                                for (JsonNode arg : argsNode) {
                                    args.add(arg.asText());
                                }
                            }
                        }
                        Map<String, String> env = new LinkedHashMap<>();
                        if (entity.serverEnv != null && !entity.serverEnv.isBlank()) {
                            JsonNode envNode = objectMapper.readTree(entity.serverEnv);
                            envNode.fields().forEachRemaining(
                                    field -> env.put(field.getKey(),
                                            field.getValue().asText()));
                        }
                        configs.put(serverName,
                                AssistantContextBuilder.McpServerConfig.stdio(
                                        entity.serverCommand, args, env));
                    } else {
                        LOG.warnf("MCP server has neither URL nor command: %s", serverName);
                    }
                } else {
                    LOG.warnf("MCP server not found: %s", serverName);
                }
            }
        }

        return configs;
    }

    /**
     * Inspects the allowed tools list and adds any MCP server entries that are
     * implied but not already present. This mirrors the auto-derivation logic
     * in {@link McpConfigGenerator#generateMcpConfig} so that users don't have
     * to manually keep the MCP servers list and allowed tools list in sync.
     * Built-in servers are added by their template alias ({@code @axiom-assistant},
     * {@code @axiom-tools}, {@code @axiom-sdk}), never by their registered name.
     *
     * @param serverNames mutable list of requested server names; implied servers are appended
     * @param allowedTools resolved allowed tools (Claude-style names such as {@code mcp__server__tool})
     */
    static void augmentServersFromAllowedTools(List<String> serverNames,
                                               List<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return;
        }

        boolean needsAxiomTools = !serverNames.contains("@axiom-tools")
                && allowedTools.stream().anyMatch(t -> t.startsWith("mcp__axiom-tools__"));
        boolean needsAxiomSdk = !serverNames.contains("@axiom-sdk")
                && allowedTools.stream().anyMatch(t -> t.startsWith("mcp__axiom-sdk__"));
        // "mcp__axiom__*" tools come from the built-in @axiom-assistant server,
        // which is registered under the name "axiom".
        boolean needsAxiomAssistant = !serverNames.contains("@axiom-assistant")
                && allowedTools.stream().anyMatch(t -> t.startsWith("mcp__axiom__"));
        // "mcp__workbench__*" tools come from the built-in @axiom-workbench server.
        boolean needsAxiomWorkbench = !serverNames.contains("@axiom-workbench")
                && allowedTools.stream().anyMatch(t -> t.startsWith("mcp__workbench__"));

        if (needsAxiomTools) {
            LOG.infof("Auto-including @axiom-tools server (implied by allowed tools)");
            serverNames.add("@axiom-tools");
        }
        if (needsAxiomSdk) {
            LOG.infof("Auto-including @axiom-sdk server (implied by allowed tools)");
            serverNames.add("@axiom-sdk");
        }
        if (needsAxiomAssistant) {
            LOG.infof("Auto-including @axiom-assistant server (implied by allowed tools)");
            serverNames.add("@axiom-assistant");
        }
        if (needsAxiomWorkbench) {
            LOG.infof("Auto-including @axiom-workbench server (implied by allowed tools)");
            serverNames.add("@axiom-workbench");
        }

        // Auto-include external MCP servers referenced in allowed tools
        for (String tool : allowedTools) {
            if (tool.startsWith("mcp__") && !tool.startsWith("mcp__axiom-tools__")
                    && !tool.startsWith("mcp__axiom-sdk__")
                    && !tool.startsWith("mcp__axiom__")
                    && !tool.startsWith("mcp__workbench__")) {
                int secondSep = tool.indexOf("__", 5);
                if (secondSep > 5) {
                    String externalName = tool.substring(5, secondSep);
                    if (!serverNames.contains(externalName)) {
                        LOG.infof("Auto-including MCP server '%s' (implied by allowed tools)",
                                externalName);
                        serverNames.add(externalName);
                    }
                }
            }
        }
    }

    private List<String> resolveAllowedTools(
            SessionTemplateService.SessionTemplate template) {
        List<String> resolved = new ArrayList<>();

        // Process allowed tools, expanding @ToolsetName references
        for (String entry : template.allowedTools()) {
            if (entry.startsWith("@")) {
                expandToolset(entry.substring(1), resolved);
            } else {
                resolved.add(entry);
            }
        }

        return resolved;
    }

    private void expandToolset(String toolsetName, List<String> target) {
        ToolsetEntity toolset = ToolsetEntity.find("name", toolsetName).firstResult();
        if (toolset != null && toolset.tools != null) {
            String[] tools = toolset.tools.split(",");
            for (String tool : tools) {
                String trimmed = tool.trim();
                if (!trimmed.isEmpty()) {
                    target.add(trimmed);
                }
            }
        } else {
            LOG.warnf("Toolset not found: %s", toolsetName);
        }
    }

    /**
     * Ensures a built-in Node.js MCP server is installed at {@code ${axiom.home}/<dirName>/}: copies its
     * {@code package.json} and {@code server.js} from the classpath and runs {@code npm install} once.
     *
     * @param dirName the install directory name under the Axiom home
     * @param resourceDir the classpath directory holding the server files (with trailing slash)
     * @return the install directory
     */
    private Path ensureMcpServerInstalled(String dirName, String resourceDir) throws IOException {
        Path installed = installedMcpServers.get(dirName);
        if (installed != null && Files.exists(installed.resolve("node_modules"))) {
            return installed;
        }

        synchronized (this) {
            installed = installedMcpServers.get(dirName);
            if (installed != null && Files.exists(installed.resolve("node_modules"))) {
                return installed;
            }

            Path serverDir = AxiomHome.path().resolve(dirName);
            Files.createDirectories(serverDir);

            for (String fileName : MCP_TEMPLATE_FILES) {
                String resourcePath = resourceDir + fileName;
                try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        throw new IOException("Template resource not found: " + resourcePath);
                    }
                    Files.copy(is, serverDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            LOG.infof("Copied MCP server template %s to %s", resourceDir, serverDir);

            LOG.infof("Running npm install for MCP server %s...", dirName);
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
                throw new IOException("npm install failed (exit code " + exitCode + "): " + output);
            }
            LOG.infof("npm install completed for MCP server in %s", serverDir);

            installedMcpServers.put(dirName, serverDir);
            return serverDir;
        }
    }

    /**
     * Describes a generated configuration item with its validation status.
     *
     * @param type the item type directory (tools, action-types, toolsets, ...)
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
