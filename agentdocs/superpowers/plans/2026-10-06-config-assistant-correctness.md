# Configuration Assistant Correctness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Make the Configuration Assistant produce items the server accepts, by sharing one validation service
across REST, live feedback, a pre-write MCP tool and Apply, and by teaching it the real formats.

**Architecture:** A new `io.apitomy.axiom.app.validation` package holds `ConfigItemValidationService` plus small
helpers (`ValidationContext`, `WorkflowContentRules`, `WorkflowPublisher`, `SubscriptionRoutingResolver`).
Existing validators (`ToolValidator`, `ActionTypeValidator`, the flow engine's `WorkflowValidator`,
`SubscriptionFilterEvaluator`) are reused, not re-implemented. Apply and configuration-pack import validate the
whole pack with pack-aware name resolution, import in dependency order inside one transaction, and publish
workflows via the same code as the REST publish endpoint. The assistant prompt gains a reference section
generated from code and examples stored as real files that tests validate.

**Tech Stack:** Java 21 / Quarkus / Panache / Jackson; apitomy-flow engine (`io.apitomy.flow.model`,
`io.apitomy.flow.validation`); JAX-RS codegen from `openapi.json`; Node MCP server (`server.js`); JUnit 5 +
RestAssured.

**Spec:** `docs/superpowers/specs/2026-10-06-config-assistant-correctness-design.md`

## Global Constraints

- Contract-first: REST changes start in `common/api/src/main/resources/openapi.json` (targeted text edits, no
  JSON round-trip); regenerate with `mvn -q -pl common/api install -DskipTests`; impls implement generated
  interfaces; no `@Path` on impls; request/response types are generated beans.
- Reuse existing validators; do not duplicate their rules. Message texts of existing REST validation errors are
  preserved (existing tests must keep passing unchanged).
- Assistant-created event sources and subscriptions default to `enabled: false`.
- Item type JSON names: `tool`, `action-type`, `toolset`, `session-template`, `workflow`, `event-source`,
  `subscription`. Session directories: `tools/`, `action-types/`, `toolsets/`, `session-templates/`,
  `workflows/`, `event-sources/`, `subscriptions/`.
- Apply/import order: tools → toolsets → action types → session templates → workflows → event sources →
  subscriptions.
- 4-space indent, Javadoc on public methods, explicit types, JUnit 5. `rg` is not installed; use `git grep`.
  No browser testing. Single Flyway migration `V1__baseline.sql` (edit in place only if a column is needed —
  none is expected).
- Every task ends with `./build.sh` passing (and `./build.sh -Pui` if `ui/` changed) and a conventional commit,
  no AI attribution.

## Reference: existing code this plan builds on

- `core/.../services/ToolValidator.validate(NewToolDefinition)` → `ToolValidator.ValidationResult(List<ValidationMessage(severity, field, message)>)`.
- `core/.../services/ActionTypeValidator.validate(NewActionType, KnownNames)`; `KnownNames(Set<String> secretNames, Set<String> toolNames, Set<String> toolsetNames, Set<String> sdkToolNames)` (null set = skip check); placeholder sets `RECOGNIZED_PROMPT_PLACEHOLDERS`, `RECOGNIZED_SCRIPT_PLACEHOLDERS` (make them `public static final` if not already).
- Flow engine: `io.apitomy.flow.model.Workflow(id, name, description, version, nodes, edges)`, `WorkflowNode(id, type, name, config, position)`, `WorkflowEdge(id, source, target, condition, priority, isDefault, label)`, `NodeType` (`start`, `end`, `action`, `human-task`, `receive-event`, `wait`); `io.apitomy.flow.validation.WorkflowValidator().validate(Workflow)` → `List<ValidationProblem(severity, code, message, nodeId, edgeId)>`, `ValidationSeverity.ERROR/WARNING`.
- `core/.../events/model/EventType` (`value()` gives `issue.created`, …); `SdkFunctionRegistry.all()` / `isValidSdkCall(String)`.
- `core/.../filters/SubscriptionFilterEvaluator.isValid(String)` (blank = valid).
- `WorkflowDefinitionsResourceImpl.publishWorkflowDefinition` (~345) and `validateStartInputs` (~450),
  constants `OPTIONAL_BUILT_IN_INPUTS`, `INPUT_NAME_PATTERN`.
- `SubscriptionsResourceImpl` routing/filter checks (~309-345).
- `AssistantItemValidator.validate(Path file, String itemType, Path workingDirectory)` → `ValidationResult(List<String> errors, List<String> warnings)`.
- `AssistantSessionManager`: live feedback `validateAndFeedback` (~734), `applySession` (~490),
  `buildConfigPack`, `applyWorkflowDefinitions` (~833), `resolveMcpServers` (~912), `buildSystemPrompt` (~625).
- `ImportExportService.importOrUpdatePack(JsonNode)` (`@Transactional`, ~214) with private `upsertX` methods.

---

### Task 1: Validation core — types, context, and non-workflow item checks

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/validation/ConfigItemType.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/validation/ConfigProblem.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/validation/ValidationContext.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/validation/ValidationContextFactory.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/validation/ConfigItemValidationService.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/validation/ConfigItemValidationServiceTest.java`

**Interfaces (produced):**

```java
package io.apitomy.axiom.app.validation;

/** Kinds of configuration items, with their JSON names and assistant session directory names. */
public enum ConfigItemType {
    TOOL("tool", "tools"), ACTION_TYPE("action-type", "action-types"), TOOLSET("toolset", "toolsets"),
    SESSION_TEMPLATE("session-template", "session-templates"), WORKFLOW("workflow", "workflows"),
    EVENT_SOURCE("event-source", "event-sources"), SUBSCRIPTION("subscription", "subscriptions");

    private final String jsonName;
    private final String directory;

    ConfigItemType(String jsonName, String directory) {
        this.jsonName = jsonName;
        this.directory = directory;
    }

    /** @return the JSON name, e.g. {@code action-type} */
    public String jsonName() { return jsonName; }

    /** @return the assistant session directory name, e.g. {@code action-types} */
    public String directory() { return directory; }

