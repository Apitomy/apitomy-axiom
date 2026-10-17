package io.apitomy.axiom.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActionTypeField;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.services.ActionTypeIoValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs draft (unpublished) action types and tools with sample inputs for the workbench's test runs. Node and
 * script action types run exactly as in a workflow task; tools run the way the MCP tools server runs them.
 */
@ApplicationScoped
public class DraftTestRunner {

    /** Message of an {@code error} result for AI Agent action types. */
    public static final String AGENT_UNSUPPORTED = "Test runs of AI Agent action types are not supported yet.";

    /** Maximum characters of stdout and of stderr kept on a result. */
    public static final int MAX_OUTPUT_CHARS = 64_000;

    /** Tool timeout, matching the MCP tools server. */
    static final long TOOL_TIMEOUT_SECONDS = 30;

    /** Problem reported when an action type exits 0 without printing a trailing JSON object. */
    public static final String NO_JSON_OUTPUT = "Output: stdout did not end with a JSON object.";

    private volatile long toolTimeoutSeconds = TOOL_TIMEOUT_SECONDS;

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    /**
     * Result of a test run.
     *
     * @param status         {@code passed}, {@code failed} or {@code error}
     * @param outputs        the parsed outputs of an action type, or {@code null}
     * @param stdout         standard output (truncated)
     * @param stderr         standard error (truncated)
     * @param exitCode       the exit code, or {@code null} when nothing ran
     * @param durationMs     how long the process ran
     * @param problems       input, parameter and output-validation problems
     * @param message        why the run could not start, or {@code null}
     * @param resolvedScript the script after placeholder substitution (or the code of a node action type), or
     *                       {@code null}
     */
    public record Result(String status, Map<String, Object> outputs, String stdout, String stderr,
                         Integer exitCode, long durationMs, List<String> problems, String message,
                         String resolvedScript) {

        static Result error(String message, List<String> problems) {
            return new Result("error", null, "", "", null, 0, problems, message, null);
        }
    }

    @Inject
    ScriptExecutionService scriptExecutionService;

