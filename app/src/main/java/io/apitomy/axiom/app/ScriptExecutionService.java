package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActionTypeField;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.services.WorkspaceService;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.axiom.core.logging.LogContext;
import io.apitomy.axiom.core.services.EncryptionService;
import io.apitomy.axiom.core.services.EnvironmentResolver;
import io.apitomy.axiom.core.services.InputBindingResolver;
import io.apitomy.axiom.core.tracing.TraceService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Executes script-mode action types by running a user-defined bash script.
 * The script template is stored on the ActionTypeEntity and receives data only
 * through its declared inputs ({@code {{inputs.NAME}}} placeholders).
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
    TaskTraceFinalizer taskTraceFinalizer;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    WorkspaceService workspaceService;

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
     * Executes the script for a task asynchronously, using the supplied subject
     * to locate the workspace directory. If {@code subject} is {@code null}, the
     * subject is loaded from the database — callers that already hold a managed
     * entity (e.g. inside an uncommitted transaction) should pass it directly to
     * avoid a race between the async thread and the transaction commit.
     *
     * @param task    the task whose action type defines the script to run
     * @param subject the subject whose workspace the script runs against, or {@code null}
     */
    public void executeScript(TaskEntity task, SubjectEntity subject) {
        try (LogContext ignored = TaskExecutionService.taskLogContext(task)) {
            markTaskInProgress(task.id);
        }

        // Runs on a common ForkJoinPool worker; the logging context is applied (and
        // removed again) inside the async body so nothing leaks to the pooled thread.
        CompletableFuture.runAsync(() -> {
            Arc.container().requestContext().activate();
            try (LogContext ignored = TaskExecutionService.taskLogContext(task)) {
                RunResult result = runScript(task, subject);
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

    private RunResult runScript(TaskEntity task, SubjectEntity subject)
            throws IOException, InterruptedException {
        ActionTypeEntity actionType = ActionTypeEntity.find("name", task.actionType).firstResult();
        if (actionType == null || actionType.scriptTemplate == null
                || actionType.scriptTemplate.isBlank()) {
            return new RunResult(1,
                    "No script template configured for action type: " + task.actionType,
                    null);
        }

        if (subject == null) {
            subject = SubjectEntity.findById(task.subjectId);
        }
        Map<String, String> placeholderEnv = new LinkedHashMap<>();
        String script = substitutePlaceholders(actionType.scriptTemplate, task,
                actionType.inputs, placeholderEnv);
        Instant startTime = Instant.now();

        Path scriptFile = Files.createTempFile("axiom-script-", ".sh");
        try {
            Files.writeString(scriptFile, script);
            scriptFile.toFile().setExecutable(true);

            LOG.infof("Executing script for task %d (%s), timeout %ds",
                    task.id, task.actionType, timeoutSeconds);

            ProcessBuilder pb = new ProcessBuilder("/bin/bash", scriptFile.toString());
            pb.environment().putAll(buildBaseEnvironment(task, subject));

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
            CapturedOutput captured = capture(process, timeoutSeconds);
            Instant endTime = Instant.now();
            long durationMs = java.time.Duration.between(startTime, endTime).toMillis();

            int exitCode = captured.exitCode();
            String stdout = captured.stdout();
            if (!captured.finished()) {
                stdout = stdout + "\n[Script timed out after " + timeoutSeconds + "s]";
            }
            // The task output is the last JSON object the script printed to stdout; logs belong on stderr.
            String output = extractScriptOutput(stdout);

            String executionLog = buildExecutionLog(task, actionType.scriptTemplate,
                    script, stdout, captured.stderr(), exitCode, startTime, durationMs);

            return new RunResult(exitCode, output, executionLog);
        } finally {
            Files.deleteIfExists(scriptFile);
        }
    }

    /** Parser that rejects trailing content, so a candidate must run to the end of stdout. */
    private static final ObjectMapper STRICT_JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Separately captured result of a script process.
     *
     * @param stdout   everything the script wrote to stdout
     * @param stderr   everything the script wrote to stderr
     * @param exitCode the exit code ({@code 1} when the script timed out)
     * @param finished false when the script was killed after the timeout
     */
    record CapturedOutput(String stdout, String stderr, int exitCode, boolean finished) {
    }

    /**
     * Reads stdout and stderr concurrently (so neither pipe can fill up and block the script), waits up to the
     * timeout, and kills the script and its child processes if it is still running.
     *
     * @param process        the started process
     * @param timeoutSeconds maximum run time
     * @return the captured output
     * @throws InterruptedException if interrupted while waiting
     */
    static CapturedOutput capture(Process process, long timeoutSeconds) throws InterruptedException {
        java.util.concurrent.CompletableFuture<String> stdout = readAsync(process.getInputStream());
        java.util.concurrent.CompletableFuture<String> stderr = readAsync(process.getErrorStream());
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        int exitCode = finished ? process.exitValue() : 1;
        return new CapturedOutput(join(stdout), join(stderr), exitCode, finished);
    }

    private static java.util.concurrent.CompletableFuture<String> readAsync(java.io.InputStream in) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    private static String join(java.util.concurrent.CompletableFuture<String> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            future.cancel(true);
            return "";
        }
    }

    /**
     * Extracts a script's outputs from its stdout: the last JSON object printed, which may span several lines
     * (e.g. pretty-printed by {@code jq}) and must be the final thing on stdout. Earlier lines are treated as
     * logs. When stdout does not end with a JSON object it is returned unchanged.
     *
     * @param stdout the script's stdout
     * @return the JSON object text, or {@code stdout} when none is found
     */
    static String extractScriptOutput(String stdout) {
        if (stdout == null) {
            return "";
        }
        String trimmed = stdout.strip();
        if (!trimmed.endsWith("}")) {
            return stdout;
        }
        for (int start = trimmed.lastIndexOf('{'); start >= 0; start = trimmed.lastIndexOf('{', start - 1)) {
            if (!startsLine(trimmed, start)) {
                continue;
            }
            String candidate = trimmed.substring(start);
            try {
                if (STRICT_JSON.readTree(candidate).isObject()) {
                    return candidate;
                }
            } catch (IOException e) {
                // not a complete object from here; try an earlier line
            }
            if (start == 0) {
                break;
            }
        }
        return stdout;
    }

    private static boolean startsLine(String text, int index) {
        for (int i = index - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n') {
                return true;
            }
            if (c != ' ' && c != '\t' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    private String buildExecutionLog(TaskEntity task, String template, String resolvedScript,
                                      String output, String stderr, int exitCode, Instant startTime,
                                      long durationMs) {
        StringBuilder log = new StringBuilder();
        log.append("═══════════════════════════════════════════════════════════════\n");
        log.append("  Script Execution Log\n");
        log.append("═══════════════════════════════════════════════════════════════\n");
        log.append("  Task:        #").append(task.id).append("\n");
        log.append("  Action Type: ").append(task.actionType).append("\n");
        log.append("  Subject:     #").append(task.subjectId).append("\n");
        log.append("  Started:     ").append(startTime).append("\n");
        log.append("  Duration:    ").append(durationMs).append(" ms\n");
        log.append("  Exit Code:   ").append(exitCode).append("\n");
        log.append("  Status:      ").append(exitCode == 0 ? "SUCCESS" : "FAILED").append("\n");
        log.append("═══════════════════════════════════════════════════════════════\n\n");

        log.append("── Script Template ────────────────────────────────────────────\n");
        log.append(template.strip()).append("\n\n");

        log.append("── Resolved Script ────────────────────────────────────────────\n");
        log.append(resolvedScript.strip()).append("\n\n");

        log.append("── Output (stdout) ────────────────────────────────────────────\n");
        if (output != null && !output.isBlank()) {
            log.append(output.strip()).append("\n");
        } else {
            log.append("(no output)\n");
        }

        log.append("\n── Errors (stderr) ────────────────────────────────────────────\n");
        if (stderr != null && !stderr.isBlank()) {
            log.append(stderr.strip()).append("\n");
        } else {
            log.append("(none)\n");
        }

        log.append("\n═══════════════════════════════════════════════════════════════\n");
        return log.toString();
    }

    /**
     * Builds the plumbing environment variables every script receives: the API base URL, the task ID
     * and the subject's workspace directory. No subject data is exposed; scripts receive data only
     * through declared inputs.
     *
     * @param task    the task being executed
     * @param subject the task's subject, may be {@code null}
     * @return the plumbing environment variables
     */
    Map<String, String> buildBaseEnvironment(TaskEntity task, SubjectEntity subject) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("AXIOM_API_URL", "http://localhost:" + httpPort + "/api/v1");
        env.put("AXIOM_TASK_ID", String.valueOf(task.id));
        env.put("AXIOM_WORK_DIR", subject != null
                ? workspaceService.getWorkspacePath(subject).toString()
                : "");
        return env;
    }

    /**
     * Resolves {@code {{inputs.NAME}}} placeholders for the action type's declared inputs in a
     * script template. No other placeholders are recognised.
     *
     * <p>To guard against shell injection, input <em>values</em> are never
     * inlined into the script text. Instead each value is exported as an
     * environment variable {@code AXIOM_INPUT_<name>} (collected into {@code env})
     * and the placeholder is replaced with a shell expansion of that variable.
     * Because bash does not re-parse the contents of a variable expansion for
     * command substitution, a value containing {@code $(...)}, backticks,
     * {@code ;}, {@code &&}, newlines, etc. is treated as literal data rather
     * than being executed (issue #267). Declared inputs without a value (e.g.
     * unmapped optional inputs) render as an empty string.
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
     * @param task     the task whose resolved inputs supply the values (workflow tasks only)
     * @param inputs   the action type's declared inputs, may be {@code null}
     * @param env      output map that receives the environment variables the
     *                 resolved script references; the caller must inject these
     *                 into the script's process environment
     * @return the resolved script with placeholders replaced by shell references
     */
    String substitutePlaceholders(String template, TaskEntity task,
                                  List<ActionTypeField> inputs, Map<String, String> env) {
        Map<String, Object> values = task.workflowRunId != null
                ? InputBindingResolver.parseInputs(task.input, objectMapper)
                : Map.of();

        Map<String, String> bindings = new LinkedHashMap<>();
        Map<String, String> rendered = new LinkedHashMap<>();
        if (inputs != null) {
            for (ActionTypeField input : inputs) {
                if (input.name == null || !VALID_INPUT_NAME.matcher(input.name).matches()) {
                    // Not a valid shell identifier — cannot bind as an env var safely.
                    continue;
                }
                String placeholder = "{{inputs." + input.name + "}}";
                bindings.put(placeholder, "AXIOM_INPUT_" + input.name);
                rendered.put(placeholder,
                        InputBindingResolver.renderValue(values.get(input.name), objectMapper));
            }
        }

        return render(template, bindings, rendered, env);
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

    @Transactional
    void markTaskInProgress(Long taskId) {
        TaskEntity task = TaskEntity.findById(taskId);
        if (task != null) {
            task.status = "InProgress";

            logActivity(task.subjectId, taskId, task.eventId, "task-started",
                    "Script task started: " + task.actionType, task.traceId);
            addThreadEntry(task.subjectId, "system", "update",
                    "Script task started: " + task.actionType);

            sseEvents.fire(SseEvent.taskUpdated(task.subjectId, taskId, "InProgress"));
            sseEvents.fire(SseEvent.subjectUpdated(task.subjectId));
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

        logActivity(task.subjectId, taskId, task.eventId, "task-" + statusText,
                "Script task " + statusText + ": " + task.actionType, task.traceId);

        String threadContent = "Script task " + statusText + ": " + task.actionType;
        if (output != null && !output.isBlank()) {
            threadContent += "\n\nOutput:\n" + output;
        }
        addThreadEntry(task.subjectId, "system", "result", threadContent);

        sseEvents.fire(SseEvent.taskUpdated(task.subjectId, taskId, task.status));
        sseEvents.fire(SseEvent.threadEntry(task.subjectId));
        if (!success) {
            sseEvents.fire(SseEvent.notification(
                    "Script task failed: " + task.actionType, "error"));
        }

        // Complete the task node and (when it was the last open task) the trace
        taskTraceFinalizer.finalizeTaskTrace(task, statusText);

        // Advance workflow if this task is part of one
        if (task.workflowRunId != null) {
            workflowExecutionService.onTaskCompleted(task.id);
        }
    }

    @Transactional
    void failTask(Long taskId, String reason) {
        completeTask(taskId, reason, false, null);
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

    private record RunResult(int exitCode, String output, String executionLog) {}
}
