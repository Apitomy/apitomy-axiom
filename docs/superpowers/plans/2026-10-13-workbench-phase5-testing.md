# Workbench Phase 5: Testing in the Loop — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Let the user (from the workbench UI) and the AI (through the `axiom-workbench` MCP server) run draft
node/script action types and draft tools with sample inputs, and show every run in a shared Test output strip.

**Architecture:** A new `DraftTestRunner` runs draft JSON directly through refactored entry points of
`ScriptExecutionService` / `NodeExecutionService` (no `TaskEntity`) and a Java port of the MCP tools server's
tool-script substitution. `CompositionTestRuns` keeps the last 50 runs per composition in memory, fires a
`composition-test-run` SSE event when a run starts and when it finishes, and is reached through a UI route
and an MCP-only agent route. The UI loads runs on mount, merges SSE updates, and shows them in a collapsible
strip under the centre pane with a **Test…** dialog.

**Tech Stack:** Java 21, Quarkus, Panache, JUnit 5, REST Assured; React 18, PatternFly 6, Vitest; Node MCP SDK.

**Spec:** `docs/superpowers/specs/2026-10-08-workflow-workbench-design.md` (Phase 5, MCP tool table,
template rules, bottom strip).

## Global Constraints

- Contract-first REST: every new route/schema goes into `common/api/src/main/resources/openapi.json` first;
  resource classes implement the generated interface (no `@Path` on impl classes); use generated beans.
- Never run `./build.sh`, `mvn clean` or `mvn install`. Backend tests:
  `mvn -q -pl app -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false` (this also regenerates the
  JAX-RS interfaces). Check results in `app/target/surefire-reports/*.txt`.
- UI (in `ui/`): `timeout 250 npx tsc -b --noEmit`, `timeout 280 npx vitest run [path]`.
- Java: 4-space indent, Javadoc on public methods, explicit types (no ambiguous `var`), JUnit 5.
- Conventional commits, no AI attribution. Commit directly on `feat/workflow-first`; never push.
- Decisions agreed with the user:
  - Only **node** and **script** action types and **tools** can be test-run. AI Agent action types return an
    `error` run with the message `Test runs of AI Agent action types are not supported yet.`
  - Runs appear in a **shared Test output strip** under the centre pane, newest first.
  - The server keeps the **last 50 runs per composition in memory** (lost on restart).
- Out of scope for this phase: showing the AI's `evaluate_expression` calls in the Test output strip (the
  spec lists them; they stay visible as tool calls in the chat), whole-workflow dry runs, and agent-mode runs.
- Statuses: `running`, `passed` (exit 0 and no problems), `failed` (non-zero exit or problems), `error`
  (could not run: agent mode, empty template, missing required input, node missing).
- stdout and stderr stored on a run are truncated to 64,000 characters each, with `\n[truncated]` appended.
- Tool test runs mimic production (`templates/axiom-mcp-server/tools-server.js`): `{{name}}` is replaced by
  the raw value (not escaped), `{{name_file}}` by a temp file holding the value, and placeholders of omitted
  optional parameters are blanked; 30 s timeout; environment = all secrets; working directory = the subject
  workspace or a temporary directory.

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/io/apitomy/axiom/app/ScriptExecutionService.java` (modify) | `ActionRun` record; `runScriptAction(...)` usable without a task |
| `app/src/main/java/io/apitomy/axiom/app/NodeExecutionService.java` (modify) | `runNodeAction(...)` usable without a task |
| `app/src/main/java/io/apitomy/axiom/app/DraftTestRunner.java` (new) | Run draft action type / tool JSON, validate, build a result |
| `app/src/main/java/io/apitomy/axiom/app/composition/CompositionTestRuns.java` (new) | In-memory run store, SSE, author/turn bookkeeping |
| `core/src/main/java/io/apitomy/axiom/core/events/SseEvent.java` (modify) | `compositionTestRun` factory |
| `common/api/src/main/resources/openapi.json` (modify) | 3 routes, 4 schemas |
| `app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java` (modify) | Route implementations |
| `app/src/main/resources/templates/axiom-workbench-mcp/server.js` (modify) | `test_action_type`, `test_tool` |
| `app/src/main/resources/templates/assistant-templates/axiom-workbench.json` (modify) | Allowed tools, rule |
| `ui/src/config/compositions.ts` (modify) | Types, API calls, SSE case |
| `ui/src/pages/workbench/useWorkbench.ts` (modify) | `testRuns` state, `runTest` |
| `ui/src/pages/workbench/TestRunDialog.tsx` (new) | Sample-input form |
| `ui/src/pages/workbench/TestOutputStrip.tsx` (new) | Collapsible list of runs |
| `ui/src/pages/workbench/WorkbenchPage.tsx` / `.css`, `workbenchPrefs.ts`, `ItemTab.tsx` (modify) | Integration |

---
### Task 1: Run node and script action types without a task

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/ScriptExecutionService.java` (`runScript` ~line 137,
  `buildBaseEnvironment` ~408, `substitutePlaceholders` ~515, records ~211/674)
- Modify: `app/src/main/java/io/apitomy/axiom/app/NodeExecutionService.java` (`runNode` ~85, `declaredInputs` ~159)
- Test: `app/src/test/java/io/apitomy/axiom/app/ScriptExecutionServiceTest.java`,
  `app/src/test/java/io/apitomy/axiom/app/NodeExecutionServiceTest.java`

**Interfaces:**
- Produces (in `ScriptExecutionService`):
  - `public record ActionRun(int exitCode, String stdout, String stderr, String output, long durationMs,
    boolean finished, String resolvedScript)` — `output` is the last JSON object on stdout (or null);
    `resolvedScript` is null for node.
  - `ActionRun runScriptAction(ActionTypeEntity actionType, Map<String, Object> inputs, SubjectEntity subject,
    String runId) throws IOException, InterruptedException` (package-private)
  - `Map<String, String> buildBaseEnvironment(String runId, Path workDir)` (package-private overload)
  - `long timeoutSeconds()` (already exists, package-private)
- Produces (in `NodeExecutionService`): `ScriptExecutionService.ActionRun runNodeAction(ActionTypeEntity
  actionType, Map<String, Object> inputs, SubjectEntity subject, String runId) throws IOException,
  InterruptedException` (package-private). `inputs` may contain undeclared keys; only declared ones are sent.
- The existing task paths (`runScript(TaskEntity, SubjectEntity)`, `runNode(TaskEntity, SubjectEntity)`)
  keep their behaviour and now delegate to these methods. `actionType` passed to the new methods may be a
  transient (never persisted) entity.

- [ ] **Step 1: Write the failing tests**

Add to `ScriptExecutionServiceTest` (it is a `@QuarkusTest`; inject `ScriptExecutionService
scriptExecutionService` if not already injected):

```java
    @Test
    void runsAScriptActionWithoutATask() throws Exception {
        ActionTypeEntity at = new ActionTypeEntity();
        at.name = "draft-script";
        at.executionMode = "script";
        at.inputs = new ArrayList<>(List.of(new ActionTypeField("name", "string", true, null)));
        at.scriptTemplate = "#!/usr/bin/env bash\nprintf '{\"greeting\":\"hello %s\"}\\n' {{inputs.name}}\n"
                + "echo 'log line' >&2\n";
        ScriptExecutionService.ActionRun run = QuarkusTransaction.requiringNew().call(() ->
                scriptExecutionService.runScriptAction(at, Map.of("name", "world"), null, "test-run"));
        assertEquals(0, run.exitCode());
        assertTrue(run.finished());
        assertEquals("{\"greeting\":\"hello world\"}", run.output());
        assertTrue(run.stderr().contains("log line"));
        assertTrue(run.resolvedScript().contains("AXIOM_INPUT_name"));
    }
```

Add to `NodeExecutionServiceTest` (node availability is already assumed in `@BeforeAll`):

```java
    @Test
    void runsANodeActionWithoutATask() throws Exception {
        ActionTypeEntity at = new ActionTypeEntity();
        at.name = "draft-node";
        at.executionMode = "node";
        at.inputs = new ArrayList<>(List.of(new ActionTypeField("labels", "object", true, null)));
        at.codeTemplate = "export async function run(inputs, ctx) {\n"
                + "    ctx.log('counting');\n"
                + "    return { count: inputs.labels.length, extra: inputs.ignored ?? null };\n"
                + "}\n";
        ScriptExecutionService.ActionRun run = QuarkusTransaction.requiringNew().call(() ->
                nodeExecutionService.runNodeAction(at, Map.of("labels", List.of("x", "y"), "ignored", "z"),
                        null, "test-run"));
        assertEquals(0, run.exitCode());
        assertEquals("{\"count\":2,\"extra\":null}", run.output());
        assertTrue(run.stderr().contains("counting"));
        assertNull(run.resolvedScript());
    }
```

Add any missing imports (`java.util.Map`, `java.util.ArrayList`, `java.util.List`,
`io.apitomy.axiom.core.entities.ActionTypeField`, `io.quarkus.narayana.jta.QuarkusTransaction`,
`static org.junit.jupiter.api.Assertions.assertNull`).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -pl app -am test -Dtest='ScriptExecutionServiceTest,NodeExecutionServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`runScriptAction`, `runNodeAction`, `ActionRun` not found).

- [ ] **Step 3: Implement in `ScriptExecutionService`**

Add the record next to `RunResult`:

```java
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
```

Add the base-environment overload and make the task version delegate:

```java
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
```

(Keep the existing Javadoc on the task overload; delete its old body.)

Split `substitutePlaceholders` so the values can be passed in:

```java
    String substitutePlaceholders(String template, TaskEntity task,
                                  List<ActionTypeField> inputs, Map<String, String> env) {
        Map<String, Object> values = task.workflowRunId != null
                ? InputBindingResolver.parseInputs(task.input, objectMapper)
                : Map.of();
        return substitutePlaceholders(template, values, inputs, env);
    }

    /**
     * Resolves {@code {{inputs.NAME}}} placeholders using the given input values.
     *
     * @param template the script template
     * @param values   input values by name
     * @param inputs   the declared inputs
     * @param env      receives the environment variables the resolved script references
     * @return the resolved script
     */
    String substitutePlaceholders(String template, Map<String, Object> values,
                                  List<ActionTypeField> inputs, Map<String, String> env) {
        // body of the old method from `Map<String, String> bindings = ...` to `return render(...)`, unchanged
    }
```

Add `runScriptAction` and rewrite `runScript` to use it:

```java
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
            ProcessBuilder pb = scriptProcess(scriptFile, workDir.path(), buildBaseEnvironment(runId, workDir.path()));
            applyActionEnvironment(pb, actionType);
            // Applied last so the reserved AXIOM_* names are authoritative (see substitutePlaceholders / #267).
            pb.environment().putAll(placeholderEnv);
            Instant startTime = Instant.now();
            Process process = pb.start();
            CapturedOutput captured = capture(process, timeoutSeconds);
            long durationMs = java.time.Duration.between(startTime, Instant.now()).toMillis();
            String stdout = captured.stdout();
            if (!captured.finished()) {
                stdout = stdout + "\n[Script timed out after " + timeoutSeconds + "s]";
            }
            return new ActionRun(captured.exitCode(), stdout, captured.stderr(), extractScriptOutput(stdout),
                    durationMs, captured.finished(), script);
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
```

- [ ] **Step 4: Implement in `NodeExecutionService`**

Replace `runNode` with a thin task wrapper plus `runNodeAction`, and make `declaredInputs` take a map:

