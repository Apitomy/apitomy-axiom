# Action Type Inputs/Outputs for Flow Workflows — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended)
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax
> for tracking.

**Goal:** Give Axiom Action Types optional, structured `inputs`/`outputs` and a `workflowEnabled` flag so
they align with Apitomy Flow action-node contracts, and plumb those declarations through the editor and
execution end-to-end.

**Architecture:** Contract-first — the OpenAPI spec changes first, beans regenerate, then the Java entity,
validator, REST mapping, import/export, seed, and execution services follow; finally the React editor
wiring and Action Type authoring UI. Declared inputs/outputs are a flat field list
(`{name, type, required, description}`) matching Flow's `ActionTypeField` 1:1. New behavior is gated on the
Action Type actually declaring inputs/outputs (and, for execution, on the task originating from a workflow
run), so anything that declares nothing behaves exactly as today.

**Tech Stack:** Java 25 / Quarkus 3.33 (Panache/Hibernate ORM, JAX-RS, Flyway on H2), JUnit 5;
React 19 + PatternFly 6 (Vite); `@apitomy/flow-ui` for the editor; `apitomy-codegen` for JAX-RS bean
generation from OpenAPI.

**Spec:** `docs/superpowers/specs/2026-08-31-action-type-workflow-io-design.md`

## Global Constraints

- **The user compiles and runs all builds/tests — never run `mvn`, `./build.sh`, or `npm` build/test
  commands automatically.** Each task below describes the tests to write and their expected result; hand
  off to the user to compile and run them, then continue based on their report. (User standing preference.)
- **OpenAPI edits use the apicurio-data-models MCP tools** (per user preference), not hand-editing
  `openapi.json` where a tool fits.
- **Code edits prefer Serena MCP symbolic tools** (`find_symbol`, `replace_symbol_body`,
  `insert_after_symbol`, etc.) over line-based editing where possible (per user preference).
- **Contract-first:** REST changes start in `common/api/src/main/resources/openapi.json`; beans regenerate
  into `common/api/target/generated-sources/jaxrs/`. REST impls implement generated interfaces and use
  generated beans — never raw `JsonNode` for request/response bodies. After Task 1, the user must
  regenerate beans (build `common/api`) before the Java tasks will compile.
- **Java style:** 4-space indent, explicit types (avoid ambiguous `var`), Javadoc on public methods,
  functional style where it reads cleanly, `camelCase`/`PascalCase`.
- **Field type set** is exactly `string | number | boolean | object` (mirrors Flow). No other types.
- **Migrations** must be H2-compatible and idempotent (`CREATE TABLE IF NOT EXISTS`, guarded drops); DDL
  auto-commits on H2. This is migration `V55`.
- **Commits:** no Claude attribution in commit messages (user preference). Work happens on branch
  `feat/action-type-workflow-io` (already created).

---

## File Structure

**Backend (Java / resources):**
- `common/api/src/main/resources/openapi.json` — add `ActionTypeField` schema; add `inputs`, `outputs`,
  `workflowEnabled` to `ActionType` and `NewActionType`; remove `inputSchema` from both.
- `core/src/main/java/io/apitomy/axiom/core/entities/ActionTypeField.java` — **new** `@Embeddable`.
- `core/src/main/java/io/apitomy/axiom/core/entities/ActionTypeEntity.java` — add `inputs`, `outputs`
  element collections and `workflowEnabled`; remove `inputSchema`.
- `app/src/main/resources/db/migration/V55__add_action_type_io_and_workflow_enabled.sql` — **new**.
- `core/src/main/java/io/apitomy/axiom/core/services/ActionTypeValidator.java` — validate inputs/outputs.
- `core/src/main/java/io/apitomy/axiom/core/services/ActionTypeIoValidator.java` — **new**, runtime
  validation of an input/output value map against declared fields (shared by execution).
- `app/src/main/java/io/apitomy/axiom/app/rest/ActionResourceImpl.java` — map new fields; add
  `workflowEnabled` filter to `listActionTypes`.
- `app/src/main/java/io/apitomy/axiom/app/ImportExportService.java` — import (2 sites) + export new fields.
- `app/src/main/java/io/apitomy/axiom/app/SeedDataInitializer.java` — set `workflowEnabled` on seeds.
- `app/src/main/java/io/apitomy/axiom/app/TaskExecutionService.java` — input validation, `{{inputs.NAME}}`
  binding, output-contract auto-append (agent).
- `app/src/main/java/io/apitomy/axiom/app/ScriptExecutionService.java` — `{{inputs.NAME}}` binding + input
  validation (script).
- `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java` — output validation on completion.

**Frontend (TypeScript / React):**
- `ui/src/config/api.ts` — `ActionType` interface fields; `fetchActionTypes` `workflowEnabled` param.
- `ui/src/pages/WorkflowDefinitionDetailPage.tsx` — populate descriptor `inputs`/`outputs`, filter palette.
- `ui/src/components/ActionTypeFieldsTab.tsx` — **new** repeatable field editor (used by Inputs & Outputs).
- `ui/src/pages/ActionTypeDetailPage.tsx` — `workflowEnabled` checkbox; conditional Inputs/Outputs tabs;
  remove `inputSchema`.

**Tests (Java):**
- `core/src/test/java/io/apitomy/axiom/core/services/ActionTypeValidatorTest.java` — extend.
- `core/src/test/java/io/apitomy/axiom/core/services/ActionTypeIoValidatorTest.java` — **new**.

---

## Task 1: OpenAPI contract — declare fields, remove inputSchema

**Files:**
- Modify: `common/api/src/main/resources/openapi.json` (schemas `ActionType` @6035, `NewActionType` @6128)

**Interfaces:**
- Produces (generated into `io.apitomy.axiom.api.beans`): `ActionTypeField` bean with
  `getName()/setName`, `getType()/setType` (enum `ActionTypeField.Type` with `STRING|NUMBER|BOOLEAN|OBJECT`
  and `.value()`/`.fromValue()`), `getRequired()/setRequired` (Boolean), `getDescription()/setDescription`;
  `ActionType`/`NewActionType` gain `getInputs()/setInputs(List<ActionTypeField>)`,
  `getOutputs()/setOutputs(List<ActionTypeField>)`, `getWorkflowEnabled()/setWorkflowEnabled(Boolean)`;
  both lose `getInputSchema()/setInputSchema`.

- [ ] **Step 1: Add the `ActionTypeField` schema**

Use the apicurio-data-models MCP tools (`document_load` the file, `document_add_schema` /
`document_add_schema_property` / `document_set_schema_type` / `document_add_schema_enum`, then
`document_save`). The schema to add under `components.schemas`:

```json
"ActionTypeField": {
  "required": ["name", "type"],
  "type": "object",
  "properties": {
    "name": { "type": "string", "description": "Field name; must be a valid identifier usable as a template key." },
    "type": { "enum": ["string", "number", "boolean", "object"], "type": "string" },
    "required": { "type": "boolean", "description": "Whether this field must be present. Defaults to false." },
    "description": { "type": "string" }
  }
}
```