    /**
     * Looks up a type by JSON name or directory name.
     *
     * @param name the JSON name or directory name
     * @return the type
     * @throws IllegalArgumentException if unknown
     */
    public static ConfigItemType fromName(String name) {
        for (ConfigItemType t : values()) {
            if (t.jsonName.equals(name) || t.directory.equals(name)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown item type: " + name);
    }
}
```

```java
package io.apitomy.axiom.app.validation;

/**
 * One validation finding.
 *
 * @param severity "error" or "warning"
 * @param path     JSON path of the offending value, e.g. {@code content.edges[2].source}; "" for the item root
 * @param code     stable machine-readable code, e.g. {@code WORKFLOW_PARSE_ERROR}
 * @param message  human-readable explanation including how to fix it
 */
public record ConfigProblem(String severity, String path, String code, String message) {

    /** Severity value for errors. */
    public static final String ERROR = "error";
    /** Severity value for warnings. */
    public static final String WARNING = "warning";

    /** @return a new error problem */
    public static ConfigProblem error(String path, String code, String message) {
        return new ConfigProblem(ERROR, path, code, message);
    }

    /** @return a new warning problem */
    public static ConfigProblem warning(String path, String code, String message) {
        return new ConfigProblem(WARNING, path, code, message);
    }

    /** @return true if this is an error */
    public boolean isError() { return ERROR.equals(severity); }
}
```

```java
package io.apitomy.axiom.app.validation;

import java.util.Set;

/**
 * Names that items may reference: existing database entities plus items pending in the same pack or session.
 * A null set disables the corresponding existence check.
 */
public record ValidationContext(Set<String> toolNames, Set<String> toolsetNames, Set<String> actionTypeNames,
                                Set<String> workflowDefinitionNames, Set<Long> workflowDefinitionIds,
                                Set<String> eventSourceIds, Set<String> secretNames) {

    /** @return a context that disables all existence checks (for unit tests without a database) */
    public static ValidationContext unchecked() {
        return new ValidationContext(null, null, null, null, null, null, null);
    }
}
```

`ValidationContextFactory` (`@ApplicationScoped`):
- `ValidationContext databaseOnly()` — `@Transactional(SUPPORTS)`-safe read of names from `ToolDefinitionEntity`,
  `ToolsetEntity`, `ActionTypeEntity`, `WorkflowDefinitionEntity` (names and ids), `EventSourceEntity` (ids),
  `SecretEntity` (names). If no request context is active, activate one as `AssistantItemValidator` does today
  (`Arc.container().requestContext()`).
- `ValidationContext forPack(JsonNode pack)` — `databaseOnly()` names unioned with names in the pack:
  `tools[].name`, `toolsets[].name`, `actionTypes[].name`, `workflowDefinitions[].name`, `eventSources[].id`.
- `ValidationContext forDirectory(Path workDir)` — `databaseOnly()` unioned with names read from
  `<dir>/<type.directory()>/*.json` (use the JSON `name` field — `id` for event sources — falling back to the
  file basename when absent or unparseable).

`ConfigItemValidationService` (`@ApplicationScoped`, injects `ObjectMapper`, `SubscriptionFilterEvaluator`):
- `public List<ConfigProblem> validate(ConfigItemType type, JsonNode item, ValidationContext context)` —
  dispatches per type. In this task implement TOOL, ACTION_TYPE, TOOLSET, SESSION_TEMPLATE, EVENT_SOURCE;
  WORKFLOW and SUBSCRIPTION return an empty list (filled by Tasks 2 and 3).
- `public static boolean hasErrors(List<ConfigProblem> problems)`.

Per-type rules for this task:
- TOOL: `objectMapper.treeToValue(item, NewToolDefinition.class)` (parse failure → error path `""`, code
  `PARSE_ERROR`); map each `ToolValidator` message to `ConfigProblem(severity lowercased, field, "TOOL_INVALID",
  message)`.
- ACTION_TYPE: parse `NewActionType`; `KnownNames(context.secretNames(), context.toolNames(),
  context.toolsetNames(), SdkFunctionRegistry.allToolNames())`; map messages with code `ACTION_TYPE_INVALID`.
- TOOLSET: `name` required (`MISSING_FIELD`); `tools` must be an array of strings; each entry of the form
  `mcp__axiom-tools__<name>` must name a tool in `context.toolNames()` (if non-null), else error
  `UNKNOWN_TOOL` at `tools[i]`; `@<name>` entries must be in `context.toolsetNames()`, else `UNKNOWN_TOOLSET`.
- SESSION_TEMPLATE: `name`, `description`, `systemPrompt` required (`MISSING_FIELD`), same field-type checks as
  the current `AssistantItemValidator.validateSessionTemplate` (move them here).
- EVENT_SOURCE: `id` matches `[a-z0-9-]+` (`INVALID_ID`); `name`, `baseUrl` required; `sourceType` in
  `github|jira` (`INVALID_SOURCE_TYPE`); `configuration` is an object; github → `configuration.repositories`
  non-empty array of strings matching `[^/\s]+/[^/\s]+` (`INVALID_CONFIGURATION` at
  `configuration.repositories[i]`); jira → `configuration.projects` non-empty array of non-blank strings;
  `secretName` present and not in `context.secretNames()` (non-null) → warning `UNKNOWN_SECRET`.

- [ ] **Step 1: Write failing tests** — plain JUnit (no Quarkus): construct the service with
  `new ConfigItemValidationService()`, set `objectMapper` and `filterEvaluator`
  (`new SubscriptionFilterEvaluator()`) fields directly (package-private fields, as `AssistantItemValidatorTest`
  does via reflection — prefer package-private field access from the same package in tests). Tests:

```java
class ConfigItemValidationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ConfigItemValidationService service;

    @BeforeEach
    void setUp() {
        service = new ConfigItemValidationService();
        service.objectMapper = mapper;
        service.filterEvaluator = new SubscriptionFilterEvaluator();
    }

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    private static ValidationContext names(Set<String> tools, Set<String> toolsets) {
        return new ValidationContext(tools, toolsets, Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    @Test
    void githubEventSourceRequiresOwnerRepoEntries() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.EVENT_SOURCE, json("""
            {"id": "gh", "name": "GH", "sourceType": "github", "baseUrl": "https://api.github.com",
             "configuration": {"repositories": ["not-a-repo"]}}
            """), ValidationContext.unchecked());
        assertTrue(problems.stream().anyMatch(p -> p.isError()
                && p.path().equals("configuration.repositories[0]")));
    }

    @Test
    void validJiraEventSourcePasses() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.EVENT_SOURCE, json("""
            {"id": "jira-prod", "name": "Jira", "sourceType": "jira", "baseUrl": "https://x.atlassian.net",
             "configuration": {"projects": ["PROJ"]}}
            """), ValidationContext.unchecked());
        assertFalse(ConfigItemValidationService.hasErrors(problems), problems.toString());
    }

    @Test
    void actionTypeMayReferenceToolsetFromSameSession() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.ACTION_TYPE, json("""
            {"name": "triage", "executionMode": "agent", "promptTemplate": "Triage {{ref}}",
             "allowedTools": ["@triage-tools"]}
            """), names(Set.of(), Set.of("triage-tools")));
        assertFalse(ConfigItemValidationService.hasErrors(problems), problems.toString());
    }

    @Test
    void actionTypeRejectsUnknownPromptPlaceholder() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.ACTION_TYPE, json("""
            {"name": "bad", "executionMode": "agent", "promptTemplate": "Subject {{subjectId}}"}
            """), ValidationContext.unchecked());
        assertTrue(ConfigItemValidationService.hasErrors(problems), problems.toString());
    }

    @Test
    void toolsetRejectsUnknownTool() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.TOOLSET, json("""
            {"name": "ts", "tools": ["mcp__axiom-tools__missing"]}
            """), names(Set.of("present"), Set.of()));
        assertTrue(problems.stream().anyMatch(p -> p.code().equals("UNKNOWN_TOOL") && p.path().equals("tools[0]")));
    }
}
```

  Adjust the action-type JSON to the minimum `NewActionType` accepts (check `ActionTypeValidator` required
  fields) so that only the asserted problem differs.

- [ ] **Step 2: Run — expect FAIL (compile).**
  `mvn -q -pl app -am test -Dtest=ConfigItemValidationServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 3: Implement** the five files per the Interfaces block.
- [ ] **Step 3b: REST reuse** — `POST /tools/validate` and `POST /action-types/validate` (and the tool and
  action-type create/update paths that currently call `ToolValidator`/`ActionTypeValidator` directly) call
  `ConfigItemValidationService` with `contextFactory.databaseOnly()` and map problems back to the existing
  `ToolValidationResult {valid, messages[{severity, field=path, message}]}` shape. Existing
  `ActionResourceTest` and tool resource tests must pass unchanged.
- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(validation): add shared configuration item validation service"`

---

### Task 2: Workflow validation and a shared publisher

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/validation/WorkflowContentRules.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/validation/WorkflowPublisher.java`
- Modify: `ConfigItemValidationService` (WORKFLOW branch)
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/WorkflowDefinitionsResourceImpl.java` (publish delegates
  to `WorkflowPublisher`; remove `validateStartInputs` and its constants after moving them)
- Test: `ConfigItemValidationServiceTest` (workflow cases), existing `WorkflowDefinitionsResourceTest` unchanged

**Interfaces (produced):**

```java
/** Rules for workflow content shared by publish and configuration validation. */
public final class WorkflowContentRules {

    /** Built-in Start inputs that may not be marked required. */
    public static final Set<String> OPTIONAL_BUILT_IN_INPUTS = Set.of("subjectName", "repository", "event");
    /** Valid Start input names. */
    public static final Pattern INPUT_NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private WorkflowContentRules() {
    }

    /**
     * Checks Start node input declarations.
     *
     * @param workflow the workflow
     * @return the first violation message, or empty if valid (message texts identical to the previous
     *         {@code validateStartInputs} 400 messages)
     */
    public static Optional<String> startInputViolation(Workflow workflow) { ... }

    /**
     * Runs the flow engine's validator.
     *
     * @param workflow the workflow
     * @return engine problems (errors and warnings)
     */
    public static List<ValidationProblem> engineProblems(Workflow workflow) {
        return new WorkflowValidator().validate(workflow);
    }
}
```

```java
/** Publishes workflow definition drafts; used by the REST publish endpoint and by configuration import. */
@ApplicationScoped
public class WorkflowPublisher {

    /**
     * Validates the draft content of a definition and creates the next published version.
     * Throws {@link jakarta.ws.rs.WebApplicationException} with exactly the status codes and bodies the REST
     * publish endpoint returned before (400 "No draft content to publish", 400 "Invalid workflow JSON: ...",
     * 400 with the list of engine ERROR problems, 400 with {"message": start-input violation}).
     *
     * @param entity the definition (managed entity)
     * @return the new version entity
     */
    @Transactional
    public WorkflowDefinitionVersionEntity publish(WorkflowDefinitionEntity entity) { ... }
}
```

`WorkflowDefinitionsResourceImpl.publishWorkflowDefinition` becomes: find entity → `workflowPublisher.publish`
→ map to bean (unchanged mapping code).

WORKFLOW validation in the service (item = `{name, description?, content}` as written by the assistant/pack):
1. `name` required (`MISSING_FIELD`, path `name`); `content` must be an object (`MISSING_FIELD`, `content`).
2. Deserialize `content` with `objectMapper.treeToValue(content, Workflow.class)`. On failure → error
   `WORKFLOW_PARSE_ERROR` at `content`; message must include Jackson's message plus this hint:
   "Node types are lowercase (start, end, action, human-task, receive-event, wait); edges use
   id/source/target/condition/priority/isDefault." Stop further checks.
3. Engine problems: map each `ValidationProblem` to `ConfigProblem` (severity lowercased, code = engine code,
   message = engine message). Path: `content.nodes[i]` where `i` is the index of `nodeId` in
   `content.nodes`; `content.edges[i]` for `edgeId`; `content` otherwise.
4. `startInputViolation` → error `INVALID_START_INPUT` at `content.nodes[<start index>].config.inputs`.
5. Each `action` node: `config.actionType` missing → error `MISSING_ACTION_TYPE`; value `sdk:<fn>` must satisfy
   `SdkFunctionRegistry.isValidSdkCall(fn)` else `UNKNOWN_SDK_FUNCTION`; otherwise must be in
   `context.actionTypeNames()` (if non-null) else `UNKNOWN_ACTION_TYPE` (message: "create it in action-types/
   or use axiom_list_action_types").
6. Each `receive-event` node whose `config.eventType` is not an `EventType` value → warning
   `UNKNOWN_EVENT_TYPE` listing valid values.

- [ ] **Step 1: Failing tests** — add to `ConfigItemValidationServiceTest`:

```java
    private static final String VALID_WORKFLOW = """
        {"name": "triage", "content": {
          "id": "triage", "name": "triage",
          "nodes": [
            {"id": "s", "type": "start", "name": "Start", "config": {}, "position": {"x": 0, "y": 0}},
            {"id": "a", "type": "action", "name": "Do", "config": {"actionType": "do-it"},
             "position": {"x": 200, "y": 0}},
            {"id": "e", "type": "end", "name": "End", "config": {}, "position": {"x": 400, "y": 0}}
          ],
          "edges": [
            {"id": "e1", "source": "s", "target": "a", "priority": 0, "isDefault": true},
            {"id": "e2", "source": "a", "target": "e", "priority": 0, "isDefault": true}
          ]}}
        """;

    private static ValidationContext actionTypes(String... names) {
        return new ValidationContext(Set.of(), Set.of(), Set.of(names), Set.of(), Set.of(), Set.of(), Set.of());
    }

    @Test
    void validWorkflowPasses() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW, json(VALID_WORKFLOW),
                actionTypes("do-it"));
        assertFalse(ConfigItemValidationService.hasErrors(problems), problems.toString());
    }

    @Test
    void uppercaseNodeTypeIsRejectedWithHint() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW,
                json(VALID_WORKFLOW.replace("\"type\": \"start\"", "\"type\": \"START\"")), actionTypes("do-it"));
        assertTrue(problems.stream().anyMatch(p -> p.code().equals("WORKFLOW_PARSE_ERROR")
                && p.message().contains("lowercase")), problems.toString());
    }

    @Test
    void sourceNodeIdEdgesAreRejected() throws Exception {
        String bad = VALID_WORKFLOW.replace("\"source\": \"s\", \"target\": \"a\"",
                "\"sourceNodeId\": \"s\", \"targetNodeId\": \"a\"");
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW, json(bad), actionTypes("do-it"));
        assertTrue(ConfigItemValidationService.hasErrors(problems), problems.toString());
    }

    @Test
    void missingContentIdIsRejected() throws Exception {
        String bad = VALID_WORKFLOW.replace("\"id\": \"triage\", ", "");
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW, json(bad), actionTypes("do-it"));
        assertTrue(ConfigItemValidationService.hasErrors(problems), problems.toString());
    }

    @Test
    void unknownActionTypeIsRejectedAtNodePath() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.WORKFLOW, json(VALID_WORKFLOW),
                actionTypes("other"));
        assertTrue(problems.stream().anyMatch(p -> p.code().equals("UNKNOWN_ACTION_TYPE")
                && p.path().startsWith("content.nodes[1]")), problems.toString());
    }
