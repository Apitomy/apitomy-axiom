# Workflow Input Contract & Trigger Failure Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Make a workflow's expected inputs a documented, enforced contract, and surface trigger failures to
the user in the UI.

**Architecture:** Axiom always injects a fixed canonical set of project-context inputs
(`projectId`, `projectName`, `repository`, `ref`). Publish-time validation constrains a definition's Start node
to that set; a run-time guard translates any engine input error into a clean HTTP 400; the UI surfaces the
message in an inline alert; and new definitions are scaffolded with the canonical inputs already declared.

**Tech Stack:** Java 25 / Quarkus (RESTEasy Reactive, Panache, JUnit 5 + RestAssured), React 19 + PatternFly 6
(TypeScript).

**Spec:** `docs/superpowers/specs/2026-08-27-workflow-input-contract-design.md`

## Global Constraints

- Branch: work stays on the current branch `feat/228-workflow-execution-phase2`. Do not create a new branch.
- Do NOT run Maven builds or run tests automatically — per project preference the user compiles and runs all
  tests manually. Test code below is written test-first for design; the "verify it fails / passes" steps are
  performed by the user, not by the executor.
- Never include Claude attribution in commit messages.
- Canonical input set (exact names/types): `projectId` (number, always present), `projectName` (string, always
  present), `repository` (string, optional), `ref` (string, optional).
- Contract rules: a Start node may declare only canonical input names; only `projectId` and `projectName` may
  be marked `required: true`.
- Backend REST resources implement generated JAX-RS interfaces; never add `@Path` to impl classes. This plan
  does not change the OpenAPI spec or any generated bean.

---

### Task 1: Publish-time Start-input validation