- [ ] **Step 2: Add `inputs`, `outputs`, `workflowEnabled` to `ActionType` and `NewActionType`; remove `inputSchema`**

In both `ActionType` (@6035) and `NewActionType` (@6128): delete the `inputSchema` property, and add:

```json
"workflowEnabled": {
  "description": "When true, this action type is offered as a building block in the Flow workflow editor palette.",
  "type": "boolean"
},
"inputs": {
  "description": "Typed inputs this action declares when used as a Flow workflow action node.",
  "type": "array",
  "items": { "$ref": "#/components/schemas/ActionTypeField" }
},
"outputs": {
  "description": "Typed outputs this action produces when used as a Flow workflow action node.",
  "type": "array",
  "items": { "$ref": "#/components/schemas/ActionTypeField" }
}
```

Do **not** add these to the `required` lists (all three are optional). Save via `document_save`.

- [ ] **Step 3: Verify the spec edits**

Read back the `ActionType`, `NewActionType`, and `ActionTypeField` schemas (`document_get_node` or Read the
file) and confirm: `inputSchema` gone from both; the three new properties present; `ActionTypeField` has the
constrained `type` enum. Validate the doc with `document_validate`.

- [ ] **Step 4: Commit**

```bash
git add common/api/src/main/resources/openapi.json
git commit -m "feat(api): declare action type inputs/outputs and workflowEnabled"
```

- [ ] **Step 5: Hand off — user regenerates beans**

Ask the user to build `common/api` (e.g. `mvn install`) so the `ActionTypeField` bean and the new
accessors exist before the Java tasks. Do not run the build yourself. Confirm generation succeeded before
starting Task 3 (Task 2 does not depend on generated beans).

---

## Task 2: Entity, embeddable, and migration

**Files:**
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/ActionTypeField.java`
- Modify: `core/src/main/java/io/apitomy/axiom/core/entities/ActionTypeEntity.java`
- Create: `app/src/main/resources/db/migration/V55__add_action_type_io_and_workflow_enabled.sql`

**Interfaces:**
- Consumes: nothing (independent of generated beans).
- Produces: `ActionTypeField` embeddable with public fields `String name`, `String type`, `boolean required`,
  `String description` (and a no-arg constructor); `ActionTypeEntity.inputs` / `ActionTypeEntity.outputs`
  (`List<ActionTypeField>`), `ActionTypeEntity.workflowEnabled` (`boolean`); `ActionTypeEntity.inputSchema`
  removed.

- [ ] **Step 1: Create the `ActionTypeField` embeddable**

```java
package io.apitomy.axiom.core.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * A single typed input or output field declared by an action type for use as a
 * Flow workflow action node. Persisted as a row in an element-collection table.
 */
@Embeddable
public class ActionTypeField {

    @Column(name = "name", nullable = false)
    public String name;

    /** One of: string, number, boolean, object. */
    @Column(name = "type", nullable = false)
    public String type;

    @Column(name = "required", nullable = false)
    public boolean required;

    @Column(name = "description", columnDefinition = "TEXT")
    public String description;

    /** Required by JPA. */
    public ActionTypeField() {
    }

    /**
     * Creates a fully-populated field.
     *
     * @param name        the field name
     * @param type        the declared type (string/number/boolean/object)
     * @param required    whether the field is required
     * @param description an optional human description
     */
    public ActionTypeField(String name, String type, boolean required, String description) {
        this.name = name;
        this.type = type;
        this.required = required;
        this.description = description;
    }
}
```

- [ ] **Step 2: Add collections and flag to `ActionTypeEntity`; remove `inputSchema`**

In `ActionTypeEntity.java`: remove the `inputSchema` field (lines 34-35). Add the imports
`jakarta.persistence.OrderColumn` and `jakarta.persistence.AttributeOverride` /
`jakarta.persistence.AttributeOverrides` are not needed (single embeddable per table). Add these members
(place `workflowEnabled` near the other boolean flags, and the collections after `labels`):

```java
    @Column(name = "workflow_enabled", nullable = false)
    public boolean workflowEnabled;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "action_type_input", joinColumns = @JoinColumn(name = "action_type_id"))
    @OrderColumn(name = "ordinal")
    public List<ActionTypeField> inputs = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "action_type_output", joinColumns = @JoinColumn(name = "action_type_id"))
    @OrderColumn(name = "ordinal")
    public List<ActionTypeField> outputs = new ArrayList<>();
```

Add `import jakarta.persistence.OrderColumn;` to the import block.

> Note: two eager `@ElementCollection`s plus the eager `labels` collection can trigger a Hibernate
> `MultipleBagFetchException` because `List` maps to a bag. The `@OrderColumn` on `inputs`/`outputs` makes
> them ordered lists (not bags), which avoids the conflict for those two. `labels` remains a bag; if the
> user's build surfaces a `MultipleBagFetchException`, the fix is to make the fetch of these collections
> subselect-based (`@org.hibernate.annotations.Fetch(FetchMode.SUBSELECT)`) — note this in the handoff so
> the user can report the exact error if it appears.

- [ ] **Step 3: Write the migration**

Create `V55__add_action_type_io_and_workflow_enabled.sql`:

```sql
-- Workflow-enablement flag: opt-in eligibility as a Flow workflow node.
ALTER TABLE action_type ADD COLUMN IF NOT EXISTS workflow_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Declared inputs (ordered).
CREATE TABLE IF NOT EXISTS action_type_input (
    action_type_id BIGINT NOT NULL,
    ordinal INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT FALSE,
    description TEXT,
    PRIMARY KEY (action_type_id, ordinal),
    FOREIGN KEY (action_type_id) REFERENCES action_type(id) ON DELETE CASCADE
);

-- Declared outputs (ordered).
CREATE TABLE IF NOT EXISTS action_type_output (
    action_type_id BIGINT NOT NULL,
    ordinal INTEGER NOT NULL,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT FALSE,
    description TEXT,
    PRIMARY KEY (action_type_id, ordinal),
    FOREIGN KEY (action_type_id) REFERENCES action_type(id) ON DELETE CASCADE
);

-- Retire the unenforced free-text input schema.
ALTER TABLE action_type DROP COLUMN IF EXISTS input_schema;
```

- [ ] **Step 4: Hand off — user compiles**

Ask the user to compile `core` and start the app (Flyway applies `V55` on H2). Confirm the entity compiles
and the migration applies cleanly (tables `action_type_input`/`action_type_output` created,
`workflow_enabled` present, `input_schema` gone) before proceeding. Report the `MultipleBagFetchException`
note above if it arises.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/io/apitomy/axiom/core/entities/ActionTypeField.java \
        core/src/main/java/io/apitomy/axiom/core/entities/ActionTypeEntity.java \
        app/src/main/resources/db/migration/V55__add_action_type_io_and_workflow_enabled.sql
git commit -m "feat: persist action type inputs/outputs and workflowEnabled"
```

---

## Task 3: Design-time validation (ActionTypeValidator)

**Files:**
- Modify: `core/src/main/java/io/apitomy/axiom/core/services/ActionTypeValidator.java`
- Test: `core/src/test/java/io/apitomy/axiom/core/services/ActionTypeValidatorTest.java`