```

  If Jackson ignores unknown edge properties (so `sourceNodeId` parses with null `source`), the engine
  validator must report it; confirm the test fails for the right reason and passes after implementation.
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement** `WorkflowContentRules`, `WorkflowPublisher`, WORKFLOW branch; refactor the REST
  publish endpoint to delegate.
- [ ] **Step 4: Run** the new tests plus `WorkflowDefinitionsResourceTest` and `WorkflowTriggerInputGuardTest` —
  expect PASS with no changes to those existing tests.
- [ ] **Step 5: `./build.sh`; commit** — `git commit -am "feat(validation): validate workflows with the flow engine and share publishing"` (use `git add -A` for new files).

---

### Task 3: Subscription validation and routing by workflow name

**Files:**
- Modify: `common/api/src/main/resources/openapi.json` — `RoutingRule` gains
  `"workflowDefinitionName": {"type": "string", "description": "Alternative to workflowDefinitionId for create-workflow: the name of the workflow definition. Resolved to workflowDefinitionId when saved."}`
- Modify: `core/src/main/java/io/apitomy/axiom/core/events/model/RoutingRule.java` — add
  `String workflowDefinitionName` component (keep `@JsonInclude(NON_NULL)`); update all constructor call sites.
- Create: `app/src/main/java/io/apitomy/axiom/app/validation/SubscriptionRoutingResolver.java`
- Modify: `ConfigItemValidationService` (SUBSCRIPTION branch)
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/SubscriptionsResourceImpl.java` (create/update use the
  service + resolver)