Reject publishing a workflow definition whose Start node violates the input contract. Adds the canonical-set
constants (reused by Task 2) and a `validateStartInputs` check to the publish path.

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/WorkflowDefinitionsResourceImpl.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowDefinitionsResourceTest.java`

**Interfaces:**
- Produces: `private static final List<String> CANONICAL_INPUT_NAMES`,
  `private static final Set<String> CANONICAL_INPUTS`, `private static final Set<String> ALWAYS_PRESENT_INPUTS`,
  and `private void validateStartInputs(Workflow workflow)` on `WorkflowDefinitionsResourceImpl`. The constants
  are reused by Task 2's scaffolding.

- [ ] **Step 1: Write the failing tests**

Add these tests to `WorkflowDefinitionsResourceTest.java` (they reuse the existing `createDefinition` helper):

```java
    @Test
    void testPublishRejectsNonCanonicalStartInput() {
        int id = createDefinition("Non-Canonical Input WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-nc",
                        "name": "Non-Canonical",
                        "nodes": [
                            {"id": "s1", "type": "start", "name": "Start",
                             "config": {"inputs": [
                                 {"name": "issueNumber", "type": "string",
                                  "required": true}
                             ]},
                             "position": {"x": 100, "y": 100}},
                            {"id": "e1", "type": "end", "name": "End",
                             "config": {}, "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1", "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(BASE_PATH + "/" + id + "/content")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .post(BASE_PATH + "/" + id + "/publish")
                .then()
                    .statusCode(400);
    }

    @Test
    void testPublishRejectsRequiredOptionalInput() {
        int id = createDefinition("Required Optional Input WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-ro",
                        "name": "Required Optional",
                        "nodes": [
                            {"id": "s1", "type": "start", "name": "Start",
                             "config": {"inputs": [
                                 {"name": "repository", "type": "string",
                                  "required": true}
                             ]},
                             "position": {"x": 100, "y": 100}},
                            {"id": "e1", "type": "end", "name": "End",
                             "config": {}, "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1", "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(BASE_PATH + "/" + id + "/content")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .post(BASE_PATH + "/" + id + "/publish")
                .then()
                    .statusCode(400);
    }

    @Test
    void testPublishAcceptsCanonicalStartInputs() {
        int id = createDefinition("Canonical Input WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-can",
                        "name": "Canonical",
                        "nodes": [
                            {"id": "s1", "type": "start", "name": "Start",
                             "config": {"inputs": [
                                 {"name": "projectId", "type": "number",
                                  "required": true},
                                 {"name": "repository", "type": "string",
                                  "required": false}
                             ]},
                             "position": {"x": 100, "y": 100}},
                            {"id": "e1", "type": "end", "name": "End",
                             "config": {}, "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1", "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(BASE_PATH + "/" + id + "/content")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .post(BASE_PATH + "/" + id + "/publish")
                .then()
                    .statusCode(200)
                    .body("version", equalTo(1));
    }
```

- [ ] **Step 2: Run the tests to verify they fail** *(user runs)*

Run: `./mvnw test -pl app -Dtest=WorkflowDefinitionsResourceTest`
Expected: `testPublishRejectsNonCanonicalStartInput` and `testPublishRejectsRequiredOptionalInput` FAIL
(publish currently returns 200 for these); `testPublishAcceptsCanonicalStartInputs` passes.

- [ ] **Step 3: Add the canonical-set constants**

In `WorkflowDefinitionsResourceImpl.java`, add `import java.util.Set;` and
`import io.apitomy.flow.model.WorkflowNode;` to the imports, then add these fields at the top of the class body
(just after `@Inject ObjectMapper objectMapper;`):

```java
    /** Inputs Axiom always injects when starting a workflow (may be marked required). */
    private static final Set<String> ALWAYS_PRESENT_INPUTS =
            Set.of("projectId", "projectName");

    /** All inputs Axiom may inject (always-present plus conditionally-present). */
    private static final List<String> CANONICAL_INPUT_NAMES =
            List.of("projectId", "projectName", "repository", "ref");

    private static final Set<String> CANONICAL_INPUTS = Set.copyOf(CANONICAL_INPUT_NAMES);
```

- [ ] **Step 4: Add the `validateStartInputs` helper**

Add this method to `WorkflowDefinitionsResourceImpl.java` in the "Helpers" section (e.g. after `findOrThrow`):

```java
    /**
     * Validates that a workflow's Start node only declares inputs from the canonical
     * input contract, and only marks always-present inputs as required. Throws a 400
     * WebApplicationException on any violation.
     */
    private void validateStartInputs(Workflow workflow) {
        WorkflowNode startNode = workflow.findStartNode().orElse(null);
        if (startNode == null) {
            return; // missing Start node is handled by structural validation
        }
        Object inputsDef = startNode.config().get("inputs");
        if (!(inputsDef instanceof List<?> inputs)) {
            return;
        }
        for (Object inputObj : inputs) {
            if (!(inputObj instanceof Map<?, ?> input)) {
                continue;
            }
            Object nameObj = input.get("name");
            String name = nameObj != null ? nameObj.toString() : null;
            if (name == null || !CANONICAL_INPUTS.contains(name)) {
                throw new WebApplicationException(
                        Response.status(400).entity(Map.of("message",
                                "Start node input '" + name + "' is not part of the "
                                        + "workflow input contract. Allowed inputs: "
                                        + String.join(", ", CANONICAL_INPUT_NAMES)))
                                .build());
            }
            if (Boolean.TRUE.equals(input.get("required"))
                    && !ALWAYS_PRESENT_INPUTS.contains(name)) {
                throw new WebApplicationException(
                        Response.status(400).entity(Map.of("message",
                                "Start node input '" + name + "' cannot be marked "
                                        + "required because Axiom does not always provide "
                                        + "it. Only projectId and projectName may be "
                                        + "required.")).build());
            }
        }
    }
```

- [ ] **Step 5: Call `validateStartInputs` from the publish path**

In `publishWorkflowDefinition`, immediately after the existing `WorkflowValidator` error block (the
`if (!errors.isEmpty()) { ... }` that ends around line 189) and before `int newVersion = ...`, insert:

```java
        validateStartInputs(workflow);
```

- [ ] **Step 6: Run the tests to verify they pass** *(user runs)*

Run: `./mvnw test -pl app -Dtest=WorkflowDefinitionsResourceTest`
Expected: all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/rest/WorkflowDefinitionsResourceImpl.java \
        app/src/test/java/io/apitomy/axiom/app/WorkflowDefinitionsResourceTest.java
git commit -m "feat: validate workflow Start-node inputs against canonical contract at publish"
```

---

### Task 2: Scaffold new definitions with the canonical inputs

New workflow definitions should be created with the canonical inputs already declared on the Start node, so
authors see what context is available and definitions conform by default.

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/WorkflowDefinitionsResourceImpl.java:306-335`
  (`createEmptyWorkflowContent`)
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowDefinitionsResourceTest.java`

**Interfaces:**
- Consumes: nothing new (uses literal input descriptors; the canonical *names* also match Task 1's constants).
- Produces: a Start node whose `config.inputs` is the canonical four-element list.

- [ ] **Step 1: Write the failing test**

Add to `WorkflowDefinitionsResourceTest.java`:

```java
    @Test
    void testNewDefinitionSeedsCanonicalInputs() {
        int id = createDefinition("Seeded Inputs WF");

        given()
                .when()
                    .get(BASE_PATH + "/" + id)
                .then()
                    .statusCode(200)
                    .body("content.nodes[0].type", equalTo("start"))
                    .body("content.nodes[0].config.inputs.size()", equalTo(4))
                    .body("content.nodes[0].config.inputs.name",
                            hasItems("projectId", "projectName",
                                     "repository", "ref"));
    }
```

- [ ] **Step 2: Run the test to verify it fails** *(user runs)*

Run: `./mvnw test -pl app -Dtest=WorkflowDefinitionsResourceTest#testNewDefinitionSeedsCanonicalInputs`
Expected: FAIL — the Start node's `config` is currently empty (`Map.of()`), so `config.inputs` is null.

- [ ] **Step 3: Seed the Start node config in `createEmptyWorkflowContent`**

In `createEmptyWorkflowContent`, build the input descriptors and attach them to the Start node's `config`.
Replace the current `startNode` declaration (the block that sets `"config", Map.of()`) with:

```java
        List<Map<String, Object>> startInputs = List.of(
                Map.of("name", "projectId", "type", "number",
                        "required", true,
                        "description", "The Axiom project id"),
                Map.of("name", "projectName", "type", "string",
                        "required", true,
                        "description", "The Axiom project name"),
                Map.of("name", "repository", "type", "string",
                        "required", false,
                        "description", "The project git repository, if any"),
                Map.of("name", "ref", "type", "string",
                        "required", false,
                        "description", "The project git ref, if any"));
        Map<String, Object> startNode = Map.of(
                "id", "start-1",
                "type", "start",
                "name", "Start",
                "config", Map.of("inputs", startInputs),
                "position", Map.of("x", 250, "y", 100));
```

- [ ] **Step 4: Run the test to verify it passes** *(user runs)*

Run: `./mvnw test -pl app -Dtest=WorkflowDefinitionsResourceTest#testNewDefinitionSeedsCanonicalInputs`
Expected: PASS. Also re-run the full `WorkflowDefinitionsResourceTest` to confirm the existing
`testCreateAndGetWorkflowDefinition` (which asserts `content.nodes.size()` == 2) still passes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/rest/WorkflowDefinitionsResourceImpl.java \
        app/src/test/java/io/apitomy/axiom/app/WorkflowDefinitionsResourceTest.java
git commit -m "feat: seed new workflow definitions with canonical Start-node inputs"
```

---

### Task 3: Run-time input-mismatch guard

Translate the engine's raw input error into a clean HTTP 400 with a readable message. This is
defense-in-depth for hand-edited or legacy definitions that predate publish-time validation, so the test
persists such a definition directly (bypassing the publish endpoint).

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java:121-122` (the
  `workflowEngine.startWorkflow(...)` call in `triggerWorkflow`)
- Create: `app/src/test/java/io/apitomy/axiom/app/WorkflowTriggerInputGuardTest.java`

**Interfaces:**
- Consumes: `WorkflowExecutionService.triggerWorkflow(long projectId, long definitionId)` (existing);
  `io.apitomy.flow.engine.WorkflowValidationException` (existing, `extends RuntimeException`).
- Produces: `triggerWorkflow` now throws `WebApplicationException` (status 400) instead of propagating
  `IllegalArgumentException` / `WorkflowValidationException`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/apitomy/axiom/app/WorkflowTriggerInputGuardTest.java`:

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class WorkflowTriggerInputGuardTest {

    @Inject
    WorkflowExecutionService service;

    /**
     * A structurally valid start→end workflow whose Start node requires a
     * non-canonical input Axiom never provides. Simulates legacy/hand-edited
     * content that bypassed publish-time validation.
     */
    private static final String LEGACY_CONTENT = """
        {
            "id": "legacy-wf",
            "name": "Legacy WF",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start",
                 "config": {"inputs": [
                     {"name": "issueNumber", "type": "string",
                      "required": true}
                 ]},
                 "position": {"x": 100, "y": 100}},
                {"id": "e1", "type": "end", "name": "End",
                 "config": {}, "position": {"x": 100, "y": 300}}
            ],
            "edges": [
                {"id": "edge1", "source": "s1", "target": "e1",
                 "priority": 0, "isDefault": true}
            ]
        }
        """;

    @Test
    void testTriggerWithMissingRequiredInputReturns400() {
        long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            ProjectEntity project = new ProjectEntity();
            project.name = "Input Guard Project";
            project.type = "other";
            project.status = "new";
            project.ref = "test/input-guard";
            project.createdOn = Instant.now();
            project.updatedOn = Instant.now();
            project.persist();

            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = "Input Guard WF";
            def.content = LEGACY_CONTENT;
            def.currentVersion = 1;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();

            WorkflowDefinitionVersionEntity version =
                    new WorkflowDefinitionVersionEntity();
            version.definitionId = def.id;
            version.version = 1;
            version.content = LEGACY_CONTENT;
            version.createdOn = Instant.now();
            version.persist();

            return new long[] { project.id, def.id };
        });

        WebApplicationException ex = assertThrows(
                WebApplicationException.class,
                () -> service.triggerWorkflow(ids[0], ids[1]));
        assertEquals(400, ex.getResponse().getStatus());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails** *(user runs)*

Run: `./mvnw test -pl app -Dtest=WorkflowTriggerInputGuardTest`
Expected: FAIL — `triggerWorkflow` currently throws a raw `IllegalArgumentException` (not a
`WebApplicationException`), so `assertThrows(WebApplicationException.class, ...)` fails.

- [ ] **Step 3: Add the run-time guard**

In `WorkflowExecutionService.java`, add these imports:

```java
import io.apitomy.flow.engine.WorkflowValidationException;
import jakarta.ws.rs.core.Response;
```

Then in `triggerWorkflow`, replace the direct engine call (currently
`WorkflowInstance instance = workflowEngine.startWorkflow(workflow, context);`) with:

```java
        WorkflowInstance instance;
        try {
            instance = workflowEngine.startWorkflow(workflow, context);
        } catch (IllegalArgumentException | WorkflowValidationException e) {
            throw new WebApplicationException(
                    Response.status(400)
                            .entity(Map.of("message", e.getMessage()))
                            .build());
        }
```

- [ ] **Step 4: Run the test to verify it passes** *(user runs)*

Run: `./mvnw test -pl app -Dtest=WorkflowTriggerInputGuardTest`
Expected: PASS. Also re-run `WorkflowInstanceResourceTest` to confirm the happy-path trigger tests still pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java \
        app/src/test/java/io/apitomy/axiom/app/WorkflowTriggerInputGuardTest.java
git commit -m "feat: return 400 with a message when a workflow trigger fails input validation"
```

---

### Task 4: Surface trigger failures in the UI

Stop discarding the backend error body in the API client, and render the failure as an inline alert in the
Run Workflow modal (which now stays open on failure).

**Files:**
- Modify: `ui/src/config/api.ts:2236-2250` (`triggerWorkflow`)
- Modify: `ui/src/components/WorkflowTab.tsx`

**Interfaces:**
- Consumes: backend 400 responses whose JSON body is `{ "message": "..." }` (from Tasks 1 and 3).
- Produces: `triggerWorkflow` rejects with an `Error` whose `.message` is the backend message when available.

- [ ] **Step 1: Surface the response body in `api.ts`**

In `ui/src/config/api.ts`, replace the error block inside `triggerWorkflow` (the
`if (!response.ok) { throw new Error(...) }`) with:

```ts
    if (!response.ok) {
        let message = `Failed to trigger workflow: ${response.status}`;
        try {
            const body = await response.json();
            if (body && typeof body === "object"
                    && typeof body.message === "string" && body.message) {
                message = body.message;
            } else if (typeof body === "string" && body) {
                message = body;
            }
        } catch {
            // No JSON body; keep the status-based message.
        }
        throw new Error(message);
    }
```

- [ ] **Step 2: Add error state and alert to `WorkflowTab.tsx`**

Make the following edits to `ui/src/components/WorkflowTab.tsx`:

(a) Add `Alert` to the PatternFly import:

```tsx
import {
    Alert,
    Button, EmptyState, EmptyStateBody,
    Flex, FlexItem, Label, Modal, ModalBody,
    ModalFooter, ModalHeader, Form, FormGroup,
    FormSelect, FormSelectOption,
} from "@patternfly/react-core";
```

(b) Add an error state next to the other `useState` hooks (near `submitting`):

```tsx
    const [triggerError, setTriggerError] = useState<string | null>(null);
```

(c) In `openTriggerModal`, clear the error before opening — add `setTriggerError(null);` inside the `.then(...)`
callback (e.g. right before `setIsTriggerOpen(true);`).

(d) Replace `handleTrigger` with a version that clears then captures the error:

```tsx
    const handleTrigger = useCallback(() => {
        if (!selectedDefId) return;
        setSubmitting(true);
        setTriggerError(null);
        triggerWorkflow(projectId, {
            workflowDefinitionId: Number(selectedDefId),
        })
            .then(() => {
                setIsTriggerOpen(false);
                onRefresh();
                loadInstance();
            })
            .catch((err) => setTriggerError(
                err instanceof Error
                    ? err.message
                    : "Failed to run workflow"))
            .finally(() => setSubmitting(false));
    }, [projectId, selectedDefId, onRefresh, loadInstance]);
```

(e) Clear the error when the modal is closed — change the trigger `Modal`'s `onClose` and the footer Cancel
button `onClick` from `() => setIsTriggerOpen(false)` to:

```tsx
() => { setIsTriggerOpen(false); setTriggerError(null); }
```

(f) Render the alert at the top of the trigger modal's `ModalBody`, before the
`{definitions.length === 0 ? ... }` expression:

```tsx
                        {triggerError && (
                            <Alert variant="danger" isInline
                                title="Failed to run workflow"
                                style={{ marginBottom: "16px" }}>
                                {triggerError}
                            </Alert>
                        )}
```

- [ ] **Step 3: Verify in the browser** *(user runs the app)*

Build/run per project convention (the user handles this). Open a project with no workflow, click **Run
Workflow**, choose a definition whose Start node requires an unsatisfiable input (e.g. a legacy one), and
confirm the modal stays open and shows the red alert with the backend message. Then run a valid definition and
confirm the modal closes and the instance appears.

- [ ] **Step 4: Commit**

```bash
git add ui/src/config/api.ts ui/src/components/WorkflowTab.tsx
git commit -m "feat(ui): show workflow trigger failures in the Run Workflow modal"
```

---

### Task 5: Documentation

Document the canonical input contract and update the Phase 2 spec's trigger error list.

**Files:**
- Create: `docs/developer-guide/workflow-input-contract.md`
- Modify: `docs/superpowers/specs/2026-08-26-workflow-execution-phase2-design.md` (trigger error-response list,
  around lines 107-110)

- [ ] **Step 1: Write the contract doc**

Create `docs/developer-guide/workflow-input-contract.md` (prose wrapped at 110 columns; the table is exempt):

```markdown
# Workflow Input Contract

When Axiom starts a workflow for a project, it injects a fixed, canonical set of inputs into the workflow's
initial context. Workflow definitions may depend only on these inputs. This keeps a definition runnable by
construction — there is no way to supply arbitrary inputs from the "Run Workflow" dialog.

## Canonical inputs

| name          | type   | presence                    | may be `required`? |
|---------------|--------|-----------------------------|--------------------|
| `projectId`   | number | always                      | yes                |
| `projectName` | string | always                      | yes                |
| `repository`  | string | only if the project has one | no — must be optional |
| `ref`         | string | only if the project has one | no — must be optional |

`repository` and `ref` are injected only when the project defines them, so they can be absent at run time.

## Rules enforced at publish

A workflow definition's Start node declares its inputs under `config.inputs` (a list of
`{ name, type, required, description }`). At publish time Axiom rejects a definition when:

1. The Start node declares an input whose `name` is not one of the canonical inputs.
2. The Start node marks `repository` or `ref` (or any non-always-present input) as `required`.

New definitions are scaffolded with all four canonical inputs already declared (`projectId`/`projectName`
required, `repository`/`ref` optional).

## Run-time behavior

As defense-in-depth for legacy or hand-edited definitions, a trigger whose context does not satisfy a
required Start-node input fails with HTTP 400 and a message naming the missing input, which the UI surfaces in
the Run Workflow dialog.
```

- [ ] **Step 2: Update the Phase 2 spec error list**

In `docs/superpowers/specs/2026-08-26-workflow-execution-phase2-design.md`, in the trigger error-response list
(around lines 107-110), add an entry for the required-input case, matching the surrounding style, e.g.:

```markdown
- `400` — the workflow's Start node requires an input Axiom does not provide (input contract violation).
```

- [ ] **Step 3: Commit**

```bash
git add docs/developer-guide/workflow-input-contract.md \
        docs/superpowers/specs/2026-08-26-workflow-execution-phase2-design.md
git commit -m "docs: document the workflow input contract"
```

---

## Self-Review

**Spec coverage:**
- Canonical contract (spec §"The canonical input contract") → Task 1 constants + Task 5 doc.
- Publish-time validation (spec §Design.1) → Task 1.
- Run-time guard (spec §Design.2) → Task 3.
- UI visibility, api.ts body + modal alert (spec §Design.3) → Task 4.
- Start-node scaffolding (spec §Design.4) → Task 2.
- Docs (spec §Design.5) → Task 5.

**Type consistency:** `validateStartInputs(Workflow)`, `CANONICAL_INPUT_NAMES`, `CANONICAL_INPUTS`,
`ALWAYS_PRESENT_INPUTS` are named identically in Task 1's definition and Task 2's reference. Error bodies are
`{ "message": string }` in Tasks 1 and 3, and Task 4's api.ts reads exactly `body.message`. `triggerWorkflow`
signature matches the existing service method used in Task 3's test.

**Placeholder scan:** No TBD/TODO; every code step has concrete code.

**Notes for the executor:** Per the Global Constraints, do not run Maven or npm builds/tests yourself — the
user runs them. Confirm each backend edit compiles conceptually against the shown imports; the `Response`
import already exists in `WorkflowDefinitionsResourceImpl` but must be *added* to `WorkflowExecutionService`.