**Interfaces:**
- Consumes: generated `NewActionType.getInputs()/getOutputs()` returning `List<ActionTypeField>` beans,
  each with `getName()`, `getType()` (enum with `.value()`), `getRequired()`.
- Produces: `ActionTypeValidator.validate(...)` now emits ERROR messages for invalid input/output field
  declarations (fields `inputs[i].name`, `outputs[i].name`).

- [ ] **Step 1: Write failing tests**

Add to `ActionTypeValidatorTest.java` (mirror the existing test style — build a `NewActionType`, call
`ActionTypeValidator.validate(def)`, assert on `result.errors()`):

```java
@Test
void rejectsDuplicateInputNames() {
    NewActionType def = agentDef("dup-inputs");
    def.setInputs(List.of(
        field("repo", ActionTypeField.Type.STRING),
        field("repo", ActionTypeField.Type.NUMBER)));
    ActionTypeValidator.ValidationResult result = ActionTypeValidator.validate(def);
    assertTrue(result.errors().stream()
        .anyMatch(e -> e.field().startsWith("inputs[") && e.message().contains("duplicate")));
}

@Test
void rejectsInvalidInputIdentifier() {
    NewActionType def = agentDef("bad-input-name");
    def.setInputs(List.of(field("has space", ActionTypeField.Type.STRING)));
    ActionTypeValidator.ValidationResult result = ActionTypeValidator.validate(def);
    assertTrue(result.errors().stream().anyMatch(e -> e.field().startsWith("inputs[")));
}

@Test
void rejectsBlankOutputName() {
    NewActionType def = agentDef("blank-output");
    def.setOutputs(List.of(field("", ActionTypeField.Type.STRING)));
    ActionTypeValidator.ValidationResult result = ActionTypeValidator.validate(def);
    assertTrue(result.errors().stream().anyMatch(e -> e.field().startsWith("outputs[")));
}

@Test
void acceptsValidInputsAndOutputs() {
    NewActionType def = agentDef("good-io");
    def.setInputs(List.of(field("repository", ActionTypeField.Type.STRING)));
    def.setOutputs(List.of(field("prNumber", ActionTypeField.Type.NUMBER)));
    ActionTypeValidator.ValidationResult result = ActionTypeValidator.validate(def);
    assertFalse(result.errors().stream()
        .anyMatch(e -> e.field().startsWith("inputs[") || e.field().startsWith("outputs[")));
}
```

Add small helpers in the test class if not already present:

```java
private static ActionTypeField field(String name, ActionTypeField.Type type) {
    ActionTypeField f = new ActionTypeField();
    f.setName(name);
    f.setType(type);
    return f;
}
// agentDef(name): a minimal valid agent-mode NewActionType (name, executionMode=AGENT,
// promptTemplate="do {{managerInput}}") — reuse the existing test's builder if one exists.
```

(Use the generated `io.apitomy.axiom.api.beans.ActionTypeField` bean, not the entity embeddable.)

- [ ] **Step 2: Run tests to verify they fail** — hand to the user; expected: the four new tests FAIL
  (no field validation yet), existing tests still pass.

- [ ] **Step 3: Implement the validation**

In `ActionTypeValidator.java`, add a field-name pattern constant near the others:

```java
    private static final Pattern VALID_FIELD_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
```

Add two calls in `validate(...)` after `validateEnvironment(...)`:

```java
        validateFields(def.getInputs(), "inputs", messages);
        validateFields(def.getOutputs(), "outputs", messages);
```

Add the method (uses the generated `ActionTypeField` bean):

```java
    private static void validateFields(java.util.List<io.apitomy.axiom.api.beans.ActionTypeField> fields,
                                       String prefix, List<ValidationMessage> messages) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            io.apitomy.axiom.api.beans.ActionTypeField f = fields.get(i);
            String field = prefix + "[" + i + "]";
            String name = f.getName();
            if (name == null || name.isBlank()) {
                messages.add(error(field, "Field name is required."));
                continue;
            }
            if (!VALID_FIELD_NAME_PATTERN.matcher(name).matches()) {
                messages.add(error(field,
                        "Field name '" + name + "' is not a valid identifier. "
                                + "Use letters, digits, and underscores; must not start with a digit."));
            }
            if (!seen.add(name)) {
                messages.add(error(field, "Field name '" + name + "' is a duplicate within " + prefix + "."));
            }
            if (f.getType() == null) {
                messages.add(error(field, "Field type is required."));
            }
        }
    }
```

(The generated enum guarantees `type` is one of the four values when non-null, so no separate value check is
needed.)

- [ ] **Step 4: Run tests to verify they pass** — hand to the user; expected: all `ActionTypeValidatorTest`
  tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/io/apitomy/axiom/core/services/ActionTypeValidator.java \
        core/src/test/java/io/apitomy/axiom/core/services/ActionTypeValidatorTest.java
git commit -m "feat: validate action type input/output field declarations"
```

---

## Task 4: REST mapping and palette filter

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/ActionResourceImpl.java`

**Interfaces:**
- Consumes: generated beans from Task 1; entity fields from Task 2.
- Produces: `applyFields` persists `inputs`/`outputs`/`workflowEnabled`; `toBean` returns them;
  `listActionTypes` accepts a `filterWorkflowEnabled` query param.

- [ ] **Step 1: Add bean↔entity field mappers**

Add two private helpers to `ActionResourceImpl` (converting between the generated bean `ActionTypeField` and
the entity embeddable `io.apitomy.axiom.core.entities.ActionTypeField`):

```java
    private static List<io.apitomy.axiom.core.entities.ActionTypeField> toEntityFields(
            List<io.apitomy.axiom.api.beans.ActionTypeField> beans) {
        List<io.apitomy.axiom.core.entities.ActionTypeField> out = new java.util.ArrayList<>();
        if (beans != null) {
            for (io.apitomy.axiom.api.beans.ActionTypeField b : beans) {
                out.add(new io.apitomy.axiom.core.entities.ActionTypeField(
                        b.getName(),
                        b.getType() != null ? b.getType().value() : null,
                        b.getRequired() != null ? b.getRequired() : false,
                        b.getDescription()));
            }
        }
        return out;
    }

    private static List<io.apitomy.axiom.api.beans.ActionTypeField> toBeanFields(
            List<io.apitomy.axiom.core.entities.ActionTypeField> entities) {
        List<io.apitomy.axiom.api.beans.ActionTypeField> out = new java.util.ArrayList<>();
        if (entities != null) {
            for (io.apitomy.axiom.core.entities.ActionTypeField e : entities) {
                io.apitomy.axiom.api.beans.ActionTypeField b = new io.apitomy.axiom.api.beans.ActionTypeField();
                b.setName(e.name);
                b.setType(e.type != null
                        ? io.apitomy.axiom.api.beans.ActionTypeField.Type.fromValue(e.type) : null);
                b.setRequired(e.required);
                b.setDescription(e.description);
                out.add(b);
            }
        }
        return out;
    }
```

- [ ] **Step 2: Update `applyFields`**

Remove the line `entity.inputSchema = data.getInputSchema();` (line 206). Add:

