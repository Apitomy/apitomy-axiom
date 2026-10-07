package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.apitomy.axiom.agents.spi.AgentRequest;
import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActionTypeField;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AgentEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.axiom.core.logging.LogContext;
import io.apitomy.axiom.core.services.ActionTypeIoValidator;
import io.apitomy.axiom.core.services.EncryptionService;
import io.apitomy.axiom.core.services.EnvironmentResolver;
import io.apitomy.axiom.core.services.InputBindingResolver;
import io.apitomy.axiom.core.services.ToolsetResolver;
import io.apitomy.axiom.core.services.WorkspaceService;
import io.apitomy.axiom.core.tracing.TraceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates task execution. Resolves the appropriate Agent implementation
 * via the AgentPool, enforces subject-level task serialization, manages the
 * task lifecycle, and records results.
 */
@ApplicationScoped
public class TaskExecutionService {

    private static final Logger LOG = Logger.getLogger(TaskExecutionService.class);

    @Inject
    AgentPool agentPool;

    @Inject
    AgentRegistry agentRegistry;

    @Inject
    WorkspaceService workspaceService;

    @Inject
    ToolsetResolver toolsetResolver;

    @Inject
    Event<SseEvent> sseEvents;

    @Inject
    McpConfigGenerator mcpConfigGenerator;

    @Inject
    ScriptExecutionService scriptExecutionService;

    @Inject
    NodeExecutionService nodeExecutionService;

    @Inject
    EncryptionService encryptionService;

    @Inject
    EnvironmentResolver environmentResolver;

    @Inject
    TraceService traceService;

    @Inject
    TaskTraceFinalizer taskTraceFinalizer;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Attempts to execute the next pending task for the given subject.
     * Does nothing if there is already an active task for the subject.
     *
     * @param subjectId the subject to check for pending tasks
     */
    public void executeNextTask(Long subjectId) {
        // Check for active tasks (serialization enforcement)
        long activeTasks = TaskEntity.count(
                "subjectId = ?1 and (status = 'InProgress' or status = 'AwaitingInput')",
                subjectId);
        if (activeTasks > 0) {
            LOG.debugf("Subject %d has an active task, skipping", subjectId);
            return;
        }

        // Find the next pending task
        TaskEntity task = TaskEntity.find(
                "subjectId = ?1 and status = 'Pending' order by createdOn asc",
                subjectId).firstResult();
        if (task == null) {
            return;
        }

        executeTask(task);
    }

    /**
     * Executes a specific task.
     *
     * @param task the task to execute
     */
    public void executeTask(TaskEntity task) {
        try (LogContext ignored = taskLogContext(task)) {
            doExecuteTask(task);
        }
    }

    /**
     * Opens a logging context carrying the task's correlation IDs (trace, event, subject, task and
     * workflow run). The caller must close it, normally with try-with-resources.
     *
     * @param task the task whose IDs are put into the MDC
     * @return the opened context
     */
    static LogContext taskLogContext(TaskEntity task) {
        return LogContext.create()
                .traceId(task.traceId)
                .eventId(task.eventId)
                .subjectId(task.subjectId)
                .taskId(task.id)
                .workflowRunId(task.workflowRunId);
    }