```java
    ScriptExecutionService.RunResult runNode(TaskEntity task, SubjectEntity subject)
            throws IOException, InterruptedException {
        ActionTypeEntity actionType = ActionTypeEntity.find("name", task.actionType).firstResult();
        if (actionType == null || actionType.codeTemplate == null || actionType.codeTemplate.isBlank()) {
            return new ScriptExecutionService.RunResult(1,
                    "No code configured for action type: " + task.actionType, null);
        }
        if (subject == null && task.subjectId != null) {
            subject = SubjectEntity.findById(task.subjectId);
        }
        LOG.infof("Executing node action for task %d (%s), timeout %ds",
                task.id, task.actionType, scriptExecutionService.timeoutSeconds());
        Instant startTime = Instant.now();
        ScriptExecutionService.ActionRun run = runNodeAction(actionType,
                InputBindingResolver.parseInputs(task.input, objectMapper), subject, String.valueOf(task.id));
        if (NODE_MISSING.equals(run.stderr())) {
            return new ScriptExecutionService.RunResult(1, NODE_MISSING, null);
        }
        String executionLog = ScriptExecutionService.buildExecutionLog("Node Execution Log", task, "Code",
                actionType.codeTemplate, null, run.stdout(), run.stderr(), run.exitCode(), startTime,
                run.durationMs());
        return new ScriptExecutionService.RunResult(run.exitCode(), run.output(), executionLog);
    }

    /**
     * Runs a node action type with explicit input values, outside any task (used for test runs). Only the
     * declared inputs are passed to the action.
     *
     * @param actionType the action type (may be transient); its {@code codeTemplate} must not be blank
     * @param inputs     input values by name
     * @param subject    the subject whose workspace to run in, or {@code null} for a temporary directory
     * @param runId      identifier exposed to the action as {@code AXIOM_TASK_ID}
     * @return the run; when node is not installed, exit code 1 with {@link #NODE_MISSING} as stderr
     * @throws IOException          if files cannot be written
     * @throws InterruptedException if interrupted while waiting for the process
     */
    ScriptExecutionService.ActionRun runNodeAction(ActionTypeEntity actionType, Map<String, Object> inputs,
                                                   SubjectEntity subject, String runId)
            throws IOException, InterruptedException {
        Path codeDir = Files.createTempDirectory("axiom-node-");
        ScriptExecutionService.WorkingDirectory workDir = null;
        try {
            long timeoutSeconds = scriptExecutionService.timeoutSeconds();
            Path runner = writeRunner(codeDir);
            Path actionFile = codeDir.resolve("action.mjs");
            Files.writeString(actionFile, actionType.codeTemplate);
            workDir = scriptExecutionService.workingDirectory(subject);
            ProcessBuilder pb = new ProcessBuilder("node", runner.toString(), actionFile.toString())
                    .directory(workDir.path().toFile());
            pb.environment().putAll(scriptExecutionService.buildBaseEnvironment(runId, workDir.path()));
            scriptExecutionService.applyActionEnvironment(pb, actionType);
            byte[] inputBytes = objectMapper.writeValueAsString(declaredInputs(inputs, actionType))
                    .getBytes(StandardCharsets.UTF_8);
            Instant startTime = Instant.now();
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                if (isNodeMissing(e, workDir.path())) {
                    return new ScriptExecutionService.ActionRun(1, "", NODE_MISSING, null, 0, true, null);
                }
                throw e;
            }
            // Written asynchronously so capture()'s timeout bounds a process that never drains stdin.
            CompletableFuture.runAsync(() -> {
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(inputBytes);
                } catch (IOException e) {
                    // The process exited before reading its inputs; its stderr explains why.
                    LOG.debugf("Could not write inputs to node action %s: %s", runId, e.getMessage());
                }
            });
            ScriptExecutionService.CapturedOutput captured =
                    ScriptExecutionService.capture(process, timeoutSeconds);
            long durationMs = Duration.between(startTime, Instant.now()).toMillis();
            String stdout = captured.stdout();
            if (!captured.finished()) {
                stdout = stdout + "\n[Node action timed out after " + timeoutSeconds + "s]";
            }
            return new ScriptExecutionService.ActionRun(captured.exitCode(), stdout, captured.stderr(),
                    ScriptExecutionService.extractScriptOutput(stdout), durationMs, captured.finished(), null);
        } finally {
            deleteRecursively(codeDir);
            if (workDir != null) {
                workDir.cleanUp();
            }
        }
    }

    /**
     * Collects the action type's declared inputs that have a value; undeclared and absent inputs are omitted.
     */
    private Map<String, Object> declaredInputs(Map<String, Object> values, ActionTypeEntity actionType) {
        Map<String, Object> declared = new LinkedHashMap<>();
        if (actionType.inputs != null) {
            for (ActionTypeField input : actionType.inputs) {
                if (input.name != null && values.containsKey(input.name)) {
                    declared.put(input.name, values.get(input.name));
                }
            }
        }
        return declared;
    }
```

Note the old `runNode` returned `NODE_MISSING` as the task output; keep that (the wrapper above does). Make
sure `InputBindingResolver` and `Map` are imported in `NodeExecutionService`.

- [ ] **Step 5: Run the tests**

Run: `mvn -q -pl app -am test -Dtest='ScriptExecutionServiceTest,NodeExecutionServiceTest,NodeRunnerTest,ScriptOutputTest,TaskExecutionServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass (the existing task-based tests prove the refactor kept behaviour).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/ScriptExecutionService.java \
        app/src/main/java/io/apitomy/axiom/app/NodeExecutionService.java \
        app/src/test/java/io/apitomy/axiom/app/ScriptExecutionServiceTest.java \
        app/src/test/java/io/apitomy/axiom/app/NodeExecutionServiceTest.java
git commit -m "refactor: run script and node action types without a task"
```

---
### Task 2: `DraftTestRunner` — run draft action types and tools

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/DraftTestRunner.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/DraftTestRunnerTest.java`

**Interfaces:**
- Consumes (Task 1): `ScriptExecutionService.runScriptAction`, `NodeExecutionService.runNodeAction`,
  `ScriptExecutionService.ActionRun`, plus existing package-private `ScriptExecutionService.workingDirectory`,
  `scriptProcess`, `capture`, `applyActionEnvironment`, `buildBaseEnvironment(String, Path)`.
- Produces (public, `@ApplicationScoped`):
  - `public record Result(String status, Map<String, Object> outputs, String stdout, String stderr,
    Integer exitCode, long durationMs, List<String> problems, String message, String resolvedScript)`;
    `status` is one of `passed`, `failed`, `error`.
  - `public Result runActionType(JsonNode content, Map<String, Object> inputs, SubjectEntity subject)`
  - `public Result runTool(JsonNode content, Map<String, Object> params, SubjectEntity subject)`
  - Constants: `public static final String AGENT_UNSUPPORTED = "Test runs of AI Agent action types are not
    supported yet.";` `public static final int MAX_OUTPUT_CHARS = 64_000;`
    `static final long TOOL_TIMEOUT_SECONDS = 30;`
  - `static String renderToolScript(String template, List<JsonNode> parameters, Map<String, Object> params,
    Path fileDir) throws IOException` (package-private, for tests)

- [ ] **Step 1: Write the failing tests**