- Test: `ConfigItemValidationServiceTest`, `app/src/test/java/io/apitomy/axiom/app/SubscriptionsResourceTest.java`

**Interfaces (produced):**

```java
/** Resolves {@code workflowDefinitionName} in routing rules to {@code workflowDefinitionId}. */
@ApplicationScoped
public class SubscriptionRoutingResolver {

    /**
     * Returns a copy of the routing array in which every rule with {@code workflowDefinitionName} has
     * {@code workflowDefinitionId} set from the database and the name removed.
     *
     * @param routing routing rules JSON array (may be null)
     * @return resolved array (null if input null)
     * @throws jakarta.ws.rs.WebApplicationException 400 "Unknown workflow definition name: <name>" if not found
     */
    public JsonNode resolve(JsonNode routing) { ... }
}
```

SUBSCRIPTION validation rules (message texts for the first three MUST equal the current REST texts so
`SubscriptionsResourceTest` keeps passing):
1. `name` required (`MISSING_FIELD`).
2. `filterExpression` non-blank and `!filterEvaluator.isValid(expr)` → error `INVALID_FILTER`, message
   `"Invalid filter expression: " + expr`.
3. For each `routing[i]`: missing `type` → `"Routing rule missing required 'type' field"`; type not in
   `create-workflow|workflow-dispatch` → `"Unsupported routing type: " + type`; `create-workflow` with neither
   id nor name → `"create-workflow routing rule requires workflowDefinitionId"` (append
   `" or workflowDefinitionName"`only if that keeps existing tests green — check assertions first); both set →
   error `AMBIGUOUS_WORKFLOW_REFERENCE`; id not in `context.workflowDefinitionIds()` (non-null) →
   `UNKNOWN_WORKFLOW_DEFINITION`; name not in `context.workflowDefinitionNames()` (non-null) →
   `UNKNOWN_WORKFLOW_DEFINITION`. Paths `routing[i].type` etc.
4. Warning `UNKNOWN_EVENT_TYPE` at `filterExpression` when it contains `event.type == '<x>'` (or `"`) where `<x>`
   is not an `EventType` value (regex `event\.type\s*==\s*['"]([^'"]+)['"]`).

REST create/update: build a `JsonNode` from the bean, call the service with `factory.databaseOnly()`; on
errors throw `WebApplicationException(firstError.message(), 400)`; then `routing = resolver.resolve(routing)`
before persisting. Existing tests that create subscriptions with a nonexistent `workflowDefinitionId` must be
checked — if any exist, they relied on no existence check; give them a real definition (do not weaken the rule).

- [ ] **Step 1: Failing tests**

`ConfigItemValidationServiceTest`:

