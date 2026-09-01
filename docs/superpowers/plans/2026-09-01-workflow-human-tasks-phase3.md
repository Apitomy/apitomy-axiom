# Workflow Human Tasks (Phase 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended)
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`)
> syntax for tracking.

**Goal:** Make Apitomy Flow `human-task` nodes executable in Axiom by surfacing them as inbox tasks and
resuming the workflow with the human's coerced answers.

**Architecture:** When a workflow parks on a `HUMAN_TASK` node, `WorkflowExecutionService` creates an
`AwaitingInput` `TaskEntity` whose `humanContext`/`outputSchema` are mapped from the engine's
`HumanTaskInfo` (via a new `WorkflowHumanTaskMapper`). Completion goes through the single, existing
`POST /inbox/{id}/complete` path; the workflow service coerces answers to the node's semantic types and
calls `engine.completeCurrentNode`. The divergent free-form `/respond` path is removed and the
project-detail UI is unified onto the structured inbox form.

**Tech Stack:** Java 25 / Quarkus 3.33 (Panache, JAX-RS generated from `openapi.json`, JUnit 5 +
RestAssured), `apitomy-flow-engine` 1.0.3, React 19 / PatternFly 6 / Vite, `@apitomy/flow-ui` 1.0.3,
`apitomy-codegen` for bean generation.

**Spec:** `docs/superpowers/specs/2026-09-01-workflow-human-tasks-phase3-design.md`

## Global Constraints

- **The user compiles and runs all builds/tests — never run `mvn`, `./build.sh`, or `npm` build/test
  commands automatically.** Each task states the tests to write and their expected result; hand off to
  the user to compile and run, then continue based on their report.
- **OpenAPI edits use the apicurio-data-models MCP tools** (per user preference), not hand-editing
  `openapi.json` where a tool fits. After OpenAPI changes, the user rebuilds `common/api` to regenerate
  beans before dependent Java tasks compile.
- **Prefer Serena MCP symbolic tools** (`find_symbol`, `replace_symbol_body`, `insert_after_symbol`)
  over line-based editing where possible. Do not activate/onboard Serena — the user does that manually.
- **Contract-first:** REST changes start in `common/api/src/main/resources/openapi.json`; beans
  regenerate into `common/api/target/generated-sources/jaxrs/`. REST impls implement generated
  interfaces (no `@Path` on impls) and use generated beans from `io.apitomy.axiom.api.beans` — never raw
  `JsonNode` for request/response bodies.
- **Java style:** 4-space indent, explicit types (avoid ambiguous `var`), Javadoc on public methods,
  functional style where it reads cleanly, `camelCase`/`PascalCase`. Tests are JUnit 5 (`@QuarkusTest` +
  RestAssured, black-box HTTP — model on `WorkflowInstanceResourceTest`).
- **Field/widget vocabulary is fixed.** Flow semantic types: `string | number | boolean | object`. Flow
  widgets received by Axiom: `text | textarea | select | number | checkbox`. Axiom `OutputSchemaField`
  types: `text | textarea | boolean | select | number`. The only non-identity mapping is
  `checkbox → boolean`.
- **Markdown** wraps at 110 chars (except tables/structured content).
- **Git:** never include Claude attribution in commit messages or PR descriptions. Work on a new branch
  `feat/228-workflow-human-tasks`. Commit after each task.

---

## File Structure

**Backend — create:**
- `app/src/main/java/io/apitomy/axiom/app/WorkflowHumanTaskMapper.java` — maps `HumanTaskInfo` →
  `HumanContext`/`OutputSchema` beans and coerces submitted answers to semantic types.
- `app/src/test/java/io/apitomy/axiom/app/WorkflowHumanTaskMapperTest.java` — unit tests for the mapper.

**Backend — modify:**
- `common/api/src/main/resources/openapi.json` — add `HumanContextDetail` schema + `details` on
  `HumanContext`; remove `respondToTask` operation and `TaskResponse` schema.
- `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java` — support `HUMAN_TASK`
  (validation, task creation branch, completion branch), inject `TaskExecutionService`.
- `app/src/main/java/io/apitomy/axiom/app/rest/ProjectsResourceImpl.java` — delete `respondToTask`.
- `app/src/main/java/io/apitomy/axiom/app/rest/InboxResourceImpl.java` — round-trip `defaultValue` and
  map `details` in `parseHumanContext`/`toInboxItem`.
- `app/pom.xml` — bump `apitomy-flow-engine` to `1.0.3`.
- `app/src/test/java/io/apitomy/axiom/app/WorkflowInstanceResourceTest.java` — rewrite the
  unsupported-node test to use `receive-event`; add human-task execution tests.

**Frontend — modify:**
- `ui/package.json` — bump `@apitomy/flow-ui` to `1.0.3`.
- `ui/src/config/api.ts` — remove `respondToTask`; add `details` to the `HumanContext` type.
- `ui/src/pages/ProjectDetailPage.tsx` — replace free-form respond box with `DynamicFormRenderer` +
  `completeInboxItem`.
- `ui/src/pages/InboxPage.tsx` — render `humanContext.details` read-only above the form.

---

### Task 1: Bump Flow dependencies

**Files:**
- Modify: `app/pom.xml:75`
- Modify: `ui/package.json:14`

**Interfaces:**
- Produces: the `OutputDefinition` accessors `label()`, `description()`, `widget()`, `defaultValue()`,
  `options()` and the `OutputOption(label, value)` record on the classpath (engine 1.0.3), consumed by
  Task 3.

- [ ] **Step 1: Bump the engine version**

In `app/pom.xml`, change the `apitomy-flow-engine` dependency version:

```xml
        <dependency>
            <groupId>io.apitomy</groupId>
            <artifactId>apitomy-flow-engine</artifactId>
            <version>1.0.3</version>
        </dependency>