    private void doExecuteTask(TaskEntity task) {
        // Check if this is a script action type
        ActionTypeEntity actionTypeEntity = ActionTypeEntity.find("name", task.actionType).firstResult();

        // Fail fast when a required declared input has no value (e.g. it is not mapped on the node),
        // then on type mismatches. Inputs are parsed once for both checks.
        if (actionTypeEntity != null) {
            Map<String, Object> resolvedInputs = parseResolvedInputs(task);
            List<String> missing = InputBindingResolver.missingRequiredInputs(
                    actionTypeEntity.inputs, resolvedInputs);
            if (!missing.isEmpty()) {
                failTask(task.id, "Missing required input(s): " + String.join(", ", missing)
                        + ". Map them on the workflow node.");
                return;
            }
            if (task.workflowRunId != null && actionTypeEntity.inputs != null
                    && !actionTypeEntity.inputs.isEmpty()) {
                List<String> inputErrors = ActionTypeIoValidator.validate(actionTypeEntity.inputs, resolvedInputs);
                if (!inputErrors.isEmpty()) {
                    failTask(task.id, "Input validation failed: " + String.join("; ", inputErrors));
                    return;
                }
            }
        }

        if (actionTypeEntity != null && "script".equals(actionTypeEntity.executionMode)) {
            scriptExecutionService.executeScript(task);
            return;
        }
        if (actionTypeEntity != null && "node".equals(actionTypeEntity.executionMode)) {
            nodeExecutionService.executeNode(task);
            return;
        }

        // Resolve the agent type for this action type (may differ from global default)
        String agentType = actionTypeEntity != null ? actionTypeEntity.engine : null;

        // Acquire an agent lease from the pool
        String capability = "action:" + task.actionType;
        AgentLease lease = agentPool.tryLease(capability, agentType, task.assignedAgent, "task", task.id)
                .orElse(null);
        if (lease == null) {
            // No agent available — wait if a suitable agent exists but is busy, otherwise fail clearly
            if (agentPool.hasEnabledAgent(agentType)) {
                LOG.debugf("All %sagents busy, task %d stays pending",
                        agentType == null || agentType.isBlank() ? "" : agentType + " ", task.id);
                return;
            }
            failTask(task.id, noAgentMessage(agentType, task.actionType));
            return;
        }

        String agentName = lease.agentEntityName();
        Long agentEntityId = lease.agentEntityId();

        // Mark task as in progress
        markTaskInProgress(task.id, agentEntityId, agentName);

        // Build the agent request
        SubjectEntity subject = SubjectEntity.findById(task.subjectId);

        // Ensure the workspace directory exists before launching the agent workload;
        // otherwise the subprocess fails to start with "No such file or directory".
        Path workspace;
        try {
            workspace = workspaceService.ensureWorkspace(subject);
        } catch (WorkspaceService.WorkspaceException e) {
            agentPool.release(lease);
            LOG.errorf(e, "Failed to prepare workspace for task %d", task.id);
            failTask(task.id, "Failed to prepare workspace: " + e.getMessage());
            return;
        }
        Map<String, String> env = buildEnvironment(
                actionTypeEntity != null ? actionTypeEntity.environment : null);

        // Inject trace env vars for MCP tool callbacks (tool calls become children of the task node)
        if (task.traceId != null) {
            try {
                TraceNodeEntity taskNode = TraceNodeEntity.find(
                        "traceId = ?1 and nodeType = 'task' and entityType = 'task' and entityId = ?2",
                        task.traceId, String.valueOf(task.id)).firstResult();
                if (taskNode != null) {
                    env.put("AXIOM_TRACE_ID", task.traceId.toString());
                    env.put("AXIOM_PARENT_NODE_ID", String.valueOf(taskNode.id));
                }
            } catch (Exception e) {
                LOG.warnf(e, "Failed to look up task trace node for task %d", task.id);
            }
        }

        // Generate MCP config filtered to only the tools allowed by this action type
        List<String> allowedTools = getToolsFromActionType(task.actionType);
        Path mcpConfig = mcpConfigGenerator.generateMcpConfig(task.id, env, allowedTools);
        // Also let the agent's MCP manager configure its own MCP servers
        // (required for OpenCode, which registers servers dynamically
        // via HTTP rather than consuming the config file above).
        try {
            agentRegistry.getMcpManager(lease.agent().getType()).configureMcpServers(task.id, env, allowedTools);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to configure agent MCP servers for task %d", task.id);
        }

        String prompt = resolvePromptTemplate(actionTypeEntity, task);
        AgentRequest request = AgentRequest.builder()
                .prompt(prompt != null ? prompt : task.input)
                .workingDirectory(workspace)
                .systemPrompt(buildSystemPrompt(actionTypeEntity))
                .allowedTools(allowedTools)
                .mcpConfigFile(mcpConfig)
                .environment(env)
                .model(actionTypeEntity != null ? actionTypeEntity.model : null)
                .maxSteps(actionTypeEntity != null && actionTypeEntity.maxSteps != null
                        ? actionTypeEntity.maxSteps : 50)
                .maxBudgetUsd(actionTypeEntity != null ? actionTypeEntity.maxBudgetUsd : null)
                .timeoutSeconds(actionTypeEntity != null && actionTypeEntity.timeoutSeconds != null
                        ? actionTypeEntity.timeoutSeconds : 120)
                .build();

        // Execute asynchronously. The callbacks run on the agent's thread (typically a common
        // ForkJoinPool worker), so the logging context is captured here and re-applied there.
        lease.agent().execute(request)
                .thenAccept(LogContext.wrap((AgentResult result) -> {
                    agentPool.release(lease);
                    onTaskCompleted(task.id, result);
                }))
                .exceptionally(LogContext.wrap((Throwable throwable) -> {
                    agentPool.release(lease);
                    LOG.errorf(throwable, "Task %d execution failed unexpectedly", task.id);
                    failTask(task.id, "Unexpected error: " + throwable.getMessage());
                    return null;
                }));
    }