```java
package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link DraftTestRunner} against draft action type and tool JSON.
 */
@QuarkusTest
class DraftTestRunnerTest {

    @Inject
    DraftTestRunner runner;

    @Inject
    ObjectMapper mapper;

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    private DraftTestRunner.Result action(String content, Map<String, Object> inputs) throws Exception {
        JsonNode node = json(content);
        return QuarkusTransaction.requiringNew().call(() -> runner.runActionType(node, inputs, null));
    }

    @Test
    void scriptActionPassesWhenOutputsMatch() throws Exception {
        DraftTestRunner.Result r = action("""
                {"name":"s","executionMode":"script",
                 "inputs":[{"name":"who","type":"string","required":true}],
                 "outputs":[{"name":"greeting","type":"string","required":true}],
                 "scriptTemplate":"printf '{\\"greeting\\":\\"hi %s\\"}\\\\n' {{inputs.who}}"}""",
                Map.of("who", "bob"));
        assertEquals("passed", r.status(), r.toString());
        assertEquals("hi bob", r.outputs().get("greeting"));
        assertEquals(0, r.exitCode());
        assertTrue(r.problems().isEmpty());
    }

    @Test
    void missingDeclaredOutputFails() throws Exception {
        DraftTestRunner.Result r = action("""
                {"name":"s","executionMode":"script","inputs":[],
                 "outputs":[{"name":"count","type":"number","required":true}],
                 "scriptTemplate":"echo '{}'"}""", Map.of());
        assertEquals("failed", r.status());
        assertEquals(List.of("Output: Missing required field 'count'."), r.problems());
    }

    @Test
    void nonZeroExitFails() throws Exception {
        DraftTestRunner.Result r = action("""
                {"name":"s","executionMode":"script","scriptTemplate":"echo boom >&2; exit 3"}""", Map.of());
        assertEquals("failed", r.status());
        assertEquals(3, r.exitCode());
        assertTrue(r.stderr().contains("boom"));
    }

    @Test
    void missingRequiredInputIsAnErrorAndDoesNotRun() throws Exception {
        DraftTestRunner.Result r = action("""
                {"name":"s","executionMode":"script",
                 "inputs":[{"name":"who","type":"string","required":true}],
                 "scriptTemplate":"echo ran"}""", Map.of());
        assertEquals("error", r.status());
        assertEquals(List.of("Input: Missing required field 'who'."), r.problems());
        assertNull(r.exitCode());
    }

    @Test
    void agentModeIsNotSupported() throws Exception {
        DraftTestRunner.Result r = action("{\"name\":\"a\",\"executionMode\":\"agent\",\"promptTemplate\":\"x\"}",
                Map.of());
        assertEquals("error", r.status());
        assertEquals(DraftTestRunner.AGENT_UNSUPPORTED, r.message());
    }

    @Test
    void emptyTemplateIsAnError() throws Exception {
        DraftTestRunner.Result r = action("{\"name\":\"n\",\"executionMode\":\"node\",\"codeTemplate\":\"  \"}",
                Map.of());
        assertEquals("error", r.status());
        assertEquals("The action type has no code to run.", r.message());
    }

    @Test
    void toolRunsWithSubstitutedParameters() throws Exception {
        JsonNode tool = json("""
                {"name":"t","parameters":[{"name":"who","required":true},{"name":"body","required":false},
                 {"name":"opt","required":false}],
                 "scriptTemplate":"echo \\"hello {{who}}\\"; cat {{body_file}}; echo \\"[{{opt}}]\\""}""");
        DraftTestRunner.Result r = QuarkusTransaction.requiringNew().call(() ->
                runner.runTool(tool, Map.of("who", "ann", "body", "long text"), null));
        assertEquals("passed", r.status(), r.toString());
        assertEquals("hello ann\nlong text[]\n", r.stdout());
        assertNull(r.outputs());
    }

    @Test
    void toolMissingRequiredParameterIsAnError() throws Exception {
        JsonNode tool = json("{\"name\":\"t\",\"parameters\":[{\"name\":\"who\",\"required\":true}],"
                + "\"scriptTemplate\":\"echo {{who}}\"}");
        DraftTestRunner.Result r = QuarkusTransaction.requiringNew().call(() ->
                runner.runTool(tool, Map.of(), null));
        assertEquals("error", r.status());
        assertEquals(List.of("Parameter: Missing required parameter 'who'."), r.problems());
    }

    @Test
    void renderToolScriptMatchesTheToolsServer(@TempDir Path dir) throws Exception {
        List<JsonNode> params = List.of(json("{\"name\":\"a\",\"required\":true}"),
                json("{\"name\":\"b\",\"required\":false}"));
        String script = DraftTestRunner.renderToolScript("x={{a}} f={{a_file}} y={{b}} z={{b_file}}",
                params, Map.of("a", "1"), dir);
        assertTrue(script.startsWith("x=1 f=" + dir), script);
        assertTrue(script.endsWith(" y= z="), script);
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            assertEquals("1", Files.readString(files.findFirst().orElseThrow()));
        }
    }

    @Test
    void longOutputIsTruncated() {
        String text = "x".repeat(DraftTestRunner.MAX_OUTPUT_CHARS + 10);
        assertTrue(DraftTestRunner.truncate(text).endsWith("\n[truncated]"));
        assertEquals(DraftTestRunner.MAX_OUTPUT_CHARS + "\n[truncated]".length(),
                DraftTestRunner.truncate(text).length());
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q -pl app -am test -Dtest=DraftTestRunnerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`DraftTestRunner` does not exist).

- [ ] **Step 3: Implement `DraftTestRunner`**

```java
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
     * @param resolvedScript the script after placeholder substitution, or {@code null}
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
        if (run.exitCode() == 0) {
            problems.addAll(prefixed("Output: ",
                    ActionTypeIoValidator.validate(actionType.outputs, outputs != null ? outputs : Map.of())));
        }
        String status = run.exitCode() == 0 && problems.isEmpty() ? "passed" : "failed";
        return new Result(status, outputs, truncate(run.stdout()), truncate(run.stderr()), run.exitCode(),
                run.durationMs(), problems, null, run.resolvedScript());
    }

    /**
     * Runs a draft tool the way the MCP tools server does.
     *
     * @param content the draft tool JSON
     * @param params  parameter values by name
     * @param subject the subject whose workspace to run in, or {@code null}
     * @return the result
     */
    public Result runTool(JsonNode content, Map<String, Object> params, SubjectEntity subject) {
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
            // A tool has no environment of its own; like an action type without one, it gets every secret.
            scriptExecutionService.applyActionEnvironment(pb, new ActionTypeEntity());
            Instant start = Instant.now();
            ScriptExecutionService.CapturedOutput captured =
                    ScriptExecutionService.capture(pb.start(), TOOL_TIMEOUT_SECONDS);
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
```

Check before relying on it: `NodeExecutionService.NODE_MISSING` must be at least package-visible (make it
`static final String` without `private` if needed), and `ScriptExecutionService.WorkingDirectory`'s
constructor is accessible from the same package (it is a package-private record). Confirm how
`ImportExportService` stores `environment` on the entity (`jsonOrNull`) and match it in `toEntity`.

- [ ] **Step 4: Run the tests**

Run: `mvn -q -pl app -am test -Dtest=DraftTestRunnerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/DraftTestRunner.java \
        app/src/main/java/io/apitomy/axiom/app/NodeExecutionService.java \
        app/src/test/java/io/apitomy/axiom/app/DraftTestRunnerTest.java
git commit -m "feat(workbench): run draft action types and tools for testing"
```

---
### Task 3: Test-run API, run store and SSE event

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`
- Modify: `core/src/main/java/io/apitomy/axiom/core/events/SseEvent.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/composition/CompositionTestRuns.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/CompositionTestRunsResourceTest.java`

**Interfaces:**
- Consumes (Task 2): `DraftTestRunner.runActionType`, `runTool`, `Result`.
- Produces REST:
  - `POST /compositions/{compositionId}/items/{itemId}/test` (operationId `testCompositionItem`), body
    `CompositionTestRequest`, returns `CompositionTestRun` (finished). 400 for a workflow item.
  - `GET /compositions/{compositionId}/test-runs` (operationId `listCompositionTestRuns`), returns
    `CompositionTestRun[]`, newest first.
  - `POST /compositions/{compositionId}/agent/test` (operationId `testAgentItem`), body `AgentTestRequest`,
    returns `CompositionTestRun`; requires `X-Axiom-Session-Token` (401); 404 when no such draft.
- Produces SSE: type `composition-test-run`, data `{"compositionId": <id>, "run": <CompositionTestRun JSON>}`,
  fired when a run starts (`status: "running"`) and again when it finishes (same `id`).
- Produces Java: `CompositionTestRuns.run(long compositionId, CompositionItemEntity item,
  Map<String, Object> inputs, Long subjectId, String author, String turnId)` returning `CompositionTestRun`;
  `CompositionTestRuns.list(long compositionId)` returning `List<CompositionTestRun>`;
  `CompositionTestRuns.MAX_RUNS = 50`; `SseEvent.compositionTestRun(long compositionId, String runJson)`.

- [ ] **Step 1: Add the schemas and routes to `openapi.json`**

Edit with a small Python script (`json.load`, modify, `json.dump(..., indent=2, ensure_ascii=False)` plus a
trailing newline) so the rest of the file keeps its formatting; check `git diff --stat` shows only
additions.

Schemas (under `components.schemas`):

```json
"CompositionTestValues": {
  "description": "Free-form values by name: test inputs or parameters, or an action type's outputs.",
  "type": "object",
  "additionalProperties": true
},
"CompositionTestRequest": {
  "type": "object",
  "properties": {
    "inputs": {"$ref": "#/components/schemas/CompositionTestValues"},
    "subjectId": {"description": "Run in this subject's workspace (optional)", "format": "int64", "type": "integer"}
  }
},
"AgentTestRequest": {
  "required": ["type", "name"],
  "type": "object",
  "properties": {
    "type": {"enum": ["action-type", "tool"], "type": "string"},
    "name": {"description": "Draft name", "type": "string"},
    "inputs": {"$ref": "#/components/schemas/CompositionTestValues"},
    "subjectId": {"format": "int64", "type": "integer"}
  }
},
"CompositionTestRun": {
  "description": "A test run of a draft action type or tool. Runs are kept in memory, the last 50 per composition.",
  "required": ["id", "itemId", "itemType", "itemName", "author", "status", "startedAt"],
  "type": "object",
  "properties": {
    "id": {"type": "string"},
    "itemId": {"format": "int64", "type": "integer"},
    "itemType": {"enum": ["action-type", "tool"], "type": "string"},
    "itemName": {"type": "string"},
    "author": {"enum": ["user", "ai"], "type": "string"},
    "turnId": {"type": "string"},
    "subjectId": {"format": "int64", "type": "integer"},
    "status": {"enum": ["running", "passed", "failed", "error"], "type": "string"},
    "startedAt": {"format": "date-time", "type": "string"},
    "durationMs": {"format": "int64", "type": "integer"},
    "inputs": {"$ref": "#/components/schemas/CompositionTestValues"},
    "outputs": {"$ref": "#/components/schemas/CompositionTestValues"},
    "stdout": {"type": "string"},
    "stderr": {"type": "string"},
    "exitCode": {"format": "int32", "type": "integer"},
    "problems": {"type": "array", "items": {"type": "string"}},
    "message": {"description": "Why the run could not start", "type": "string"},
    "resolvedScript": {"type": "string"}
  }
}
```

Paths (copy `tags: ["Compositions"]` and the `compositionId` path parameter object from
`/compositions/{compositionId}/agent/items`; `itemId` is the same shape as in
`/compositions/{compositionId}/items/{itemId}`):

```json
"/compositions/{compositionId}/items/{itemId}/test": {
  "post": {
    "tags": ["Compositions"],
    "summary": "Test-run a draft action type or tool",
    "description": "Runs the saved draft with sample inputs and returns the finished run. Only node and script action types and tools can run; other action types return an error run.",
    "operationId": "testCompositionItem",
    "requestBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CompositionTestRequest"}}}},
    "responses": {
      "200": {"description": "The finished run", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CompositionTestRun"}}}},
      "400": {"description": "The item is the workflow"},
      "404": {"$ref": "#/components/responses/NotFound"}
    }
  },
  "parameters": [ <compositionId>, <itemId> ]
},
"/compositions/{compositionId}/test-runs": {
  "get": {
    "tags": ["Compositions"],
    "summary": "List recent test runs",
    "description": "The composition's recent test runs (by the user or the AI), newest first.",
    "operationId": "listCompositionTestRuns",
    "responses": {
      "200": {"description": "The runs", "content": {"application/json": {"schema": {"type": "array", "items": {"$ref": "#/components/schemas/CompositionTestRun"}}}}},
      "404": {"$ref": "#/components/responses/NotFound"}
    }
  },
  "parameters": [ <compositionId> ]
},
"/compositions/{compositionId}/agent/test": {
  "post": {
    "tags": ["Compositions"],
    "summary": "Agent: test-run a draft action type or tool",
    "description": "Runs a draft by type and name. Requires the `X-Axiom-Session-Token` header of the composition's live workbench session (401 otherwise).",
    "operationId": "testAgentItem",
    "requestBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/AgentTestRequest"}}}},
    "responses": {
      "200": {"description": "The finished run", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CompositionTestRun"}}}},
      "401": {"description": "Missing or invalid session token"},
      "404": {"$ref": "#/components/responses/NotFound"}
    }
  },
  "parameters": [ <compositionId> ]
}
```

- [ ] **Step 2: Write the failing REST test**

```java
package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * REST tests for test runs of composition drafts.
 */
@QuarkusTest
class CompositionTestRunsResourceTest {

    static final String BASE = "/api/v1/compositions";

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    private long scriptActionType(long compositionId) {
        long itemId = given().contentType(ContentType.JSON)
                .body(Map.of("type", "action-type", "name", "greet", "executionMode", "script"))
                .post(BASE + "/{id}/items", compositionId).then().statusCode(200)
                .extract().jsonPath().getLong("id");
        String revision = given().get(BASE + "/{id}/items/{itemId}", compositionId, itemId)
                .then().statusCode(200).extract().jsonPath().getString("contentRevision");
        Map<String, Object> content = Map.of("name", "greet", "description", "", "executionMode", "script",
                "inputs", List.of(Map.of("name", "who", "type", "string", "required", true)),
                "outputs", List.of(Map.of("name", "greeting", "type", "string", "required", true)),
                "scriptTemplate", "printf '{\"greeting\":\"hi %s\"}\\n' {{inputs.who}}");
        given().contentType(ContentType.JSON).body(Map.of("content", content, "baseRevision", revision))
                .put(BASE + "/{id}/items/{itemId}", compositionId, itemId).then().statusCode(200);
        return itemId;
    }

    @Test
    void runsADraftAndListsTheRun() {
        long id = CompositionsResourceTest.createComposition();
        long itemId = scriptActionType(id);
        given().contentType(ContentType.JSON).body(Map.of("inputs", Map.of("who", "bob")))
                .post(BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200)
                .body("status", equalTo("passed"))
                .body("author", equalTo("user"))
                .body("itemName", equalTo("greet"))
                .body("outputs.greeting", equalTo("hi bob"));
        given().get(BASE + "/{id}/test-runs", id).then().statusCode(200)
                .body("$", hasSize(1)).body("[0].status", equalTo("passed"));
    }

    @Test
    void rejectsTheWorkflow() {
        long id = CompositionsResourceTest.createComposition();
        long workflowId = given().get(BASE + "/{id}/items", id).then().statusCode(200)
                .extract().jsonPath().getLong("find { it.type == 'workflow' }.id");
        given().contentType(ContentType.JSON).body(Map.of())
                .post(BASE + "/{id}/items/{itemId}/test", id, workflowId).then().statusCode(400);
    }

    @Test
    void agentRouteRequiresTheSessionToken() {
        long id = CompositionsResourceTest.createComposition();
        given().contentType(ContentType.JSON).body(Map.of("type", "action-type", "name", "greet"))
                .post(BASE + "/{id}/agent/test", id).then().statusCode(401);
    }

    @Test
    void keepsOnlyTheMostRecentRuns() {
        long id = CompositionsResourceTest.createComposition();
        long itemId = scriptActionType(id);
        for (int i = 0; i < CompositionTestRuns.MAX_RUNS + 2; i++) {
            given().contentType(ContentType.JSON).body(Map.of("inputs", Map.of())).post(
                    BASE + "/{id}/items/{itemId}/test", id, itemId).then().statusCode(200);
        }
        given().get(BASE + "/{id}/test-runs", id).then().statusCode(200)
                .body("$", hasSize(CompositionTestRuns.MAX_RUNS));
    }
}
```

If `createComposition` does not create a workflow item, create one through `addItem(id, "workflow", ...)`
the way `CompositionsResourceTest` does, and adjust `rejectsTheWorkflow` accordingly. Missing inputs make
`keepsOnlyTheMostRecentRuns` produce fast `error` runs, which is intended.

- [ ] **Step 3: Run it to verify it fails**

Run: `mvn -q -pl app -am test -Dtest=CompositionTestRunsResourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — `CompositionsResourceImpl` does not implement `testCompositionItem`,
`listCompositionTestRuns`, `testAgentItem`.

- [ ] **Step 4: Add the SSE factory to `SseEvent`**

```java
    /**
     * Creates a composition-test-run event, emitted when a test run starts and when it finishes.
     *
     * @param compositionId the composition id
     * @param runJson       the run, as raw JSON (embedded unescaped)
     * @return the SSE event
     */
    public static SseEvent compositionTestRun(long compositionId, String runJson) {
        return new SseEvent("composition-test-run",
                "{\"compositionId\":" + compositionId + ",\"run\":" + runJson + "}");
    }
```

- [ ] **Step 5: Implement `CompositionTestRuns`**

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.CompositionTestRun;
import io.apitomy.axiom.api.beans.CompositionTestValues;
import io.apitomy.axiom.app.DraftTestRunner;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.events.SseEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs composition drafts for testing and keeps the most recent runs of each composition in memory, telling
 * the workbench about each run over SSE when it starts and when it finishes.
 */
@ApplicationScoped
public class CompositionTestRuns {

    private static final Logger LOG = Logger.getLogger(CompositionTestRuns.class);

    /** Runs kept per composition. */
    public static final int MAX_RUNS = 50;

    private final Map<Long, Deque<CompositionTestRun>> runs = new ConcurrentHashMap<>();

    @Inject
    CompositionService service;

    @Inject
    DraftTestRunner runner;

    @Inject
    ObjectMapper mapper;

    @Inject
    Event<SseEvent> sseEvents;

    /**
     * Runs an action type or tool draft and records the run.
     *
     * @param compositionId the composition id
     * @param item          the draft (not the workflow)
     * @param inputs        input or parameter values
     * @param subjectId     the subject to run in, or {@code null}
     * @param author        {@link CompositionService#AUTHOR_USER} or {@link CompositionService#AUTHOR_AI}
     * @param turnId        the AI turn id, or {@code null}
     * @return the finished run
     * @throws IllegalArgumentException for the workflow or an unknown subject
     */
    public CompositionTestRun run(long compositionId, CompositionItemEntity item, Map<String, Object> inputs,
                                  Long subjectId, String author, String turnId) {
        boolean tool = "tool".equals(item.itemType);
        if (!tool && !"action-type".equals(item.itemType)) {
            throw new IllegalArgumentException("Only action types and tools can be test-run");
        }
        SubjectEntity subject = null;
        if (subjectId != null) {
            subject = SubjectEntity.findById(subjectId);
            if (subject == null) {
                throw new IllegalArgumentException("Subject not found: " + subjectId);
            }
        }
        Map<String, Object> values = inputs != null ? inputs : Map.of();
        CompositionTestRun run = new CompositionTestRun();
        run.setId(UUID.randomUUID().toString());
        run.setItemId(item.id);
        run.setItemType(CompositionTestRun.ItemType.fromValue(item.itemType));
        run.setItemName(item.name);
        run.setAuthor(CompositionTestRun.Author.fromValue(author));
        run.setTurnId(turnId);
        run.setSubjectId(subjectId);
        run.setStatus(CompositionTestRun.Status.RUNNING);
        run.setStartedAt(Date.from(Instant.now()));
        run.setInputs(values(values));
        remember(compositionId, run);
        fire(compositionId, run);

        JsonNode content = service.contentOf(item);
        DraftTestRunner.Result result = tool
                ? runner.runTool(content, values, subject)
                : runner.runActionType(content, values, subject);
        run.setStatus(CompositionTestRun.Status.fromValue(result.status()));
        run.setDurationMs(result.durationMs());
        run.setOutputs(result.outputs() != null ? values(result.outputs()) : null);
        run.setStdout(result.stdout());
        run.setStderr(result.stderr());
        run.setExitCode(result.exitCode());
        run.setProblems(new ArrayList<>(result.problems()));
        run.setMessage(result.message());
        run.setResolvedScript(result.resolvedScript());
        fire(compositionId, run);
        return run;
    }

    /**
     * Lists a composition's recent runs.
     *
     * @param compositionId the composition id
     * @return the runs, newest first
     */
    public List<CompositionTestRun> list(long compositionId) {
        Deque<CompositionTestRun> deque = runs.get(compositionId);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    private void remember(long compositionId, CompositionTestRun run) {
        Deque<CompositionTestRun> deque = runs.computeIfAbsent(compositionId, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addFirst(run);
            while (deque.size() > MAX_RUNS) {
                deque.removeLast();
            }
        }
    }

    private void fire(long compositionId, CompositionTestRun run) {
        try {
            sseEvents.fire(SseEvent.compositionTestRun(compositionId, mapper.writeValueAsString(run)));
        } catch (JsonProcessingException e) {
            LOG.warnf("Could not serialize test run %s: %s", run.getId(), e.getMessage());
        }
    }

    private static CompositionTestValues values(Map<String, Object> map) {
        CompositionTestValues values = new CompositionTestValues();
        map.forEach(values::setAdditionalProperty);
        return values;
    }
}
```

Check the generated bean names before compiling (`common/api/target/generated-sources/jaxrs/.../beans/
CompositionTestRun.java`): the enum class names (`ItemType`, `Author`, `Status`), the `RUNNING` constant
and the additional-properties setter (`setAdditionalProperty` or `getAdditionalProperties().put`). Also check
how `CompositionTurns` fires SSE (`Event<SseEvent>`) and copy its injection style. When a run is deleted
because its composition is deleted, nothing is needed: `list` simply keeps returning the old runs until
restart; add `public void forget(long compositionId) { runs.remove(compositionId); }` and call it from the
composition delete path in `CompositionsResourceImpl.deleteComposition`.

- [ ] **Step 6: Implement the routes in `CompositionsResourceImpl`**

Inject `CompositionTestRuns testRuns;` and add:

```java
    /** {@inheritDoc} */
    @Override
    public CompositionTestRun testCompositionItem(long compositionId, long itemId, CompositionTestRequest data) {
        CompositionItemEntity item = service.item(compositionId, itemId);
        if (ConfigItemType.WORKFLOW.jsonName().equals(item.itemType)) {
            throw new BadRequestException("The workflow cannot be test-run");
        }
        return testRuns.run(compositionId, item, testValues(data.getInputs()), data.getSubjectId(),
                CompositionService.AUTHOR_USER, null);
    }

    /** {@inheritDoc} */
    @Override
    public List<CompositionTestRun> listCompositionTestRuns(long compositionId) {
        service.get(compositionId);
        return testRuns.list(compositionId);
    }

    /** {@inheritDoc} */
    @Override
    public CompositionTestRun testAgentItem(long compositionId, AgentTestRequest data) {
        requireToken(compositionId);
        String type = data.getType().value();
        CompositionItemEntity item = service.items(compositionId).stream()
                .filter(i -> i.itemType.equals(type) && i.name.equals(data.getName()))
                .findFirst()
                .orElseThrow(() -> new CompositionNotFoundException(
                        "No " + type + " named " + data.getName() + " in composition " + compositionId));
        CompositionEntity composition = service.get(compositionId);
        return testRuns.run(compositionId, item, testValues(data.getInputs()), data.getSubjectId(),
                CompositionService.AUTHOR_AI, composition.currentTurnId);
    }

    private static Map<String, Object> testValues(CompositionTestValues values) {
        return values == null ? Map.of() : values.getAdditionalProperties();
    }
```

Match the exact generated method signatures (parameter types for the path ids — `long` vs `Long` vs
`BigInteger` — follow the existing composition methods in this class). Map `IllegalArgumentException` from
`CompositionTestRuns.run` to 400 the way the class already handles it (check existing exception mappers;
if none applies, catch it and throw `BadRequestException`). Use `ConfigItemType`/`jsonName()` or compare
with the literal `"workflow"` — whichever the class already uses.

- [ ] **Step 7: Run the tests**

Run: `mvn -q -pl app -am test -Dtest='CompositionTestRunsResourceTest,CompositionsResourceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass.

- [ ] **Step 8: Commit**

```bash
git add common/api/src/main/resources/openapi.json core/src/main/java/io/apitomy/axiom/core/events/SseEvent.java \
        app/src/main/java/io/apitomy/axiom/app/composition/CompositionTestRuns.java \
        app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java \
        app/src/test/java/io/apitomy/axiom/app/composition/CompositionTestRunsResourceTest.java
git commit -m "feat(workbench): test-run API for composition drafts"
```

---
### Task 4: MCP tools `test_action_type` and `test_tool`, template rule

**Files:**
- Modify: `app/src/main/resources/templates/axiom-workbench-mcp/server.js`
- Modify: `app/src/test/node/workbench-mcp.test.js`
- Modify: `app/src/main/resources/templates/assistant-templates/axiom-workbench.json`
- Modify: `app/src/test/java/io/apitomy/axiom/app/assistant/WorkbenchReferenceTest.java`

**Interfaces:**
- Consumes (Task 3): `POST /compositions/{id}/agent/test` with `{type, name, inputs?, subjectId?}` returning
  `CompositionTestRun`.
- Produces: MCP tools `test_action_type(name, inputs?, subjectId?)` and `test_tool(name, params?,
  subjectId?)`; helper `formatTestRun(run)` exported from `server.js`.

- [ ] **Step 1: Write the failing Node test** (append to `app/src/test/node/workbench-mcp.test.js`)

```js
test("formatTestRun summarizes status, outputs, problems and output tails", () => {
    const text = helpers.formatTestRun({
        itemType: "action-type", itemName: "greet", status: "failed", exitCode: 0, durationMs: 12,
        outputs: { greeting: "hi" }, problems: ["Output: Missing required field 'count'."],
        stdout: "x".repeat(5000), stderr: "warn",
    });
    assert.match(text, /^FAILED: action-type greet \(exit 0, 12 ms\)/);
    assert.match(text, /Outputs: \{"greeting":"hi"\}/);
    assert.match(text, /- Output: Missing required field 'count'\./);
    assert.match(text, /stdout \(last 4000 chars\):\n…x{4000}/);
    assert.match(text, /stderr:\nwarn/);
});

test("formatTestRun explains a run that could not start", () => {
    assert.strictEqual(helpers.formatTestRun({ itemType: "action-type", itemName: "a", status: "error",
        message: "Test runs of AI Agent action types are not supported yet.", problems: [], stdout: "", stderr: "" }),
        "ERROR: action-type a\nTest runs of AI Agent action types are not supported yet.");
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `node --test app/src/test/node/workbench-mcp.test.js`
Expected: FAIL, `helpers.formatTestRun is not a function`.

- [ ] **Step 3: Implement in `server.js`**

Add the helper above `const TOOLS = [`:

```js
const TAIL_CHARS = 4000;

/**
 * Returns the end of a long text with a marker.
 * @param {string} text the text
 * @returns {{label: string, text: string}} the label suffix and the (possibly shortened) text
 */
function tail(text) {
    return text.length > TAIL_CHARS
        ? { label: ` (last ${TAIL_CHARS} chars)`, text: "…" + text.substring(text.length - TAIL_CHARS) }
        : { label: "", text };
}

/**
 * Formats a test run for the agent.
 * @param {object} run the CompositionTestRun
 * @returns {string} the summary
 */
function formatTestRun(run) {
    const head = `${String(run.status).toUpperCase()}: ${run.itemType} ${run.itemName}`;
    if (run.status === "error") {
        const lines = [head];
        if (run.message) lines.push(run.message);
        for (const p of run.problems || []) lines.push(`- ${p}`);
        return lines.join("\n");
    }
    const lines = [`${head} (exit ${run.exitCode}, ${run.durationMs} ms)`];
    if (run.outputs) lines.push(`Outputs: ${JSON.stringify(run.outputs)}`);
    if ((run.problems || []).length > 0) {
        lines.push("Problems:");
        for (const p of run.problems) lines.push(`- ${p}`);
    }
    for (const stream of ["stdout", "stderr"]) {
        const value = run[stream] || "";
        if (value) {
            const t = tail(value);
            lines.push(`${stream}${t.label}:\n${t.text}`);
        }
    }
    return lines.join("\n");
}
```

Add two entries to `TOOLS` (after `validate_composition`):

```js
    {
        name: "test_action_type",
        description: "Run a draft node or script action type with sample inputs. Returns the status, outputs, "
            + "output-validation problems, exit code, duration, stdout and stderr. The user sees the run in the "
            + "workbench's Test output strip. AI Agent action types cannot be test-run.",
        parameters: [
            { name: "name", type: "string", description: "Draft action type name", required: true },
            { name: "inputs", type: "object", description: "Input values by name", required: false },
            { name: "subjectId", type: "number", description: "Optional subject whose workspace to run in", required: false },
        ],
        handler: async (args) => {
            const body = { type: "action-type", name: args.name, inputs: asObject(args.inputs ?? {}, "inputs") };
            if (args.subjectId !== undefined) body.subjectId = Number(args.subjectId);
            return formatTestRun(JSON.parse(await axiomApi("POST", `${agentBase()}/test`, body)));
        },
    },
    {
        name: "test_tool",
        description: "Run a draft tool's script with sample parameter values, the way agents run it. Returns "
            + "the status, exit code, duration, stdout and stderr. The user sees the run in the workbench.",
        parameters: [
            { name: "name", type: "string", description: "Draft tool name", required: true },
            { name: "params", type: "object", description: "Parameter values by name", required: false },
            { name: "subjectId", type: "number", description: "Optional subject whose workspace to run in", required: false },
        ],
        handler: async (args) => {
            const body = { type: "tool", name: args.name, inputs: asObject(args.params ?? {}, "params") };
            if (args.subjectId !== undefined) body.subjectId = Number(args.subjectId);
            return formatTestRun(JSON.parse(await axiomApi("POST", `${agentBase()}/test`, body)));
        },
    },
```

Export it: `module.exports = { summarizeEvent, pickPath, truncateDeep, formatProblems, formatTestRun };`.
Check how the ListTools handler maps `type: "number"` parameters to JSON schema (if only `string` and
`object` are handled, add `number`).

- [ ] **Step 4: Update the template and its test**

In `axiom-workbench.json`:
- Add `"mcp__workbench__test_action_type"` and `"mcp__workbench__test_tool"` to `allowedTools` (after
  `mcp__workbench__validate_composition`).
- In the system prompt's working loop, change step 3 to: `3. Create or update action types and tools with
  upsert_item and fix every INVALID result. Test each node or script action type and each tool you create
  or change with test_action_type or test_tool, using realistic sample inputs, and fix what fails.`
- Add this rule after "Declare an input for everything an action uses.": `- Test action types and tools
  you create with test_action_type or test_tool before finishing a turn. Report a failing test you could
  not fix. AI Agent action types cannot be test-run.`

In `WorkbenchReferenceTest.promptCarriesEveryRule`, add
`"Test action types and tools you create with test_action_type or test_tool before finishing a turn"` to the
list, and add a test:

```java
    @Test
    void templateAllowsTheTestTools() throws Exception {
        String tools = template().path("allowedTools").toString();
        assertTrue(tools.contains("mcp__workbench__test_action_type"));
        assertTrue(tools.contains("mcp__workbench__test_tool"));
    }
```

- [ ] **Step 5: Run the tests**

Run: `node --test app/src/test/node/workbench-mcp.test.js` then
`mvn -q -pl app -am test -Dtest='WorkbenchReferenceTest,WorkbenchMcpServerScriptTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/resources/templates/axiom-workbench-mcp/server.js app/src/test/node/workbench-mcp.test.js \
        app/src/main/resources/templates/assistant-templates/axiom-workbench.json \
        app/src/test/java/io/apitomy/axiom/app/assistant/WorkbenchReferenceTest.java
git commit -m "feat(workbench): test_action_type and test_tool MCP tools"
```

---
### Task 5: UI data layer — types, API calls, SSE and `useWorkbench`

**Files:**
- Modify: `ui/src/config/compositions.ts`
- Modify: `ui/src/pages/workbench/useWorkbench.ts`
- Test: `ui/src/pages/workbench/useWorkbench.test.tsx`, `ui/src/pages/workbench/WorkbenchPage.test.tsx`
  (its `useWorkbench` mock must gain the new fields)

**Interfaces:**
- Consumes (Task 3): the three routes and the `composition-test-run` SSE event.
- Produces (`compositions.ts`):
  - `export type TestRunStatus = "running" | "passed" | "failed" | "error";`
  - `export interface CompositionTestRun { id: string; itemId: number; itemType: "action-type" | "tool";
    itemName: string; author: "user" | "ai"; turnId?: string; subjectId?: number; status: TestRunStatus;
    startedAt: string; durationMs?: number; inputs?: Record<string, unknown>; outputs?: Record<string,
    unknown>; stdout?: string; stderr?: string; exitCode?: number; problems?: string[]; message?: string;
    resolvedScript?: string; }`
  - `export interface CompositionTestRunEvent { compositionId: number; run: CompositionTestRun; }`
  - `testItem(id: number, itemId: number, data: { inputs: Record<string, unknown>; subjectId?: number }):
    Promise<CompositionTestRun>`; `listTestRuns(id: number): Promise<CompositionTestRun[]>`
  - `CompositionEventHandlers.testRun?: (event: CompositionTestRunEvent) => void`
  - `export function mergeTestRun(runs: CompositionTestRun[], run: CompositionTestRun): CompositionTestRun[]`
    — replaces the run with the same `id` in place, or prepends it; keeps at most 50.
- Produces (`WorkbenchState`): `testRuns: CompositionTestRun[]` (newest first) and
  `runTest(itemId: number, inputs: Record<string, unknown>, subjectId?: number): Promise<CompositionTestRun>`.

- [ ] **Step 1: Write the failing tests** (append to `useWorkbench.test.tsx`; in `beforeEach` add
  `vi.spyOn(c, "listTestRuns").mockResolvedValue([]);`)

```tsx
const run = (id: string, status: c.TestRunStatus): c.CompositionTestRun => ({
    id, itemId: 11, itemType: "action-type", itemName: "greet", author: "ai", status, startedAt: "",
});

describe("test runs", () => {
    it("loads runs and merges SSE updates by id, newest first", async () => {
        vi.spyOn(c, "listTestRuns").mockResolvedValue([run("a", "passed")]);
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(result.current.testRuns).toHaveLength(1));
        act(() => sse.testRun?.({ compositionId: 1, run: run("b", "running") }));
        expect(result.current.testRuns.map((r) => r.id)).toEqual(["b", "a"]);
        act(() => sse.testRun?.({ compositionId: 1, run: run("b", "failed") }));
        expect(result.current.testRuns.map((r) => `${r.id}:${r.status}`)).toEqual(["b:failed", "a:passed"]);
    });

    it("runs a test and keeps its result", async () => {
        const test = vi.spyOn(c, "testItem").mockResolvedValue(run("c", "passed"));
        const { result } = renderHook(() => useWorkbench(1));
        await waitFor(() => expect(result.current.items).toHaveLength(1));
        await act(async () => { await result.current.runTest(11, { who: "bob" }, 5); });
        expect(test).toHaveBeenCalledWith(1, 11, { inputs: { who: "bob" }, subjectId: 5 });
        expect(result.current.testRuns[0].id).toBe("c");
    });
});

describe("mergeTestRun", () => {
    it("caps the list at 50", () => {
        const many = Array.from({ length: 50 }, (_v, i) => run(String(i), "passed"));
        expect(c.mergeTestRun(many, run("new", "running"))).toHaveLength(50);
        expect(c.mergeTestRun(many, run("new", "running"))[0].id).toBe("new");
    });
});
```

In `WorkbenchPage.test.tsx`, add `testRuns: [], runTest: async () => ({}) as never,` to the mocked
`useWorkbench` return value.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd ui && timeout 280 npx vitest run src/pages/workbench/useWorkbench.test.tsx`
Expected: FAIL (`listTestRuns`/`testItem`/`mergeTestRun` missing; TypeScript errors on `testRun`).

- [ ] **Step 3: Implement in `compositions.ts`**

Add the types listed under Interfaces (with one-line doc comments), then:

```ts
/** Test-runs a draft action type or tool and returns the finished run. */
export function testItem(id: number, itemId: number,
    data: { inputs: Record<string, unknown>; subjectId?: number }): Promise<CompositionTestRun> {
    return request("POST", `${it(id, itemId)}/test`, data);
}

/** Lists a composition's recent test runs, newest first. */
export function listTestRuns(id: number): Promise<CompositionTestRun[]> {
    return request("GET", `${c(id)}/test-runs`);
}

const MAX_TEST_RUNS: number = 50;

/**
 * Adds or updates a run in a newest-first list.
 * @param runs the current runs
 * @param run the new or updated run
 * @returns the new list
 */
export function mergeTestRun(runs: CompositionTestRun[], run: CompositionTestRun): CompositionTestRun[] {
    if (runs.some((r) => r.id === run.id)) {
        return runs.map((r) => (r.id === run.id ? run : r));
    }
    return [run, ...runs].slice(0, MAX_TEST_RUNS);
}
```

(`c(id)` and `it(id, itemId)` are the existing path helpers used by `addItem`/`fetchItem`.) Add
`testRun?: (event: CompositionTestRunEvent) => void;` to `CompositionEventHandlers` and a case to
`subscribeComposition`:

```ts
            case "composition-test-run":
                handlers.testRun?.(event.data as unknown as CompositionTestRunEvent);
                break;
```

- [ ] **Step 4: Implement in `useWorkbench.ts`**

- Import `listTestRuns`, `testItem`, `mergeTestRun`, `type CompositionTestRun`.
- Add to `WorkbenchState` (with doc comments): `testRuns: CompositionTestRun[];` and
  `runTest(itemId: number, inputs: Record<string, unknown>, subjectId?: number): Promise<CompositionTestRun>;`
- State: `const [testRuns, setTestRuns] = useState<CompositionTestRun[]>([]);`
- In the initial load effect, next to `startSession`/`validate`, load runs (failures are logged, not fatal):

```ts
            void listTestRuns(compositionId)
                .then((runs) => { if (aliveRef.current && !isCancelled()) setTestRuns(runs); })
                .catch(console.error);
```

- In the SSE subscription handlers add:

```ts
            testRun: (ev) => setTestRuns((prev) => mergeTestRun(prev, ev.run)),
```

- Add the action and return both new members:

```ts
    const runTest = useCallback(async (itemId: number, inputs: Record<string, unknown>, subjectId?: number) => {
        const run: CompositionTestRun = await testItem(compositionId, itemId,
            subjectId === undefined ? { inputs } : { inputs, subjectId });
        if (aliveRef.current) {
            setTestRuns((prev) => mergeTestRun(prev, run));
        }
        return run;
    }, [compositionId]);
```

Note: the test expects `{ inputs, subjectId: 5 }` when a subject is given and `{ inputs }` otherwise.

- [ ] **Step 5: Run the tests**

Run: `cd ui && timeout 250 npx tsc -b --noEmit && timeout 280 npx vitest run src/pages/workbench`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add ui/src/config/compositions.ts ui/src/pages/workbench/useWorkbench.ts \
        ui/src/pages/workbench/useWorkbench.test.tsx ui/src/pages/workbench/WorkbenchPage.test.tsx
git commit -m "feat(workbench): load, run and stream test runs in the workbench state"
```

---
### Task 6: `TestRunDialog` — sample-input form

**Files:**
- Create: `ui/src/pages/workbench/TestRunDialog.tsx`
- Create: `ui/src/pages/workbench/testRunLogic.ts`
- Test: `ui/src/pages/workbench/TestRunDialog.test.tsx`, `ui/src/pages/workbench/testRunLogic.test.ts`

**Interfaces:**
- Produces (`testRunLogic.ts`):
  - `export interface TestField { name: string; type: string; required: boolean; description?: string }`
  - `export function testFields(type: "action-type" | "tool", content: Record<string, unknown>): TestField[]`
    — action types read `inputs`, tools read `parameters`; entries without a name are skipped; a missing
    type means `string`.
  - `export function testUnsupportedReason(type: "action-type" | "tool", content: Record<string, unknown>):
    string | null` — returns `"Test runs of AI Agent action types are not supported yet."` for an action
    type whose `executionMode` is not `node` or `script`, otherwise `null`.
  - `export function parseFieldValue(field: TestField, text: string): { value?: unknown; error?: string }` —
    empty text gives `{}` (omitted); `string` fields keep the text; `number` must parse as a finite number;
    `boolean` accepts `true`/`false`; `object`/`array` must parse as JSON of that shape.
- Produces (`TestRunDialog.tsx`):
  `export function TestRunDialog(props: { itemName: string; itemType: "action-type" | "tool"; content:
  Record<string, unknown>; initialValues: Record<string, string>; initialSubjectId?: number; onRun(inputs:
  Record<string, unknown>, values: Record<string, string>, subjectId?: number): void; onClose(): void })`.
  `values` are the raw texts, so the page can prefill the next run of the same item.

- [ ] **Step 1: Write the failing tests**

`testRunLogic.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { parseFieldValue, testFields, testUnsupportedReason } from "./testRunLogic";

describe("testRunLogic", () => {
    it("reads action type inputs and tool parameters", () => {
        expect(testFields("action-type", { inputs: [{ name: "a", type: "number", required: true }, { type: "x" }] }))
            .toEqual([{ name: "a", type: "number", required: true, description: undefined }]);
        expect(testFields("tool", { parameters: [{ name: "p", description: "d" }] }))
            .toEqual([{ name: "p", type: "string", required: false, description: "d" }]);
    });

    it("refuses agent action types only", () => {
        expect(testUnsupportedReason("action-type", { executionMode: "agent" }))
            .toBe("Test runs of AI Agent action types are not supported yet.");
        expect(testUnsupportedReason("action-type", { executionMode: "node" })).toBeNull();
        expect(testUnsupportedReason("tool", {})).toBeNull();
    });

    it("parses values by type", () => {
        const f = (type: string) => ({ name: "x", type, required: false });
        expect(parseFieldValue(f("string"), "")).toEqual({});
        expect(parseFieldValue(f("string"), " hi ")).toEqual({ value: " hi " });
        expect(parseFieldValue(f("number"), "4.5")).toEqual({ value: 4.5 });
        expect(parseFieldValue(f("number"), "four").error).toBe("Enter a number.");
        expect(parseFieldValue(f("boolean"), "true")).toEqual({ value: true });
        expect(parseFieldValue(f("boolean"), "yes").error).toBe("Enter true or false.");
        expect(parseFieldValue(f("array"), "[1,2]")).toEqual({ value: [1, 2] });
        expect(parseFieldValue(f("array"), "{}").error).toBe("Enter a JSON array.");
        expect(parseFieldValue(f("object"), "{\"a\":1}")).toEqual({ value: { a: 1 } });
        expect(parseFieldValue(f("object"), "[").error).toBe("Enter a JSON object.");
    });
});
```

`TestRunDialog.test.tsx`:

```tsx
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import * as api from "../../config/api";
import { TestRunDialog } from "./TestRunDialog";

vi.spyOn(api, "fetchSubjects").mockResolvedValue({ items: [{ id: 5, name: "repo-x" }], count: 1 } as never);

const content = { executionMode: "script", inputs: [
    { name: "who", type: "string", required: true }, { name: "times", type: "number", required: false }] };

describe("TestRunDialog", () => {
    it("runs with parsed inputs and the raw values", () => {
        const onRun = vi.fn();
        render(<TestRunDialog itemName="greet" itemType="action-type" content={content} initialValues={{ who: "ann" }}
            onRun={onRun} onClose={vi.fn()} />);
        fireEvent.change(screen.getByLabelText("times"), { target: { value: "3" } });
        fireEvent.click(screen.getByRole("button", { name: "Run" }));
        expect(onRun).toHaveBeenCalledWith({ who: "ann", times: 3 }, { who: "ann", times: "3" }, undefined);
    });

    it("blocks a run with a missing required input or an invalid value", () => {
        const onRun = vi.fn();
        render(<TestRunDialog itemName="greet" itemType="action-type" content={content} initialValues={{}}
            onRun={onRun} onClose={vi.fn()} />);
        fireEvent.change(screen.getByLabelText("times"), { target: { value: "x" } });
        fireEvent.click(screen.getByRole("button", { name: "Run" }));
        expect(onRun).not.toHaveBeenCalled();
        expect(screen.getByText("Required.")).toBeTruthy();
        expect(screen.getByText("Enter a number.")).toBeTruthy();
    });

    it("explains that agent action types cannot run", () => {
        render(<TestRunDialog itemName="a" itemType="action-type" content={{ executionMode: "agent" }}
            initialValues={{}} onRun={vi.fn()} onClose={vi.fn()} />);
        expect(screen.getByText("Test runs of AI Agent action types are not supported yet.")).toBeTruthy();
        expect(screen.queryByRole("button", { name: "Run" })).toBeNull();
    });
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd ui && timeout 280 npx vitest run src/pages/workbench/testRunLogic.test.ts src/pages/workbench/TestRunDialog.test.tsx`
Expected: FAIL (modules not found).

- [ ] **Step 3: Implement `testRunLogic.ts`**

```ts
/** A field of the test form: an action type input or a tool parameter. */
export interface TestField {
    name: string;
    type: string;
    required: boolean;
    description?: string;
}

/** Message shown for action types that cannot be test-run. */
export const AGENT_UNSUPPORTED: string = "Test runs of AI Agent action types are not supported yet.";

/**
 * Lists the fields to ask for: an action type's inputs or a tool's parameters.
 * @param type the item type
 * @param content the draft content
 * @returns the fields, in declaration order
 */
export function testFields(type: "action-type" | "tool", content: Record<string, unknown>): TestField[] {
    const raw: unknown = type === "tool" ? content.parameters : content.inputs;
    if (!Array.isArray(raw)) {
        return [];
    }
    return raw
        .filter((f: unknown): f is Record<string, unknown> =>
            !!f && typeof f === "object" && typeof (f as Record<string, unknown>).name === "string"
            && (f as Record<string, unknown>).name !== "")
        .map((f: Record<string, unknown>) => ({
            name: f.name as string,
            type: typeof f.type === "string" ? f.type : "string",
            required: f.required === true,
            description: typeof f.description === "string" ? f.description : undefined,
        }));
}

/**
 * Explains why an item cannot be test-run.
 * @param type the item type
 * @param content the draft content
 * @returns the reason, or null when it can run
 */
export function testUnsupportedReason(type: "action-type" | "tool", content: Record<string, unknown>): string | null {
    if (type === "tool") {
        return null;
    }
    return content.executionMode === "node" || content.executionMode === "script" ? null : AGENT_UNSUPPORTED;
}

/**
 * Parses the text typed for a field.
 * @param field the field
 * @param text the typed text
 * @returns the value, nothing for empty text, or an error message
 */
export function parseFieldValue(field: TestField, text: string): { value?: unknown; error?: string } {
    if (text.trim() === "") {
        return {};
    }
    switch (field.type) {
        case "number": {
            const n: number = Number(text);
            return Number.isFinite(n) ? { value: n } : { error: "Enter a number." };
        }
        case "boolean":
            return text.trim() === "true" ? { value: true }
                : text.trim() === "false" ? { value: false } : { error: "Enter true or false." };
        case "array":
        case "object": {
            const message: string = field.type === "array" ? "Enter a JSON array." : "Enter a JSON object.";
            try {
                const parsed: unknown = JSON.parse(text);
                const ok: boolean = field.type === "array" ? Array.isArray(parsed)
                    : !!parsed && typeof parsed === "object" && !Array.isArray(parsed);
                return ok ? { value: parsed } : { error: message };
            } catch {
                return { error: message };
            }
        }
        default:
            return { value: text };
    }
}
```

- [ ] **Step 4: Implement `TestRunDialog.tsx`**

```tsx
import { useEffect, useState } from "react";
import {
    Alert, Button, Form, FormGroup, FormHelperText, FormSelect, FormSelectOption, HelperText, HelperTextItem,
    Modal, ModalBody, ModalFooter, ModalHeader, TextArea, TextInput,
} from "@patternfly/react-core";
import { fetchSubjects } from "../../config/api";
import { parseFieldValue, type TestField, testFields, testUnsupportedReason } from "./testRunLogic";

/** Props for {@link TestRunDialog}. */
export interface TestRunDialogProps {
    itemName: string;
    itemType: "action-type" | "tool";
    content: Record<string, unknown>;
    /** Texts typed in the previous run of this item. */
    initialValues: Record<string, string>;
    initialSubjectId?: number;
    /** Called with the parsed inputs, the raw texts and the chosen subject. */
    onRun(inputs: Record<string, unknown>, values: Record<string, string>, subjectId?: number): void;
    onClose(): void;
}

/**
 * Asks for sample inputs (or tool parameters) and an optional subject, then starts a test run.
 */
export function TestRunDialog(props: TestRunDialogProps) {
    const fields: TestField[] = testFields(props.itemType, props.content);
    const unsupported: string | null = testUnsupportedReason(props.itemType, props.content);
    const [values, setValues] = useState<Record<string, string>>(props.initialValues);
    const [errors, setErrors] = useState<Record<string, string>>({});
    const [subjectId, setSubjectId] = useState<string>(props.initialSubjectId ? String(props.initialSubjectId) : "");
    const [subjects, setSubjects] = useState<{ id: number; name: string }[]>([]);
    const noun: string = props.itemType === "tool" ? "parameters" : "inputs";

    useEffect(() => {
        fetchSubjects(1, 100).then((r) => setSubjects(r.items.map((s) => ({ id: s.id, name: s.name }))))
            .catch(console.error);
    }, []);

    const run = () => {
        const nextErrors: Record<string, string> = {};
        const inputs: Record<string, unknown> = {};
        for (const field of fields) {
            const parsed = parseFieldValue(field, values[field.name] ?? "");
            if (parsed.error) {
                nextErrors[field.name] = parsed.error;
            } else if (parsed.value !== undefined) {
                inputs[field.name] = parsed.value;
            } else if (field.required) {
                nextErrors[field.name] = "Required.";
            }
        }
        setErrors(nextErrors);
        if (Object.keys(nextErrors).length === 0) {
            props.onRun(inputs, values, subjectId ? Number(subjectId) : undefined);
        }
    };

    return (
        <Modal isOpen variant="medium" onClose={props.onClose} aria-labelledby="test-run-title">
            <ModalHeader title={`Test ${props.itemName}`} labelId="test-run-title" />
            <ModalBody>
                {unsupported ? <Alert isInline variant="info" title={unsupported} /> : (
                    <Form onSubmit={(e) => { e.preventDefault(); run(); }}>
                        {fields.length === 0 && <p className="axiom-text-subtle">No {noun} are declared.</p>}
                        {fields.map((field) => {
                            const multiline: boolean = field.type === "object" || field.type === "array";
                            const id: string = `test-field-${field.name}`;
                            const onChange = (_e: unknown, v: string) => setValues((prev) => ({ ...prev, [field.name]: v }));
                            return (
                                <FormGroup key={field.name} label={field.name} isRequired={field.required} fieldId={id}>
                                    {multiline
                                        ? <TextArea id={id} aria-label={field.name} value={values[field.name] ?? ""}
                                            onChange={onChange} rows={4} resizeOrientation="vertical" />
                                        : <TextInput id={id} aria-label={field.name} value={values[field.name] ?? ""}
                                            onChange={onChange} />}
                                    <FormHelperText>
                                        <HelperText>
                                            {errors[field.name]
                                                ? <HelperTextItem variant="error">{errors[field.name]}</HelperTextItem>
                                                : <HelperTextItem>{field.type}{field.description ? ` — ${field.description}` : ""}</HelperTextItem>}
                                        </HelperText>
                                    </FormHelperText>
                                </FormGroup>
                            );
                        })}
                        <FormGroup label="Subject" fieldId="test-subject">
                            <FormSelect id="test-subject" value={subjectId} onChange={(_e, v) => setSubjectId(v)}>
                                <FormSelectOption value="" label="None — run in a temporary directory" />
                                {subjects.map((s) => <FormSelectOption key={s.id} value={String(s.id)} label={s.name} />)}
                            </FormSelect>
                        </FormGroup>
                    </Form>
                )}
            </ModalBody>
            <ModalFooter>
                {!unsupported && <Button variant="primary" onClick={run}>Run</Button>}
                <Button variant="link" onClick={props.onClose}>{unsupported ? "Close" : "Cancel"}</Button>
            </ModalFooter>
        </Modal>
    );
}
```

If PatternFly's focus trap fails in jsdom (as in `WorkbenchListPage.test.tsx`), mock `Modal` the same way
that test does. Use `elementToFocus` on the first field (`#test-field-<first>`) when there is one, so the
Close button is not focused first.

- [ ] **Step 5: Run the tests**

Run: `cd ui && timeout 250 npx tsc -b --noEmit && timeout 280 npx vitest run src/pages/workbench`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add ui/src/pages/workbench/TestRunDialog.tsx ui/src/pages/workbench/testRunLogic.ts \
        ui/src/pages/workbench/TestRunDialog.test.tsx ui/src/pages/workbench/testRunLogic.test.ts
git commit -m "feat(workbench): test run dialog for sample inputs"
```

---
### Task 7: Test output strip and workbench integration

**Files:**
- Create: `ui/src/pages/workbench/TestOutputStrip.tsx`
- Test: `ui/src/pages/workbench/TestOutputStrip.test.tsx`
- Modify: `ui/src/pages/workbench/WorkbenchPage.tsx`, `WorkbenchPage.css`, `workbenchPrefs.ts`, `ItemTab.tsx`
- Test: `ui/src/pages/workbench/WorkbenchPage.test.tsx`, `ui/src/pages/workbench/workbenchPrefs.test.ts` (if
  it exists; otherwise cover the pref in `WorkbenchPage.test.tsx`)

**Interfaces:**
- Consumes: `wb.testRuns`, `wb.runTest` (Task 5), `TestRunDialog` (Task 6).
- Produces: `export function TestOutputStrip(props: { runs: CompositionTestRun[]; collapsed: boolean;
  onToggle(): void; onOpenItem(itemId: number): void; actions?: ReactNode })`; pref
  `WorkbenchPrefs.testStripCollapsed: boolean` (default `true`).

Behaviour:
- The strip sits at the bottom of the centre pane, under the tab content, for every tab.
- Header: a collapse toggle, the title **Test output**, the number of runs, and `actions` on the right (the
  page passes a **Test…** button there).
- Expanded body: 220 px high, scrolls; one row per run, newest first:
  status icon (spinner for `running`, green check `passed`, red circle `failed`, orange triangle `error`),
  the item name as a link button that opens the item's tab, an `AI` label for `author: "ai"`, the start
  time (`toLocaleTimeString()`), the duration, the exit code, and a one-line summary (the `message`, else the
  first problem, else `Outputs: <json>` for action types, else nothing).
  Clicking the row's expand toggle shows: Inputs (JSON), Outputs (JSON), Problems (list), stdout and
  stderr (each a `<pre>` with max-height 200 px), and the resolved script in a collapsed
  `ExpandableSection`.
- When a run starts (a run id not seen before appears at the top) the strip expands.
- **Test…** is enabled only when the active tab is an action type or tool whose content is loaded; it
  flushes that tab's pending save (`ItemTabHandle.flush()`) before opening `TestRunDialog`. After **Run** the
  dialog closes immediately and the run shows as `running` in the strip (from SSE) until it finishes; if
  the request fails, show the error in an inline danger `Alert` above the strip with a Dismiss link.
- The page remembers the raw values and subject typed for each item (in component state, keyed by item id)
  and passes them back as `initialValues`/`initialSubjectId` next time.
- In the workbench, the tool editor's old **Test** tab is hidden: `ItemTab` no longer passes `toolId` to
  `ToolEditor`. The tool detail page keeps its Test tab.

- [ ] **Step 1: Write the failing strip tests**

```tsx
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type { CompositionTestRun } from "../../config/compositions";
import { TestOutputStrip } from "./TestOutputStrip";

const runs: CompositionTestRun[] = [
    { id: "b", itemId: 11, itemType: "action-type", itemName: "greet", author: "ai", status: "failed",
        startedAt: "2026-10-13T10:00:00Z", durationMs: 40, exitCode: 0, outputs: {},
        problems: ["Output: Missing required field 'greeting'."], stdout: "{}", stderr: "warn" },
    { id: "a", itemId: 12, itemType: "tool", itemName: "list-labels", author: "user", status: "passed",
        startedAt: "2026-10-13T09:59:00Z", durationMs: 5, exitCode: 0, problems: [], stdout: "bug\n", stderr: "" },
];

describe("TestOutputStrip", () => {
    it("lists runs with status, author and summary", () => {
        render(<TestOutputStrip runs={runs} collapsed={false} onToggle={vi.fn()} onOpenItem={vi.fn()} />);
        expect(screen.getByText("Test output")).toBeTruthy();
        expect(screen.getByText("2")).toBeTruthy();
        expect(screen.getByLabelText("failed")).toBeTruthy();
        expect(screen.getByLabelText("passed")).toBeTruthy();
        expect(screen.getAllByText("AI")).toHaveLength(1);
        expect(screen.getByText("Output: Missing required field 'greeting'.")).toBeTruthy();
    });

    it("opens the item and expands details", () => {
        const onOpenItem = vi.fn();
        render(<TestOutputStrip runs={runs} collapsed={false} onToggle={vi.fn()} onOpenItem={onOpenItem} />);
        fireEvent.click(screen.getByRole("button", { name: "greet" }));
        expect(onOpenItem).toHaveBeenCalledWith(11);
        fireEvent.click(screen.getAllByRole("button", { name: "Show details" })[0]);
        expect(screen.getByText("warn")).toBeTruthy();
    });

    it("hides the list when collapsed", () => {
        const onToggle = vi.fn();
        render(<TestOutputStrip runs={runs} collapsed onToggle={onToggle} onOpenItem={vi.fn()} />);
        expect(screen.queryByRole("button", { name: "greet" })).toBeNull();
        fireEvent.click(screen.getByRole("button", { name: "Expand test output" }));
        expect(onToggle).toHaveBeenCalled();
    });
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd ui && timeout 280 npx vitest run src/pages/workbench/TestOutputStrip.test.tsx`
Expected: FAIL (module not found).

- [ ] **Step 3: Implement `TestOutputStrip.tsx`**

```tsx
import { type ReactNode, useState } from "react";
import { Button, ExpandableSection, Label, Spinner } from "@patternfly/react-core";
import {
    AngleDownIcon, AngleRightIcon, AngleUpIcon, CheckCircleIcon, ExclamationCircleIcon, ExclamationTriangleIcon,
} from "@patternfly/react-icons";
import type { CompositionTestRun } from "../../config/compositions";

/** Props for {@link TestOutputStrip}. */
export interface TestOutputStripProps {
    /** Runs, newest first. */
    runs: CompositionTestRun[];
    collapsed: boolean;
    onToggle(): void;
    onOpenItem(itemId: number): void;
    /** Controls shown at the right of the header (e.g. the Test… button). */
    actions?: ReactNode;
}

function StatusIcon({ status }: { status: CompositionTestRun["status"] }) {
    switch (status) {
        case "running":
            return <Spinner size="sm" aria-label="running" />;
        case "passed":
            return <CheckCircleIcon aria-label="passed" color="var(--pf-t--global--icon--color--status--success--default)" />;
        case "failed":
            return <ExclamationCircleIcon aria-label="failed" color="var(--pf-t--global--icon--color--status--danger--default)" />;
        default:
            return <ExclamationTriangleIcon aria-label="error" color="var(--pf-t--global--icon--color--status--warning--default)" />;
    }
}

/**
 * One-line summary of a run.
 * @param run the run
 * @returns the summary text
 */
export function runSummary(run: CompositionTestRun): string {
    if (run.status === "running") {
        return "Running…";
    }
    if (run.message) {
        return run.message;
    }
    if (run.problems && run.problems.length > 0) {
        return run.problems[0];
    }
    if (run.itemType === "action-type" && run.outputs) {
        return `Outputs: ${JSON.stringify(run.outputs)}`;
    }
    return "";
}

function Details({ run }: { run: CompositionTestRun }) {
    const [scriptOpen, setScriptOpen] = useState<boolean>(false);
    return (
        <div className="axiom-test-strip__details">
            <h4>Inputs</h4>
            <pre>{JSON.stringify(run.inputs ?? {}, null, 2)}</pre>
            {run.outputs && <><h4>Outputs</h4><pre>{JSON.stringify(run.outputs, null, 2)}</pre></>}
            {(run.problems ?? []).length > 0 && (
                <><h4>Problems</h4><ul>{run.problems?.map((p) => <li key={p}>{p}</li>)}</ul></>
            )}
            {run.stdout && <><h4>stdout</h4><pre>{run.stdout}</pre></>}
            {run.stderr && <><h4>stderr</h4><pre>{run.stderr}</pre></>}
            {run.resolvedScript && (
                <ExpandableSection toggleText="Resolved script" isExpanded={scriptOpen}
                    onToggle={(_e, open) => setScriptOpen(open)}>
                    <pre>{run.resolvedScript}</pre>
                </ExpandableSection>
            )}
        </div>
    );
}

/**
 * Collapsible list of the composition's recent test runs, by the user and the AI.
 */
export function TestOutputStrip({ runs, collapsed, onToggle, onOpenItem, actions }: TestOutputStripProps) {
    const [open, setOpen] = useState<Set<string>>(new Set<string>());
    const toggle = (id: string) => setOpen((prev) => {
        const next = new Set<string>(prev);
        if (next.has(id)) {
            next.delete(id);
        } else {
            next.add(id);
        }
        return next;
    });
    return (
        <section className="axiom-test-strip" aria-label="Test output">
            <div className="axiom-test-strip__header">
                <Button variant="plain" size="sm" onClick={onToggle}
                    aria-label={collapsed ? "Expand test output" : "Collapse test output"}
                    icon={collapsed ? <AngleUpIcon /> : <AngleDownIcon />} />
                <span className="axiom-workbench__pane-title">Test output</span>
                <Label isCompact>{runs.length}</Label>
                <span className="axiom-workbench__spacer" />
                {actions}
            </div>
            {!collapsed && (
                <div className="axiom-test-strip__body">
                    {runs.length === 0 && (
                        <p className="axiom-text-subtle axiom-test-strip__empty">
                            No test runs yet. Use Test… on an action type or tool, or ask the AI to test one.
                        </p>
                    )}
                    <ul className="axiom-test-strip__list">
                        {runs.map((run) => (
                            <li key={run.id}>
                                <div className="axiom-test-strip__row">
                                    <Button variant="plain" size="sm" aria-label={open.has(run.id) ? "Hide details" : "Show details"}
                                        aria-expanded={open.has(run.id)} onClick={() => toggle(run.id)}
                                        icon={open.has(run.id) ? <AngleDownIcon /> : <AngleRightIcon />} />
                                    <StatusIcon status={run.status} />
                                    <Button variant="link" isInline onClick={() => onOpenItem(run.itemId)}>{run.itemName}</Button>
                                    {run.author === "ai" && <Label isCompact color="purple">AI</Label>}
                                    <span className="axiom-text-subtle">
                                        {new Date(run.startedAt).toLocaleTimeString()}
                                        {run.durationMs !== undefined && run.status !== "running" ? ` · ${run.durationMs} ms` : ""}
                                        {run.exitCode !== undefined ? ` · exit ${run.exitCode}` : ""}
                                    </span>
                                    <span className="axiom-test-strip__summary">{runSummary(run)}</span>
                                </div>
                                {open.has(run.id) && <Details run={run} />}
                            </li>
                        ))}
                    </ul>
                </div>
            )}
        </section>
    );
}
```

CSS (append to `WorkbenchPage.css`):

```css
/* Test output strip at the bottom of the centre pane. */
.axiom-test-strip {
    flex: 0 0 auto;
    border-top: 1px solid var(--pf-t--global--border--color--default, #d2d2d2);
}

.axiom-test-strip__header {
    display: flex;
    align-items: center;
    gap: var(--pf-t--global--spacer--sm, 8px);
    padding: 0 var(--pf-t--global--spacer--sm, 8px);
}

.axiom-test-strip__body {
    height: 220px;
    overflow: auto;
    padding: 0 var(--pf-t--global--spacer--sm, 8px) var(--pf-t--global--spacer--sm, 8px);
}

.axiom-test-strip__list {
    list-style: none;
    margin: 0;
    padding: 0;
}

.axiom-test-strip__row {
    display: flex;
    align-items: center;
    gap: var(--pf-t--global--spacer--sm, 8px);
    min-width: 0;
}

.axiom-test-strip__summary {
    flex: 1 1 auto;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.axiom-test-strip__details {
    padding: 0 0 var(--pf-t--global--spacer--sm, 8px) 56px;
}

.axiom-test-strip__details h4 {
    margin-top: var(--pf-t--global--spacer--sm, 8px);
    font-weight: var(--pf-t--global--font--weight--body--bold, 600);
}

.axiom-test-strip__details pre {
    max-height: 200px;
    overflow: auto;
    font-family: var(--pf-t--global--font--family--mono);
    font-size: var(--pf-t--global--font--size--sm);
    white-space: pre-wrap;
    background: var(--pf-t--global--background--color--secondary--default);
    padding: var(--pf-t--global--spacer--xs, 4px) var(--pf-t--global--spacer--sm, 8px);
}

.axiom-test-strip__empty {
    margin: var(--pf-t--global--spacer--sm, 8px) 0;
}
```

- [ ] **Step 4: Add the pref**

In `workbenchPrefs.ts`: add `testStripCollapsed: boolean;` to `WorkbenchPrefs`, `testStripCollapsed: true`
to `defaults()`, and in `loadPrefs()`:
`testStripCollapsed: typeof raw.testStripCollapsed === "boolean" ? raw.testStripCollapsed : d.testStripCollapsed,`.

- [ ] **Step 5: Write the failing page tests** (append to `WorkbenchPage.test.tsx`)

Make the mocked `useWorkbench` return a tool item `11` with loaded content and a `runTest` spy:

```tsx
// in the mock: items gains { id: 11, type: "tool", name: "my-tool", ... } (already there from the
// middle-click test), contents gains 11: { name: "my-tool", parameters: [{ name: "who", required: true }],
// scriptTemplate: "echo {{who}}" }, and:
//   testRuns: [], runTest,
// with `const runTest = vi.fn().mockResolvedValue({});` declared next to `setContent`.

    it("tests the active tool from the Test output strip", async () => {
        renderPage();
        fireEvent.click(screen.getByRole("button", { name: /^my-tool/ }));
        fireEvent.click(screen.getByRole("button", { name: "Test…" }));
        fireEvent.change(await screen.findByLabelText("who"), { target: { value: "ann" } });
        fireEvent.click(screen.getByRole("button", { name: "Run" }));
        await waitFor(() => expect(runTest).toHaveBeenCalledWith(11, { who: "ann" }, undefined));
    });

    it("disables Test… on the workflow tab", () => {
        renderPage();
        expect((screen.getByRole("button", { name: "Test…" }) as HTMLButtonElement).disabled).toBe(true);
    });
```

`ItemTab` is not mocked in this file and will render `ToolEditor`; if that makes the test noisy, mock
`./ItemTab` with a component that registers a `flush` handle (`useImperativeHandle(props.ref, () => ({ flush:
async () => {} }))`), the same way `WorkflowTab` is mocked. Mock `fetchSubjects` from `../../config/api`
to resolve `{ items: [], count: 0 }`.

- [ ] **Step 6: Integrate in `WorkbenchPage.tsx`**

- Imports: `TestOutputStrip`, `TestRunDialog`, `type CompositionTestRun`, `BeakerIcon` from
  `@patternfly/react-icons` (for the button icon; skip the icon if it is not available).
- State:

```tsx
    const [testing, setTesting] = useState<number | null>(null);
    const [testError, setTestError] = useState<string | null>(null);
    const testValues = useRef<Map<number, { values: Record<string, string>; subjectId?: number }>>(new Map());
```

- The testable active item:

```tsx
    const testable: CompositionItem | undefined = activeItem && activeItem.type !== "workflow"
        && wb.contents[activeItem.id] ? activeItem : undefined;

    const openTest = async () => {
        if (!testable) {
            return;
        }
        await itemTabRefs.current.get(testable.id)?.flush();
        setTesting(testable.id);
    };

    const runTest = (itemId: number, inputs: Record<string, unknown>, values: Record<string, string>,
                     subjectId?: number) => {
        testValues.current.set(itemId, { values, subjectId });
        setTesting(null);
        setTestError(null);
        wb.runTest(itemId, inputs, subjectId).catch((e: unknown) =>
            setTestError(e instanceof Error ? e.message : String(e)));
    };
```

- Expand the strip when a new run appears:

```tsx
    const newestRunId: string | undefined = wb.testRuns[0]?.id;
    const seenRunId = useRef<string | undefined>(undefined);
    useEffect(() => {
        if (newestRunId && seenRunId.current !== undefined && newestRunId !== seenRunId.current
                && prefs.testStripCollapsed) {
            updatePrefs({ testStripCollapsed: false });
        }
        seenRunId.current = newestRunId ?? seenRunId.current ?? "";
    }, [newestRunId, prefs.testStripCollapsed]);
```

  Use the page's existing way of changing and saving prefs (look at how `leftCollapsed` is toggled and
  saved, and call the same helper instead of `updatePrefs` if it is named differently). Initialising
  `seenRunId` to `""` after the first render keeps the runs loaded on mount from expanding the strip.

- Inside `<main className="axiom-workbench__centre">`, after `</Tabs>`:

```tsx
                {testError && (
                    <Alert isInline variant="danger" title={`Could not run the test: ${testError}`}
                        actionLinks={<AlertActionLink onClick={() => setTestError(null)}>Dismiss</AlertActionLink>} />
                )}
                <TestOutputStrip runs={wb.testRuns} collapsed={prefs.testStripCollapsed}
                    onToggle={() => updatePrefs({ testStripCollapsed: !prefs.testStripCollapsed })}
                    onOpenItem={openItem}
                    actions={
                        <Tooltip content={testable ? `Run ${testable.name} with sample inputs`
                            : "Open an action type or tool to test it"}>
                            <Button variant="secondary" size="sm" isAriaDisabled={!testable}
                                onClick={() => void openTest()}>Test…</Button>
                        </Tooltip>
                    } />
```

  The test checks `disabled`; with `isAriaDisabled` PatternFly sets `aria-disabled` instead. Use
  `isDisabled={!testable}` and wrap the button in a `<span>` inside the `Tooltip` so the tooltip still
  shows on a disabled button.
- After the other dialogs:

```tsx
            {testing !== null && wb.contents[testing] && (() => {
                const item: CompositionItem | undefined = wb.items.find((i) => i.id === testing);
                const remembered = testValues.current.get(testing);
                return item && (
                    <TestRunDialog itemName={item.name} itemType={item.type === "tool" ? "tool" : "action-type"}
                        content={wb.contents[testing]} initialValues={remembered?.values ?? {}}
                        initialSubjectId={remembered?.subjectId} onClose={() => setTesting(null)}
                        onRun={(inputs, values, subjectId) => runTest(testing, inputs, values, subjectId)} />
                );
            })()}
```

- In `ItemTab.tsx`, remove `toolId={item.baseItemId}` from `<ToolEditor ...>` and add a comment: the
  workbench tests tools through the Test output strip.

- [ ] **Step 7: Run all UI checks**

Run: `cd ui && timeout 250 npx tsc -b --noEmit && timeout 280 npx vitest run`
Expected: all pass.

- [ ] **Step 8: Commit**

```bash
git add ui/src/pages/workbench
git commit -m "feat(workbench): test output strip and Test… dialog"
```

---

### Task 8: Live verification

**Files:** none (fix anything found in the task it belongs to, with its own test, and commit separately).

- [ ] **Step 1:** Ask the user to restart `dev.sh` (backend changes need it). Open
  `http://localhost:9191/workbench/<id>` with Chrome DevTools MCP.
- [ ] **Step 2: User run, script action type.** Create a Script action type with an input `who` and a
  required output `greeting`, script `printf '{"greeting":"hi %s"}\n' {{inputs.who}}`. Click **Test…**,
  enter `bob`, **Run**. Expect a `running` row, then `passed` with `Outputs: {"greeting":"hi bob"}`.
- [ ] **Step 3: Failing run.** Change the script to `echo '{}'`, run again. Expect `failed` with
  `Output: Missing required field 'greeting'.`; expand it and check stdout.
- [ ] **Step 4: Tool.** Open a tool with a `{{name}}` parameter, run it, check stdout.
- [ ] **Step 5: Agent mode.** On an AI Agent action type, **Test…** shows the "not supported yet" message.
- [ ] **Step 6: AI run.** Ask the AI: "Create a node action type that counts the labels it is given and
  test it." Expect `test_action_type` in the chat, a run with the `AI` label in the strip (which expands),
  and the AI reporting the result.
- [ ] **Step 7: Reload.** Reload the page; the runs are still listed.
- [ ] **Step 8:** Report results to the user with screenshots.