```java
        entity.workflowEnabled = data.getWorkflowEnabled() != null ? data.getWorkflowEnabled() : false;
        entity.inputs.clear();
        entity.inputs.addAll(toEntityFields(data.getInputs()));
        entity.outputs.clear();
        entity.outputs.addAll(toEntityFields(data.getOutputs()));
```

- [ ] **Step 3: Update `toBean`**

Remove `actionType.setInputSchema(entity.inputSchema);` (line 240). Add:

```java
        actionType.setWorkflowEnabled(entity.workflowEnabled);
        actionType.setInputs(toBeanFields(entity.inputs));
        actionType.setOutputs(toBeanFields(entity.outputs));
```

- [ ] **Step 4: Add the `workflowEnabled` filter to `listActionTypes`**

The generated `ActionResource` interface signature changes when a query param is added to the OpenAPI
`listActionTypes` operation. Add the param in OpenAPI first (apicurio tools): a boolean query parameter
`filterWorkflowEnabled` on `GET /action-types`. Then implement in `ActionResourceImpl.listActionTypes` by
adding the new parameter to the method signature and this clause alongside the existing filters:

```java
        if (filterWorkflowEnabled != null && filterWorkflowEnabled) {
            hql.append(" and workflowEnabled = true");
        }
```

> If the user prefers to avoid another generated-signature change, an acceptable alternative is client-side
> filtering in Task 9 and skipping this step. Default to the server-side filter; note the alternative in the
> handoff.

- [ ] **Step 5: Hand off — user compiles & smoke-tests**

Ask the user to build `app` and exercise create/get/update of an action type with inputs/outputs and
`workflowEnabled` (e.g. via the REST API or UI once Task 10 lands), and to confirm `GET
/action-types?filterWorkflowEnabled=true` returns only enabled types. No auto-run.

- [ ] **Step 6: Commit**

```bash
git add common/api/src/main/resources/openapi.json \
        app/src/main/java/io/apitomy/axiom/app/rest/ActionResourceImpl.java
git commit -m "feat: map action type inputs/outputs/workflowEnabled and add palette filter"
```

---

## Task 5: Import/export

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/ImportExportService.java`

**Interfaces:**
- Consumes: entity fields from Task 2.
- Produces: import/export round-trips `inputs`, `outputs`, `workflowEnabled`; no longer reads/writes
  `inputSchema`.

- [ ] **Step 1: Add a field-collection JSON helper**

Add a private helper to read a JSON array of field objects into entity embeddables:

```java
    private List<io.apitomy.axiom.core.entities.ActionTypeField> importFields(JsonNode item, String key) {
        List<io.apitomy.axiom.core.entities.ActionTypeField> out = new java.util.ArrayList<>();
        JsonNode arr = item.path(key);
        if (arr.isArray()) {
            for (JsonNode f : arr) {
                out.add(new io.apitomy.axiom.core.entities.ActionTypeField(
                        f.path("name").asText(null),
                        f.path("type").asText("string"),
                        f.path("required").asBoolean(false),
                        f.path("description").asText(null)));
            }
        }
        return out;
    }
```

- [ ] **Step 2: Update the fresh import (`importActionTypes`, ~line 317-348)**

Replace `entity.inputSchema = jsonOrNull(item, "inputSchema");` (line 328) with:

```java
            entity.workflowEnabled = item.path("workflowEnabled").asBoolean(false);
            entity.inputs.addAll(importFields(item, "inputs"));
            entity.outputs.addAll(importFields(item, "outputs"));
```

- [ ] **Step 3: Update the upsert import (~line 455-484)**

Replace `entity.inputSchema = jsonOrNull(item, "inputSchema");` (line 468) with the same three lines, but
clear first (this path reuses existing entities):

```java
            entity.workflowEnabled = item.path("workflowEnabled").asBoolean(false);
            entity.inputs.clear();
            entity.inputs.addAll(importFields(item, "inputs"));
            entity.outputs.clear();
            entity.outputs.addAll(importFields(item, "outputs"));
```

- [ ] **Step 4: Update export (`serializeActionType`, ~line 670-693)**

Replace `putIfNotNull(n, "inputSchema", e.inputSchema);` (line 678) with:

```java
        n.put("workflowEnabled", e.workflowEnabled);
        if (e.inputs != null && !e.inputs.isEmpty()) {
            var inputsArr = n.putArray("inputs");
            e.inputs.forEach(f -> serializeField(inputsArr.addObject(), f));
        }
        if (e.outputs != null && !e.outputs.isEmpty()) {
            var outputsArr = n.putArray("outputs");
            e.outputs.forEach(f -> serializeField(outputsArr.addObject(), f));
        }
```

Add the field serializer helper:

```java
    private void serializeField(ObjectNode n, io.apitomy.axiom.core.entities.ActionTypeField f) {
        n.put("name", f.name);
        n.put("type", f.type);
        n.put("required", f.required);
        putIfNotNull(n, "description", f.description);
    }
```

- [ ] **Step 5: Hand off — user verifies round-trip**

Ask the user to export then re-import a project fixture containing an action type with inputs/outputs and
confirm the fields survive. No auto-run.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/ImportExportService.java
git commit -m "feat: round-trip action type inputs/outputs/workflowEnabled in import/export"
```

---

## Task 6: Seed data

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/SeedDataInitializer.java`

**Interfaces:**
- Consumes: entity fields from Task 2.
- Produces: seeded action types carry a `workflowEnabled` value.

- [ ] **Step 1: Thread `workflowEnabled` through the seed helper**

`seedActionType` (lines 363-384) has two overloads. Add a `boolean workflowEnabled` parameter to the full
overload and set `entity.workflowEnabled = workflowEnabled;` before `entity.persist();`. Update the shorter
overload to pass a default (`false`) and update all existing call sites accordingly. For any seed action
type that is a natural workflow building block (e.g. script-mode or clearly composable actions), pass
`true`.

```java
    private void seedActionType(String name, String description, String executionMode,
                                boolean userTriggerable, boolean emitsEvent, String allowedTools,
                                String promptTemplate, String scriptTemplate, boolean workflowEnabled) {
        ActionTypeEntity entity = new ActionTypeEntity();
        entity.name = name;
        entity.description = description;
        entity.executionMode = executionMode;
        entity.userTriggerable = userTriggerable;
        entity.managerTriggerable = true;
        entity.emitsEvent = emitsEvent;
        entity.allowedTools = allowedTools;
        entity.promptTemplate = promptTemplate;
        entity.scriptTemplate = scriptTemplate;
        entity.workflowEnabled = workflowEnabled;
        entity.persist();
    }
```

Keep backward-compatible overloads by delegating with `workflowEnabled = false`.

- [ ] **Step 2: Hand off — user runs a clean seed**

Ask the user to start with a fresh DB and confirm seeding succeeds and chosen seeds show `workflowEnabled =
true`. No auto-run.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/SeedDataInitializer.java
git commit -m "feat: set workflowEnabled on seeded action types"
```

---

## Task 7: Runtime I/O validator (shared)

