# Explicit Action Type Inputs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Make declared inputs the only way data reaches Action Types (script and agent), with explicit node
mappings validated everywhere.

**Architecture:** Five tasks: (1) workflow context carries all subject fields; (2) the action type validator
accepts only `{{inputs.X}}`, and all bundled content (assistant examples, seed data, reference text) is migrated
in the same task so the build stays green; (3) runtime — script substitution/env and agent prompt assembly drop
implicit data, add the generated Inputs section, and fail tasks missing required inputs; (4) workflow node
mapping rules in the shared validator and at publish; (5) AI helper prompts, assistant guidance, UI
autocomplete and docs.

**Tech Stack:** Java 21 / Quarkus / Panache / Jackson; apitomy-flow engine; React + Monaco (UI autocomplete);
JUnit 5, Vitest.

**Spec:** `docs/superpowers/specs/2026-10-06-explicit-action-inputs-design.md`

## Global Constraints

- `{{inputs.X}}` is the only template placeholder in action type `promptTemplate` and `scriptTemplate`; `X` must
  be a declared input.
- Plumbing env vars kept for scripts and agents: `AXIOM_API_URL`, `AXIOM_TASK_ID`, `AXIOM_WORK_DIR` (plus
  secrets, the action type `environment`, `AXIOM_INPUT_*` used by script input substitution, and existing
  tracing vars `AXIOM_TRACE_ID`, `AXIOM_PARENT_NODE_ID`). Removed: `AXIOM_SUBJECT_ID`, `AXIOM_EVENT_ID`,
  `AXIOM_REF`, `AXIOM_REPOSITORY`, `AXIOM_SUBJECT_NAME`.
- Workflow context always has `subjectId`, `subjectName`, `ref`, `repository` (empty string when null) and
  `event` when event-started; all five protected from override by supplied run inputs.
- Required declared inputs must be mapped on the node; unknown mapping keys are errors; optional inputs may be
  unmapped and are then absent (omitted from the Inputs section; `{{inputs.X}}` renders empty).
- Agent prompt = rendered template + `## Inputs` section (every declared input that has a value, declaration
  order, even if referenced inline) + existing output contract. System prompt has no subject data.
- No backward compatibility; edit seed data and bundled examples in place. Session template placeholders
  (e.g. `{{subjectName}}` in assistant session templates) are a different feature and are NOT changed.
- 4-space indent, Javadoc on public methods, JUnit 5, Vitest; `rg` is not installed (use `git grep`); no browser
  testing; every task ends with `./build.sh` (and `-Pui` when `ui/` changes) passing and a conventional commit
  without AI attribution.

## Reference: existing code

- `core/.../services/ActionTypeValidator.java`: `PLACEHOLDER_PATTERN` `\{\{([a-zA-Z_][a-zA-Z0-9_]*)\}\}` (:22),
  `INPUT_PLACEHOLDER_PATTERN` (:24), `RECOGNIZED_PROMPT_PLACEHOLDERS` / `RECOGNIZED_SCRIPT_PLACEHOLDERS`
  (public, :31-37), prompt check loop (:129-141), script loop (:155-167), `checkInputPlaceholders` (:310-322,
  already errors on undeclared `inputs.X`). Only other user of the constants:
  `AssistantReferenceGenerator.java:141,144`.
- `ScriptExecutionService.java`: always-set env (:153-155: `AXIOM_API_URL`, `AXIOM_SUBJECT_ID`, `AXIOM_TASK_ID`),
  `substitutePlaceholders` (:272-318) with `addBinding` for subject/event/plumbing placeholders (:284-294) and
  `AXIOM_INPUT_*` bindings for workflow tasks (:301-314); placeholder env only added when referenced.
- `TaskExecutionService.java`: `resolvePromptTemplate` (:313-336), `buildSystemPrompt` (:349-358).
- `core/.../services/InputBindingResolver.java`: `parseInputs`, `bindInputs`, `renderValue`,
  `buildOutputContract(List<ActionTypeField>)`.