```

- [ ] **Step 2: Bump the flow-ui version**

In `ui/package.json`, change the dependency:

```json
        "@apitomy/flow-ui": "1.0.3",
```

- [ ] **Step 3: Developer checkpoint**

Ask the user to refresh dependencies (`./build.sh` for Maven; `npm install` in `ui/`) and confirm both
resolve. Expected: `io.apitomy.flow.model.OutputOption` and the extended `OutputDefinition` constructor
are available; `@apitomy/flow-ui@1.0.3` installs.

- [ ] **Step 4: Commit**

```bash
git add app/pom.xml ui/package.json
git commit -m "chore: upgrade Apitomy Flow engine and UI to 1.0.3 for human-task metadata"
```

---

### Task 2: OpenAPI — add `HumanContext.details`, remove the free-form respond path

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`

**Interfaces:**
- Produces: generated beans `HumanContextDetail` (`{label, value}`) and a `details` list on
  `HumanContext`, consumed by Tasks 3, 5, 6. Removes generated `TaskResponse` and the `respondToTask`
  method from the `Projects`/`Tasks` interface (consumed-removal handled in Tasks 5 UI + backend).

- [ ] **Step 1: Add the `HumanContextDetail` schema**

Using the apicurio-data-models MCP tools, add a schema `HumanContextDetail` under
`components.schemas`:

```json
{
  "type": "object",
  "required": ["label", "value"],
  "properties": {
    "label": { "type": "string" },
    "value": { "type": "string" }
  }
}
```

- [ ] **Step 2: Add `details` to `HumanContext`**

Add an optional `details` property to the `HumanContext` schema (do not add it to `required`):

```json
      "details": {
        "type": "array",
        "description": "Display-only context values resolved from the workflow node's inputs.",
        "items": { "$ref": "#/components/schemas/HumanContextDetail" }
      }
```

- [ ] **Step 3: Remove the `respondToTask` operation**

Delete the entire `/projects/{projectId}/tasks/{taskId}/respond` path item (operation
`respondToTask`) from `paths`.

- [ ] **Step 4: Remove the `TaskResponse` schema**

Delete the `TaskResponse` schema from `components.schemas`.

- [ ] **Step 5: Validate the document**

Run the apicurio-data-models `document_validate` tool. Expected: no unresolved `$ref` and no remaining
reference to `TaskResponse` or `respondToTask`.

- [ ] **Step 6: Developer checkpoint**

Ask the user to build `common/api` to regenerate beans. Expected: `HumanContextDetail` and
`HumanContext.getDetails()` exist in `io.apitomy.axiom.api.beans`; `TaskResponse` no longer exists (so
`ProjectsResourceImpl.respondToTask` will not compile until Task 5 — that is expected).

- [ ] **Step 7: Commit**

```bash
git add common/api/src/main/resources/openapi.json
git commit -m "feat(api): add HumanContext.details and remove free-form task respond endpoint"
```

---