**Files:**
- Create: `core/src/main/java/io/apitomy/axiom/core/services/ActionTypeIoValidator.java`
- Test: `core/src/test/java/io/apitomy/axiom/core/services/ActionTypeIoValidatorTest.java`

**Interfaces:**
- Consumes: entity embeddable `io.apitomy.axiom.core.entities.ActionTypeField`.
- Produces: `ActionTypeIoValidator.validate(List<ActionTypeField> declared, Map<String,Object> values)`
  returning `List<String>` of error messages (empty = valid). Required declared fields must be present and
  non-null; present values are best-effort type-checked; extra keys are allowed (lenient).

- [ ] **Step 1: Write failing tests**

```java
package io.apitomy.axiom.core.services;

import io.apitomy.axiom.core.entities.ActionTypeField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ActionTypeIoValidatorTest {

    @Test
    void missingRequiredFieldReportsError() {
        List<ActionTypeField> declared = List.of(new ActionTypeField("repo", "string", true, null));
        List<String> errors = ActionTypeIoValidator.validate(declared, Map.of());
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).contains("repo"));
    }

    @Test
    void wrongTypeReportsError() {
        List<ActionTypeField> declared = List.of(new ActionTypeField("count", "number", true, null));
        List<String> errors = ActionTypeIoValidator.validate(declared, Map.of("count", "not-a-number"));
        assertFalse(errors.isEmpty());
    }

    @Test
    void numericAcceptedForNumberType() {
        List<ActionTypeField> declared = List.of(new ActionTypeField("count", "number", true, null));
        assertEquals(List.of(), ActionTypeIoValidator.validate(declared, Map.of("count", 42)));
    }

    @Test
    void optionalMissingFieldIsOk() {
        List<ActionTypeField> declared = List.of(new ActionTypeField("note", "string", false, null));
        assertEquals(List.of(), ActionTypeIoValidator.validate(declared, Map.of()));
    }

    @Test
    void extraKeysAreAllowed() {
        List<ActionTypeField> declared = List.of(new ActionTypeField("a", "string", true, null));
        assertEquals(List.of(), ActionTypeIoValidator.validate(declared, Map.of("a", "x", "b", "y")));
    }

    @Test
    void nullOrEmptyDeclaredIsAlwaysValid() {
        assertEquals(List.of(), ActionTypeIoValidator.validate(null, Map.of("x", 1)));
        assertEquals(List.of(), ActionTypeIoValidator.validate(List.of(), Map.of("x", 1)));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail** — hand to user; expected: FAIL (class does not exist).

- [ ] **Step 3: Implement the validator**

```java
package io.apitomy.axiom.core.services;

import io.apitomy.axiom.core.entities.ActionTypeField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates a runtime value map against a list of declared action-type fields.
 *
 * <p>Used at execution time to enforce an action type's declared inputs (before the
 * action runs) and outputs (after it completes). Required fields must be present and
 * non-null; present values are best-effort type-checked; extra keys are permitted.</p>
 */
public final class ActionTypeIoValidator {

    private ActionTypeIoValidator() {
    }

    /**
     * Validates the given values against the declared fields.
     *
     * @param declared the declared field contract (null or empty means "no contract" → always valid)
     * @param values   the runtime values keyed by field name (null treated as empty)
     * @return a list of human-readable error messages; empty when valid
     */
    public static List<String> validate(List<ActionTypeField> declared, Map<String, Object> values) {
        List<String> errors = new ArrayList<>();
        if (declared == null || declared.isEmpty()) {
            return errors;
        }
        Map<String, Object> safeValues = values != null ? values : Map.of();
        for (ActionTypeField field : declared) {
            boolean present = safeValues.containsKey(field.name) && safeValues.get(field.name) != null;
            if (!present) {
                if (field.required) {
                    errors.add("Missing required field '" + field.name + "'.");
                }
                continue;
            }
            Object value = safeValues.get(field.name);
            if (!matchesType(field.type, value)) {
                errors.add("Field '" + field.name + "' expected type " + field.type
                        + " but got " + value.getClass().getSimpleName() + ".");
            }
        }
        return errors;
    }

    private static boolean matchesType(String type, Object value) {
        if (type == null) {
            return true;
        }
        return switch (type) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map;
            default -> true;
        };
    }
}
```

- [ ] **Step 4: Run tests to verify they pass** — hand to user; expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/io/apitomy/axiom/core/services/ActionTypeIoValidator.java \
        core/src/test/java/io/apitomy/axiom/core/services/ActionTypeIoValidatorTest.java
git commit -m "feat: shared runtime validator for action type input/output contracts"
```

---