    @Inject
    NodeExecutionService nodeExecutionService;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Runs a draft action type.
     *
     * @param content the draft action type JSON
     * @param inputs  input values by name
     * @param subject the subject whose workspace to run in, or {@code null}
     * @return the result
     */
    public Result runActionType(JsonNode content, Map<String, Object> inputs, SubjectEntity subject) {
        ActionTypeEntity actionType = toEntity(content);
        String mode = actionType.executionMode;
        if (!"script".equals(mode) && !"node".equals(mode)) {
            return Result.error(AGENT_UNSUPPORTED, List.of());
        }
        String source = "script".equals(mode) ? actionType.scriptTemplate : actionType.codeTemplate;
        if (source == null || source.isBlank()) {
            return Result.error("script".equals(mode) ? "The action type has no script to run."
                    : "The action type has no code to run.", List.of());
        }
        List<String> inputProblems = prefixed("Input: ", ActionTypeIoValidator.validate(actionType.inputs, inputs));
        if (!inputProblems.isEmpty()) {
            return Result.error("Fix the inputs and run again.", inputProblems);
        }
        String runId = "test-" + UUID.randomUUID();
        ScriptExecutionService.ActionRun run;
        try {
            run = "script".equals(mode)
                    ? scriptExecutionService.runScriptAction(actionType, inputs, subject, runId)
                    : nodeExecutionService.runNodeAction(actionType, inputs, subject, runId);
        } catch (IOException e) {
            return Result.error("Could not run the action type: " + e.getMessage(), List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("The test run was interrupted.", List.of());
        }
        if (NodeExecutionService.NODE_MISSING.equals(run.stderr())) {
            return Result.error(NodeExecutionService.NODE_MISSING, List.of());
        }
        Map<String, Object> outputs = parseOutputs(run.output());
        List<String> problems = new ArrayList<>();
        if (!run.finished()) {
            problems.add("Timed out.");
        }
        if (run.exitCode() == 0 && outputs == null && !actionType.outputs.isEmpty()) {
            problems.add(NO_JSON_OUTPUT);
        }
        if (run.exitCode() == 0) {
            problems.addAll(prefixed("Output: ",
                    ActionTypeIoValidator.validate(actionType.outputs, outputs != null ? outputs : Map.of())));
        }
        String status = run.exitCode() == 0 && problems.isEmpty() ? "passed" : "failed";
        return new Result(status, outputs, truncate(run.stdout()), truncate(run.stderr()), run.exitCode(),
                run.durationMs(), problems, null,
                "node".equals(mode) ? actionType.codeTemplate : run.resolvedScript());
    }

    /**
     * Runs a draft tool the way the MCP tools server does.
     *
     * @param content     the draft tool JSON
     * @param params      parameter values by name
     * @param environment environment variables for the run ({@code ${secret:NAME}} allowed); a tool gets no
     *                    other secrets
     * @param subject     the subject whose workspace to run in, or {@code null}
     * @return the result
     * @throws io.apitomy.axiom.core.services.MissingSecretException when a referenced secret does not exist
     */
    public Result runTool(JsonNode content, Map<String, Object> params, Map<String, String> environment,
                          SubjectEntity subject) {
        String template = content.path("scriptTemplate").asText("");
        if (template.isBlank()) {
            return Result.error("The tool has no script to run.", List.of());
        }
        List<JsonNode> parameters = new ArrayList<>();
        content.path("parameters").forEach(parameters::add);
        List<String> problems = new ArrayList<>();
        for (JsonNode p : parameters) {
            String name = p.path("name").asText("");
            if (p.path("required").asBoolean(false) && params.get(name) == null) {
                problems.add("Parameter: Missing required parameter '" + name + "'.");
            }
        }
        if (!problems.isEmpty()) {
            return Result.error("Fix the parameters and run again.", problems);
        }
        Path fileDir = null;
        Path scriptFile = null;
        ScriptExecutionService.WorkingDirectory workDir = null;
        try {
            fileDir = Files.createTempDirectory("axiom-tool-test-");
            String script = renderToolScript(template, parameters, params, fileDir);
            scriptFile = fileDir.resolve("tool.sh");
            Files.writeString(scriptFile, script);
            workDir = scriptExecutionService.workingDirectory(subject);
            ProcessBuilder pb = ScriptExecutionService.scriptProcess(scriptFile, workDir.path(),
                    scriptExecutionService.buildBaseEnvironment("test-" + UUID.randomUUID(), workDir.path()));
            // A tool has no environment of its own: it gets only the variables given for this test run.
            ActionTypeEntity holder = new ActionTypeEntity();
            holder.environment = environment == null || environment.isEmpty() ? null
                    : objectMapper.writeValueAsString(environment);
            scriptExecutionService.applyActionEnvironment(pb, holder);
            Instant start = Instant.now();
            ScriptExecutionService.CapturedOutput captured =
                    ScriptExecutionService.capture(ScriptExecutionService.startScript(pb), toolTimeoutSeconds);
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            List<String> runProblems = captured.finished() ? List.of() : List.of("Timed out.");
            String status = captured.exitCode() == 0 && runProblems.isEmpty() ? "passed" : "failed";
            return new Result(status, null, truncate(captured.stdout()), truncate(captured.stderr()),
                    captured.exitCode(), durationMs, runProblems, null, script);
        } catch (IOException e) {
            return Result.error("Could not run the tool: " + e.getMessage(), List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("The test run was interrupted.", List.of());
        } finally {
            if (workDir != null) {
                workDir.cleanUp();
            }
            if (fileDir != null) {
                new ScriptExecutionService.WorkingDirectory(fileDir, true).cleanUp();
            }
        }
    }

    /**
     * Overrides the tool timeout. For tests.
     *
     * @param seconds the timeout in seconds
     */
    void setToolTimeoutSeconds(long seconds) {
        toolTimeoutSeconds = seconds;
    }

    /**
     * Resolves a tool's script template like {@code tools-server.js}: {@code {{name_file}}} becomes a file
     * holding the value, {@code {{name}}} becomes the raw value, and the placeholders of omitted optional
     * parameters are removed.
     *
     * @param template   the script template
     * @param parameters the declared parameters
     * @param params     the supplied values
     * @param fileDir    where {@code _file} values are written
     * @return the resolved script
     * @throws IOException if a value file cannot be written
     */
    static String renderToolScript(String template, List<JsonNode> parameters, Map<String, Object> params,
                                   Path fileDir) throws IOException {
        String script = template;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String value = stringValue(e.getValue());
            String fileKey = "{{" + e.getKey() + "_file}}";
            if (script.contains(fileKey)) {
                Path file = Files.createTempFile(fileDir, "param-" + e.getKey() + "-", ".txt");
                Files.writeString(file, value);
                script = script.replace(fileKey, file.toString());
            }
            script = script.replace("{{" + e.getKey() + "}}", value);
        }
        for (JsonNode p : parameters) {
            String name = p.path("name").asText("");
            if (!p.path("required").asBoolean(false) && !params.containsKey(name)) {
                script = script.replace("{{" + name + "_file}}", "").replace("{{" + name + "}}", "");
            }
        }
        return script;
    }

    /**
     * Truncates text to {@link #MAX_OUTPUT_CHARS}, appending a marker.
     *
     * @param text the text, may be {@code null}
     * @return the text, never {@code null}
     */
    static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_OUTPUT_CHARS ? text : text.substring(0, MAX_OUTPUT_CHARS) + "\n[truncated]";
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return new ObjectMapper().writeValueAsString(value);
            } catch (IOException e) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    private static List<String> prefixed(String prefix, List<String> messages) {
        return messages.stream().map(m -> prefix + m).toList();
    }

    private Map<String, Object> parseOutputs(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(output, MAP);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Builds a transient (never persisted) action type from draft JSON. Invalid input and output fields are
     * skipped; the draft's own validation reports them.
     */
    private ActionTypeEntity toEntity(JsonNode content) {
        ActionTypeEntity entity = new ActionTypeEntity();
        entity.name = content.path("name").asText("draft");
        entity.executionMode = content.path("executionMode").asText("agent");
        entity.scriptTemplate = content.hasNonNull("scriptTemplate") ? content.path("scriptTemplate").asText() : null;
        entity.codeTemplate = content.hasNonNull("codeTemplate") ? content.path("codeTemplate").asText() : null;
        JsonNode environment = content.path("environment");
        entity.environment = environment.isMissingNode() || environment.isNull() ? null
                : environment.isTextual() ? environment.asText() : environment.toString();
        entity.inputs = fields(content.path("inputs"));
        entity.outputs = fields(content.path("outputs"));
        return entity;
    }

    private static List<ActionTypeField> fields(JsonNode array) {
        List<ActionTypeField> out = new ArrayList<>();
        for (JsonNode f : array) {
            String name = f.path("name").asText("");
            if (!name.isBlank()) {
                out.add(new ActionTypeField(name, f.path("type").asText("string"),
                        f.path("required").asBoolean(false), f.path("description").asText(null)));
            }
        }
        return out;
    }
}