### Task 3: `WorkflowHumanTaskMapper` — mapping and answer coercion

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/WorkflowHumanTaskMapper.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowHumanTaskMapperTest.java`

**Interfaces:**
- Consumes: `io.apitomy.flow.model.HumanTaskInfo`, `OutputDefinition`, `OutputOption` (engine 1.0.3);
  `io.apitomy.axiom.api.beans.{HumanContext, HumanContextDetail, OutputSchema, OutputSchemaField,
  OutputSchemaFieldOption}` (Task 2).
- Produces: `static HumanContext toHumanContext(HumanTaskInfo hti)`,
  `static OutputSchema toOutputSchema(List<OutputDefinition> outputs)`,
  `static Map<String,Object> coerceAnswers(List<OutputDefinition> outputs, Map<String,Object> answers)`.
  Consumed by `WorkflowExecutionService` (Task 5).

- [ ] **Step 1: Write the failing tests**

Create `WorkflowHumanTaskMapperTest.java`:

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.api.beans.HumanContext;
import io.apitomy.axiom.api.beans.OutputSchema;
import io.apitomy.axiom.api.beans.OutputSchemaField;
import io.apitomy.flow.model.HumanTaskInfo;
import io.apitomy.flow.model.OutputDefinition;
import io.apitomy.flow.model.OutputOption;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowHumanTaskMapperTest {

    @Test
    void toHumanContextUsesNodeNameAndDescriptionAndDetails() {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("Credit score", 720);
        inputs.put("Applicant", "Ada");
        HumanTaskInfo hti = new HumanTaskInfo("ht1", "Approve loan", "Review the application",
                inputs, List.of());

        HumanContext ctx = WorkflowHumanTaskMapper.toHumanContext(hti);

        assertEquals("Approve loan", ctx.getTitle());
        assertEquals("Review the application", ctx.getDescription());
        assertEquals(2, ctx.getDetails().size());
        assertEquals("Credit score", ctx.getDetails().get(0).getLabel());
        assertEquals("720", ctx.getDetails().get(0).getValue());
    }

    @Test
    void toHumanContextFallsBackToDefaultTitle() {
        HumanTaskInfo hti = new HumanTaskInfo("ht1", "  ", null, Map.of(), List.of());
        HumanContext ctx = WorkflowHumanTaskMapper.toHumanContext(hti);
        assertEquals("Human Task", ctx.getTitle());
    }

    @Test
    void toOutputSchemaMapsWidgetsTypesOptionsAndDefault() {
        OutputDefinition approve = new OutputDefinition(
                "approved", "boolean", true, "Approve?", "Check to approve", "checkbox", true, null);
        OutputDefinition tier = new OutputDefinition(
                "tier", "string", false, "Tier", null, "select", "gold",
                List.of(new OutputOption("Gold", "gold"), new OutputOption("Silver", "silver")));
        OutputDefinition notes = new OutputDefinition(
                "notes", "string", false, "Notes", null, "textarea", null, null);

        OutputSchema schema = WorkflowHumanTaskMapper.toOutputSchema(List.of(approve, tier, notes));

        assertEquals(3, schema.getFields().size());
        OutputSchemaField f0 = schema.getFields().get(0);
        assertEquals("approved", f0.getName());
        assertEquals(OutputSchemaField.Type.BOOLEAN, f0.getType());
        assertEquals("Approve?", f0.getLabel());
        assertTrue(f0.getRequired());
        assertEquals(Boolean.TRUE, f0.getDefaultValue());

        OutputSchemaField f1 = schema.getFields().get(1);
        assertEquals(OutputSchemaField.Type.SELECT, f1.getType());
        assertEquals(2, f1.getOptions().size());
        assertEquals("gold", f1.getOptions().get(0).getValue());

        assertEquals(OutputSchemaField.Type.TEXTAREA, schema.getFields().get(2).getType());
    }

    @Test
    void toOutputSchemaReturnsNullWhenNoOutputs() {
        assertNull(WorkflowHumanTaskMapper.toOutputSchema(List.of()));
        assertNull(WorkflowHumanTaskMapper.toOutputSchema(null));
    }

    @Test
    void coerceAnswersConvertsToSemanticTypes() {
        List<OutputDefinition> outputs = List.of(
                new OutputDefinition("score", "number", false),
                new OutputDefinition("approved", "boolean", false),
                new OutputDefinition("meta", "object", false),
                new OutputDefinition("comment", "string", false));

        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("score", "720");
        answers.put("approved", "true");
        answers.put("meta", "{\"k\":1}");
        answers.put("comment", 42);

        Map<String, Object> coerced = WorkflowHumanTaskMapper.coerceAnswers(outputs, answers);

        assertEquals(720L, coerced.get("score"));
        assertEquals(Boolean.TRUE, coerced.get("approved"));
        assertTrue(coerced.get("meta") instanceof Map);
        assertEquals("42", coerced.get("comment"));
    }

    @Test
    void coerceAnswersSkipsMissingAndKeepsNativeTypes() {
        List<OutputDefinition> outputs = List.of(
                new OutputDefinition("score", "number", false),
                new OutputDefinition("absent", "string", false));
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("score", 3.5);

        Map<String, Object> coerced = WorkflowHumanTaskMapper.coerceAnswers(outputs, answers);

        assertEquals(3.5, coerced.get("score"));
        assertTrue(!coerced.containsKey("absent"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Ask the user to run `WorkflowHumanTaskMapperTest`. Expected: FAIL — `WorkflowHumanTaskMapper` does not
exist.

- [ ] **Step 3: Implement the mapper**

Create `WorkflowHumanTaskMapper.java`:

```java
package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.HumanContext;
import io.apitomy.axiom.api.beans.HumanContextDetail;
import io.apitomy.axiom.api.beans.OutputSchema;
import io.apitomy.axiom.api.beans.OutputSchemaField;
import io.apitomy.axiom.api.beans.OutputSchemaFieldOption;
import io.apitomy.flow.model.HumanTaskInfo;
import io.apitomy.flow.model.OutputDefinition;
import io.apitomy.flow.model.OutputOption;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps Apitomy Flow human-task node data ({@link HumanTaskInfo}) onto Axiom inbox beans and coerces a
 * human's submitted answers back to the node's declared semantic types before they are merged into the
 * workflow context.
 */