## Task 8: Execution — input binding, input validation, output-contract auto-append

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/TaskExecutionService.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/ScriptExecutionService.java`

**Interfaces:**
- Consumes: `ActionTypeEntity.inputs/outputs`, `ActionTypeIoValidator.validate(...)`, `TaskEntity.input`
  (JSON of resolved inputs when `workflowRunId != null`), `TaskEntity.workflowRunId`.
- Produces: resolved templates contain `{{inputs.NAME}}` substitutions; agent prompts for workflow tasks
  with declared outputs get an appended JSON output-contract section; a workflow task whose resolved inputs
  fail validation is failed before running.

**Gating rule (both services):** the new input parsing/validation applies only when `task.workflowRunId !=
null` (inputs arrive as a JSON object from the engine) **and** the action type declares inputs. Non-workflow
tasks keep passing `task.input` as free text through `{{managerInput}}`.

- [ ] **Step 1: Add a shared helper to parse resolved inputs**

In `TaskExecutionService`, add:

```java
    /**
     * Parses a workflow task's resolved inputs (a JSON object) into a map.
     * Returns an empty map for non-workflow tasks or unparseable input.
     */
    private Map<String, Object> parseResolvedInputs(TaskEntity task) {
        if (task.workflowRunId == null || task.input == null || task.input.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(task.input, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
```

Ensure `com.fasterxml.jackson.core.type.TypeReference` is imported.

- [ ] **Step 2: Validate resolved inputs (fail fast) in `executeTask`**

Near the top of `executeTask` (after loading `actionTypeEntity` at line 112), add:

```java
        if (actionTypeEntity != null && task.workflowRunId != null
                && actionTypeEntity.inputs != null && !actionTypeEntity.inputs.isEmpty()) {
            List<String> inputErrors = ActionTypeIoValidator.validate(
                    actionTypeEntity.inputs, parseResolvedInputs(task));
            if (!inputErrors.isEmpty()) {
                failTask(task.id, "Input validation failed: " + String.join("; ", inputErrors));
                return;
            }
        }
```

This runs before the script branch and before leasing an agent, so it covers both modes. Add
`import io.apitomy.axiom.core.services.ActionTypeIoValidator;`.

- [ ] **Step 3: Bind `{{inputs.NAME}}` and append the output contract in `resolvePromptTemplate`**

Change `resolvePromptTemplate` to accept the resolved-inputs map and the entity's declared outputs. Replace
the current body (lines 269-284) with:

```java
    private String resolvePromptTemplate(ActionTypeEntity actionType, TaskEntity task,
                                          ProjectEntity project, Path workspace) {
        if (actionType == null || actionType.promptTemplate == null
                || actionType.promptTemplate.isBlank()) {
            return null;
        }

        String resolved = actionType.promptTemplate;
        resolved = resolved.replace("{{managerInput}}", task.input != null ? task.input : "");
        resolved = resolved.replace("{{actionType}}", task.actionType != null ? task.actionType : "");
        resolved = resolved.replace("{{ref}}", project.ref != null ? project.ref : "");
        resolved = resolved.replace("{{repository}}", project.repository != null ? project.repository : "");
        resolved = resolved.replace("{{projectName}}", project.name != null ? project.name : "");
        resolved = resolved.replace("{{workDir}}", workspace != null ? workspace.toAbsolutePath().toString() : "");

        // Bind named workflow inputs as {{inputs.NAME}}.
        Map<String, Object> resolvedInputs = parseResolvedInputs(task);
        for (Map.Entry<String, Object> e : resolvedInputs.entrySet()) {
            resolved = resolved.replace("{{inputs." + e.getKey() + "}}",
                    e.getValue() != null ? e.getValue().toString() : "");
        }

        // Auto-append the output contract for workflow tasks that declare outputs.
        if (task.workflowRunId != null && actionType.outputs != null && !actionType.outputs.isEmpty()) {
            resolved = resolved + "\n\n" + buildOutputContract(actionType.outputs);
        }
        return resolved;
    }

    /**
     * Builds the instruction block appended to an agent prompt telling it to emit a
     * JSON object keyed by the declared output names as its final result.
     */
    private String buildOutputContract(List<io.apitomy.axiom.core.entities.ActionTypeField> outputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Required output\n");
        sb.append("When finished, output ONLY a single JSON object (no prose, no code fences) ");
        sb.append("with exactly these keys:\n");
        for (io.apitomy.axiom.core.entities.ActionTypeField f : outputs) {
            sb.append("- \"").append(f.name).append("\" (").append(f.type).append(")");
            if (f.required) {
                sb.append(" [required]");
            }
            if (f.description != null && !f.description.isBlank()) {
                sb.append(" — ").append(f.description);
            }
            sb.append("\n");
        }
        return sb.toString();
    }
```

(No signature change is needed — the method already receives `task` and `actionType`.)

- [ ] **Step 4: Bind `{{inputs.NAME}}` in the script template**

In `ScriptExecutionService`, locate the template resolution method (contains the `{{projectId}}` etc.
replacements around lines 198-210). It needs access to the `TaskEntity` (it already resolves `task.*`). After
the existing replacements, add binding of named inputs. First add a parse helper mirroring Task 8 Step 1
(inject `ObjectMapper` if not already present in this service — check the constructor/fields; Quarkus
provides it via `@Inject`), then:

```java
        // Bind named workflow inputs as {{inputs.NAME}} (workflow tasks only).
        if (task.workflowRunId != null && task.input != null && !task.input.isBlank()) {
            try {
                Map<String, Object> inputs = objectMapper.readValue(
                        task.input, new TypeReference<Map<String, Object>>() {});
                for (Map.Entry<String, Object> e : inputs.entrySet()) {
                    resolved = resolved.replace("{{inputs." + e.getKey() + "}}",
                            e.getValue() != null ? e.getValue().toString() : "");
                }
            } catch (Exception ignored) {
                // Non-object input — leave {{inputs.*}} placeholders untouched.
            }
        }
```

Add imports for `com.fasterxml.jackson.core.type.TypeReference` and `java.util.Map` if missing.

- [ ] **Step 5: Update the design-time placeholder allow-lists (so authoring validation accepts the new
  placeholders)**

`ActionTypeValidator` rejects unrecognized `{{...}}` placeholders. `{{inputs.foo}}` contains a `.` which the
`PLACEHOLDER_PATTERN` (`[a-zA-Z_][a-zA-Z0-9_]*`) does not match, so it is currently ignored (not flagged) —
confirm by inspection that a dotted placeholder is simply not captured by the pattern. If it IS captured/
flagged in practice, widen the pattern to allow an optional `.suffix` and add `inputs` handling. Add a brief
unit test to `ActionTypeValidatorTest` asserting a prompt containing `{{inputs.repository}}` produces no
`promptTemplate` error.

- [ ] **Step 6: Hand off — user compiles & runs a workflow**

Ask the user to build `app`, define a workflowEnabled action type with an input `repository` and an output
`prNumber`, wire it into a workflow, trigger a run, and confirm: the prompt shows the bound input and the
appended output contract; a missing required input fails the task fast. No auto-run.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/TaskExecutionService.java \
        app/src/main/java/io/apitomy/axiom/app/ScriptExecutionService.java \
        core/src/test/java/io/apitomy/axiom/core/services/ActionTypeValidatorTest.java
git commit -m "feat: bind named inputs, validate inputs, and append output contract at execution"
```

---

## Task 9: Execution — output validation on completion

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java`

**Interfaces:**
- Consumes: `parseOutputMap` (existing), `ActionTypeEntity.outputs`, `ActionTypeIoValidator`.
- Produces: on workflow task completion, `task.output` is validated against the action type's declared
  outputs; validation failure makes the node fail (fail fast) instead of advancing with a bad contract.

- [ ] **Step 1: Locate the completion path**

In `onTaskCompleted(long taskId)` (around lines 176-226), the success branch builds
`NodeResult(COMPLETED, parseOutputMap(task.output))`. The task's action type is `task.actionType`.

- [ ] **Step 2: Validate outputs before building a COMPLETED result**

In the success branch, before constructing the COMPLETED `NodeResult`, add:

```java
        Map<String, Object> outputMap = parseOutputMap(task.output);
        ActionTypeEntity at = ActionTypeEntity.find("name", task.actionType).firstResult();
        if (at != null && at.outputs != null && !at.outputs.isEmpty()) {
            List<String> outputErrors = ActionTypeIoValidator.validate(at.outputs, outputMap);
            if (!outputErrors.isEmpty()) {
                NodeResult failed = new NodeResult(NodeResultStatus.FAILED, Map.of());
                // Advance the engine with a FAILED result and record the reason.
                LOG.warnf("Workflow task %d output validation failed: %s",
                        task.id, String.join("; ", outputErrors));
                workflowEngine.completeCurrentNode(workflow, instance, failed);
                // fall through to the existing FAILED persistence/finalization path
                ... (mirror the existing FAILED handling: persist instance state, finalize run as failed,
                     complete trace, fire SSE) ...
                return;
            }
        }
        NodeResult result = new NodeResult(NodeResultStatus.COMPLETED, outputMap);
```

Replace the existing `parseOutputMap(task.output)` call in the COMPLETED construction with the pre-computed
`outputMap`. Reuse the exact FAILED-branch finalization already present in this method rather than
duplicating logic — extract it into a private helper if that reads cleaner (e.g.
`finalizeFailedRun(entity, workflow, instance, reason)`), and call it from both the existing failure branch
and this new one. Add `import io.apitomy.axiom.core.services.ActionTypeIoValidator;` and
`import io.apitomy.axiom.core.entities.ActionTypeEntity;` if not already imported.

- [ ] **Step 3: Hand off — user verifies**

Ask the user to run a workflow whose action emits valid output (run advances) and one whose action omits a
required output or emits non-JSON (run fails with the validation reason). No auto-run.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java
git commit -m "feat: validate workflow action outputs against declared contract"
```

---

## Task 10: Frontend — API types and Flow SPI wiring

**Files:**
- Modify: `ui/src/config/api.ts`
- Modify: `ui/src/pages/WorkflowDefinitionDetailPage.tsx`

**Interfaces:**
- Consumes: REST fields from Tasks 1/4.
- Produces: `ActionType`/`NewActionType` TS types carry `inputs`, `outputs`, `workflowEnabled` (no
  `inputSchema`); `fetchActionTypes` supports a `filterWorkflowEnabled` argument; the Flow editor SPI
  provider returns descriptors populated with `inputs`/`outputs`, filtered to workflow-enabled types.

- [ ] **Step 1: Update the `ActionType` interface and add a field type**

In `ui/src/config/api.ts`, add near the Action Types section:

```ts
export interface ActionTypeField {
    name: string;
    type: "string" | "number" | "boolean" | "object";
    required?: boolean;
    description?: string;
}
```

In `interface ActionType` (lines 376-395): remove `inputSchema?: string;` and add:

```ts
    workflowEnabled?: boolean;
    inputs?: ActionTypeField[];
    outputs?: ActionTypeField[];
```

- [ ] **Step 2: Add the `filterWorkflowEnabled` argument to `fetchActionTypes`**

Update the signature and query building (lines 399-411):

```ts
export async function fetchActionTypes(
    page = 1, limit = 20, filterName?: string, filterMode?: string,
    filterLabels?: string, filterWorkflowEnabled?: boolean
): Promise<SearchResults<ActionType>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterName) params.set("filterName", filterName);
    if (filterMode) params.set("filterMode", filterMode);
    if (filterLabels) params.set("filterLabels", filterLabels);
    if (filterWorkflowEnabled) params.set("filterWorkflowEnabled", "true");
    const response = await fetch(`${API}/action-types?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch action types: ${response.status}`);
    return response.json();
}
```

- [ ] **Step 3: Populate the descriptor and filter the palette**

In `WorkflowDefinitionDetailPage.tsx`, replace the SPI provider (lines 70-79):

```ts
    const spi: EditorSpi = useMemo(() => ({
        actionTypes: async () => {
            const results = await fetchActionTypes(1, 1000, undefined, undefined, undefined, true);
            return results.items.map((at): ActionTypeDescriptor => ({
                value: at.name,
                label: at.name,
                description: at.description,
                inputs: at.inputs,
                outputs: at.outputs,
            }));
        },
    }), []);
```

(The Axiom `ActionTypeField` shape matches Flow's `ActionTypeField`, so `at.inputs`/`at.outputs` pass
through directly. If the TS compiler flags a nominal-type mismatch between the two `ActionTypeField`
declarations, map explicitly: `at.inputs?.map(f => ({ name: f.name, type: f.type, required: f.required,
description: f.description }))`.)

- [ ] **Step 4: Hand off — user builds the UI & checks the editor**

Ask the user to run the Vite build / dev server and confirm: the workflow editor palette lists only
workflow-enabled action types, and selecting one auto-generates its input binding fields and read-only
outputs. (No frontend test harness exists in this repo, so verification is build + manual.) No auto-run.

- [ ] **Step 5: Commit**

```bash
git add ui/src/config/api.ts ui/src/pages/WorkflowDefinitionDetailPage.tsx
git commit -m "feat(ui): feed action type inputs/outputs into the Flow editor and curate the palette"
```

---

## Task 11: Frontend — Action Type authoring UI

**Files:**
- Create: `ui/src/components/ActionTypeFieldsTab.tsx`
- Modify: `ui/src/pages/ActionTypeDetailPage.tsx`

**Interfaces:**
- Consumes: `ActionTypeField` type and `NewActionType` from `ui/src/config/api.ts`.
- Produces: an `ActionTypeFieldsTab` reused for both Inputs and Outputs; a `workflowEnabled` checkbox on the
  Info tab; conditional Inputs/Outputs tabs; `inputSchema` removed from the form.

- [ ] **Step 1: Create the repeatable field editor**

Create `ui/src/components/ActionTypeFieldsTab.tsx` (modeled on `EnvironmentTab.tsx`):

```tsx
import { useState } from "react";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Checkbox,
    Flex,
    FlexItem,
    FormGroup,
    FormSelect,
    FormSelectOption,
    TextInput,
} from "@patternfly/react-core";
import PlusCircleIcon from "@patternfly/react-icons/dist/esm/icons/plus-circle-icon";
import TimesIcon from "@patternfly/react-icons/dist/esm/icons/times-icon";
import type { ActionTypeField } from "../config/api";

const FIELD_TYPES: ActionTypeField["type"][] = ["string", "number", "boolean", "object"];

/**
 * Editable list of typed fields (name/type/required/description). Reused by the
 * Action Type Inputs and Outputs tabs. Preserves order.
 */
export function ActionTypeFieldsTab({ kind, fields, onChange }: {
    kind: "input" | "output";
    fields: ActionTypeField[];
    onChange: (updated: ActionTypeField[]) => void;
}) {
    const [newName, setNewName] = useState("");

    const handleAdd = () => {
        const trimmed = newName.trim();
        if (!trimmed || fields.some((f) => f.name === trimmed)) return;
        onChange([...fields, { name: trimmed, type: "string", required: false }]);
        setNewName("");
    };

    const handleRemove = (index: number) => {
        onChange(fields.filter((_, i) => i !== index));
    };

    const handleUpdate = (index: number, patch: Partial<ActionTypeField>) => {
        onChange(fields.map((f, i) => (i === index ? { ...f, ...patch } : f)));
    };

    return (
        <div style={{ maxWidth: "800px" }}>
            <p className="axiom-text-subtle" style={{ marginBottom: "16px" }}>
                {kind === "input"
                    ? "Typed inputs this action expects when used as a workflow action node."
                    : "Typed outputs this action produces when used as a workflow action node."}
            </p>

            <Flex alignItems={{ default: "alignItemsFlexEnd" }} style={{ marginBottom: "16px", gap: "8px" }}>
                <FlexItem style={{ flex: 1 }}>
                    <FormGroup label="Name" fieldId={`${kind}-new-name`}>
                        <TextInput id={`${kind}-new-name`} value={newName}
                            onChange={(_e, v) => setNewName(v)}
                            placeholder="e.g. repository"
                            onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); handleAdd(); } }}
                        />
                    </FormGroup>
                </FlexItem>
                <FlexItem>
                    <Button variant="secondary" icon={<PlusCircleIcon />} onClick={handleAdd}
                        isDisabled={!newName.trim() || fields.some((f) => f.name === newName.trim())}
                        style={{ marginBottom: "1px" }}>
                        Add
                    </Button>
                </FlexItem>
            </Flex>

            {fields.length === 0 ? (
                <EmptyState>
                    <EmptyStateBody>No {kind}s declared.</EmptyStateBody>
                </EmptyState>
            ) : (
                <div style={{ display: "flex", flexDirection: "column", gap: "4px" }}>
                    {fields.map((f, i) => (
                        <Flex key={i} alignItems={{ default: "alignItemsCenter" }}
                            style={{
                                padding: "8px 12px",
                                backgroundColor: "var(--pf-t--global--background--color--secondary--default)",
                                borderRadius: "4px",
                                gap: "8px",
                            }}>
                            <FlexItem style={{ minWidth: "160px" }}>
                                <code style={{ fontSize: "13px", fontWeight: 600 }}>{f.name}</code>
                            </FlexItem>
                            <FlexItem style={{ width: "130px" }}>
                                <FormSelect value={f.type} aria-label={`Type for ${f.name}`}
                                    onChange={(_e, v) => handleUpdate(i, { type: v as ActionTypeField["type"] })}>
                                    {FIELD_TYPES.map((t) => (
                                        <FormSelectOption key={t} value={t} label={t} />
                                    ))}
                                </FormSelect>
                            </FlexItem>
                            <FlexItem>
                                <Checkbox id={`${kind}-required-${i}`} label="Required"
                                    isChecked={!!f.required}
                                    onChange={(_e, v) => handleUpdate(i, { required: v })} />
                            </FlexItem>
                            <FlexItem grow={{ default: "grow" }}>
                                <TextInput value={f.description || ""}
                                    onChange={(_e, v) => handleUpdate(i, { description: v })}
                                    placeholder="Description (optional)"
                                    aria-label={`Description for ${f.name}`}
                                    style={{ fontSize: "13px" }} />
                            </FlexItem>
                            <FlexItem>
                                <Button variant="plain" size="sm" onClick={() => handleRemove(i)}
                                    aria-label={`Remove ${f.name}`}>
                                    <TimesIcon />
                                </Button>
                            </FlexItem>
                        </Flex>
                    ))}
                </div>
            )}
        </div>
    );
}
```

- [ ] **Step 2: Add the `workflowEnabled` checkbox to the Info tab**

In `ActionTypeDetailPage.tsx` `InfoTab` (the `<FormGroup fieldId="flags">` block, lines 432-451), add a
checkbox:

```tsx
                <Checkbox
                    id="workflowEnabled"
                    label="Workflow enabled — offered as a building block in the Flow workflow editor"
                    isChecked={form.workflowEnabled}
                    onChange={(_e, v) => updateForm({ workflowEnabled: v })}
                />
```

- [ ] **Step 3: Add the Inputs and Outputs tabs (conditional on `workflowEnabled`)**

Import the component at the top of `ActionTypeDetailPage.tsx`:

```tsx
import { ActionTypeFieldsTab } from "../components/ActionTypeFieldsTab";
```

Insert two tabs in the `<Tabs>` block *after* the Environment tab (which ends at line 302) and *before* the
Prompt Template tab (line 303). Use new event keys `6` and `7`:

```tsx
                {form.workflowEnabled && (
                    <Tab eventKey={6} title={<TabTitleText>Inputs ({(form.inputs || []).length})</TabTitleText>}>
                        <TabContent id="inputs-tab" eventKey={6} activeKey={activeTab} style={{ marginTop: "24px" }}>
                            <ActionTypeFieldsTab
                                kind="input"
                                fields={form.inputs || []}
                                onChange={(updated) => updateForm({ inputs: updated })}
                            />
                        </TabContent>
                    </Tab>
                )}
                {form.workflowEnabled && (
                    <Tab eventKey={7} title={<TabTitleText>Outputs ({(form.outputs || []).length})</TabTitleText>}>
                        <TabContent id="outputs-tab" eventKey={7} activeKey={activeTab} style={{ marginTop: "24px" }}>
                            <ActionTypeFieldsTab
                                kind="output"
                                fields={form.outputs || []}
                                onChange={(updated) => updateForm({ outputs: updated })}
                            />
                        </TabContent>
                    </Tab>
                )}
```

- [ ] **Step 4: Remove any `inputSchema` usage from the form**

Search `ActionTypeDetailPage.tsx` (and the initial form state / `buildValidationData` if referenced) for
`inputSchema` and remove it. The `NewActionType` type no longer has the field (Task 10), so the compiler will
flag any leftover reference — resolve all of them.

- [ ] **Step 5: Hand off — user builds the UI & checks authoring**

Ask the user to run the Vite build / dev server and confirm: checking "Workflow enabled" reveals the Inputs
and Outputs tabs (before Prompt Template/Script); unchecking hides them; adding/removing/editing rows and
saving persists the fields (reload shows them). (No frontend test harness — build + manual verification.)
No auto-run.

- [ ] **Step 6: Commit**

```bash
git add ui/src/components/ActionTypeFieldsTab.tsx ui/src/pages/ActionTypeDetailPage.tsx
git commit -m "feat(ui): author action type inputs/outputs and workflowEnabled"
```

---

## Task 12: Docs & final verification

**Files:**
- Modify: `docs/developer-guide/api-first-development.md` (only if it enumerates Action Type fields) or a
  relevant Action Types doc, if one exists — otherwise skip.

- [ ] **Step 1: Update developer docs if Action Type fields are documented**

Grep `docs/` for `inputSchema` and for an Action Types reference; if found, replace with the new
`inputs`/`outputs`/`workflowEnabled` model. If no such doc exists, skip this task (do not invent one).

- [ ] **Step 2: Hand off — user runs the full suite**

Ask the user to run the complete backend test suite and a full app build + UI build, then exercise an
end-to-end workflow using a workflow-enabled action type with declared inputs and outputs. Confirm:
palette curation, editor auto-generation, `{{inputs.NAME}}` binding, appended output contract, and
fail-fast on bad input/output.

- [ ] **Step 3: Commit any doc changes**

```bash
git add docs/
git commit -m "docs: document action type inputs/outputs and workflowEnabled"
```

---

## Self-Review — spec coverage

- Declaration model (`inputs`/`outputs` as `ActionTypeField[]`, `workflowEnabled`) → Tasks 1, 2.
- Retire `inputSchema` → Tasks 1 (API), 2 (entity/migration), 4 (REST), 5 (import/export), 11 (UI).
- Child tables with order → Task 2 (`@OrderColumn`, `V55`).
- Design-time validation → Task 3.
- Editor SPI wiring + palette curation (`workflowEnabled` filter) → Tasks 4 (server filter), 10 (provider).
- Execution: named input binding `{{inputs.NAME}}` → Task 8 (agent + script).
- Execution: fail-fast input validation → Task 8; shared validator → Task 7.
- Execution: auto-appended output contract → Task 8.
- Execution: output validation on completion → Task 9.
- Authoring UI (Info checkbox + conditional Inputs/Outputs tabs) → Task 11.
- Import/export + seed → Tasks 5, 6.
- Backward compatibility (no declared I/O → unchanged; flag off → published workflows keep running) →
  enforced by the gating rules in Tasks 8, 9 and the palette-only scope in Task 4.
- Testing strategy → Tasks 3, 7 (JUnit); execution/UI verified via user hand-off (no frontend test harness).