- `core/.../entities/ActionTypeField` (`@Embeddable`; public `name`, `type`, `required`, `description`).
  `ActionTypeEntity.inputs` / `.outputs` are lists of it.
- `WorkflowExecutionService.java`: `PROTECTED_INPUTS` (:67), context build (:208-225), `task.input =
  serializeInputs(actionInfo)` (:739). Unmapped declared inputs are simply absent from `task.input`.
- `app/.../validation/`: `ValidationContext` record (:9-16), `ValidationContextFactory` (`databaseOnly()`,
  `forPack(JsonNode)`, `forDirectory(Path)` — names only), `ConfigItemValidationService.validateActionNode`
  (:313-332), `WorkflowContentRules` (`startInputViolation`, `engineProblems`), `WorkflowPublisher.publish`
  (:49-60).
- `SeedDataInitializer.java:45-53` seeds `close-subject` (script calls removed `/subjects/{id}/close`).
- Assistant examples: `templates/assistant-reference/examples/action-types/{triage-issue,request-more-info}.json`,
  `workflows/issue-triage.json`; tested by `AssistantReferenceTest`.
- `AssistantReferenceGenerator.java:137-150` (placeholder lists), `:130-131` (`OPTIONAL_BUILT_IN_INPUTS`).
- `ActionTypeAiService.java:52-56`, `ScriptAiService.java:50-78` (placeholder/env text).
- UI `ui/src/components/PlaceholderCompletionProvider.ts` (`ACTION_TYPE_PLACEHOLDERS` :122-128,
  `SCRIPT_PLACEHOLDERS` :133-142) used by `ui/src/pages/ActionTypeDetailPage.tsx` (:496-503, :616-623); also
  `ActionTypeAiModal.tsx`, `ScriptAiModal.tsx`, `ActionTypesPage.tsx` mention removed placeholders.
- Docs mentioning placeholders: `docs/user-guide/ai-assistant.md`, `concepts.md`, `usage-examples.md`.

---

### Task 1: Workflow context carries all subject fields

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java` (:67, :208-225)
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowRunMetadataTest.java` (add tests; uses `TestFixtures`)

**Interfaces:**
- Produces: context keys `subjectId`, `subjectName`, `ref`, `repository`, (`event`); `PROTECTED_INPUTS =
  Set.of("subjectId", "subjectName", "ref", "repository", "event")`.

- [ ] **Step 1: Failing tests** in `WorkflowRunMetadataTest`:

```java
    /**
     * The run context always exposes the subject's name and repository so nodes can map them explicitly.
     */
    @Test
    void contextContainsSubjectNameAndRepository() {
        long subjectId = QuarkusTransaction.requiringNew().call(() -> {
            SubjectEntity s = new SubjectEntity();
            s.name = "ctx-subject-" + UUID.randomUUID();
            s.type = "other";
            s.ref = "https://github.com/o/r/issues/1";
            s.repository = "o/r";
            s.createdOn = Instant.now();
            s.updatedOn = Instant.now();
            s.persist();
            return s.id;
        });
        long defId = TestFixtures.createWaitDefinition("Ctx Def " + UUID.randomUUID());
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defId, RunRequest.EMPTY, null));
        String state = QuarkusTransaction.requiringNew().call(() ->
                ((WorkflowRunEntity) WorkflowRunEntity.findById(run.id)).instanceState);
        assertTrue(state.contains("\"repository\":\"o/r\""), state);
        assertTrue(state.contains("\"subjectName\":\"ctx-subject-"), state);
    }

    /**
     * Supplied run inputs cannot override protected subject keys.
     */
    @Test
    void suppliedInputsCannotOverrideRepositoryOrSubjectName() {
        long subjectId = TestFixtures.createSubject("ctx-protected-" + UUID.randomUUID());
        long defId = TestFixtures.createWaitDefinition("Ctx Prot " + UUID.randomUUID());
        RunRequest request = new RunRequest(null, null,
                Map.of("repository", "evil/repo", "subjectName", "evil"), null);
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defId, request, null));
        String state = QuarkusTransaction.requiringNew().call(() ->
                ((WorkflowRunEntity) WorkflowRunEntity.findById(run.id)).instanceState);
        assertFalse(state.contains("evil"), state);
    }
```

  Check how `instanceState` serializes the context (adjust the `contains` assertions to the actual JSON, e.g.
  spacing) and use the class's existing imports/fixtures.
