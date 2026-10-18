package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActionTypeField;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.services.WorkspaceService;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.axiom.core.logging.LogContext;
import io.apitomy.axiom.core.services.EnvironmentResolver;
import io.apitomy.axiom.core.services.InputBindingResolver;
import io.apitomy.axiom.core.services.ShellContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.apitomy.axiom.core.util.BoundedProcessRunner;
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
     * The maximum run time of a script or node action ({@code axiom.script.timeout-seconds}).
     *
     * @return the timeout in seconds
     */
    int timeoutSeconds() {
        return timeoutSeconds;
    }

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

    /**
     * Runs a script action type with explicit input values, outside any task (used for test runs).
     *
     * @param actionType the action type (may be transient); its {@code scriptTemplate} must not be blank
     * @param inputs     input values by name
     * @param subject    the subject whose workspace to run in, or {@code null} for a temporary directory
     * @param runId      identifier exposed to the script as {@code AXIOM_TASK_ID}
     * @return the run
     * @throws IOException          if files cannot be written
     * @throws InterruptedException if interrupted while waiting for the process
     */
    ActionRun runScriptAction(ActionTypeEntity actionType, Map<String, Object> inputs, SubjectEntity subject,
                              String runId) throws IOException, InterruptedException {
        Map<String, String> placeholderEnv = new LinkedHashMap<>();
        String script = substitutePlaceholders(actionType.scriptTemplate, inputs, actionType.inputs,
                placeholderEnv);
        Path scriptFile = Files.createTempFile("axiom-script-", ".sh");
        WorkingDirectory workDir = workingDirectory(subject);
        try {
            Files.writeString(scriptFile, script);
            scriptFile.toFile().setExecutable(true);
            ProcessBuilder pb = scriptProcess(scriptFile, workDir.path(),
                    buildBaseEnvironment(runId, workDir.path()));
            applyActionEnvironment(pb, actionType);
            // Inject substituted placeholder values as environment variables. The script references them via
            // quoted shell expansions ("$AXIOM_*"), so values containing shell metacharacters are literal data
            // (see substitutePlaceholders / issue #267). Applied last so the reserved AXIOM_* names win.
            pb.environment().putAll(placeholderEnv);
            Instant startTime = Instant.now();
            Process process = startScript(pb);
            CapturedOutput captured = capture(process, timeoutSeconds);
            long durationMs = java.time.Duration.between(startTime, Instant.now()).toMillis();
            return toActionRun(captured, "Script", timeoutSeconds, durationMs, script);
        } finally {
            Files.deleteIfExists(scriptFile);
            workDir.cleanUp();
        }
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
        Map<String, Object> inputs = task.workflowRunId != null
                ? InputBindingResolver.parseInputs(task.input, objectMapper)
                : Map.of();
        LOG.infof("Executing script for task %d (%s), timeout %ds", task.id, task.actionType, timeoutSeconds);
        Instant startTime = Instant.now();
        ActionRun run = runScriptAction(actionType, inputs, subject, String.valueOf(task.id));
        String executionLog = buildExecutionLog(task, "Script Template", actionType.scriptTemplate,
                run.resolvedScript(), run.stdout(), run.stderr(), run.exitCode(), startTime, run.durationMs());
        return new RunResult(run.exitCode(), run.output(), executionLog);
    }

    /** Parser that rejects trailing content, so a candidate must run to the end of stdout. */
    private static final ObjectMapper STRICT_JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Separately captured result of a script process.
     *
     * @param stdout          what the script wrote to stdout (head and tail when truncated)
     * @param stderr          what the script wrote to stderr (head and tail when truncated)
     * @param exitCode        the exit code ({@link BoundedProcessRunner#TIMEOUT_EXIT_CODE} when the script timed
     *                        out)
     * @param finished        false when the script was killed after the timeout
     * @param stdoutTruncated true when stdout exceeded {@link BoundedProcessRunner#DEFAULT_MAX_OUTPUT_BYTES}
     * @param stderrTruncated true when stderr exceeded {@link BoundedProcessRunner#DEFAULT_MAX_OUTPUT_BYTES}
     */
    record CapturedOutput(String stdout, String stderr, int exitCode, boolean finished,
                          boolean stdoutTruncated, boolean stderrTruncated) {
    }

    /** Reported when a truncated stdout does not end with the script's JSON result. */
    static final String TRUNCATED_WITHOUT_RESULT = "stdout exceeded 1 MiB and no JSON result was found at the end;"
            + " write logs to stderr and print the JSON result last";

    /**
     * Starts a script process whose stdin Axiom does not feed; stdin is closed so the script cannot block on it.
     *
     * @param pb the process builder
     * @return the started process
     * @throws IOException if the process cannot be started
     */
    static Process startScript(ProcessBuilder pb) throws IOException {
        return BoundedProcessRunner.start(pb);
    }

    /**
     * Turns captured output into an action run: marks a timeout in stdout, extracts the JSON result, and fails the
     * run when stdout was truncated and no JSON result survived at its end (otherwise ~1 MiB of logs would be
     * reported as the output).
     *
     * @param captured       the captured output
     * @param label          what ran, for the timeout message (e.g. "Script", "Node action")
     * @param timeoutSeconds the timeout that applied
     * @param durationMs     the run time
     * @param resolvedScript the resolved script text, or null
     * @return the action run
     */
    static ActionRun toActionRun(CapturedOutput captured, String label, long timeoutSeconds, long durationMs,
                                 String resolvedScript) {
        String stdout = captured.stdout();
        String stderr = captured.stderr();
        int exitCode = captured.exitCode();
        if (!captured.finished()) {
            stdout = stdout + "\n[" + label + " timed out after " + timeoutSeconds + "s]";
        }
        // The output is the last JSON object the script printed to stdout; logs belong on stderr.
        String result = findScriptResult(stdout);
        if (captured.finished() && captured.stdoutTruncated() && result == null) {
            exitCode = exitCode == 0 ? 1 : exitCode;
            stderr = (stderr == null || stderr.isEmpty() ? "" : stderr + "\n") + TRUNCATED_WITHOUT_RESULT;
        }
        return new ActionRun(exitCode, stdout, stderr, result != null ? result : stdout, durationMs,
                captured.finished(), resolvedScript);
    }

    /**
     * Reads stdout and stderr concurrently (so neither pipe can fill up and block the script), waits up to the
     * timeout, and kills the script and its child processes if it is still running. Each stream is capped at
     * {@link BoundedProcessRunner#DEFAULT_MAX_OUTPUT_BYTES}.
     *
     * @param process        the started process
     * @param timeoutSeconds maximum run time
     * @return the captured output
     * @throws InterruptedException if interrupted while waiting
     */
    static CapturedOutput capture(Process process, long timeoutSeconds) throws InterruptedException {
        BoundedProcessRunner.Result result = BoundedProcessRunner.await(process,
                java.time.Duration.ofSeconds(timeoutSeconds), BoundedProcessRunner.DEFAULT_MAX_OUTPUT_BYTES);
        int exitCode = result.timedOut() ? BoundedProcessRunner.TIMEOUT_EXIT_CODE : result.exitCode();
        return new CapturedOutput(result.stdout(), result.stderr(), exitCode, !result.timedOut(),
                result.stdoutTruncated(), result.stderrTruncated());
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
        String result = findScriptResult(stdout);
        return result != null ? result : stdout;
    }

    /**
     * Finds the last JSON object at the end of stdout (see {@link #extractScriptOutput(String)}).
     *
     * @param stdout the script's stdout
     * @return the JSON object text, or null when stdout does not end with one
     */
    static String findScriptResult(String stdout) {
        if (stdout == null) {
            return null;
        }
        String trimmed = stdout.strip();
        if (!trimmed.endsWith("}")) {
            return null;
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
        return null;
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

    /**
     * Injects the action type's declared environment into a process, with {@code ${secret:NAME}} references
     * resolved. Secrets are never injected automatically.
     *
     * @param pb         the process builder to add variables to
     * @param actionType the action type being run
     * @throws io.apitomy.axiom.core.services.MissingSecretException when a referenced secret does not exist
     */
    void applyActionEnvironment(ProcessBuilder pb, ActionTypeEntity actionType) {
        pb.environment().putAll(environmentResolver.resolve(actionType.environment));
    }

    /**
     * Builds the human-readable execution log stored on a task.
     *
     * @param task          the task that ran
     * @param sourceLabel   heading for the source section (e.g. "Script Template" or "Code")
     * @param source        the source that was run
     * @param resolvedSource the resolved script, or {@code null} to omit the "Resolved Script" section
     * @param output        the process stdout
     * @param stderr        the process stderr
     * @param exitCode      the exit code
     * @param startTime     when the run started
     * @param durationMs    run time in milliseconds
     * @return the execution log
     */
    static String buildExecutionLog(TaskEntity task, String sourceLabel, String source, String resolvedSource,
                                    String output, String stderr, int exitCode, Instant startTime,
                                    long durationMs) {
        return buildExecutionLog("Script Execution Log", task, sourceLabel, source, resolvedSource, output,
                stderr, exitCode, startTime, durationMs);
    }

    /**
     * Builds an execution log with the given header title.
     *
     * @param title the header title, e.g. {@code Script Execution Log}
     * @return the execution log
     */
    static String buildExecutionLog(String title, TaskEntity task, String sourceLabel, String source, String resolvedSource,
                                    String output, String stderr, int exitCode, Instant startTime,
                                    long durationMs) {
        StringBuilder log = new StringBuilder();
        log.append("═══════════════════════════════════════════════════════════════\n");
        log.append("  ").append(title).append("\n");
        log.append("═══════════════════════════════════════════════════════════════\n");
        log.append("  Task:        #").append(task.id).append("\n");
        log.append("  Action Type: ").append(task.actionType).append("\n");
        log.append("  Subject:     #").append(task.subjectId).append("\n");
        log.append("  Started:     ").append(startTime).append("\n");
        log.append("  Duration:    ").append(durationMs).append(" ms\n");
        log.append("  Exit Code:   ").append(exitCode).append("\n");
        log.append("  Status:      ").append(exitCode == 0 ? "SUCCESS" : "FAILED").append("\n");
        log.append("═══════════════════════════════════════════════════════════════\n\n");

        log.append(sectionHeader(sourceLabel)).append("\n");
        log.append(source.strip()).append("\n\n");

        if (resolvedSource != null) {
            log.append("── Resolved Script ────────────────────────────────────────────\n");
            log.append(resolvedSource.strip()).append("\n\n");
        }

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

    /** Renders a section heading such as {@code ── Code ───…}, padded with rules to the log width. */
    private static String sectionHeader(String label) {
        String head = "── " + label + " ";
        return head + "─".repeat(Math.max(0, 63 - head.length()));
    }

    /**
     * Builds the plumbing environment variables every script receives: the API base URL, the task ID
     * and the script's working directory. No subject data is exposed; scripts receive data only
     * through declared inputs.
     *
     * @param task    the task being executed
     * @param workDir the directory the script runs in
     * @return the plumbing environment variables
     */
    Map<String, String> buildBaseEnvironment(TaskEntity task, Path workDir) {
        return buildBaseEnvironment(String.valueOf(task.id), workDir);
    }

    /**
     * Builds the plumbing environment variables for a run that is identified by {@code runId} instead of a
     * task: the API base URL, the run id (as {@code AXIOM_TASK_ID}) and the working directory.
     *
     * @param runId   the task id or test-run id
     * @param workDir the directory the script runs in
     * @return the plumbing environment variables
     */
    Map<String, String> buildBaseEnvironment(String runId, Path workDir) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("AXIOM_API_URL", "http://localhost:" + httpPort + "/api/v1");
        env.put("AXIOM_TASK_ID", runId);
        env.put("AXIOM_WORK_DIR", workDir.toString());
        return env;
    }

    /**
     * The directory a script runs in.
     *
     * @param path      the directory
     * @param temporary true when it was created for this run and must be deleted afterwards
     */
    record WorkingDirectory(Path path, boolean temporary) {

        /** Deletes the directory (recursively) if it was created for this run; a subject workspace is kept. */
        void cleanUp() {
            if (!temporary) {
                return;
            }
            try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                LOG.debugf("Failed to delete temporary script directory %s: %s", path, e.getMessage());
            }
        }
    }

    /**
     * Chooses where a script runs: the subject's workspace (created if needed), or a new temporary directory
     * when the task has no subject. Scripts never run in the server's own directory, so tools like {@code gh}
     * or {@code git} cannot pick up the repository Axiom itself runs from.
     *
     * @param subject the task's subject, may be {@code null}
     * @return the working directory
     * @throws IOException if a temporary directory cannot be created
     */
    WorkingDirectory workingDirectory(SubjectEntity subject) throws IOException {
        if (subject != null) {
            return new WorkingDirectory(workspaceService.ensureWorkspace(subject), false);
        }
        return new WorkingDirectory(Files.createTempDirectory("axiom-script-dir-"), true);
    }

    /**
     * Builds the process for a script file, running in {@code workDir} with the given extra environment.
     *
     * @param scriptFile the script to run
     * @param workDir    the working directory
     * @param env        environment variables to add
     * @return the process builder
     */
    static ProcessBuilder scriptProcess(Path scriptFile, Path workDir, Map<String, String> env) {
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", scriptFile.toString()).directory(workDir.toFile());
        pb.environment().putAll(env);
        return pb;
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
        return substitutePlaceholders(template, values, inputs, env);
    }

    /**
     * Resolves {@code {{inputs.NAME}}} placeholders using the given input values; see
     * {@link #substitutePlaceholders(String, TaskEntity, List, Map)} for how values are bound.
     *
     * @param template the script template
     * @param values   input values by name
     * @param inputs   the declared inputs, may be {@code null}
     * @param env      receives the environment variables the resolved script references
     * @return the resolved script
     */
    String substitutePlaceholders(String template, Map<String, Object> values,
                                  List<ActionTypeField> inputs, Map<String, String> env) {
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
     * Renders {@code template}, replacing each placeholder with a shell reference suited to the quoting
     * context it appears in (see {@link ShellContext} and {@link #substitutePlaceholders(String, TaskEntity, List, Map)}). Environment
     * variables are added to {@code env} only for placeholders that are actually present.
     */
    private String render(String template, Map<String, String> bindings,
                          Map<String, String> values, Map<String, String> env) {
        ShellContext.Context[] contexts = ShellContext.analyze(template);
        StringBuilder out = new StringBuilder(template.length() + 32);
        int i = 0;
        int n = template.length();
        while (i < n) {
            if (template.charAt(i) == '{' && i + 1 < n && template.charAt(i + 1) == '{') {
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
                    out.append(reference(envName, contexts[i]));
                    i += match.length();
                    continue;
                }
            }
            out.append(template.charAt(i));
            i++;
        }
        return out.toString();
    }

    /**
     * Builds the shell reference for {@code envName} appropriate to the quoting context it replaces a
     * placeholder in. The value is always passed through the environment, so it is never parsed as code.
     */
    private String reference(String envName, ShellContext.Context context) {
        return switch (context) {
            // Already inside double quotes: expansion is safe as-is.
            case DOUBLE -> "${" + envName + "}";
            // Inside single quotes bash does not expand; break out, expand within double quotes, then reopen.
            case SINGLE -> "'\"${" + envName + "}\"'";
            // Heredoc body: expansions are not word-split or executed, and quotes would be literal text.
            // (In a quoted heredoc nothing expands at all; the validator rejects placeholders there.)
            case HEREDOC, QUOTED_HEREDOC -> "${" + envName + "}";
            // Unquoted: quote the expansion so whitespace does not word-split it.
            case UNQUOTED -> "\"${" + envName + "}\"";
        };
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
        if (task == null || !TaskFinishGuard.lockRunAndCheckUnfinished(task)) {
            return;
        }

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

    /**
     * Result of running a script or node action.
     *
     * @param exitCode     the process exit code
     * @param output       the task output
     * @param executionLog the execution log, may be {@code null}
     */
    record RunResult(int exitCode, String output, String executionLog) {}

    /**
     * Result of running an action type's script or code directly, without a task.
     *
     * @param exitCode       the exit code ({@code 1} when the run timed out or could not start)
     * @param stdout         everything written to stdout
     * @param stderr         everything written to stderr
     * @param output         the last JSON object printed to stdout, or {@code null}
     * @param durationMs     how long the process ran
     * @param finished       false when the process was killed after the timeout
     * @param resolvedScript the script after placeholder substitution, or {@code null} for node actions
     */
    public record ActionRun(int exitCode, String stdout, String stderr, String output, long durationMs,
                            boolean finished, String resolvedScript) {
    }
}
