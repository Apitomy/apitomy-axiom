package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.tracing.TraceService;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.axiom.core.services.EncryptionService;
import io.apitomy.axiom.core.services.EnvironmentResolver;
import io.apitomy.axiom.core.services.InputBindingResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Executes script-mode action types by running a user-defined bash script.
 * The script template is stored on the ActionTypeEntity and supports
 * placeholder substitution for project/event context and the API base URL.
 */
@ApplicationScoped
public class ScriptExecutionService {

    private static final Logger LOG = Logger.getLogger(ScriptExecutionService.class);

    @Inject
    Event<SseEvent> sseEvents;

    @Inject
    EncryptionService encryptionService;

    @Inject
    EnvironmentResolver environmentResolver;

    @Inject
    TraceService traceService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "9090")
    int httpPort;

    @ConfigProperty(name = "axiom.script.timeout-seconds", defaultValue = "60")
    int timeoutSeconds;

    /**
     * Executes the script for a task asynchronously.
     *
     * @param task the task whose action type defines the script to run
     */
    public void executeScript(TaskEntity task) {
        executeScript(task, null);
    }

    /**
     * Executes the script for a task asynchronously, using the supplied project
     * for template variable resolution. If {@code project} is {@code null}, the
     * project is loaded from the database — callers that already hold a managed
     * entity (e.g. inside an uncommitted transaction) should pass it directly to
     * avoid a race between the async thread and the transaction commit.
     *
     * @param task    the task whose action type defines the script to run
     * @param project the project to use for placeholder substitution, or {@code null}
     */
    public void executeScript(TaskEntity task, ProjectEntity project) {
        markTaskInProgress(task.id);

        CompletableFuture.runAsync(() -> {
            Arc.container().requestContext().activate();
            try {
                RunResult result = runScript(task, project);
                completeTask(task.id, result.output, result.exitCode == 0,
                        result.executionLog);
            } catch (Exception e) {
                LOG.errorf(e, "Script execution failed for task %d", task.id);
                failTask(task.id, "Script execution error: " + e.getMessage());
            } finally {
                Arc.container().requestContext().terminate();
            }
        });
    }

    private RunResult runScript(TaskEntity task, ProjectEntity project)
            throws IOException, InterruptedException {
        ActionTypeEntity actionType = ActionTypeEntity.find("name", task.actionType).firstResult();
        if (actionType == null || actionType.scriptTemplate == null
                || actionType.scriptTemplate.isBlank()) {
            return new RunResult(1,
                    "No script template configured for action type: " + task.actionType,
                    null);
        }

        if (project == null) {
            project = ProjectEntity.findById(task.projectId);
        }
        String script = substitutePlaceholders(actionType.scriptTemplate, task, project);
        Instant startTime = Instant.now();

        Path scriptFile = Files.createTempFile("axiom-script-", ".sh");
        try {
            Files.writeString(scriptFile, script);
            scriptFile.toFile().setExecutable(true);

            LOG.infof("Executing script for task %d (%s), timeout %ds",
                    task.id, task.actionType, timeoutSeconds);

            ProcessBuilder pb = new ProcessBuilder("/bin/bash", scriptFile.toString())
                    .redirectErrorStream(true);
            pb.environment().put("AXIOM_API_URL", "http://localhost:" + httpPort + "/api/v1");
            pb.environment().put("AXIOM_PROJECT_ID", String.valueOf(task.projectId));
            pb.environment().put("AXIOM_TASK_ID", String.valueOf(task.id));

            // Inject environment variables (custom or all secrets as fallback)
            if (environmentResolver.hasCustomEnvironment(actionType.environment)) {
                pb.environment().putAll(environmentResolver.resolve(actionType.environment));
            } else {
                for (SecretEntity secret : SecretEntity.<SecretEntity>listAll()) {
                    try {
                        pb.environment().put(secret.name, encryptionService.decrypt(secret.encryptedValue));
                    } catch (Exception e) {
                        LOG.warnf("Failed to decrypt secret '%s' for script — skipping", secret.name);
                    }
                }
            }

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            Instant endTime = Instant.now();
            long durationMs = java.time.Duration.between(startTime, endTime).toMillis();

            int exitCode;
            if (!finished) {
                process.destroyForcibly();
                exitCode = 1;
                output = output + "\n[Script timed out after " + timeoutSeconds + "s]";
            } else {
                exitCode = process.exitValue();
            }

            String executionLog = buildExecutionLog(task, actionType.scriptTemplate,
                    script, output, exitCode, startTime, durationMs);

            return new RunResult(exitCode, output, executionLog);
        } finally {
            Files.deleteIfExists(scriptFile);
        }
    }

    private String buildExecutionLog(TaskEntity task, String template, String resolvedScript,
                                      String output, int exitCode, Instant startTime,
                                      long durationMs) {
        StringBuilder log = new StringBuilder();
        log.append("═══════════════════════════════════════════════════════════════\n");
        log.append("  Script Execution Log\n");
        log.append("═══════════════════════════════════════════════════════════════\n");
        log.append("  Task:        #").append(task.id).append("\n");
        log.append("  Action Type: ").append(task.actionType).append("\n");
        log.append("  Project:     #").append(task.projectId).append("\n");
        log.append("  Started:     ").append(startTime).append("\n");
        log.append("  Duration:    ").append(durationMs).append(" ms\n");
        log.append("  Exit Code:   ").append(exitCode).append("\n");
        log.append("  Status:      ").append(exitCode == 0 ? "SUCCESS" : "FAILED").append("\n");
        log.append("═══════════════════════════════════════════════════════════════\n\n");

        log.append("── Script Template ────────────────────────────────────────────\n");
        log.append(template.strip()).append("\n\n");

        log.append("── Resolved Script ────────────────────────────────────────────\n");
        log.append(resolvedScript.strip()).append("\n\n");

        log.append("── Output ─────────────────────────────────────────────────────\n");
        if (output != null && !output.isBlank()) {
            log.append(output.strip()).append("\n");
        } else {
            log.append("(no output)\n");
        }

        log.append("\n═══════════════════════════════════════════════════════════════\n");
        return log.toString();
    }

    private String substitutePlaceholders(String template, TaskEntity task,
                                           ProjectEntity project) {
        String apiBaseUrl = "http://localhost:" + httpPort + "/api/v1";
        String resolved = template;
        resolved = resolved.replace("{{projectId}}", str(task.projectId));
        resolved = resolved.replace("{{eventId}}", str(task.eventId));
        resolved = resolved.replace("{{taskId}}", str(task.id));
        resolved = resolved.replace("{{ref}}", project != null && project.ref != null
                ? project.ref : "");
        resolved = resolved.replace("{{repository}}", project != null && project.repository != null
                ? project.repository : "");
        resolved = resolved.replace("{{projectName}}", project != null && project.name != null
                ? project.name : "");
        resolved = resolved.replace("{{managerInput}}", task.input != null ? task.input : "");
        resolved = resolved.replace("{{apiBaseUrl}}", apiBaseUrl);
        String workDir = System.getProperty("user.home") + "/.axiom/workspaces/project-" + task.projectId;
        resolved = resolved.replace("{{workDir}}", workDir);

        // Bind named workflow inputs as {{inputs.NAME}} (workflow tasks only).
        if (task.workflowRunId != null) {
            resolved = InputBindingResolver.bindInputs(
                    resolved, InputBindingResolver.parseInputs(task.input, objectMapper), objectMapper);
        }

        return resolved;
    }

    private String str(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    @Transactional
    void markTaskInProgress(Long taskId) {
        TaskEntity task = TaskEntity.findById(taskId);
        if (task != null) {
            task.status = "InProgress";

            ProjectEntity project = ProjectEntity.findById(task.projectId);
            if (project != null) {
                project.status = "InProgress";
                project.updatedOn = Instant.now();
            }

            logActivity(task.projectId, taskId, task.eventId, "task-started",
                    "Script task started: " + task.actionType);
            addThreadEntry(task.projectId, "system", "update",
                    "Script task started: " + task.actionType);

            sseEvents.fire(SseEvent.taskUpdated(task.projectId, taskId, "InProgress"));
            sseEvents.fire(SseEvent.projectUpdated(task.projectId));
        }
    }

    @Transactional
    void completeTask(Long taskId, String output, boolean success, String executionLog) {
        TaskEntity task = TaskEntity.findById(taskId);
        if (task == null) return;

        task.status = success ? "Completed" : "Failed";
        task.output = output;
        task.executionLog = executionLog;
        task.completedOn = Instant.now();

        String statusText = success ? "completed" : "failed";
        LOG.infof("Script task %d %s", taskId, statusText);

        logActivity(task.projectId, taskId, task.eventId, "task-" + statusText,
                "Script task " + statusText + ": " + task.actionType);

        String threadContent = "Script task " + statusText + ": " + task.actionType;
        if (output != null && !output.isBlank()) {
            threadContent += "\n\nOutput:\n" + output;
        }
        addThreadEntry(task.projectId, "system", "result", threadContent);

        sseEvents.fire(SseEvent.taskUpdated(task.projectId, taskId, task.status));
        sseEvents.fire(SseEvent.threadEntry(task.projectId));
        if (!success) {
            sseEvents.fire(SseEvent.notification(
                    "Script task failed: " + task.actionType, "error"));
        }

        // Complete the trace (async traces are finalized here)
        if (task.traceId != null) {
            try {
                // Complete the task node with final status
                TraceNodeEntity taskNode = TraceNodeEntity.find(
                        "traceId = ?1 and nodeType = 'task' and entityType = 'task' and entityId = ?2",
                        task.traceId, task.id).firstResult();
                if (taskNode != null) {
                    traceService.completeNode(taskNode.id, statusText);
                }

                // Workflow runs own their trace lifecycle: WorkflowExecutionService
                // completes the trace when the run reaches a terminal state.
                if (task.workflowRunId == null) {
                    traceService.completeTrace(task.traceId, success ? "completed" : "failed");
                }
            } catch (Exception e) {
                LOG.warnf(e, "Failed to complete trace for script task %d", taskId);
            }
        }

        updateProjectStatusAfterTask(task.projectId);

        // Advance workflow if this task is part of one
        if (task.workflowRunId != null) {
            workflowExecutionService.onTaskCompleted(task.id);
        }
    }

    @Transactional
    void failTask(Long taskId, String reason) {
        completeTask(taskId, reason, false, null);
    }

    private void updateProjectStatusAfterTask(Long projectId) {
        long activeTasks = TaskEntity.count(
                "projectId = ?1 and (status = 'InProgress' or status = 'AwaitingInput')",
                projectId);
        if (activeTasks == 0) {
            ProjectEntity project = ProjectEntity.findById(projectId);
            if (project != null && "InProgress".equals(project.status)) {
                project.status = "Idle";
                project.updatedOn = Instant.now();
            }
        }
    }

    private void logActivity(Long projectId, Long taskId, Long eventId,
                              String entryType, String summary) {
        ActivityLogEntity log = new ActivityLogEntity();
        log.projectId = projectId;
        log.taskId = taskId;
        log.eventId = eventId;
        log.entryType = entryType;
        log.summary = summary != null && summary.length() > 1024
                ? summary.substring(0, 1021) + "..."
                : summary;
        log.createdOn = Instant.now();
        log.persist();
    }

    private void addThreadEntry(Long projectId, String authorType, String entryType,
                                String content) {
        ThreadEntryEntity entry = new ThreadEntryEntity();
        entry.projectId = projectId;
        entry.authorType = authorType;
        entry.entryType = entryType;
        entry.content = content;
        entry.createdOn = Instant.now();
        entry.persist();
    }

    private record RunResult(int exitCode, String output, String executionLog) {}
}