    /**
     * Reads the allowed tools from the ActionTypeEntity's allowedTools field.
     * Falls back to a minimal read-only set if not configured.
     */
    private List<String> getToolsFromActionType(String actionType) {
        ActionTypeEntity actionTypeEntity = ActionTypeEntity.find("name", actionType).firstResult();
        if (actionTypeEntity != null
                && actionTypeEntity.allowedTools != null
                && !actionTypeEntity.allowedTools.isBlank()) {
            return toolsetResolver.resolve(actionTypeEntity.allowedTools);
        }
        // Fallback: minimal read-only tools
        LOG.warnf("No allowed tools configured for action type '%s', using minimal read-only defaults",
                actionType);
        return List.of("Read", "Glob", "Grep");
    }

    /**
     * Builds environment variables to pass to the subprocess.
     * If the action type has a custom environment configured, resolves
     * that (including ${secret:NAME} references). Otherwise falls back
     * to injecting all configured secrets.
     */
    private Map<String, String> buildEnvironment(String customEnvironmentJson) {
        if (environmentResolver.hasCustomEnvironment(customEnvironmentJson)) {
            Map<String, String> env = environmentResolver.resolve(customEnvironmentJson);
            LOG.debugf("Using custom environment with %d variable(s)", env.size());
            return env;
        }

        Map<String, String> env = new HashMap<>();
        List<SecretEntity> secrets = SecretEntity.listAll();
        for (SecretEntity secret : secrets) {
            try {
                env.put(secret.name, encryptionService.decrypt(secret.encryptedValue));
            } catch (Exception e) {
                LOG.warnf("Failed to decrypt secret '%s' — skipping", secret.name);
            }
        }

        if (secrets.isEmpty()) {
            LOG.debug("No secrets configured — subprocess will have no injected credentials");
        } else {
            LOG.debugf("Injected %d secret(s) into subprocess environment", secrets.size());
        }

        return env;
    }

    /**
     * The failure message when no enabled agent can run an action type.
     *
     * @param engine     the action type's engine, or null/blank for any
     * @param actionType the action type name
     * @return the message
     */
    static String noAgentMessage(String engine, String actionType) {
        if (engine == null || engine.isBlank()) {
            return "No agent available for task type: " + actionType;
        }
        return "No enabled agent of type '" + engine + "' is configured for action type '" + actionType
                + "'. Add one under Components → Agents.";
    }