- [ ] **Step 2: Run — expect FAIL.**
  `mvn -q -pl app -am test -Dtest=WorkflowRunMetadataTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement** — after `context.put("ref", ...)` add
  `context.put("subjectName", subject.name != null ? subject.name : "");` and
  `context.put("repository", subject.repository != null ? subject.repository : "");`; extend
  `PROTECTED_INPUTS`. Also ensure `extraContext` cannot clobber these (apply subject keys after `extraContext`,
  except `event` which comes from `extraContext`).
- [ ] **Step 4: Run — PASS**; also run `WorkflowExecutionServiceTest`, `EventWorkflowLinksTest`.
- [ ] **Step 5: `./build.sh`; commit** — `feat(workflow): expose subject name and repository in run context`

---

### Task 2: Validator accepts only `{{inputs.X}}`; migrate bundled content

**Files:**
- Modify: `core/src/main/java/io/apitomy/axiom/core/services/ActionTypeValidator.java`
- Modify: `app/src/test/java/io/apitomy/axiom/core/services/ActionTypeValidatorTest.java` (22 uses of removed
  placeholders), `ActionResourceTest.java` (3), `assistant/AssistantItemValidatorTest.java` (1),
  `validation/ConfigItemValidationServiceTest.java` (2), and any other test whose action type JSON uses removed
  placeholders (`git grep -n -E '\{\{(ref|repository|subjectName|subjectId|eventId|taskId|workDir|apiBaseUrl|actionType|input)\}\}' -- app/src/test core/src/test`)
- Modify: `app/src/main/java/io/apitomy/axiom/app/SeedDataInitializer.java` — delete the `close-subject` seed
  (its endpoint no longer exists); delete `seedActionType` if now unused
- Modify: assistant examples `action-types/triage-issue.json`, `action-types/request-more-info.json`,
  `workflows/issue-triage.json`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantReferenceGenerator.java` (:137-150)

**Interfaces:**
- Produces: `ActionTypeValidator.REMOVED_PLACEHOLDER_HINTS` (public `Map<String, String>`: placeholder name →
  replacement hint, used for messages and by the reference generator). `RECOGNIZED_PROMPT_PLACEHOLDERS` and
  `RECOGNIZED_SCRIPT_PLACEHOLDERS` are deleted.

Hints (exact text):

```java
    /** Former placeholders and how to replace them; any of these in a template is an error. */
    public static final Map<String, String> REMOVED_PLACEHOLDER_HINTS = Map.ofEntries(
            Map.entry("ref", "Declare an input 'ref' and map it from context.ref on the workflow node."),
            Map.entry("repository",
                    "Declare an input 'repository' and map it from context.repository on the workflow node."),
            Map.entry("subjectName",
                    "Declare an input 'subjectName' and map it from context.subjectName on the workflow node."),
            Map.entry("subjectId",
                    "Declare an input 'subjectId' and map it from context.subjectId on the workflow node."),
            Map.entry("eventId",
                    "Declare an input 'eventId' and map it from context.event.id on the workflow node."),
            Map.entry("workDir", "Not a placeholder. Scripts can use $AXIOM_WORK_DIR; agents already run there."),
            Map.entry("apiBaseUrl", "Not a placeholder. Scripts can use $AXIOM_API_URL."),
            Map.entry("taskId", "Not a placeholder. Scripts can use $AXIOM_TASK_ID."),
            Map.entry("actionType", "Not available. Remove it."),
            Map.entry("input", "Not available. Declare named inputs and use {{inputs.<name>}}."));
```