```java
    @Test
    void subscriptionMayReferenceWorkflowByName() throws Exception {
        ValidationContext ctx = new ValidationContext(Set.of(), Set.of(), Set.of(), Set.of("triage"),
                Set.of(), Set.of(), Set.of());
        List<ConfigProblem> problems = service.validate(ConfigItemType.SUBSCRIPTION, json("""
            {"name": "s", "filterExpression": "event.type == 'issue.created'",
             "routing": [{"type": "create-workflow", "workflowDefinitionName": "triage"}]}
            """), ctx);
        assertFalse(ConfigItemValidationService.hasErrors(problems), problems.toString());
    }

    @Test
    void subscriptionWithUnknownWorkflowNameFails() throws Exception {
        ValidationContext ctx = new ValidationContext(Set.of(), Set.of(), Set.of(), Set.of("triage"),
                Set.of(), Set.of(), Set.of());
        List<ConfigProblem> problems = service.validate(ConfigItemType.SUBSCRIPTION, json("""
            {"name": "s", "routing": [{"type": "create-workflow", "workflowDefinitionName": "nope"}]}
            """), ctx);
        assertTrue(problems.stream().anyMatch(p -> p.code().equals("UNKNOWN_WORKFLOW_DEFINITION")));
    }

    @Test
    void unknownEventTypeInFilterWarns() throws Exception {
        List<ConfigProblem> problems = service.validate(ConfigItemType.SUBSCRIPTION, json("""
            {"name": "s", "filterExpression": "event.type == 'issues'"}
            """), ValidationContext.unchecked());
        assertTrue(problems.stream().anyMatch(p -> !p.isError() && p.code().equals("UNKNOWN_EVENT_TYPE")));
    }
```

`SubscriptionsResourceTest` (copy fixture style from the class; base path `/api/v1/subscriptions`; create a
workflow definition named `"route-by-name-wf"` via `QuarkusTransaction.requiringNew()` with any content):

```java
    /**
     * A create-workflow rule may reference a workflow definition by name; the server stores the id.
     */
    @Test
    void createWorkflowRuleResolvesDefinitionName() {
        long defId = createWorkflowDefinition("route-by-name-wf");
        given().contentType(ContentType.JSON).body("""
            {"name": "by-name-sub", "routing": [{"type": "create-workflow",
              "workflowDefinitionName": "route-by-name-wf"}]}
            """)
        .when().post(SUBSCRIPTIONS_PATH)
        .then().statusCode(200)
            .body("routing[0].workflowDefinitionId", equalTo((int) defId))
            .body("routing[0].workflowDefinitionName", nullValue());
    }

    /**
     * An unknown workflow definition name is rejected.
     */
    @Test
    void createWorkflowRuleWithUnknownNameIsRejected() {
        given().contentType(ContentType.JSON).body("""
            {"name": "bad-name-sub", "routing": [{"type": "create-workflow",
              "workflowDefinitionName": "does-not-exist"}]}
            """)
        .when().post(SUBSCRIPTIONS_PATH)
        .then().statusCode(400);
    }
```

  Use the class's actual path constant and create-response status (check sibling tests).
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: openapi edit + regenerate; core record; resolver; service branch; REST wiring.**
- [ ] **Step 4: Run** new tests + full `SubscriptionsResourceTest`, `EventStreamOrchestratorTest`,
  `ConfigItemValidationServiceTest` — PASS.
- [ ] **Step 5: `./build.sh`; commit** — `feat(subscriptions): validate via shared service; route by workflow name`

---

### Task 4: Apply and pack import — ordered, validated, transactional

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantPackBuilder.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/ImportExportService.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantSessionManager.java` (`applySession`,
  remove `buildConfigPack`'s workflow skip and `applyWorkflowDefinitions`)
- Create: `app/src/main/java/io/apitomy/axiom/app/validation/ConfigValidationException.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/ConfigPackApplyTest.java`

**Interfaces:**
- Consumes: `ConfigItemValidationService.validate`, `ValidationContextFactory.forPack(JsonNode)`,
  `WorkflowPublisher.publish(entity)`, `SubscriptionRoutingResolver.resolve(JsonNode)`.
- Produces:

```java
/** Thrown when a configuration pack contains invalid items; nothing has been written. */
public class ConfigValidationException extends RuntimeException {
    private final Map<String, List<ConfigProblem>> problemsByItem;
    /** @param problemsByItem problems keyed by "<type>/<name>" */
    public ConfigValidationException(Map<String, List<ConfigProblem>> problemsByItem) { ... }
    /** @return problems keyed by "<type>/<name>" */
    public Map<String, List<ConfigProblem>> problemsByItem() { return problemsByItem; }
}
```

```java
/** Builds a configuration pack JSON from an assistant working directory. */
public final class AssistantPackBuilder {
    /**
     * Reads {@code <dir>/<type.directory()>/*.json} for every {@link ConfigItemType} and returns a pack with
     * arrays tools, toolsets, actionTypes, sessionTemplates, workflowDefinitions, eventSources, subscriptions.
     * Workflow files are included as-is ({name, description?, content}).
     *
     * @param workDir   the session working directory
     * @param mapper    the object mapper
     * @return the pack
     * @throws IOException if a file cannot be read or parsed
     */
    public static ObjectNode fromDirectory(Path workDir, ObjectMapper mapper) throws IOException { ... }
}
```

`ImportExportService.importOrUpdatePack(JsonNode pack)` (still `@Transactional`):
1. `ValidationContext ctx = contextFactory.forPack(pack);` validate every item of every array with the
   matching `ConfigItemType`; collect errors keyed `"<jsonName>/<name or id>"`; if any → throw
   `ConfigValidationException` before any write.
2. Upsert in order: tools → toolsets → action types → session templates → workflows → event sources →
   subscriptions.
3. Workflows: upsert by name; set `content` (string) and `description`; then `workflowPublisher.publish(entity)`
   (replaces direct `WorkflowDefinitionVersionEntity` creation). A `WebApplicationException` propagates and rolls
   back the whole import.
4. Subscriptions: `entity.routing = resolver.resolve(item.path("routing"))` (as JSON string).
5. Event sources and subscriptions keep `enabled` default `false`.

`AssistantSessionManager.applySession`: `pack = AssistantPackBuilder.fromDirectory(workDir, objectMapper)`;
call `importOrUpdatePack(pack)`; map `ConfigValidationException` to the existing `ValidationException` (flatten
messages as `"<key> <path>: <message>"`); remove `applyWorkflowDefinitions` and the workflow counting hack —
`UpsertResult` workflow counts now come from `importOrUpdatePack`.

- [ ] **Step 1: Failing tests** — `@QuarkusTest class ConfigPackApplyTest` injecting `ImportExportService` and
  `ObjectMapper`. Use unique names per test (append a short UUID).

```java
    /** A pack with an action type, a workflow using it, and a subscription naming the workflow applies. */
    @Test
    void packWithCrossReferencesApplies() throws Exception {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        JsonNode pack = mapper.readTree(PACK_TEMPLATE.replace("SFX", sfx));
        importExportService.importOrUpdatePack(pack);
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowDefinitionEntity def = WorkflowDefinitionEntity.find("name", "wf-" + sfx).firstResult();
            assertNotNull(def);
            assertEquals(1, def.currentVersion);
            EventSubscriptionEntity sub = EventSubscriptionEntity.find("name", "sub-" + sfx).firstResult();
            assertTrue(sub.routing.contains("\"workflowDefinitionId\":" + def.id), sub.routing);
            assertFalse(sub.enabled);
        });
    }

    /** An invalid workflow aborts the whole pack: nothing is written. */
    @Test
    void invalidWorkflowRollsBackEverything() throws Exception {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        JsonNode pack = mapper.readTree(PACK_TEMPLATE.replace("SFX", sfx)
                .replace("\"type\": \"end\"", "\"type\": \"END\""));
        assertThrows(ConfigValidationException.class, () -> importExportService.importOrUpdatePack(pack));
        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(ActionTypeEntity.find("name", "at-" + sfx).firstResult());
            assertNull(WorkflowDefinitionEntity.find("name", "wf-" + sfx).firstResult());
        });
    }

    /** A subscription naming a workflow that exists nowhere is rejected. */
    @Test
    void unknownWorkflowNameIsRejected() throws Exception {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        JsonNode pack = mapper.readTree(PACK_TEMPLATE.replace("SFX", sfx)
                .replace("\"workflowDefinitionName\": \"wf-" + sfx + "\"", "\"workflowDefinitionName\": \"nope\""));
        assertThrows(ConfigValidationException.class, () -> importExportService.importOrUpdatePack(pack));
    }