    /**
     * Resolves the prompt template for the action type: binds declared inputs into
     * {@code {{inputs.NAME}}} placeholders (absent inputs render empty), then appends the
     * "## Inputs" section and, for workflow tasks with declared outputs, the output contract.
     * Returns null if no template is configured.
     *
     * @param actionType the action type, may be {@code null}
     * @param task       the task whose resolved inputs supply the values
     * @return the assembled prompt, or {@code null} when no template is configured
     */
    String resolvePromptTemplate(ActionTypeEntity actionType, TaskEntity task) {
        if (actionType == null || actionType.promptTemplate == null
                || actionType.promptTemplate.isBlank()) {
            return null;
        }

        Map<String, Object> values = parseResolvedInputs(task);
        Map<String, Object> bindings = new HashMap<>();
        if (actionType.inputs != null) {
            for (ActionTypeField input : actionType.inputs) {
                bindings.put(input.name, values.get(input.name));
            }
        }
        String resolved = InputBindingResolver.bindInputs(actionType.promptTemplate, bindings, objectMapper);

        String inputsSection = InputBindingResolver.buildInputsSection(actionType.inputs, values, objectMapper);
        if (!inputsSection.isEmpty()) {
            resolved = resolved + "\n\n" + inputsSection;
        }

        // Auto-append the output contract for workflow tasks that declare outputs.
        if (task.workflowRunId != null && actionType.outputs != null && !actionType.outputs.isEmpty()) {
            resolved = resolved + "\n\n" + InputBindingResolver.buildOutputContract(actionType.outputs);
        }
        return resolved;
    }

    /**
     * Parses a workflow task's resolved inputs (a JSON object) into a map.
     * Returns an empty map for non-workflow tasks or unparseable input.
     */
    private Map<String, Object> parseResolvedInputs(TaskEntity task) {
        if (task.workflowRunId == null) {
            return Map.of();
        }
        return InputBindingResolver.parseInputs(task.input, objectMapper);
    }