public final class WorkflowHumanTaskMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_TITLE = "Human Task";

    private WorkflowHumanTaskMapper() {
    }

    /**
     * Builds the human-facing context (title, description, display-only details) for an inbox task from
     * a parked human-task node.
     *
     * @param hti the engine's human-task introspection
     * @return a populated {@link HumanContext}
     */
    public static HumanContext toHumanContext(HumanTaskInfo hti) {
        HumanContext ctx = new HumanContext();
        String name = hti.nodeName();
        ctx.setTitle(name != null && !name.isBlank() ? name : DEFAULT_TITLE);
        ctx.setDescription(hti.description());

        if (hti.inputs() != null && !hti.inputs().isEmpty()) {
            List<HumanContextDetail> details = new ArrayList<>();
            for (Map.Entry<String, Object> entry : hti.inputs().entrySet()) {
                HumanContextDetail detail = new HumanContextDetail();
                detail.setLabel(entry.getKey());
                detail.setValue(entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
                details.add(detail);
            }
            ctx.setDetails(details);
        }
        return ctx;
    }

    /**
     * Builds the completion form schema from a human-task node's declared outputs.
     *
     * @param outputs the node's output definitions (may be null/empty)
     * @return an {@link OutputSchema}, or {@code null} when there are no outputs (free-form response)
     */
    public static OutputSchema toOutputSchema(List<OutputDefinition> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return null;
        }
        List<OutputSchemaField> fields = new ArrayList<>();
        for (OutputDefinition output : outputs) {
            OutputSchemaField field = new OutputSchemaField();
            field.setName(output.name());
            field.setType(widgetToFieldType(output.widget()));
            field.setLabel(output.label() != null ? output.label() : output.name());
            field.setDescription(output.description());
            field.setRequired(output.required());
            field.setDefaultValue(output.defaultValue());
            if (output.options() != null && !output.options().isEmpty()) {
                List<OutputSchemaFieldOption> options = new ArrayList<>();
                for (OutputOption option : output.options()) {
                    OutputSchemaFieldOption o = new OutputSchemaFieldOption();
                    o.setLabel(option.label());
                    o.setValue(option.value());
                    options.add(o);
                }
                field.setOptions(options);
            }
            fields.add(field);
        }
        OutputSchema schema = new OutputSchema();
        schema.setFields(fields);
        return schema;
    }

    /**
     * Coerces a human's submitted answers to the semantic types declared by the node's outputs, so that
     * downstream edge conditions and nodes see real numbers/booleans/objects rather than strings.
     * Missing answers are omitted; undeclared keys pass through unchanged.
     *
     * @param outputs the node's output definitions
     * @param answers the submitted answer map (field name to value)
     * @return a new map with values coerced to declared types
     */
    public static Map<String, Object> coerceAnswers(List<OutputDefinition> outputs,
            Map<String, Object> answers) {
        Map<String, Object> result = new LinkedHashMap<>(answers != null ? answers : Map.of());
        if (outputs == null) {
            return result;
        }
        for (OutputDefinition output : outputs) {
            String name = output.name();
            if (answers == null || !answers.containsKey(name) || answers.get(name) == null) {
                result.remove(name);
                continue;
            }
            result.put(name, coerce(output.type(), answers.get(name)));
        }
        return result;
    }

    private static Object coerce(String type, Object value) {
        String semanticType = type != null ? type : "string";
        return switch (semanticType) {
            case "number" -> coerceNumber(value);
            case "boolean" -> value instanceof Boolean b ? b
                    : value instanceof String s ? Boolean.parseBoolean(s) : value;
            case "object" -> coerceObject(value);
            default -> String.valueOf(value);
        };
    }

    private static Object coerceNumber(Object value) {
        if (value instanceof Number) {
            return value;
        }
        if (value instanceof String s) {
            try {
                if (s.contains(".") || s.contains("e") || s.contains("E")) {
                    return Double.parseDouble(s);
                }
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return value;
            }
        }
        return value;
    }

    private static Object coerceObject(Object value) {
        if (value instanceof Map || value instanceof List) {
            return value;
        }
        if (value instanceof String s) {
            try {
                return MAPPER.readValue(s, Object.class);
            } catch (Exception e) {
                return value;
            }
        }
        return value;
    }

    private static OutputSchemaField.Type widgetToFieldType(String widget) {
        String w = widget != null ? widget : "text";
        return switch (w) {
            case "checkbox", "boolean" -> OutputSchemaField.Type.BOOLEAN;
            case "textarea" -> OutputSchemaField.Type.TEXTAREA;
            case "select" -> OutputSchemaField.Type.SELECT;
            case "number" -> OutputSchemaField.Type.NUMBER;
            default -> OutputSchemaField.Type.TEXT;
        };
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Ask the user to run `WorkflowHumanTaskMapperTest`. Expected: PASS. (If `OutputSchemaField.Type` enum
constant names differ from `BOOLEAN`/`TEXTAREA`/`SELECT`/`NUMBER`/`TEXT`, adjust both the mapper and
test to the generated names — verify against the generated bean.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/WorkflowHumanTaskMapper.java \
        app/src/test/java/io/apitomy/axiom/app/WorkflowHumanTaskMapperTest.java
git commit -m "feat: map Flow human-task info to inbox beans and coerce answers to semantic types"
```

---

### Task 4: `WorkflowExecutionService` — execute and complete human-task nodes

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java`

**Interfaces:**
- Consumes: `WorkflowHumanTaskMapper.toHumanContext/toOutputSchema/coerceAnswers` (Task 3);
  `TaskExecutionService.markTaskAwaitingInput(Long)`; `WorkflowEngine.getHumanTaskInfo(Workflow,
  WorkflowInstance)` returning `HumanTaskInfo`.
- Produces: human-task creation on entry and human-task-aware completion, keyed off
  `getHumanTaskInfo(...) != null`.

- [ ] **Step 1: Add `HUMAN_TASK` to the supported set and update the message**

Edit the constant and `validateNodeTypes`:

```java
    private static final Set<NodeType> SUPPORTED_NODE_TYPES =
            Set.of(NodeType.START, NodeType.END, NodeType.ACTION, NodeType.HUMAN_TASK);
```

In `validateNodeTypes`, change the message tail to:

```java
                            + ". Supported: start, end, action, human-task.",
```

- [ ] **Step 2: Inject `TaskExecutionService`**

Add the imports and field:

```java
import io.apitomy.flow.model.HumanTaskInfo;
```

```java
    @Inject
    TaskExecutionService taskExecutionService;
```

- [ ] **Step 3: Branch task creation by node kind**

Replace the body of `createTaskForCurrentNode(...)` so it delegates to a human-task path when the parked
node is a human task, otherwise keeps the existing action path:

```java
    private void createTaskForCurrentNode(WorkflowRunEntity entity,
            Workflow workflow, WorkflowInstance instance) {
        HumanTaskInfo humanTaskInfo = workflowEngine.getHumanTaskInfo(workflow, instance);
        if (humanTaskInfo != null) {
            createHumanTaskForNode(entity, instance, humanTaskInfo);
            return;
        }

        ActionInfo actionInfo = workflowEngine.getActionInfo(workflow, instance);
        if (actionInfo == null) {
            LOG.warnf("No action info for current node in instance %d", entity.id);
            return;
        }

        TaskEntity task = new TaskEntity();
        task.projectId = entity.projectId;
        task.actionType = actionInfo.actionType();
        task.createdBy = "workflow";
        task.status = "Pending";
        task.input = serializeInputs(actionInfo);
        task.workflowRunId = entity.id;
        task.nodeId = instance.currentNodeId();
        task.traceId = entity.traceId;
        task.createdOn = Instant.now();
        task.persist();

        TraceContext traceCtx = traceContextFor(entity);
        if (traceCtx != null) {
            try {
                traceService.addNode(traceCtx, "task", "in-progress",
                        "Node: " + actionInfo.actionType(), "task", task.id);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to add workflow task trace node");
            }
        }

        LOG.infof("Created task %d for workflow instance %d (action: %s)",
                task.id, entity.id, actionInfo.actionType());

        sseEvents.fire(SseEvent.taskUpdated(entity.projectId, task.id, task.status));
    }
```

- [ ] **Step 4: Implement the human-task creation helper**

Add this private method (e.g. after `createTaskForCurrentNode`):

```java
    /**
     * Creates an inbox task ({@code AwaitingInput}) for a workflow that has parked on a human-task
     * node, mapping the node's Flow config into human-facing context and a completion form.
     *
     * @param entity        the owning workflow run
     * @param instance      the parked (WAITING) engine instance
     * @param humanTaskInfo the engine's human-task introspection for the current node
     */
    private void createHumanTaskForNode(WorkflowRunEntity entity,
            WorkflowInstance instance, HumanTaskInfo humanTaskInfo) {
        TaskEntity task = new TaskEntity();
        task.projectId = entity.projectId;
        String nodeName = humanTaskInfo.nodeName();
        task.actionType = nodeName != null && !nodeName.isBlank() ? nodeName : "Human Task";
        task.createdBy = "workflow";
        task.status = "Pending";
        task.workflowRunId = entity.id;
        task.nodeId = instance.currentNodeId();
        task.traceId = entity.traceId;
        task.createdOn = Instant.now();

        try {
            task.humanContext = objectMapper.writeValueAsString(
                    WorkflowHumanTaskMapper.toHumanContext(humanTaskInfo));
            OutputSchema schema = WorkflowHumanTaskMapper.toOutputSchema(humanTaskInfo.outputs());
            task.outputSchema = schema != null ? objectMapper.writeValueAsString(schema) : null;
        } catch (JsonProcessingException e) {
            throw new WebApplicationException(
                    "Failed to serialize human task context", 500);
        }
        task.persist();

        TraceContext traceCtx = traceContextFor(entity);
        if (traceCtx != null) {
            try {
                traceService.addNode(traceCtx, "task", "in-progress",
                        "Human task: " + task.actionType, "task", task.id);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to add workflow human-task trace node");
            }
        }

        LOG.infof("Created human task %d for workflow instance %d (node: %s)",
                task.id, entity.id, task.nodeId);

        taskExecutionService.markTaskAwaitingInput(task.id);
    }
```

Add the import for the bean:

```java
import io.apitomy.axiom.api.beans.OutputSchema;
```

- [ ] **Step 5: Branch completion by node kind**

In `onTaskCompleted(long taskId)`, replace the block that builds `result` (the `if
("Completed".equals(task.status)) { ... } else { ... }`) with a human-task-aware version. Insert the
human-task branch before the existing action logic:

```java
        NodeResult result;
        HumanTaskInfo humanTaskInfo = workflowEngine.getHumanTaskInfo(workflow, instance);
        if (humanTaskInfo != null) {
            if ("Completed".equals(task.status)) {
                Map<String, Object> answers = WorkflowHumanTaskMapper.coerceAnswers(
                        humanTaskInfo.outputs(), parseOutputMap(task.output));
                result = new NodeResult(NodeResultStatus.COMPLETED, answers);
            } else {
                result = new NodeResult(NodeResultStatus.FAILED, Map.of());
            }
        } else if ("Completed".equals(task.status)) {
            Map<String, Object> outputMap = parseOutputMap(task.output);
            ActionTypeEntity at = ActionTypeEntity.find("name", task.actionType).firstResult();
            if (at != null && at.outputs != null && !at.outputs.isEmpty()) {
                List<String> outputErrors = ActionTypeIoValidator.validate(at.outputs, outputMap);
                if (!outputErrors.isEmpty()) {
                    String reason = "Output validation failed: " + String.join("; ", outputErrors);
                    LOG.warnf("Workflow task %d %s", task.id, reason);
                    task.status = "Failed";
                    task.executionLog = appendReason(task.executionLog, reason);
                    reconcileTaskTraceNodeFailed(task);
                    sseEvents.fire(SseEvent.taskUpdated(task.projectId, task.id, "Failed"));
                    result = new NodeResult(NodeResultStatus.FAILED, Map.of());
                } else {
                    result = new NodeResult(NodeResultStatus.COMPLETED, outputMap);
                }
            } else {
                result = new NodeResult(NodeResultStatus.COMPLETED, outputMap);
            }
        } else {
            result = new NodeResult(NodeResultStatus.FAILED, Map.of());
        }
```

(The `instance` variable is already in scope above this block — it is deserialized earlier in
`onTaskCompleted`.)

- [ ] **Step 6: Developer checkpoint**

Ask the user to compile `app`. Expected: compiles clean. (Integration test coverage is added in Task
5.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java
git commit -m "feat: execute human-task workflow nodes via the inbox and resume with coerced answers"
```

---

### Task 5: Integration tests — human-task execution end to end

**Files:**
- Modify: `app/src/test/java/io/apitomy/axiom/app/WorkflowInstanceResourceTest.java`

**Interfaces:**
- Consumes: existing test helpers `createProject`, `createDefinition`, and the constants
  `PROJECTS_PATH`, `WORKFLOWS_PATH` in that test class; the inbox endpoints `GET /inbox`,
  `POST /inbox/{taskId}/complete`.

- [ ] **Step 1: Rewrite the unsupported-node test to use `receive-event`**

Replace the `human-task` node in `testTriggerWithUnsupportedNodeTypesReturns400` with a
`receive-event` node (still unsupported), so the test keeps asserting a 400 for a genuinely unsupported
type:

```java
                            {"id": "re1", "type": "receive-event",
                             "name": "Receive Event",
                             "config": {"eventType": "something"},
                             "position": {"x": 100, "y": 200}},
```

Update the two edges to reference `re1` instead of `ht1`.

- [ ] **Step 2: Write the failing human-task execution test**

Add a test that publishes a Start→human-task→End workflow, triggers it, asserts the run is `waiting`
and an inbox item exists, then completes it and asserts the run reaches `completed`. Use a human-task
node whose single boolean output drives a conditional edge:

```java
    @Test
    void testHumanTaskWorkflowSurfacesInboxItemAndAdvancesOnCompletion() {
        int projectId = createProject("WF Human Task Project");
        int definitionId = createDefinition("Human Task WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-human",
                        "name": "Human",
                        "nodes": [
                            {"id": "s1", "type": "start", "name": "Start",
                             "config": {}, "position": {"x": 100, "y": 100}},
                            {"id": "ht1", "type": "human-task", "name": "Approve",
                             "config": {"description": "Approve the request",
                                        "outputs": [{"name": "approved", "type": "boolean",
                                                     "required": true, "label": "Approve?"}]},
                             "position": {"x": 100, "y": 200}},
                            {"id": "e1", "type": "end", "name": "End",
                             "config": {}, "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1", "target": "ht1",
                             "priority": 0, "isDefault": true},
                            {"id": "edge2", "source": "ht1", "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(WORKFLOWS_PATH + "/" + definitionId + "/content")
                .then()
                    .statusCode(204);

        given().when().post(WORKFLOWS_PATH + "/" + definitionId + "/publish")
                .then().statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"workflowDefinitionId": %d}
                    """.formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("waiting"));

        // The parked human-task node produced an inbox item for this project.
        Integer taskId = given()
                .when()
                    .get("/inbox")
                .then()
                    .statusCode(200)
                    .body("items.find { it.projectId == " + projectId + " }.actionType",
                            equalTo("Approve"))
                    .extract()
                    .path("items.find { it.projectId == " + projectId + " }.id");

        // Completing the inbox item with the required boolean advances the workflow to completed.
        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"approved": true}
                    """)
                .when()
                    .post("/inbox/" + taskId + "/complete")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("completed"));
    }
```

(If the get-workflow-for-project endpoint path or response shape differs, align the final assertion
with the pattern already used by the `"waiting"` assertions elsewhere in this test class.)

- [ ] **Step 3: Run the tests**

Ask the user to run `WorkflowInstanceResourceTest`. Expected: the rewritten unsupported-node test still
passes (400 for `receive-event`), and the new human-task test passes.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/io/apitomy/axiom/app/WorkflowInstanceResourceTest.java
git commit -m "test: cover human-task workflow execution and keep receive-event rejected"
```

---

### Task 6: Backend cleanup — remove `respondToTask`, round-trip `defaultValue` and `details`

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/ProjectsResourceImpl.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/InboxResourceImpl.java`

**Interfaces:**
- Consumes: generated `HumanContext.setDetails(List<HumanContextDetail>)`,
  `OutputSchemaField.setDefaultValue(Object)` (from Task 2 + existing bean).
- Produces: `respondToTask` no longer implemented; `parseOutputSchema` round-trips `defaultValue`;
  `parseHumanContext` round-trips `details`.

- [ ] **Step 1: Delete `respondToTask` and its import**

Remove the `respondToTask(...)` method from `ProjectsResourceImpl` and the now-unused
`import io.apitomy.axiom.api.beans.TaskResponse;`. (The generated interface no longer declares the
method after Task 2, so the `@Override` must go.)

- [ ] **Step 2: Round-trip `defaultValue` in `parseOutputSchema`**

In `InboxResourceImpl.parseOutputSchema`, after `field.setRequired(...)`, add:

```java
                if (fieldNode.has("defaultValue") && !fieldNode.get("defaultValue").isNull()) {
                    field.setDefaultValue(
                            objectMapper.convertValue(fieldNode.get("defaultValue"), Object.class));
                }
```

- [ ] **Step 3: Round-trip `details` in `parseHumanContext`**

In `InboxResourceImpl.parseHumanContext`, before `return ctx;`, add:

```java
        if (node.has("details") && node.get("details").isArray()) {
            java.util.List<io.apitomy.axiom.api.beans.HumanContextDetail> details =
                    new java.util.ArrayList<>();
            for (JsonNode detailNode : node.get("details")) {
                io.apitomy.axiom.api.beans.HumanContextDetail detail =
                        new io.apitomy.axiom.api.beans.HumanContextDetail();
                detail.setLabel(detailNode.path("label").asText(""));
                detail.setValue(detailNode.path("value").asText(""));
                details.add(detail);
            }
            ctx.setDetails(details);
        }
```

- [ ] **Step 4: Developer checkpoint**

Ask the user to compile `app`. Expected: compiles clean (no dangling `respondToTask`/`TaskResponse`
references).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/rest/ProjectsResourceImpl.java \
        app/src/main/java/io/apitomy/axiom/app/rest/InboxResourceImpl.java
git commit -m "refactor: remove free-form respondToTask and round-trip defaultValue/details"
```

---

### Task 7: UI — unify project-detail responding onto the structured inbox form

**Files:**
- Modify: `ui/src/config/api.ts`
- Modify: `ui/src/pages/ProjectDetailPage.tsx`
- Modify: `ui/src/pages/InboxPage.tsx`

**Interfaces:**
- Consumes: existing `fetchInboxItem(taskId)`, `completeInboxItem(taskId, values)`, and the
  `DynamicFormRenderer` component.
- Produces: project-detail responding via the structured form; `respondToTask` removed; `details`
  rendered in the inbox detail modal.

- [ ] **Step 1: Remove `respondToTask` and add `details` to the type**

In `ui/src/config/api.ts`, delete the `respondToTask` function (around line 347). In the `HumanContext`
type, add:

```ts
    details?: { label: string; value: string }[];
```

- [ ] **Step 2: Replace the project-detail respond box**

In `ui/src/pages/ProjectDetailPage.tsx`:

- Remove the `respondToTask` import and the `responseText` state.
- Add state to hold the fetched item and form values:

```tsx
    const [respondItem, setRespondItem] = useState<InboxItem | null>(null);
    const [formValues, setFormValues] = useState<Record<string, unknown>>({});
```

- Change the "Respond" button handler to fetch the structured item:

```tsx
                                        onClick={() => {
                                            setRespondingTo(task.id);
                                            setFormValues({});
                                            fetchInboxItem(task.id)
                                                .then(setRespondItem)
                                                .catch(console.error);
                                        }}
```

- Replace the free-form `TextArea` card body with the dynamic form and structured submit:

```tsx
                        <Title headingLevel="h4" size="md">
                            Respond to Task #{respondingTo}
                        </Title>
                        {respondItem?.humanContext?.details?.map((d) => (
                            <div key={d.label}>
                                <strong>{d.label}:</strong> {d.value}
                            </div>
                        ))}
                        <DynamicFormRenderer
                            schema={respondItem?.outputSchema}
                            values={formValues}
                            onChange={setFormValues}
                        />
```

- Rewrite `handleSubmitResponse` to complete via the inbox endpoint:

```tsx
    const handleSubmitResponse = (taskId: number) => {
        setSubmitting(true);
        completeInboxItem(taskId, formValues)
            .then(() => {
                setRespondingTo(null);
                setRespondItem(null);
                setFormValues({});
                onRefresh();
            })
            .catch(console.error)
            .finally(() => setSubmitting(false));
    };
```

- Update the submit button's `isDisabled` to no longer depend on `responseText` (use `submitting`
  only), and add the imports for `fetchInboxItem`, `completeInboxItem`, `DynamicFormRenderer`, and the
  `InboxItem` type.

- [ ] **Step 3: Render `details` in the inbox detail modal**

In `ui/src/pages/InboxPage.tsx`, in the response modal (near where `humanContext.description` and
references render), add a read-only details block:

```tsx
                    {selectedItem?.humanContext?.details?.map((d) => (
                        <div key={d.label}>
                            <strong>{d.label}:</strong> {d.value}
                        </div>
                    ))}
```

- [ ] **Step 4: Developer checkpoint**

Ask the user to run `npm run build` (and `tsc`/lint) in `ui/`, then exercise: trigger a human-task
workflow, respond from both the Inbox page and the Project detail page, and confirm the workflow
advances. Expected: both paths submit structured answers and the run completes.

- [ ] **Step 5: Commit**

```bash
git add ui/src/config/api.ts ui/src/pages/ProjectDetailPage.tsx ui/src/pages/InboxPage.tsx
git commit -m "feat(ui): respond to human tasks with the structured form from the project page"
```

---

### Task 8: Documentation

**Files:**
- Modify: `docs/developer-guide/api-first-development.md` (or the workflow docs area, whichever the
  workflow phases used)
- Create/append: workflow human-task usage notes

**Interfaces:** none (docs only).

- [ ] **Step 1: Document human-task workflow support**

Add a short section describing: authoring a human-task node (description, display inputs, outputs with
optional rich metadata), how it surfaces as an inbox task, the unified completion path, and that answers
are coerced to the node's semantic types and merged into workflow context. Note that `wait` and
`receive-event` remain unsupported. Wrap prose at 110 chars.

- [ ] **Step 2: Commit**

```bash
git add docs/
git commit -m "docs: document human-task workflow nodes and inbox integration"
```

---

## Self-Review

**Spec coverage:** §1 enable execution → Task 4.1; §2 inline task creation → Task 4.3/4.4; §3 mapping +
coercion → Task 3; §4 resume on completion → Task 4.5; §5 unify completion (remove `/respond`, unify
project UI, add `details`) → Tasks 2, 6, 7; §6 `defaultValue` fix → Task 6.2; §7 dependency bumps →
Task 1. Testing strategy → Tasks 3 (unit) and 5 (integration). All spec sections map to a task.

**Type consistency:** `WorkflowHumanTaskMapper` method names (`toHumanContext`, `toOutputSchema`,
`coerceAnswers`) are identical in Task 3 (definition) and Tasks 4.4/4.5 (use). The only widget/type
translation (`checkbox → boolean`) is applied consistently in `widgetToFieldType`. `OutputSchemaField.
Type` enum constant names are assumed `BOOLEAN/TEXTAREA/SELECT/NUMBER/TEXT` — Task 3.4 flags verifying
against the generated bean.

**Known verification points for the executor:** (a) the get-workflow-for-project endpoint path/shape in
Task 5.2's final assertion; (b) `OutputSchemaField.Type` generated constant names; (c) exact line for
the `respondToTask` removal and the `DynamicFormRenderer`/`TextArea` block in `ProjectDetailPage.tsx`
(line numbers drift — match by content).