```

  `PACK_TEMPLATE` (text block): `actionTypes` with `{"name": "at-SFX", "executionMode": "script",
  "scriptTemplate": "echo hi"}` (adjust to minimal valid), `workflowDefinitions` with the Task 2
  `VALID_WORKFLOW` shape using names `wf-SFX` and `"actionType": "at-SFX"`, `subscriptions` with
  `{"name": "sub-SFX", "filterExpression": "event.type == 'issue.created'", "routing": [{"type":
  "create-workflow", "workflowDefinitionName": "wf-SFX"}]}`. Also test that the transaction really rolls back
  the publish failure case: a pack whose workflow passes structural parsing but fails `WorkflowPublisher` is
  not needed — validation runs first; the rollback test above covers "nothing written".
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement** as specified. Also update `ConfigurationPacksPage`-facing REST import (it calls
  `importPack`/`importOrUpdatePack`): map `ConfigValidationException` to HTTP 400 with body
  `{"message": "Configuration pack has invalid items", "problems": {...}}` in the REST impl that exposes import
  (find with `git grep -n importOrUpdatePack app/src/main/java`).
- [ ] **Step 4: Run** `ConfigPackApplyTest` and existing import/export and assistant tests
  (`git grep -l -E 'importPack|importOrUpdatePack|applySession' app/src/test`) — PASS.
- [ ] **Step 5: `./build.sh -Pui`; commit** — `feat(assistant): validated, ordered, transactional apply with workflow publishing`

---

### Task 5: Assistant validator adapter, validate endpoint, MCP tool, live feedback

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantItemValidator.java` (delegate)
- Modify: `app/src/test/java/io/apitomy/axiom/app/assistant/AssistantItemValidatorTest.java`
- Modify: `common/api/src/main/resources/openapi.json` — new path + schemas
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/AssistantResourceImpl.java`
- Modify: `AssistantSessionManager` (MCP env var, "all valid" message, validate method)
- Modify: `app/src/main/resources/templates/axiom-assistant-mcp/server.js`
- Modify: `app/src/main/resources/templates/assistant-templates/axiom-config-assistant.json` (`allowedTools` only)
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/AssistantValidateEndpointTest.java`,
  `AssistantMcpServerAugmentationTest` (or a new small test) for `server.js`

**Interfaces:**
- `AssistantItemValidator.validate(Path file, String itemType, Path workingDirectory)` keeps its signature and
  `ValidationResult(List<String> errors, List<String> warnings)`; implementation: parse file (JSON parse error →
  single error "Invalid JSON: ..."), `ConfigItemType.fromName(itemType)`, context =
  `contextFactory.forDirectory(workingDirectory)` (or `ValidationContext.unchecked()` when no CDI container, so
  the plain unit test still works), service, map each problem to `"<path>: <message>"` (path omitted if empty)
  into errors/warnings. Delete the old per-type private methods.
- openapi: `POST /assistant/sessions/{sessionId}/validate`, tag `Assistant`, operationId
  `validateAssistantItem`, request `AssistantValidateRequest {type (enum of the 7 JSON names, required),
  item (object, required)}`, response 200 `AssistantValidateResult {valid (boolean), problems (array of
  ConfigProblem {severity enum error|warning, path, code, message})}`, 404 for unknown session.
- `AssistantSessionManager.validateItem(String sessionId, String type, JsonNode item)` →
  `List<ConfigProblem>`; throws `IllegalArgumentException` for unknown session (REST maps to 404).
- MCP env: in `resolveMcpServers` add `env.put("AXIOM_ASSISTANT_SESSION_ID", sessionId)` (thread the session id
  into that method).
- `server.js` tool:

```js
    {
        name: "axiom_validate_item",
        description: "Validate a draft configuration item BEFORE writing it to a file. type is one of: tool, "
            + "action-type, toolset, session-template, workflow, event-source, subscription. item is the full "
            + "JSON object you intend to write. Items already written in this session count as existing.",
        parameters: [
            { name: "type", type: "string", description: "Item type", required: true },
            { name: "item", type: "object", description: "The complete item JSON", required: true },
        ],
        handler: async (args) => {
            if (!AXIOM_ASSISTANT_SESSION_ID) {
                return "Validation unavailable: no assistant session id.";
            }
            const result = JSON.parse(await axiomApi("POST",
                "/assistant/sessions/" + encodeURIComponent(AXIOM_ASSISTANT_SESSION_ID) + "/validate",
                { type: args.type, item: args.item }));
            if (result.valid && result.problems.length === 0) {
                return "VALID. You can write this item to its file.";
            }
            const lines = result.problems.map(p =>
                `- [${p.severity.toUpperCase()}] ${p.path || "(root)"} ${p.code}: ${p.message}`);
            return (result.valid ? "VALID with warnings:\n" : "INVALID — fix these and validate again:\n")
                + lines.join("\n");
        },
    },
```

  with `const AXIOM_ASSISTANT_SESSION_ID = process.env.AXIOM_ASSISTANT_SESSION_ID;` near the other env reads.
  Check how `server.js` passes `type: "object"` parameters to the MCP schema; extend its schema builder if it
  only supports string/number.
