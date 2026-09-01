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
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Executes script-mode action types by running a user-defined bash script.
 * The script template is stored on the ActionTypeEntity and supports
 * placeholder substitution for project/event context and the API base URL.
 */
@ApplicationScoped
public class ScriptExecutionService {

    private static final Logger LOG = Logger.getLogger(ScriptExecutionService.class);

    /**
     * Workflow input names that can be safely bound as shell environment
     * variables. Mirrors the identifier rule enforced by ActionTypeValidator.
     */
    private static final Pattern VALID_INPUT_NAME =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

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
        Map<String, String> placeholderEnv = new LinkedHashMap<>();
        String script = substitutePlaceholders(actionType.scriptTemplate, task, project,
                placeholderEnv);
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

            // Inject substituted placeholder values as environment variables. The
            // script references them via quoted shell expansions ("$AXIOM_*"), so
            // values that contain shell metacharacters are treated as literal data
            // rather than being executed (see substitutePlaceholders / issue #267).
            // Applied last so the reserved AXIOM_* names are authoritative.
            pb.environment().putAll(placeholderEnv);

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

    /**
     * Resolves {@code {{...}}} placeholders in a script template.
     *
     * <p>To guard against shell injection, placeholder <em>values</em> are never
     * inlined into the script text. Instead each value is exported as an
     * environment variable (collected into {@code env}) and the placeholder is
     * replaced with a shell expansion of that variable — e.g.
     * {@code {{managerInput}}} becomes a reference to {@code AXIOM_MANAGER_INPUT}.
     * Because bash does not re-parse the contents of a variable expansion for
     * command substitution, a value containing {@code $(...)}, backticks,
     * {@code ;}, {@code &&}, newlines, etc. is treated as literal data rather
     * than being executed (issue #267).
     *
     * <p>The exact form of the injected reference is chosen based on the shell
     * quoting context in which the placeholder appears, so that values containing
     * whitespace survive as a single argument regardless of how the template
     * quotes the placeholder:
     * <ul>
     *   <li><b>Unquoted</b> ({@code echo {{x}}}) &rarr; {@code "${AXIOM_X}"} —
     *       the reference is quoted so whitespace does not word-split it.</li>
     *   <li><b>Inside double quotes</b> ({@code echo "a: {{x}}"}) &rarr;
     *       {@code ${AXIOM_X}} — the surrounding quotes already prevent
     *       word-splitting, so no extra quotes are added (adding them would
     *       break the enclosing string and re-introduce word-splitting).</li>
     *   <li><b>Inside single quotes</b> ({@code echo '{{x}}'}) &rarr;
     *       {@code '"${AXIOM_X}"'} — single quotes suppress expansion, so the
     *       reference temporarily closes the single-quoted region, expands the
     *       value inside double quotes, then reopens it.</li>
     * </ul>
     *
     * @param template the raw script template
     * @param task     the task providing context values
     * @param project  the project providing context values, may be {@code null}
     * @param env      output map that receives the environment variables the
     *                 resolved script references; the caller must inject these
     *                 into the script's process environment
     * @return the resolved script with placeholders replaced by shell references
     */
    String substitutePlaceholders(String template, TaskEntity task,
                                           ProjectEntity project, Map<String, String> env) {
        String apiBaseUrl = "http://localhost:" + httpPort + "/api/v1";
        String workDir = System.getProperty("user.home")
                + "/.axiom/workspaces/project-" + task.projectId;

        // Collect every candidate placeholder and its target environment
        // variable. Longer placeholders are matched first during rendering so
        // that e.g. {{inputs.title}} wins over any shorter prefix match.
        Map<String, String> bindings = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        addBinding(bindings, values, "{{projectId}}", "AXIOM_PROJECT_ID", str(task.projectId));
        addBinding(bindings, values, "{{eventId}}", "AXIOM_EVENT_ID", str(task.eventId));
        addBinding(bindings, values, "{{taskId}}", "AXIOM_TASK_ID", str(task.id));
        addBinding(bindings, values, "{{ref}}", "AXIOM_REF",
                project != null && project.ref != null ? project.ref : "");
        addBinding(bindings, values, "{{repository}}", "AXIOM_REPOSITORY",
                project != null && project.repository != null ? project.repository : "");
        addBinding(bindings, values, "{{projectName}}", "AXIOM_PROJECT_NAME",
                project != null && project.name != null ? project.name : "");
        addBinding(bindings, values, "{{managerInput}}", "AXIOM_MANAGER_INPUT",
                task.input != null ? task.input : "");
        addBinding(bindings, values, "{{apiBaseUrl}}", "AXIOM_API_URL", apiBaseUrl);
        addBinding(bindings, values, "{{workDir}}", "AXIOM_WORK_DIR", workDir);

        // Bind named workflow inputs as {{inputs.NAME}} (workflow tasks only).
        if (task.workflowRunId != null && task.input != null && !task.input.isBlank()) {
            try {
                Map<String, Object> inputs = objectMapper.readValue(
                        task.input, new TypeReference<Map<String, Object>>() {});
                for (Map.Entry<String, Object> e : inputs.entrySet()) {
                    String key = e.getKey();
                    if (!VALID_INPUT_NAME.matcher(key).matches()) {
                        // Not a valid shell identifier — cannot bind as an env var
                        // safely; leave the placeholder untouched.
                        continue;
                    }
                    Object v = e.getValue();
                    String rendered;
                    if (v == null) {
                        rendered = "";
                    } else if (v instanceof String s) {
                        rendered = s;
                    } else {
                        try {
                            rendered = objectMapper.writeValueAsString(v);
                        } catch (Exception ex) {
                            LOG.debugf("Failed to serialize input '%s' to JSON, using toString()", key);
                            rendered = String.valueOf(v);
                        }
                    }
                    addBinding(bindings, values, "{{inputs." + key + "}}",
                            "AXIOM_INPUT_" + key, rendered);
                }
            } catch (Exception ignored) {
                // Non-object input — leave {{inputs.*}} placeholders untouched.
            }
        }

        return render(template, bindings, values, env);
    }