Validator rule (both templates): every `PLACEHOLDER_PATTERN` match is an error. Message:
`"Placeholder '{{" + name + "}}' is not supported. " + hint` where hint is from the map, else
`"Only {{inputs.<name>}} placeholders for declared inputs are supported."`. Keep `checkMalformedPlaceholders`
and `checkInputPlaceholders` as they are.

Example migration (exact):
- `request-more-info.json`: add inputs `ref` (string, required, "Issue URL"), `repository` (string, required,
  "owner/repo"); script `gh issue comment "{{inputs.ref}}" --repo "{{inputs.repository}}" --body "Thanks for the
  report! Could you add reproduction steps?"` (drop the subject id text).
- `triage-issue.json`: add inputs `ref` (string, required) and `repository` (string, required) before
  `priorityHint`; prompt uses `{{inputs.ref}}` / `{{inputs.repository}}`; remove any hand-written output-format
  instructions from the prompt (the output contract is appended automatically).
- `issue-triage.json`: map `"ref": "context.ref", "repository": "context.repository"` on both action nodes
  (keep `"priorityHint": "'normal'"`).

Reference generator: replace the two placeholder lines with: "The only template placeholder is
`{{inputs.<name>}}` for a declared input. Other former placeholders are rejected:" followed by a bullet list
from `REMOVED_PLACEHOLDER_HINTS` (sorted by key); then "Scripts and agents get runtime environment variables
`AXIOM_API_URL`, `AXIOM_TASK_ID`, `AXIOM_WORK_DIR`. The workflow context provides `subjectId`, `subjectName`,
`ref`, `repository` and `event` for mapping into inputs." Update `:130-131` text if it implies built-ins reach
actions implicitly.