- `allowedTools` additions: `mcp__axiom__axiom_validate_item`, `mcp__axiom__axiom_list_secrets`,
  `mcp__axiom__axiom_list_subjects`, `mcp__axiom__axiom_list_actors`.
- Live feedback: in the validation listener, after validating all directories, if the tracker had at least one
  file with problems before this pass and now has none, send once:
  `"All configuration items in this session are valid."` Add a method to `ValidationFeedbackTracker`
  (e.g. `boolean transitionedToAllValid()`) with a unit test in `ValidationFeedbackTrackerTest`.

- [ ] **Step 1: Failing tests**
  - `AssistantItemValidatorTest`: replace tests that assert the old `"START"`/`"END"` behavior with:
    a lowercase valid workflow (Task 2 `VALID_WORKFLOW` content, `unchecked` context so the unknown action type
    check is skipped) passes; an uppercase `START` workflow fails with a message containing `lowercase`; an
    event source with `"repositories": ["bad"]` fails. Keep all existing tool/action-type tests passing.
  - `AssistantValidateEndpointTest` (`@QuarkusTest`, mock `InteractiveSessionDriverFactory` exactly as
    `AssistantEngineSelectionIntegrationTest` does; create a session via the existing REST create-session call
    with template `axiom-config-assistant`):
    - `POST /api/v1/assistant/sessions/{id}/validate` with `{"type": "workflow", "item": <uppercase START
      workflow>}` → 200, `valid == false`, `problems[0].code == "WORKFLOW_PARSE_ERROR"`.
    - After writing `action-types/at-x.json` (valid script action type) into the session working directory
      (`assistantSessionManager.getSession(id).getWorkingDirectory()`), validating a workflow whose action node
      uses `at-x` → `valid == true`.
    - Unknown session id → 404.
  - `ValidationFeedbackTrackerTest.transitionedToAllValid...`.
  - `server.js` test: read the resource text and assert it contains `name: "axiom_validate_item"` and
    `AXIOM_ASSISTANT_SESSION_ID`; read the template JSON and assert `allowedTools` contains
    `mcp__axiom__axiom_validate_item` and does not mention `axiom_list_agents` anywhere in the file.
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement** (openapi first, regenerate).
- [ ] **Step 4: Run** the tests above plus all `app/src/test/java/io/apitomy/axiom/app/assistant/**` — PASS.
- [ ] **Step 5: `./build.sh`; commit** — `feat(assistant): validate drafts via MCP tool and shared validation`

---

### Task 6: Generated reference, examples, and the rewritten prompt

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/assistant/AssistantReferenceGenerator.java`
- Create: `app/src/main/resources/templates/assistant-reference/examples/` with the session layout:
  - `tools/post-issue-comment.json`
  - `toolsets/triage-tools.json`
  - `action-types/triage-issue.json` (agent mode, `allowedTools` uses `@triage-tools`, prompt uses
    `{{ref}}`, `{{repository}}`, `{{inputs.priorityHint}}`, declares input `priorityHint` and outputs
    `severity` (string) and `needsInfo` (boolean))
  - `action-types/request-more-info.json` (script mode, uses `{{subjectId}}` in the script — allowed there)
  - `workflows/issue-triage.json` — start (inputs: `subjectId`, `ref`, `event`) → action `triage-issue` →
    branch: edge with condition `context.needsInfo == true` → action `request-more-info` → `receive-event`
    (`eventType: issue.comment.created`) → end; default edge → end. All edges have `id`, `source`, `target`,
    `priority`, `isDefault`; exactly one default per branching node; positions spaced 200px.
  - `event-sources/github-com.json` (github, `repositories: ["my-org/my-repo"]`, `secretName: "GH_TOKEN"`,
    `enabled: false`)
  - `subscriptions/triage-new-issues.json` — filter
    `event.type == 'issue.created' && event.eventSourceId == 'github-com'`, routing
    `[{"type": "create-workflow", "workflowDefinitionName": "issue-triage"}]`, `enabled: false`
  - Verify the output→context mapping the engine uses for action outputs (read `WorkflowExecutionService`)
    before writing the workflow's branch condition, so the condition refers to a key that really exists.
- Modify: `AssistantSessionManager.buildSystemPrompt` — always replace `{{reference}}` with
  `referenceGenerator.generate()` (independent of subject).
- Modify: `app/src/main/resources/templates/assistant-templates/axiom-config-assistant.json` — rewrite
  `systemPrompt` and `welcomeMessage`.
- Test: `app/src/test/java/io/apitomy/axiom/app/assistant/AssistantReferenceTest.java`

**Interfaces:**

```java
/** Builds the Configuration Assistant's reference text from code and the bundled examples. */
@ApplicationScoped
public class AssistantReferenceGenerator {

    /**
     * Per node type documentation of config fields; keys must cover every {@link NodeType}.
     * Kept next to the generator so a new node type fails {@code AssistantReferenceTest}.
     */
    static final Map<NodeType, String> NODE_CONFIG_DOCS = Map.of(...);

    /** @return Markdown reference text (cached after first call) */
    public String generate() { ... }