    /**
     * Builds the agent system prompt: the action being performed, its description (if any) and the
     * working-directory note. Contains no subject data — agents receive data only through inputs.
     *
     * @param actionType the action type, may be {@code null}
     * @return the system prompt
     */
    String buildSystemPrompt(ActionTypeEntity actionType) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are performing the action '")
                .append(actionType != null ? actionType.name : "").append("'.\n");
        if (actionType != null && actionType.description != null && !actionType.description.isBlank()) {
            sb.append(actionType.description).append("\n");
        }
        sb.append("Your current working directory is the task's workspace.\n");
        return sb.toString();
    }

    @Transactional
    void markTaskInProgress(Long taskId, Long agentEntityId, String agentName) {
        TaskEntity task = TaskEntity.findById(taskId);
        if (task != null) {
            task.status = "InProgress";
            task.assignedAgent = agentEntityId;

            // Log to activity
            logActivity(task.subjectId, taskId, task.eventId, "task-started",
                    "Task started: " + task.actionType + " (agent: " + agentName + ")", task.traceId);

            // Log to thread
            addThreadEntry(task.subjectId, "system", "update",
                    "Task started: " + task.actionType + "\nAssigned to: " + agentName);

            // Fire SSE events
            sseEvents.fire(SseEvent.taskUpdated(task.subjectId, taskId, "InProgress"));
            sseEvents.fire(SseEvent.subjectUpdated(task.subjectId));
            sseEvents.fire(SseEvent.threadEntry(task.subjectId));
        }
    }

    /**
     * Marks a task as awaiting human input. Used for direct human tasks
     * created via the inbox.
     *
     * @param taskId the task ID
     */
    @Transactional
    public void markTaskAwaitingInput(Long taskId) {
        TaskEntity task = TaskEntity.findById(taskId);
        if (task != null) {
            task.status = "AwaitingInput";

            // Log to activity
            logActivity(task.subjectId, taskId, task.eventId, "task-awaiting-input",
                    "Task awaiting human input: " + task.actionType, task.traceId);

            // Log to thread
            addThreadEntry(task.subjectId, "system", "update",
                    "Task awaiting human input: " + task.actionType
                            + (task.input != null ? "\n\n" + task.input : ""));

            // Fire SSE events
            sseEvents.fire(SseEvent.taskUpdated(task.subjectId, taskId, "AwaitingInput"));
            sseEvents.fire(SseEvent.subjectUpdated(task.subjectId));
            sseEvents.fire(SseEvent.threadEntry(task.subjectId));

            // Notify inbox subscribers
            long inboxCount = TaskEntity.count("status", "AwaitingInput");
            sseEvents.fire(SseEvent.inboxUpdated(taskId, "added", inboxCount));
        }
    }

    /**
     * Completes a task and records its result. The database is the source of
     * truth for task state, so this can be called directly (e.g. from
     * {@code InboxResourceImpl}) even when no in-memory future is tracking the
     * task — this happens for human tasks left in {@code AwaitingInput} across
     * an application restart.
     *
     * @param taskId the task ID
     * @param result the agent result
     */
    @Transactional
    public void onTaskCompleted(Long taskId, AgentResult result) {
        TaskEntity task = TaskEntity.findById(taskId);
        if (task == null) {
            return;
        }
        try (LogContext ignored = taskLogContext(task)) {
            recordTaskCompletion(task, result);
        }
    }

    private void recordTaskCompletion(TaskEntity task, AgentResult result) {
        Long taskId = task.id;
        String previousStatus = task.status;
        task.status = result.success() ? "Completed" : "Failed";
        task.output = result.output();
        task.completedOn = Instant.now();
        task.sessionId = result.sessionId();
        task.executionLog = result.executionLog();

        String statusText = result.success() ? "completed" : "failed";
        LOG.infof("Task %d %s (cost: $%s, tokens: %d/%d)",
                taskId, statusText,
                result.costUsd() != null ? String.format("%.4f", result.costUsd()) : "n/a",
                result.inputTokens() != null ? result.inputTokens() : 0,
                result.outputTokens() != null ? result.outputTokens() : 0);

        // Record AI usage (prefer engine/model from the agent result, fall back to action type config)
        ActionTypeEntity actionTypeEntity = ActionTypeEntity.find("name", task.actionType).firstResult();
        String engine = result.engine();
        if (engine == null || engine.isBlank()) {
            engine = actionTypeEntity != null ? actionTypeEntity.engine : null;
        }
        if (engine == null || engine.isBlank()) {
            engine = agentRegistry.getDefaultAgentType();
        }
        String model = result.model();
        if (model == null || model.isBlank()) {
            model = actionTypeEntity != null ? actionTypeEntity.model : null;
        }
        recordAiUsage("task", taskId, task.eventId, task.subjectId,
                task.assignedAgent, task.actionType, engine, model,
                result.costUsd(), result.inputTokens(), result.outputTokens(),
                task.traceId);

        // Log to activity
        logActivity(task.subjectId, taskId, task.eventId, "task-" + statusText,
                "Task " + statusText + ": " + task.actionType, task.traceId);

        // Log to thread
        String threadContent = "Task " + statusText + ": " + task.actionType;
        if (result.output() != null && !result.output().isEmpty()) {
            threadContent += "\n\nResult:\n" + result.output();
        }
        if (result.errorMessage() != null) {
            threadContent += "\n\nError: " + result.errorMessage();
        }
        addThreadEntry(task.subjectId, "system", "result", threadContent);

        // Fire SSE events
        sseEvents.fire(SseEvent.taskUpdated(task.subjectId, taskId, task.status));
        sseEvents.fire(SseEvent.threadEntry(task.subjectId));
        sseEvents.fire(SseEvent.activity("task-" + statusText,
                "Task " + statusText + ": " + task.actionType));
        if (!result.success()) {
            sseEvents.fire(SseEvent.notification(
                    "Task failed: " + task.actionType, "error"));
        }

        // Notify inbox subscribers if this task was awaiting human input
        if ("AwaitingInput".equals(previousStatus)) {
            long inboxCount = TaskEntity.count("status", "AwaitingInput");
            sseEvents.fire(SseEvent.inboxUpdated(taskId, "removed", inboxCount));
        }

        // Complete the task node and (when it was the last open task) the trace
        taskTraceFinalizer.finalizeTaskTrace(task, statusText);

        // Clean up temporary MCP config files
        mcpConfigGenerator.cleanupTempFiles(task.id);

        // Refresh disk usage and start the next pending task, if any
        afterTaskFinished(task.subjectId);

        // Advance workflow if this task is part of one. This runs output-contract
        // validation and may transition the task to Failed. It shares this
        // transaction's persistence context, so any status change is reflected in
        // the managed `task` entity below.
        if (task.workflowRunId != null) {
            workflowExecutionService.onTaskCompleted(task.id);
        }

    }

    @Transactional
    void failTask(Long taskId, String reason) {
        TaskEntity task = TaskEntity.findById(taskId);
        if (task == null) {
            return;
        }
        try (LogContext ignored = taskLogContext(task)) {
            task.status = "Failed";
            task.output = reason;
            task.completedOn = Instant.now();

            logActivity(task.subjectId, taskId, task.eventId, "task-failed",
                    "Task failed: " + task.actionType + " — " + reason, task.traceId);
            addThreadEntry(task.subjectId, "system", "result",
                    "Task failed: " + task.actionType + "\n\nError: " + reason);

            sseEvents.fire(SseEvent.taskUpdated(task.subjectId, taskId, task.status));

            // Close the task's trace node (and the trace, for non-workflow tasks)
            taskTraceFinalizer.finalizeTaskTrace(task, "failed");

            mcpConfigGenerator.cleanupTempFiles(taskId);
            afterTaskFinished(task.subjectId);

            // Advance workflow if this task is part of one
            if (task.workflowRunId != null) {
                workflowExecutionService.onTaskCompleted(task.id);
            }
        }
    }

    private void afterTaskFinished(Long subjectId) {
        long activeTasks = TaskEntity.count(
                "subjectId = ?1 and (status = 'InProgress' or status = 'AwaitingInput')",
                subjectId);
        long pendingTasks = TaskEntity.count(
                "subjectId = ?1 and status = 'Pending'", subjectId);

        // Update disk usage for the subject workspace
        SubjectEntity subject = SubjectEntity.findById(subjectId);
        if (subject != null) {
            subject.diskUsageBytes = workspaceService.computeDiskUsage(subject);
        }

        // If there are pending tasks, trigger the next one
        if (activeTasks == 0 && pendingTasks > 0) {
            executeNextTask(subjectId);
        }
    }

    private void logActivity(Long subjectId, Long taskId, UUID eventId,
                              String entryType, String summary, UUID traceId) {
        ActivityLogEntity log = new ActivityLogEntity();
        log.subjectId = subjectId;
        log.taskId = taskId;
        log.eventId = eventId;
        log.entryType = entryType;
        log.summary = summary != null && summary.length() > 1024
                ? summary.substring(0, 1021) + "..."
                : summary;
        log.createdOn = Instant.now();
        log.traceId = traceId;
        log.persist();
    }

    private void addThreadEntry(Long subjectId, String authorType, String entryType,
                                String content) {
        ThreadEntryEntity entry = new ThreadEntryEntity();
        entry.subjectId = subjectId;
        entry.authorType = authorType;
        entry.entryType = entryType;
        entry.content = content;
        entry.createdOn = Instant.now();
        entry.persist();
    }

    private void recordAiUsage(String invocationType, Long taskId, UUID eventId,
                                Long subjectId, Long agentId, String actionType,
                                String engine, String model,
                                Double costUsd, Long inputTokens, Long outputTokens,
                                UUID traceId) {
        AiUsageEntity usage = new AiUsageEntity();
        usage.invocationType = invocationType;
        usage.taskId = taskId;
        usage.eventId = eventId;
        usage.subjectId = subjectId;
        usage.agentId = agentId;
        usage.actionType = actionType;
        usage.engine = engine;
        usage.model = model;
        usage.costUsd = costUsd;
        usage.inputTokens = inputTokens;
        usage.outputTokens = outputTokens;
        usage.createdOn = Instant.now();
        usage.traceId = traceId;
        usage.persist();
    }
}