- [ ] **Step 1: Failing tests** — in `ActionTypeValidatorTest` add (adapt to the class's helpers):

```java
    @Test
    void removedPromptPlaceholderIsRejectedWithHint() {
        NewActionType def = agent("p", "Triage {{ref}}");
        ActionTypeValidator.ValidationResult result = ActionTypeValidator.validate(def);
        assertTrue(result.errors().stream().anyMatch(m -> m.message().contains("context.ref")),
                result.toString());
    }

    @Test
    void removedScriptPlaceholderIsRejectedWithEnvHint() {
        NewActionType def = script("s", "cd {{workDir}}");
        ActionTypeValidator.ValidationResult result = ActionTypeValidator.validate(def);
        assertTrue(result.errors().stream().anyMatch(m -> m.message().contains("$AXIOM_WORK_DIR")),
                result.toString());
    }

    @Test
    void declaredInputPlaceholderIsAccepted() {
        NewActionType def = agent("p", "Triage {{inputs.ref}}");
        def.setInputs(List.of(field("ref", "string", true)));
        assertFalse(ActionTypeValidator.validate(def).hasErrors());
    }
```

  (`agent`, `script`, `field` are small private helpers building `NewActionType`/`ActionTypeField` beans; add
  them if the class has none.) Rewrite existing tests that assert the old placeholders are valid so they now
  assert the new behavior (do not delete coverage).
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement** validator, examples, seed removal, generator text; fix every test using removed
  placeholders.
- [ ] **Step 4: Run** `ActionTypeValidatorTest`, `ActionResourceTest`, `AssistantItemValidatorTest`,
  `ConfigItemValidationServiceTest`, `AssistantReferenceTest` (examples must still validate warning-free except
  `UNKNOWN_SECRET`, and apply end to end) — PASS.
- [ ] **Step 5: `./build.sh`; commit** — `feat(action-types): only declared input placeholders are supported`

---

### Task 3: Runtime — scripts and agent prompts use declared inputs only

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/ScriptExecutionService.java` (:153-155, :272-318)
- Modify: `app/src/main/java/io/apitomy/axiom/app/TaskExecutionService.java` (:313-358)
- Modify: `core/src/main/java/io/apitomy/axiom/core/services/InputBindingResolver.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/ScriptExecutionServiceTest.java`,
  `core/src/test/java/io/apitomy/axiom/core/services/InputBindingResolverTest.java` (create if absent),
  `app/src/test/java/io/apitomy/axiom/app/TaskPromptAssemblyTest.java` (create)

**Interfaces:**
- Produces in `InputBindingResolver`:

```java
    /**
     * Builds the "## Inputs" prompt section listing every declared input that has a value, in declaration
     * order. Strings are rendered as-is, numbers and booleans as text, objects and arrays as pretty JSON in a
     * fenced block. Returns an empty string when no declared input has a value.
     *
     * @param inputs declared inputs (may be null)
     * @param values resolved input values by name
     * @param mapper object mapper used for JSON rendering
     * @return the section text, or "" when empty
     */
    public static String buildInputsSection(List<ActionTypeField> inputs, Map<String, Object> values,
                                            ObjectMapper mapper) { ... }

    /**
     * Returns the names of required declared inputs that have no value.
     *
     * @param inputs declared inputs (may be null)
     * @param values resolved input values by name
     * @return missing required input names, in declaration order
     */
    public static List<String> missingRequiredInputs(List<ActionTypeField> inputs, Map<String, Object> values) { ... }
```

  Section format (exact):

```
## Inputs
- **<name>** (<type>) — <description>
  <value>
```

  where the description part (` — <description>`) is omitted when blank, and a JSON value is written as a
  fenced ```json block indented two spaces. Values are "present" when the key exists and is non-null.

Behavior:
- **Scripts:** remove the `AXIOM_SUBJECT_ID` line (:154); in `substitutePlaceholders` keep only bindings for
  declared inputs: for each declared input of the action type, bind `{{inputs.<name>}}` → `AXIOM_INPUT_<name>`
  with the rendered value, or `""` when absent (so unmapped optional inputs render empty). Remove the subject,
  event and plumbing placeholder bindings. Keep `AXIOM_API_URL`, `AXIOM_TASK_ID` (:153, :155) and always set
  `AXIOM_WORK_DIR` to the subject workspace path. Change `substitutePlaceholders` to take the
  `ActionTypeEntity` (or its input list) so it knows the declared inputs; update callers.
- **Agents:** `resolvePromptTemplate` removes the five `replace(...)` calls; binds declared inputs (absent →
  `""`); appends `buildInputsSection(...)` (when non-empty) then the output contract (existing condition).
  `buildSystemPrompt` becomes: `"You are performing the action '" + actionType.name + "'."` + description line
  if present + `"Your current working directory is the task's workspace."` — no subject data.
- **Both:** before starting, if `missingRequiredInputs(...)` is non-empty, fail the task (same failure path as
  other pre-start errors) with message `"Missing required input(s): a, b. Map them on the workflow node."`.

- [ ] **Step 1: Failing tests**
  - `InputBindingResolverTest`: section lists string, number and object inputs (object as ```json block), skips
    absent optional, includes inline-referenced ones, empty when nothing valued; `missingRequiredInputs`
    returns only required absent names.
  - `ScriptExecutionServiceTest`: update existing env assertions — a workflow task's script env does NOT contain
    `AXIOM_SUBJECT_ID`, `AXIOM_REF`, `AXIOM_REPOSITORY`, `AXIOM_SUBJECT_NAME`, `AXIOM_EVENT_ID`; contains
    `AXIOM_API_URL`, `AXIOM_TASK_ID`, `AXIOM_WORK_DIR`; an unmapped optional `{{inputs.x}}` renders as empty;
    keep the shell-injection tests (switch them to declared inputs if they used subject placeholders).
  - `TaskPromptAssemblyTest` (`@QuarkusTest`; build an agent action type with inputs `issue` (string, required)
    and `meta` (object, optional) plus one output, a subject with name/ref/body, a workflow task with input JSON
    `{"issue":"Bug X","meta":{"a":1}}`; call the package-private prompt/system-prompt builders — make them
    package-private if private): prompt contains the template text, `## Inputs`, `**issue**`, a ```json block
    for `meta`, and `## Required output` after the Inputs section; system prompt contains the action name and
    none of the subject's name, ref or body; a task missing `issue` fails with "Missing required input(s): issue".
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the three test classes plus `WorkflowExecutionServiceTest` — PASS.
- [ ] **Step 5: `./build.sh`; commit** — `feat(tasks): pass data to actions only through declared inputs`

---

### Task 4: Node input mapping validation

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/validation/ValidationContext.java`,
  `ValidationContextFactory.java`, `WorkflowContentRules.java`, `ConfigItemValidationService.java`,
  `WorkflowPublisher.java`
- Test: `ConfigItemValidationServiceTest`, `WorkflowDefinitionsResourceTest` (publish), and update every
  `new ValidationContext(...)` call site in tests

**Interfaces:**
- `ValidationContext` gains a final component `Map<String, Map<String, Boolean>> actionTypeInputs`
  (action type name → declared input name → required). `unchecked()` passes `null`.
- `ValidationContextFactory` fills it from `ActionTypeEntity.inputs` (DB) and, for `forPack` / `forDirectory`,
  from pending action types' `inputs` arrays (pending overrides DB for the same name).
- `WorkflowContentRules`:

```java
    /**
     * A node input-mapping problem.
     *
     * @param nodeIndex index of the node in content.nodes
     * @param key       the input name concerned
     * @param code      UNMAPPED_REQUIRED_INPUT or UNKNOWN_INPUT_MAPPING
     * @param message   human-readable explanation
     */
    public record InputMappingProblem(int nodeIndex, String key, String code, String message) {
    }

    /**
     * Checks that every action node maps each required declared input and maps no undeclared input. Nodes
     * whose action type is {@code sdk:*} or unknown to {@code declarations} are skipped.
     *
     * @param workflow     the workflow
     * @param declarations action type name to (input name to required)
     * @return problems, empty if valid
     */
    public static List<InputMappingProblem> inputMappingProblems(Workflow workflow,
            Map<String, Map<String, Boolean>> declarations) { ... }
```

  Messages: `"Required input '<k>' of action type '<t>' is not mapped. Add it to config.inputs (e.g. \"<k>\":
  \"context.<k>\")."` and `"Action type '<t>' has no input named '<k>'."`.
- `ConfigItemValidationService` (action nodes, when `context.actionTypeInputs() != null`) maps these to errors
  at `content.nodes[i].config.inputs` (unmapped) and `content.nodes[i].config.inputs.<k>` (unknown).
- `WorkflowPublisher.publish`: builds declarations from `ActionTypeEntity` and throws 400 with
  `{"message": "<first problem message>", "problems": [...messages]}` when any problem exists (after the
  existing engine and start-input checks).

- [ ] **Step 1: Failing tests** — `ConfigItemValidationServiceTest`: context with `actionTypeInputs =
  Map.of("do-it", Map.of("ref", true, "note", false))`:
  - node with `"inputs": {}` → `UNMAPPED_REQUIRED_INPUT` for `ref`;
  - node with `"inputs": {"ref": "context.ref"}` → no mapping errors (optional `note` unmapped is fine);
  - node with `"inputs": {"ref": "context.ref", "bogus": "1"}` → `UNKNOWN_INPUT_MAPPING` at
    `content.nodes[1].config.inputs.bogus`.
  `WorkflowDefinitionsResourceTest`: create action type `map-test-<uuid>` with required input `ref` via REST,
  save a draft whose action node has `"inputs": {}`, publish → 400 with message containing "is not mapped".
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement**; update all `ValidationContext` constructor call sites.
- [ ] **Step 4: Run** the tests above, `AssistantReferenceTest`, `ConfigPackApplyTest`,
  `AssistantValidateEndpointTest` — PASS (fix test fixtures whose workflows do not map required inputs).
- [ ] **Step 5: `./build.sh`; commit** — `feat(validation): require explicit node mappings for action inputs`

---

### Task 5: AI helpers, assistant guidance, UI autocomplete, docs

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/ActionTypeAiService.java` (:52-56),
  `ScriptAiService.java` (:50-78)
- Modify: `app/src/main/resources/templates/assistant-templates/axiom-config-assistant.json` (system prompt)
- Modify: `ui/src/components/PlaceholderCompletionProvider.ts`, `ui/src/pages/ActionTypeDetailPage.tsx`,
  `ui/src/components/ActionTypeAiModal.tsx`, `ui/src/components/ScriptAiModal.tsx`,
  `ui/src/pages/ActionTypesPage.tsx` (only action-type placeholder text; leave session templates alone)
- Modify docs: `docs/user-guide/concepts.md`, `usage-examples.md`, `ai-assistant.md`, `workflows.md`, and
  developer docs found by `git grep -l -E '\{\{(ref|repository|subjectId|apiBaseUrl|workDir)\}\}' -- docs ':!docs/superpowers'`
- Test: `ui/src/components/PlaceholderCompletionProvider.test.ts` (create),
  `AssistantReferenceTest` (prompt assertions)

**Content:**
- AI helper prompts: describe the single placeholder `{{inputs.<name>}}` (declared inputs only), the generated
  Inputs section and output contract (agents), and the plumbing env vars `AXIOM_API_URL`, `AXIOM_TASK_ID`,
  `AXIOM_WORK_DIR` (scripts). Script REST examples use `$AXIOM_API_URL/subjects/{{inputs.subjectId}}`.
- Assistant system prompt adds two rules: "Declare an input for everything an action operates on (issue
  reference, repository, ids) and map it on the workflow node from `context.*` or earlier outputs; never rely on
  ambient subject data." and "Do not write output-format instructions in prompts; Axiom appends the output
  contract automatically."
- UI: remove `ACTION_TYPE_PLACEHOLDERS` and `SCRIPT_PLACEHOLDERS`; the editors offer only
  `inputs.<name>` completions for declared inputs; script mode additionally lists env vars `AXIOM_API_URL`,
  `AXIOM_TASK_ID`, `AXIOM_WORK_DIR` as documentation entries (export a `SCRIPT_ENV_VARS` list used in the
  editor's help text, not as `{{…}}` completions).
- Docs: replace placeholder tables/examples with declared inputs + node mappings; document context keys,
  required/optional mapping rules, Inputs section and env vars. Wrap prose at 110 columns.

- [ ] **Step 1: Failing tests** — Vitest: `inputPlaceholderItems([{name: "ref", description: "Issue URL"}])`
  (export this helper from `PlaceholderCompletionProvider.ts`) returns exactly `[{name: "inputs.ref",
  description: "Issue URL"}]`, and the module no longer exports `ACTION_TYPE_PLACEHOLDERS`. Java: extend
  `AssistantReferenceTest.configAssistantPromptUsesReferencePlaceholder` to assert the prompt contains
  "Declare an input for everything an action operates on" and the generated reference does not contain
  `{{ref}}` as a supported placeholder (only in the removed-hints list).
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement** content changes.
- [ ] **Step 4: Run** `cd ui && npm test && npm run build`, `AssistantReferenceTest`; `mkdocs build --strict` if
  installed.
- [ ] **Step 5: `./build.sh -Pui`; commit** — `docs,ui: explicit action inputs in guidance, editors and docs`

---

## Final Verification

- [ ] `./build.sh -Pui` — BUILD SUCCESS.
- [ ] `git grep -n -E '\{\{(ref|repository|subjectName|subjectId|eventId|taskId|workDir|apiBaseUrl|actionType)\}\}' -- app/src/main core/src/main ui/src docs ':!docs/superpowers'`
  — matches only in `REMOVED_PLACEHOLDER_HINTS`/its tests, generated hint text, and session-template code.
- [ ] `git grep -n -E 'AXIOM_(SUBJECT_ID|EVENT_ID|REF|REPOSITORY|SUBJECT_NAME)' -- app/src/main` — no matches in
  task/script execution (assistant session env for subject-scoped sessions is a separate feature and may remain).