    /**
     * Lists bundled example files as (relative path, JSON text) pairs, in ConfigItemType order.
     *
     * @return example files
     */
    public List<Map.Entry<String, String>> examples() { ... }
}
```

`generate()` Markdown sections (all lists built from code, not literals, except `NODE_CONFIG_DOCS` text and
the plain-language rule sentences):
1. **Workflows** — JSON shape `{name, description, content: {id, name, nodes, edges}}`; node fields
   (`id`, `type`, `name`, `config`, `position {x,y}`); node types from `NodeType.values()` with their JSON
   names (`@JsonProperty` value) and `NODE_CONFIG_DOCS`; edge fields from `WorkflowEdge` record components
   (`WorkflowEdge.class.getRecordComponents()`); graph rules in plain language (one default edge per branching
   node, default edge has no condition, single conditional edge needs a fallback, don't mix fork and
   conditional edges, all nodes connected, every non-end node has an outgoing edge, no automated cycles);
   conditions are raw EL without `${}` using `context.<key>` and `event.<field>`; Start input rules (names match
   `WorkflowContentRules.INPUT_NAME_PATTERN`, `WorkflowContentRules.OPTIONAL_BUILT_IN_INPUTS` may not be
   required); `action` `actionType` may be `sdk:<fn>` for: `SdkFunctionRegistry.sdkCallFunctions()` names.
2. **Action types** — prompt placeholders `ActionTypeValidator.RECOGNIZED_PROMPT_PLACEHOLDERS` plus
   `{{inputs.<name>}}`; script placeholders `RECOGNIZED_SCRIPT_PLACEHOLDERS`; field types
   `string|number|boolean|object`; allowed tool formats `mcp__axiom-tools__<tool>`, `@<toolset>`,
   `mcp__axiom-sdk__<fn>` for `SdkFunctionRegistry.allToolNames()`; runs only from workflow action nodes.
3. **Events** — event types `EventType.values()`; subscription filter variables `event.id`, `event.type`,
   `event.source` (`github`|`jira`), `event.eventSourceId`, `event.ref`, `event.timestamp`, `event.actor`,
   `event.payload`; event-source configuration per source type.
4. **Subscriptions** — routing types; `workflowDefinitionName` preferred for workflows created in the same
   session; created disabled.
5. **Examples** — each example file as a fenced JSON block titled with its path.

Prompt (`systemPrompt`) content: role; the loop "discover with list/get tools → draft → call
`axiom_validate_item` → fix until VALID → write to `<dir>/<name>.json` → read validation feedback"; concepts
(tool vs action type; how workflow, subscription and event source fit; action types only run from workflow
action nodes; event sources and subscriptions are created disabled — tell the user to enable them); updating
existing items (fetch with get tools, write complete definition with the same name); file layout per type;
then `{{reference}}`. Remove all hard-coded schemas from the old prompt (they now live in the reference).
`welcomeMessage`: say "Event Sources" (not Connections) and list the seven item types.

- [ ] **Step 1: Failing tests** — `@QuarkusTest class AssistantReferenceTest`:

```java
    @Inject
    AssistantReferenceGenerator generator;
    @Inject
    ImportExportService importExportService;
    @Inject
    ObjectMapper mapper;
    @Inject
    ConfigItemValidationService validationService;
    @Inject
    ValidationContextFactory contextFactory;

    @Test
    void nodeConfigDocsCoverEveryNodeType() {
        assertEquals(EnumSet.allOf(NodeType.class), EnumSet.copyOf(AssistantReferenceGenerator.NODE_CONFIG_DOCS.keySet()));
    }

    @Test
    void referenceListsEveryNodeAndEventType() {
        String ref = generator.generate();
        for (EventType t : EventType.values()) {
            assertTrue(ref.contains(t.value()), t.value());
        }
        for (String n : List.of("start", "end", "action", "human-task", "receive-event", "wait")) {
            assertTrue(ref.contains("`" + n + "`"), n);
        }
    }

    @Test
    void everyExampleValidatesWithoutErrors(@TempDir Path dir) throws Exception {
        copyExamples(dir);
        ObjectNode pack = AssistantPackBuilder.fromDirectory(dir, mapper);
        ValidationContext ctx = contextFactory.forPack(pack);
        for (Map.Entry<String, String> e : generator.examples()) {
            ConfigItemType type = ConfigItemType.fromName(e.getKey().substring(0, e.getKey().indexOf('/')));
            List<ConfigProblem> problems = validationService.validate(type, mapper.readTree(e.getValue()), ctx);
            assertFalse(ConfigItemValidationService.hasErrors(problems), e.getKey() + ": " + problems);
        }
    }

    @Test
    void examplesApplyEndToEnd(@TempDir Path dir) throws Exception {
        copyExamples(dir);
        importExportService.importOrUpdatePack(AssistantPackBuilder.fromDirectory(dir, mapper));
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowDefinitionEntity wf = WorkflowDefinitionEntity.find("name", "issue-triage").firstResult();
            assertNotNull(wf);
            assertNotNull(wf.currentVersion);
            EventSubscriptionEntity sub = EventSubscriptionEntity.find("name", "triage-new-issues").firstResult();
            assertTrue(sub.routing.contains("\"workflowDefinitionId\":" + wf.id), sub.routing);
        });
    }

    @Test
    void configAssistantPromptUsesReferencePlaceholder() throws Exception {
        String template = new String(getClass().getResourceAsStream(
                "/templates/assistant-templates/axiom-config-assistant.json").readAllBytes(), StandardCharsets.UTF_8);
        String prompt = mapper.readTree(template).path("systemPrompt").asText();
        assertTrue(prompt.contains("{{reference}}"));
        assertTrue(prompt.contains("axiom_validate_item"));
        assertFalse(prompt.contains("\"START\""));
        assertFalse(prompt.contains("sourceNodeId"));
    }

    private void copyExamples(Path dir) throws IOException {
        for (Map.Entry<String, String> e : generator.examples()) {
            Path f = dir.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
        }
    }
```

  Also add one `AssistantSessionManager`-level check (in an existing assistant `@QuarkusTest` or a new one) that
  a created config-assistant session's system prompt (as passed to the mocked driver's request — see
  `AssistantEngineSelectionIntegrationTest.lastRequest`) contains `issue.comment.created` and does not contain
  `{{reference}}`.
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Implement** generator, examples, `buildSystemPrompt` change, prompt rewrite. Load examples from
  the classpath (list them from a small index file `examples/index.txt` with one relative path per line, since
  classpath directory listing is unreliable in packaged jars).
- [ ] **Step 4: Run** `AssistantReferenceTest` and assistant tests — PASS.
- [ ] **Step 5: `./build.sh`; commit** — `feat(assistant): generated reference, validated examples and rewritten prompt`

---

### Task 7: Documentation

**Files:** `docs/user-guide/ai-assistant.md` (Configuration Assistant section), `docs/user-guide/workflows.md`
(note subscriptions may reference workflows by name), `docs/developer-guide/` page that covers the assistant or
validation (find with `git grep -l -i -E 'assistant|validat' docs/developer-guide`), `openapi`-facing docs if any
list `RoutingRule` fields.

- [ ] **Step 1: Edit** — describe: the validate-before-write loop and `axiom_validate_item`; that Apply validates
  everything, creates in dependency order, publishes workflows, and rolls back on any error; that
  `workflowDefinitionName` is accepted in `create-workflow` routing rules (resolved to an id); that
  assistant-created event sources/subscriptions are disabled until enabled; developer note that
  `ConfigItemValidationService` is the single place for item validation and that examples under
  `templates/assistant-reference/examples/` are tested. Wrap prose at 110 columns.
- [ ] **Step 2: Verify** `mkdocs build --strict` if installed.
- [ ] **Step 3: Commit** — `docs: describe configuration assistant validation and apply`

---

## Final Verification

- [ ] `./build.sh -Pui` — BUILD SUCCESS.
- [ ] `git grep -n -E '"START"|sourceNodeId|axiom_list_agents' -- app/src/main` — no matches.
- [ ] `git grep -n 'applyWorkflowDefinitions' -- app/src/main` — no matches.