    /**
     * Records a candidate placeholder-to-environment-variable binding. The value
     * is only exported (and the env var only referenced) if the placeholder is
     * actually encountered while rendering the template.
     */
    private void addBinding(Map<String, String> bindings, Map<String, String> values,
                            String placeholder, String envName, String value) {
        bindings.put(placeholder, envName);
        values.put(placeholder, value != null ? value : "");
    }

    /**
     * Renders {@code template} in a single pass, tracking bash quoting context so
     * each placeholder is replaced with a shell reference appropriate to where it
     * appears (see {@link #substitutePlaceholders}). Environment variables are
     * added to {@code env} only for placeholders that are actually present.
     */
    private String render(String template, Map<String, String> bindings,
                          Map<String, String> values, Map<String, String> env) {
        StringBuilder out = new StringBuilder(template.length() + 32);
        int i = 0;
        int n = template.length();
        // Current quoting context: 0 = unquoted, '\'' = single, '"' = double.
        char quote = 0;
        while (i < n) {
            char c = template.charAt(i);

            // Attempt to match a placeholder at this position (longest wins).
            if (c == '{' && i + 1 < n && template.charAt(i + 1) == '{') {
                String match = null;
                for (String placeholder : bindings.keySet()) {
                    if (template.startsWith(placeholder, i)
                            && (match == null || placeholder.length() > match.length())) {
                        match = placeholder;
                    }
                }
                if (match != null) {
                    String envName = bindings.get(match);
                    env.put(envName, values.get(match));
                    out.append(reference(envName, quote));
                    i += match.length();
                    continue;
                }
            }

            // Not a placeholder — advance the quoting state machine. Injected
            // references are always quote-balanced, so tracking only the
            // original template characters keeps the context accurate.
            if (quote == 0) {
                if (c == '\'') {
                    quote = '\'';
                } else if (c == '"') {
                    quote = '"';
                } else if (c == '\\' && i + 1 < n) {
                    // Backslash escapes the next character outside single quotes.
                    out.append(c).append(template.charAt(i + 1));
                    i += 2;
                    continue;
                }
            } else if (quote == '"') {
                if (c == '"') {
                    quote = 0;
                } else if (c == '\\' && i + 1 < n) {
                    out.append(c).append(template.charAt(i + 1));
                    i += 2;
                    continue;
                }
            } else { // single quotes: no escapes, only a closing quote ends it
                if (c == '\'') {
                    quote = 0;
                }
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Builds the shell reference for {@code envName} appropriate to the current
     * quoting context {@code quote} (0 = unquoted, {@code '} = single-quoted,
     * {@code "} = double-quoted). See {@link #substitutePlaceholders} for the
     * rationale behind each form.
     */
    private String reference(String envName, char quote) {
        if (quote == '"') {
            // Already inside double quotes: expansion is safe as-is.
            return "${" + envName + "}";
        }
        if (quote == '\'') {
            // Inside single quotes bash does not expand; break out, expand within
            // double quotes, then reopen the single-quoted region.
            return "'\"${" + envName + "}\"'";
        }
        // Unquoted: quote the expansion so whitespace does not word-split it.
        return "\"${" + envName + "}\"";
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
