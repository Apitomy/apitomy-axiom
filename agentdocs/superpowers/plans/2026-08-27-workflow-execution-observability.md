# Workflow Execution Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user review a workflow execution — live and after completion — with per-run history, a
node→task→execution-log drill-down, and the full agent-activity trace, surfaced on the project page and
in a new **Logs → Workflow Runs** area.

**Architecture:** A renamed run-history table (`workflow_run`) is the source of truth for run
identity/status, wired into the existing tracing subsystem so the deep tool-call drill-down comes for
free. Each run gets a trace root; each workflow-spawned task gets a `trace_id`, a `node_id`, and a task
trace node, so MCP tool calls nest automatically. New contract-first REST endpoints expose run identity
and the node→task→trace linkage; new React pages render the list and a tabbed run-detail view.

**Tech Stack:** Java 25 / Quarkus 3.33 (Panache, Hibernate ORM, Flyway, JAX-RS generated from
`openapi.json`), `apitomy-flow-engine` 1.0.1, React 19 / PatternFly 6 / Vite, `@apitomy/flow-ui`,
`@xyflow/react`.

**Spec:** `docs/superpowers/specs/2026-08-27-workflow-execution-observability-design.md`

## Global Constraints

These apply to **every** task. Copied verbatim from the user's standing rules and the spec.

- **Contract-first:** All REST changes start in `common/api/src/main/resources/openapi.json`. Prefer the
  **apicurio-data-models MCP tools** for every edit to that file. JAX-RS interfaces and beans regenerate
  from the spec into `common/api/target/generated-sources/jaxrs/`.
- **Never add `@Path` on impl classes** — paths come from the generated interface. Impls `implement` the
  generated interface and use generated beans from `io.apitomy.axiom.api.beans` (never raw `JsonNode`).
- **The developer runs all builds and tests manually.** Do **NOT** auto-run `mvn`/`./build.sh` or the
  test suite. Every "verify" step below is a **developer checkpoint**: pause and ask the user to build
  (`./build.sh` — regenerates beans), run the named test, or exercise the UI, then report back. Do not
  invoke Maven yourself.
- **Serena is not activated** in this session; do not activate/onboard it. Prefer Serena symbolic tools
  only if the user has activated the project; otherwise use `Edit`/`Write`.
- **Java style:** 4-space indent, explicit types (avoid ambiguous `var`), Javadoc on public methods,
  camelCase vars / PascalCase classes, functional style (streams/lambdas) where it reads well. Tests are
  JUnit 5 (`@QuarkusTest` + RestAssured, black-box HTTP — model on `WorkflowInstanceResourceTest`).
- **Markdown** wraps at 110 chars (except tables/structured content).
- **Git:** never include Claude attribution in commit messages or PR descriptions. Branch:
  `feat/workflow-execution-observability`. Commit after each task.
- **Upstream known limitation:** run *status* is derived from the engine instance; until
  [apitomy-flow#38](https://github.com/Apitomy/apitomy-flow/issues/38) is fixed and a new Flow is
  released, a failed node still yields a "completed" run badge. The failed task and its trace node are
  recorded truthfully, so drill-down stays honest. Do not duplicate engine logic in Axiom to work around
  this.

---

## File Structure

**Backend — create:**
- `app/src/main/resources/db/migration/V54__rename_workflow_instance_to_run.sql` — schema migration.
- `app/src/main/java/io/apitomy/axiom/app/rest/WorkflowRunsResourceImpl.java` — new `/workflow-runs`
  resource (list + get-by-id).
- `app/src/test/java/io/apitomy/axiom/app/WorkflowRunsResourceTest.java` — run-history + REST tests.

**Backend — rename/modify:**
- `core/.../entities/WorkflowInstanceEntity.java` → **`WorkflowRunEntity.java`** (`@Table("workflow_run")`,
  drop `unique`, add `traceId`).
- `core/.../entities/TaskEntity.java` — `workflowInstanceId` → **`workflowRunId`**; add **`nodeId`**.
- `core/.../events/SseEvent.java` — enrich `workflowUpdated`.
- `app/.../WorkflowExecutionService.java` — lifecycle + trace wiring; all entity references.
- `app/.../TaskExecutionService.java` — field rename; defer trace completion for workflow tasks.
- `app/.../rest/ProjectsResourceImpl.java` — enrich `toWorkflowInstanceBean` (latest run + history
  taskId/taskStatus); entity references.
- `app/.../rest/WorkflowDefinitionsResourceImpl.java` — add `listWorkflowDefinitionRuns`.
- `common/api/src/main/resources/openapi.json` — schemas, paths, tag.

**Frontend — create:**
- `ui/src/pages/WorkflowRunsPage.tsx` — runs list.
- `ui/src/pages/WorkflowRunDetailPage.tsx` — tabbed run detail.
- `ui/src/components/WorkflowStepTimeline.tsx` — shared step-timeline component.

**Frontend — modify:**
- `ui/src/config/api.ts` — types + fetch functions; enrich existing workflow types.
- `ui/src/App.tsx` — two routes.
- `ui/src/components/AppSidebar.tsx` — "Workflow Runs" nav item.
- `ui/src/components/WorkflowTab.tsx` — "View run details →" link.

**Side-deliverable:** GitHub issues on `Apitomy/apitomy-flow` for the flow-ui node-click callback and
history `edgeId`/`edgeCondition` exposure (Task 19).

---

## Phase 1 — Data Model & Entity Rename

### Task 1: Flyway migration V54 (rename table, add columns, run history)

**Files:**
- Create: `app/src/main/resources/db/migration/V54__rename_workflow_instance_to_run.sql`
- Reference (do not edit): `app/src/main/resources/db/migration/V53__create_workflow_instance.sql`

**Interfaces:**
- Produces: table `workflow_run` (no `UNIQUE(project_id)`, new `trace_id UUID`), sequence
  `workflow_run_SEQ`, index `idx_wf_run_project_started`, `task.workflow_run_id`, `task.node_id`.

- [ ] **Step 1: Write the migration**

Create `app/src/main/resources/db/migration/V54__rename_workflow_instance_to_run.sql`:

```sql
-- Rename the single-instance-per-project table into a run-history table.
ALTER TABLE workflow_instance RENAME TO workflow_run;

-- Many runs per project: drop the uniqueness constraint on project_id.
-- V53 created it inline, so Postgres named it workflow_instance_project_id_key.
ALTER TABLE workflow_run DROP CONSTRAINT IF EXISTS workflow_instance_project_id_key;

-- Link a run to its execution trace (nullable; trace creation is best-effort).
ALTER TABLE workflow_run ADD COLUMN trace_id UUID;

-- Rename the Hibernate sequence to match the new table name.
ALTER SEQUENCE IF EXISTS workflow_instance_SEQ RENAME TO workflow_run_SEQ;

-- Replace the status-only index with one that supports latest-run and
-- per-project history queries.
DROP INDEX IF EXISTS idx_wf_instance_status;
CREATE INDEX idx_wf_run_project_started ON workflow_run(project_id, started_on DESC);
CREATE INDEX idx_wf_run_status ON workflow_run(status);

-- Task → run linkage rename, plus the node this task represents.
ALTER TABLE task RENAME COLUMN workflow_instance_id TO workflow_run_id;
ALTER TABLE task ADD COLUMN node_id VARCHAR(255);
```

- [ ] **Step 2: Developer checkpoint — verify migration applies**

Ask the developer to start the app (dev mode) or run the migration so Flyway applies V54, and confirm
there are no errors and the schema reflects `workflow_run` + new columns. Do **not** run Maven yourself.
Expected: app boots; `\d workflow_run` shows `trace_id`, no unique on `project_id`; `\d task` shows
`workflow_run_id` and `node_id`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/resources/db/migration/V54__rename_workflow_instance_to_run.sql
git commit -m "feat(db): migrate workflow_instance to workflow_run history table"
```

---

### Task 2: Rename entity + task fields

Renames `WorkflowInstanceEntity` → `WorkflowRunEntity`, adds `traceId`, and renames/extends the task
linkage fields. This task deliberately leaves the codebase **non-compiling** until Task 3 updates the
references — they are one reviewable unit (entity shape) but split so a reviewer can gate the schema
mapping independently. Execute Task 2 and Task 3 back-to-back before the compile checkpoint.

**Files:**
- Rename: `core/src/main/java/io/apitomy/axiom/core/entities/WorkflowInstanceEntity.java` →
  `core/src/main/java/io/apitomy/axiom/core/entities/WorkflowRunEntity.java`
- Modify: `core/src/main/java/io/apitomy/axiom/core/entities/TaskEntity.java`

**Interfaces:**
- Produces: class `WorkflowRunEntity extends PanacheEntity` with public fields `projectId`,
  `definitionId`, `definitionVersion`, `instanceState`, `status`, `currentNodeId`, `failureReason`,
  `startedOn`, `completedOn`, and new `public java.util.UUID traceId`. `TaskEntity.workflowRunId`
  (was `workflowInstanceId`) and new `public String nodeId`.

- [ ] **Step 1: Create `WorkflowRunEntity.java`**

Create `core/src/main/java/io/apitomy/axiom/core/entities/WorkflowRunEntity.java` (delete the old
`WorkflowInstanceEntity.java` after):

```java
package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single execution (run) of a workflow definition against a project.
 * Runs accumulate as history; at most one non-terminal run exists per project.
 */
@Entity
@Table(name = "workflow_run")
public class WorkflowRunEntity extends PanacheEntity {

    @Column(name = "project_id", nullable = false)
    public Long projectId;

    @Column(name = "definition_id", nullable = false)
    public Long definitionId;

    @Column(name = "definition_version", nullable = false)
    public int definitionVersion;

    @Column(name = "instance_state", columnDefinition = "TEXT", nullable = false)
    public String instanceState;

    @Column(length = 20, nullable = false)
    public String status;

    @Column(name = "current_node_id")
    public String currentNodeId;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    public String failureReason;

    /** Links this run to its execution trace; null if trace creation failed. */
    @Column(name = "trace_id")
    public UUID traceId;

    @Column(name = "started_on", nullable = false)
    public Instant startedOn;

    @Column(name = "completed_on")
    public Instant completedOn;
}
```

- [ ] **Step 2: Delete the old entity file**

```bash
git rm core/src/main/java/io/apitomy/axiom/core/entities/WorkflowInstanceEntity.java
```

- [ ] **Step 3: Rename + extend the task linkage fields**

In `core/src/main/java/io/apitomy/axiom/core/entities/TaskEntity.java`, replace the
`workflow_instance_id` field (currently lines 55-56) with:

```java
    @Column(name = "workflow_run_id")
    public Long workflowRunId;

    /** The workflow node id this task represents (null for non-workflow tasks). */
    @Column(name = "node_id")
    public String nodeId;
```

- [ ] **Step 4: (Checkpoint deferred to Task 3)** — the project will not compile until references are
  updated. Proceed directly to Task 3.

---

### Task 3: Update all references to the renamed entity/fields

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/TaskExecutionService.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/ProjectsResourceImpl.java`
- (Search the whole repo for any other `WorkflowInstanceEntity` / `workflowInstanceId` usages.)

**Interfaces:**
- Consumes: `WorkflowRunEntity`, `TaskEntity.workflowRunId`, `TaskEntity.nodeId` (Task 2).
- Produces: a compiling backend with all references migrated. Behavior unchanged in this task except the
  identifier rename.

- [ ] **Step 1: Update `WorkflowExecutionService.java`**

Replace the import (line 9) `import io.apitomy.axiom.core.entities.WorkflowInstanceEntity;` with:

```java
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
```

Then replace every `WorkflowInstanceEntity` type reference in the file with `WorkflowRunEntity`, and
every `task.workflowInstanceId` with `task.workflowRunId`. Concretely, these sites (as of the current
file): the `triggerWorkflow` `existing`/insert block, `onTaskCompleted` (`task.workflowInstanceId`
guard at ~line 159 and the `WorkflowInstanceEntity entity = ... .findById(task.workflowInstanceId)` at
~line 164), `cancelWorkflow` (~line 210), `createTaskForCurrentNode` (`task.workflowInstanceId =
entity.id` at ~line 266 becomes `task.workflowRunId = entity.id`), and `persistInstanceState`
signatures. Do a file-wide find/replace of the two identifiers; leave the flow-engine
`WorkflowInstance` (from `io.apitomy.flow.model`) untouched — only the Axiom entity is renamed.

- [ ] **Step 2: Update `TaskExecutionService.java` references**

Replace both `task.workflowInstanceId != null` guards (in `onTaskCompleted` ~line 469 and `failTask`
~line 491) with `task.workflowRunId != null`. Leave the `workflowExecutionService.onTaskCompleted(...)`
calls as-is.

- [ ] **Step 3: Update `ProjectsResourceImpl.java` references**

Replace the `WorkflowInstanceEntity` import and all type references (in `getProjectWorkflowInstance`
~line 527 and `toWorkflowInstanceBean` ~line 540) with `WorkflowRunEntity`. Leave the API bean type
`io.apitomy.axiom.api.beans.WorkflowInstance` and the flow-model `WorkflowInstance` untouched.

- [ ] **Step 4: Sweep for stragglers**

Search the repo for remaining `WorkflowInstanceEntity` and `workflowInstanceId` identifiers and update
any found (e.g. other resources, tests). Use Grep:

```
Grep pattern: WorkflowInstanceEntity|workflowInstanceId
```

Update each hit to `WorkflowRunEntity` / `workflowRunId`. Update `WorkflowInstanceResourceTest` only for
identifiers if it references the entity directly (it is black-box HTTP, so likely no change needed).

- [ ] **Step 5: Developer checkpoint — compile**

Ask the developer to build (`./build.sh`) and confirm the project compiles with no unresolved
`WorkflowInstanceEntity`/`workflowInstanceId` references. Expected: clean compile; existing
`WorkflowInstanceResourceTest` still passes (behavior unchanged).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: rename WorkflowInstanceEntity to WorkflowRunEntity and task linkage fields"
```

---

## Phase 2 — Run Lifecycle & Trace Wiring

### Task 4: Allow run history; create a trace root on trigger

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowRunsResourceTest.java` (created here, extended
  later)

**Interfaces:**
- Consumes: `TraceService.createTrace(traceType, summary, eventId, projectId, reportId, rootNodeType,
  rootNodeSummary, rootEntityType, rootEntityId)` → `TraceContext` with `traceId()`; `WorkflowRunEntity`.
- Produces: `triggerWorkflow` inserts a new run each call (409 only when an **active** run exists) and
  stores `run.traceId`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/apitomy/axiom/app/WorkflowRunsResourceTest.java`. Model setup helpers on
`WorkflowInstanceResourceTest` (`createProject`, `createAndPublishDefinition`,
`createAndPublishActionWorkflow`). Add this first test:

```java
package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class WorkflowRunsResourceTest {

    private static final String PROJECTS_PATH = "/api/v1/projects";
    private static final String WORKFLOWS_PATH = "/api/v1/workflow-definitions";

    @Test
    void triggeringActionWorkflowStoresTraceId() {
        int projectId = createProject("WF Trace Project");
        int definitionId = createAndPublishActionWorkflow("Trace WF");

        given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("waiting"))
                    .body("traceId", notNullValue());
    }

    // --- helpers copied/adapted from WorkflowInstanceResourceTest ---
    // createProject(name), createDefinition(name),
    // createAndPublishActionWorkflow(name)
}
```

Copy the `createProject`, `createDefinition`, and `createAndPublishActionWorkflow` private helpers
verbatim from `WorkflowInstanceResourceTest` into this class. (The `traceId` assertion requires the
openapi `WorkflowInstance.traceId` property added in Task 9 to be returned — if running this test before
Task 9/12, assert only `status`; add the `traceId` assertion once Task 12 is done. Note this dependency
in the commit.)

- [ ] **Step 2: Developer checkpoint — verify it fails**

Ask the developer to run `WorkflowRunsResourceTest#triggeringActionWorkflowStoresTraceId`. Expected:
FAIL — `traceId` is null (not yet created/returned).

- [ ] **Step 3: Inject `TraceService` and relax the active-run guard**

In `WorkflowExecutionService.java`, add the import and injection:

```java
import io.apitomy.axiom.core.tracing.TraceService;
import io.apitomy.axiom.core.tracing.TraceContext;
```

```java
    @Inject
    TraceService traceService;
```

Replace the existing single-instance guard in `triggerWorkflow` (current lines 81-86) with an
active-run guard:

```java
        WorkflowRunEntity activeRun = WorkflowRunEntity
                .find("projectId = ?1 and status in ?2",
                        projectId, List.of("running", "waiting"))
                .firstResult();
        if (activeRun != null) {
            throw new WebApplicationException(
                    "Project already has an active workflow run", 409);
        }
```

- [ ] **Step 4: Create the trace root after the run entity is persisted**

In `triggerWorkflow`, immediately after the `WorkflowRunEntity` is persisted (find the existing
`entity.persist()` call that saves the new run — it is after `persistInstanceState` and before
`createTaskForCurrentNode`), add best-effort trace creation:

```java
        try {
            TraceContext traceCtx = traceService.createTrace(
                    "workflow",
                    "Workflow: " + definition.name,
                    null, project.id, null,
                    "workflow", "Workflow: " + definition.name,
                    "workflow-run", entity.id);
            entity.traceId = traceCtx.traceId();
        } catch (Exception e) {
            LOG.warnf(e, "Failed to create trace for workflow run %d", entity.id);
        }
```

> Note: `entity.traceId` is set on the managed entity within the `@Transactional` method, so it flushes
> without an explicit update. `TraceService` runs in `requiringNew()`, so a trace failure does not roll
> back the run.

- [ ] **Step 5: Developer checkpoint — verify it passes**

Ask the developer to build (`./build.sh`, regenerates the `traceId` bean field from Task 9) then run the
test. Expected: PASS once Tasks 9 & 12 are also in place. If sequencing strictly, land Step 3-4 now and
flip the `traceId` assertion on after Task 12.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java \
        app/src/test/java/io/apitomy/axiom/app/WorkflowRunsResourceTest.java
git commit -m "feat: allow workflow run history and create a trace root per run"
```

---

### Task 5: Wire the node task into the trace (traceId, nodeId, task node)

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowRunsResourceTest.java`

**Interfaces:**
- Consumes: `TraceService.addNode(TraceContext ctx, String nodeType, String status, String summary,
  String entityType, Long entityId)` → `Long` (node id). `TraceContext(UUID traceId, Long rootNodeId)`.
- Produces: each workflow node-task carries `workflowRunId`, `nodeId`, `traceId`, and has a matching
  `"task"` trace node under the run root (so `TaskExecutionService` injects `AXIOM_TRACE_ID` /
  `AXIOM_PARENT_NODE_ID` generically).

The trace root node id is not persisted on the run; reconstruct the `TraceContext` from `run.traceId`
plus a lookup of the root node. Add a private helper.

- [ ] **Step 1: Write the failing test**

Add to `WorkflowRunsResourceTest`:

```java
    @Test
    void nodeTaskCarriesRunNodeAndTrace() {
        int projectId = createProject("WF Node Task Project");
        int definitionId = createAndPublishActionWorkflow("Node Task WF");

        given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("waiting"));

        // The spawned action task should expose its nodeId and workflowRunId.
        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId + "/tasks")
                .then()
                    .statusCode(200)
                    .body("[0].nodeId", notNullValue())
                    .body("[0].workflowRunId", notNullValue())
                    .body("[0].traceId", notNullValue());
    }
```

> This relies on the `Task` bean gaining `nodeId` / `workflowRunId` (Task 9) and the tasks-list endpoint
> mapping them (verify `ProjectsResourceImpl` task mapping sets these — add if missing in Task 9 wiring).
> If the tasks-list order is not guaranteed, adjust the matcher to `find`/`hasItem`.

- [ ] **Step 2: Developer checkpoint — verify it fails**

Ask the developer to run `nodeTaskCarriesRunNodeAndTrace`. Expected: FAIL — `nodeId`/`traceId` null on
the task.

- [ ] **Step 3: Add a `TraceContext` reconstruction helper**

In `WorkflowExecutionService.java` add:

```java
    /**
     * Rebuilds a {@link TraceContext} rooted at a run's trace root node, or
     * null if the run has no trace or the root cannot be found.
     */
    private TraceContext traceContextFor(WorkflowRunEntity run) {
        if (run.traceId == null) {
            return null;
        }
        io.apitomy.axiom.core.entities.TraceNodeEntity root =
                io.apitomy.axiom.core.entities.TraceNodeEntity.find(
                        "traceId = ?1 and parentNodeId is null", run.traceId)
                        .firstResult();
        if (root == null) {
            return null;
        }
        return new TraceContext(run.traceId, root.id);
    }
```

- [ ] **Step 4: Stamp the task and add its trace node in `createTaskForCurrentNode`**

In `createTaskForCurrentNode`, set the new fields before `task.persist()` (the method currently sets
`task.workflowInstanceId = entity.id` — already renamed to `workflowRunId` in Task 3):

```java
        task.workflowRunId = entity.id;
        task.nodeId = instance.currentNodeId();
        task.traceId = entity.traceId;
```

Then, after `task.persist()`, add the task trace node (mirroring
`PipelineOrchestrator.handleCreateTask`):

```java
        TraceContext traceCtx = traceContextFor(entity);
        if (traceCtx != null) {
            try {
                traceService.addNode(traceCtx, "task", "in-progress",
                        "Node: " + actionInfo.actionType(), "task", task.id);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to add workflow task trace node");
            }
        }
```

- [ ] **Step 5: Developer checkpoint — verify it passes**

Ask the developer to build and run `nodeTaskCarriesRunNodeAndTrace`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java \
        app/src/test/java/io/apitomy/axiom/app/WorkflowRunsResourceTest.java
git commit -m "feat: stamp workflow node tasks with run/node/trace and add task trace node"
```

---

### Task 6: Complete the trace and set completedOn on terminal runs

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java`

**Interfaces:**
- Consumes: `TraceService.completeTrace(UUID traceId, String status)`.
- Produces: `onTaskCompleted` and `cancelWorkflow` call `completeTrace` when the run reaches a terminal
  state; `completedOn` is already set in those branches.

- [ ] **Step 1: Complete the trace in `onTaskCompleted` terminal branches**

In `onTaskCompleted`, the COMPLETED and FAILED branches already set `entity.completedOn`. Add trace
completion. Replace the `advanced.status() == InstanceStatus.COMPLETED` and `== InstanceStatus.FAILED`
branches (current lines 190-200) with:

```java
        } else if (advanced.status() == InstanceStatus.COMPLETED) {
            entity.completedOn = Instant.now();
            completeRunTrace(entity, "completed");
            logActivity(entity.projectId, "workflow-completed",
                    "Workflow completed");
        } else if (advanced.status() == InstanceStatus.FAILED) {
            entity.completedOn = Instant.now();
            completeRunTrace(entity, "failed");
            logActivity(entity.projectId, "workflow-failed",
                    "Workflow failed: " + advanced.failureReason());
            sseEvents.fire(SseEvent.notification(
                    "Workflow failed for project", "error"));
        }
```

- [ ] **Step 2: Complete the trace in `cancelWorkflow`**

In `cancelWorkflow`, after `entity.completedOn = Instant.now();` (current line 231) add:

```java
        completeRunTrace(entity, "cancelled");
```

- [ ] **Step 3: Add the `completeRunTrace` helper**

```java
    /** Best-effort completion of a run's execution trace. */
    private void completeRunTrace(WorkflowRunEntity run, String status) {
        if (run.traceId == null) {
            return;
        }
        try {
            traceService.completeTrace(run.traceId, status);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to complete trace for workflow run %d", run.id);
        }
    }
```

- [ ] **Step 4: Developer checkpoint**

Ask the developer to build. Behavior verified end-to-end after Task 7 (which stops premature trace
completion). No standalone test here; covered by Task 7's test.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java
git commit -m "feat: complete workflow run trace when the run reaches a terminal state"
```

---

### Task 7: Stop TaskExecutionService from prematurely completing workflow traces

`TaskExecutionService.onTaskCompleted` unconditionally calls `traceService.completeTrace(task.traceId,
...)` for any traced task. A workflow shares one trace across many node-tasks, so this would finalize the
run's trace after the first node. Workflow tasks must complete only their **node**, deferring trace
completion to `WorkflowExecutionService` (Task 6).

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/TaskExecutionService.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowRunsResourceTest.java`

**Interfaces:**
- Consumes: `TaskEntity.workflowRunId`; existing generic task-node lookup by `traceId`/`entityId`.
- Produces: for tasks with `workflowRunId != null`, the task **node** is completed but the **trace** is
  not; non-workflow tasks are unchanged.

- [ ] **Step 1: Write the failing test**

Add to `WorkflowRunsResourceTest` a test that a multi-node run keeps its trace open until the run
finishes. Use the two-action helper if available, or assert on trace status via the traces endpoint after
the first node completes. Minimal version asserting the trace is not prematurely completed:

```java
    @Test
    void runTraceStaysOpenUntilRunCompletes() {
        int projectId = createProject("WF Trace Lifecycle Project");
        int definitionId = createAndPublishActionWorkflow("Trace Lifecycle WF");

        String traceId = given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .extract().path("traceId");

        // While the single action node is still waiting, its trace must be
        // in-progress (not completed).
        given()
                .when()
                    .get("/api/v1/traces/" + traceId)
                .then()
                    .statusCode(200)
                    .body("trace.status", equalTo("in-progress"));
    }
```

> Confirm the trace-detail endpoint path/shape (`GET /api/v1/traces/{traceId}` → `{ trace, nodes }`) and
> the root trace initial status string (`createTrace` sets nodes; the trace's own status is its initial
> value — verify whether it is `"in-progress"` and adjust the matcher). If the created trace's status is
> a different initial string, assert `not(equalTo("completed"))` instead.

- [ ] **Step 2: Developer checkpoint — verify it fails**

Ask the developer to run `runTraceStaysOpenUntilRunCompletes`. Expected: FAIL — trace already
`completed` because the task path finalized it.

- [ ] **Step 3: Guard trace completion for workflow tasks**

In `TaskExecutionService.onTaskCompleted` (the trace-finalization block, ~lines 443-458), keep the task
**node** completion but skip `completeTrace` for workflow tasks. Replace the block with:

```java
        // Complete the trace (async traces are finalized here).
        if (task.traceId != null) {
            try {
                // Complete the task node with final status.
                TraceNodeEntity taskNode = TraceNodeEntity.find(
                        "traceId = ?1 and nodeType = 'task' and entityType = 'task' and entityId = ?2",
                        task.traceId, task.id).firstResult();
                if (taskNode != null) {
                    traceService.completeNode(taskNode.id, statusText);
                }

                // Workflow runs own their trace lifecycle: WorkflowExecutionService
                // completes the trace when the run reaches a terminal state.
                if (task.workflowRunId == null) {
                    traceService.completeTrace(task.traceId,
                            result.success() ? "completed" : "failed");
                }
            } catch (Exception e) {
                LOG.warnf(e, "Failed to complete trace for task %d", task.id);
            }
        }
```

> `statusText` / `result` are the existing local variables in that method — keep whatever names the
> current code uses; only the `if (task.workflowRunId == null)` guard around `completeTrace` is new.

- [ ] **Step 4: Developer checkpoint — verify it passes**

Ask the developer to build and run `runTraceStaysOpenUntilRunCompletes`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/TaskExecutionService.java \
        app/src/test/java/io/apitomy/axiom/app/WorkflowRunsResourceTest.java
git commit -m "fix: defer workflow trace completion to the run lifecycle"
```

---

### Task 8: Enrich the `workflow-updated` SSE event

**Files:**
- Modify: `core/src/main/java/io/apitomy/axiom/core/events/SseEvent.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java`

**Interfaces:**
- Produces: `SseEvent.workflowUpdated(long projectId, Long runId, String status)` emitting
  `{"projectId":…,"runId":…,"status":"…"}`. Existing `projectId`-only consumers keep working.

- [ ] **Step 1: Replace `workflowUpdated` with the enriched overload**

In `SseEvent.java`, replace the current `workflowUpdated(long projectId)` (lines 170-173) with:

```java
    /**
     * Fires when a workflow run changes state.
     *
     * @param projectId the project the run belongs to
     * @param runId the workflow run that changed (nullable)
     * @param status the run status (nullable)
     */
    public static SseEvent workflowUpdated(long projectId, Long runId, String status) {
        StringBuilder json = new StringBuilder("{\"projectId\":").append(projectId);
        if (runId != null) {
            json.append(",\"runId\":").append(runId);
        }
        if (status != null) {
            json.append(",\"status\":\"").append(escapeJson(status)).append("\"");
        }
        json.append("}");
        return new SseEvent("workflow-updated", json.toString());
    }
```

- [ ] **Step 2: Update all firing sites in `WorkflowExecutionService`**

Replace each `sseEvents.fire(SseEvent.workflowUpdated(entity.projectId))` /
`...workflowUpdated(projectId))` call with the enriched form. In `onTaskCompleted` (line ~202):

```java
        sseEvents.fire(SseEvent.workflowUpdated(
                entity.projectId, entity.id, entity.status));
```

In `cancelWorkflow` (line ~245):

```java
        sseEvents.fire(SseEvent.workflowUpdated(
                entity.projectId, entity.id, entity.status));
```

Also update the firing site in `triggerWorkflow` (search for `workflowUpdated` there) to pass
`entity.id` and `entity.status`. Sweep the repo for any other `workflowUpdated(` callers and update
them.

- [ ] **Step 3: Developer checkpoint — compile + existing tests**

Ask the developer to build and run `WorkflowInstanceResourceTest` + `WorkflowRunsResourceTest`. Expected:
compile clean; tests pass (the WorkflowTab consumer filters on `data.projectId`, still present).

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/io/apitomy/axiom/core/events/SseEvent.java \
        app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java
git commit -m "feat: include runId and status in workflow-updated SSE events"
```

---

## Phase 3 — API Surface (contract-first)

> All openapi.json edits use the **apicurio-data-models MCP tools**. After each openapi task, the
> developer runs `./build.sh` to regenerate beans/interfaces before impl code referencing them compiles.

### Task 9: Enrich existing schemas (WorkflowInstance, HistoryEntry, Task)

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`

**Interfaces:**
- Produces beans: `WorkflowInstance` gains `runId` (int64) and `traceId` (uuid); `HistoryEntry` gains
  `taskId` (int64) and `taskStatus` (string); `Task` gains `workflowRunId` (int64) and `nodeId` (string).

- [ ] **Step 1: Load the document**

Use apicurio `document_load` on `common/api/src/main/resources/openapi.json`.

- [ ] **Step 2: Add properties to `WorkflowInstance`**

Using `document_add_schema_property` (or `document_set_node`), add to `WorkflowInstance`:

```json
"runId":   { "format": "int64", "type": "integer" },
"traceId": { "format": "uuid", "type": "string" }
```

- [ ] **Step 3: Add properties to `HistoryEntry`**

```json
"taskId":     { "format": "int64", "type": "integer" },
"taskStatus": { "type": "string" }
```

- [ ] **Step 4: Add properties to `Task`**

```json
"workflowRunId": { "format": "int64", "type": "integer" },
"nodeId":        { "type": "string" }
```

- [ ] **Step 5: Save**

Use apicurio `document_save`. Developer checkpoint: run `./build.sh` to regenerate beans.

- [ ] **Step 6: Commit**

```bash
git add common/api/src/main/resources/openapi.json
git commit -m "feat(api): add run/trace/node fields to workflow beans"
```

---

### Task 10: Add WorkflowRun schemas, tag, and `/workflow-runs` paths

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`

**Interfaces:**
- Produces schemas `WorkflowRunSummary` and `WorkflowRunSearchResults`; tag `WorkflowRuns`; paths
  `GET /workflow-runs` (operationId `listWorkflowRuns`) and `GET /workflow-runs/{runId}` (operationId
  `getWorkflowRun`, returns `WorkflowInstance`). Beans: `WorkflowRunSummary`,
  `WorkflowRunSearchResults`, generated interface `WorkflowRunsResource`.

- [ ] **Step 1: Add schema `WorkflowRunSummary`**

```json
"WorkflowRunSummary": {
  "type": "object",
  "properties": {
    "runId":             { "format": "int64", "type": "integer" },
    "projectId":         { "format": "int64", "type": "integer" },
    "projectName":       { "type": "string" },
    "definitionId":      { "format": "int64", "type": "integer" },
    "definitionName":    { "type": "string" },
    "definitionVersion": { "format": "int32", "type": "integer" },
    "status":            { "type": "string" },
    "currentNodeName":   { "type": "string" },
    "traceId":           { "format": "uuid", "type": "string" },
    "startedOn":         { "format": "date-time", "type": "string" },
    "completedOn":       { "format": "date-time", "type": "string" }
  }
}
```

- [ ] **Step 2: Add schema `WorkflowRunSearchResults`** (mirrors `ScheduledJobRunSearchResults`)

```json
"WorkflowRunSearchResults": {
  "type": "object",
  "properties": {
    "items":      { "type": "array", "items": { "$ref": "#/components/schemas/WorkflowRunSummary" } },
    "totalCount": { "format": "int64", "type": "integer" },
    "page":       { "type": "integer" },
    "limit":      { "type": "integer" }
  }
}
```

- [ ] **Step 3: Add tag `WorkflowRuns`**

Using `document_add_tag`: name `WorkflowRuns`, description `Workflow run history and execution
observability`.

- [ ] **Step 4: Add path `/workflow-runs`**

`document_add_path` `/workflow-runs`, then add a GET operation (`document_add_operation`) with:

```json
{
  "tags": ["WorkflowRuns"],
  "summary": "List workflow runs across all projects",
  "operationId": "listWorkflowRuns",
  "parameters": [
    { "name": "projectId", "in": "query", "schema": { "format": "int64", "type": "integer" } },
    { "name": "status", "in": "query", "description": "Filter by status (comma-separated)", "schema": { "type": "string" } },
    { "name": "page", "in": "query", "schema": { "type": "integer" } },
    { "name": "limit", "in": "query", "schema": { "type": "integer" } }
  ],
  "responses": {
    "200": {
      "description": "Paginated list of workflow runs",
      "content": { "application/json": { "schema": { "$ref": "#/components/schemas/WorkflowRunSearchResults" } } }
    }
  }
}
```

- [ ] **Step 5: Add path `/workflow-runs/{runId}`**

GET operation:

```json
{
  "tags": ["WorkflowRuns"],
  "summary": "Get a single workflow run by ID",
  "operationId": "getWorkflowRun",
  "responses": {
    "200": {
      "description": "The workflow run",
      "content": { "application/json": { "schema": { "$ref": "#/components/schemas/WorkflowInstance" } } }
    },
    "404": { "$ref": "#/components/responses/NotFound" }
  }
}
```

Add the path-level `runId` parameter:

```json
{ "name": "runId", "in": "path", "description": "Workflow run ID", "required": true,
  "schema": { "format": "int64", "type": "integer" } }
```

- [ ] **Step 6: Save + developer checkpoint**

`document_save`; ask developer to run `./build.sh`. Expected: generated `WorkflowRunsResource` interface
and `WorkflowRunSummary` / `WorkflowRunSearchResults` beans exist.

- [ ] **Step 7: Commit**

```bash
git add common/api/src/main/resources/openapi.json
git commit -m "feat(api): add /workflow-runs endpoints and run-summary schemas"
```

---

### Task 11: Add `/workflow-definitions/{workflowDefinitionId}/runs`

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`

**Interfaces:**
- Produces: GET `/workflow-definitions/{workflowDefinitionId}/runs` (operationId
  `listWorkflowDefinitionRuns`) added to the existing `WorkflowResource` interface, returning
  `WorkflowRunSearchResults`.

- [ ] **Step 1: Add the path + operation**

`document_add_path` `/workflow-definitions/{workflowDefinitionId}/runs`, GET operation:

```json
{
  "tags": ["WorkflowDefinitions"],
  "summary": "List runs for a specific workflow definition (all versions)",
  "operationId": "listWorkflowDefinitionRuns",
  "parameters": [
    { "name": "status", "in": "query", "description": "Filter by status (comma-separated)", "schema": { "type": "string" } },
    { "name": "page", "in": "query", "schema": { "type": "integer" } },
    { "name": "limit", "in": "query", "schema": { "type": "integer" } }
  ],
  "responses": {
    "200": {
      "description": "Paginated list of runs for the definition",
      "content": { "application/json": { "schema": { "$ref": "#/components/schemas/WorkflowRunSearchResults" } } }
    }
  }
}
```

Add the path-level parameter referencing the existing `WorkflowDefinitionId`:

```json
{ "$ref": "#/components/parameters/WorkflowDefinitionId" }
```

- [ ] **Step 2: Save + developer checkpoint**

`document_save`; developer runs `./build.sh`. Expected: `WorkflowResource` interface gains
`listWorkflowDefinitionRuns`.

- [ ] **Step 3: Commit**

```bash
git add common/api/src/main/resources/openapi.json
git commit -m "feat(api): add per-definition workflow runs endpoint"
```

---

### Task 12: Enrich `toWorkflowInstanceBean` — latest run + history taskId/taskStatus

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/ProjectsResourceImpl.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowRunsResourceTest.java`

**Interfaces:**
- Consumes: generated beans `WorkflowInstance.setRunId/setTraceId`, `HistoryEntry.setTaskId/setTaskStatus`;
  `WorkflowRunEntity`, `TaskEntity.workflowRunId/nodeId`.
- Produces: `getProjectWorkflowInstance` returns the **latest** run for the project; the bean carries
  `runId`, `traceId`; each history entry carries `taskId`/`taskStatus` (joined on `node_id`).

- [ ] **Step 1: Write the failing test**

Add to `WorkflowRunsResourceTest`:

```java
    @Test
    void projectWorkflowReturnsLatestRunWithRunIdAndTrace() {
        int projectId = createProject("WF Latest Run Project");
        int definitionId = createAndPublishActionWorkflow("Latest Run WF");

        int firstRunId = given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when().post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then().statusCode(200).extract().path("runId");

        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("runId", equalTo(firstRunId))
                    .body("traceId", notNullValue());
    }
```

- [ ] **Step 2: Developer checkpoint — verify it fails**

Ask developer to run `projectWorkflowReturnsLatestRunWithRunIdAndTrace`. Expected: FAIL — `runId` null.

- [ ] **Step 3: Return the latest run in `getProjectWorkflowInstance`**

Replace the `find("projectId", projectId).firstResult()` lookup (line ~527) with the latest-by-start:

```java
        WorkflowRunEntity entity = WorkflowRunEntity
                .find("projectId", io.quarkus.panache.common.Sort.descending("startedOn"),
                        projectId)
                .firstResult();
```

- [ ] **Step 4: Set `runId` and `traceId` in `toWorkflowInstanceBean`**

In `toWorkflowInstanceBean`, after `bean.setId(entity.id);` add:

```java
        bean.setRunId(entity.id);
        if (entity.traceId != null) {
            bean.setTraceId(entity.traceId);
        }
```

- [ ] **Step 5: Populate history `taskId`/`taskStatus` by joining tasks on `node_id`**

The bean's history is built in `toWorkflowInstanceBean` from the flow-model history (lines ~595-596 map
`flowInstance.history()` via `toHistoryEntryBean`). Before mapping, build a `nodeId → TaskEntity` map for
this run and thread it into the mapping. Replace the history mapping with:

```java
            List<TaskEntity> runTasks = TaskEntity
                    .<TaskEntity>find("workflowRunId", entity.id).list();
            Map<String, TaskEntity> tasksByNode = runTasks.stream()
                    .filter(t -> t.nodeId != null)
                    .collect(java.util.stream.Collectors.toMap(
                            t -> t.nodeId, t -> t, (a, b) -> b));
            bean.setHistory(flowInstance.history().stream()
                    .map(h -> toHistoryEntryBean(h, tasksByNode))
                    .toList());
```

Update `toHistoryEntryBean` to accept the map and set the fields:

```java
    private io.apitomy.axiom.api.beans.HistoryEntry toHistoryEntryBean(
            io.apitomy.flow.model.HistoryEntry entry,
            Map<String, TaskEntity> tasksByNode) {
        io.apitomy.axiom.api.beans.HistoryEntry bean =
                new io.apitomy.axiom.api.beans.HistoryEntry();
        bean.setNodeId(entry.nodeId());
        bean.setNodeName(entry.nodeName());
        if (entry.enteredOn() != null) {
            bean.setEnteredOn(Date.from(entry.enteredOn()));
        }
        if (entry.completedOn() != null) {
            bean.setCompletedOn(Date.from(entry.completedOn()));
        }
        if (entry.output() != null && !entry.output().isEmpty()) {
            bean.setOutput(objectMapper.convertValue(
                    entry.output(),
                    io.apitomy.axiom.api.beans.Output.class));
        }
        TaskEntity task = entry.nodeId() != null
                ? tasksByNode.get(entry.nodeId()) : null;
        if (task != null) {
            bean.setTaskId(task.id);
            bean.setTaskStatus(task.status);
        }
        return bean;
    }
```

Add `import java.util.Map;` if not present.

- [ ] **Step 6: Also map task `workflowRunId`/`nodeId` in the tasks-list mapping**

Find where `ProjectsResourceImpl` maps `TaskEntity` → the `Task` bean (the tasks-list endpoint). Add:

```java
        bean.setWorkflowRunId(entity.workflowRunId);
        bean.setNodeId(entity.nodeId);
```

(This satisfies Task 5's `nodeTaskCarriesRunNodeAndTrace` assertions.)

- [ ] **Step 7: Developer checkpoint — verify it passes**

Ask developer to build and run `WorkflowRunsResourceTest` (all tests, incl. Task 4/5 assertions now
fully wired) + `WorkflowInstanceResourceTest`. Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/rest/ProjectsResourceImpl.java \
        app/src/test/java/io/apitomy/axiom/app/WorkflowRunsResourceTest.java
git commit -m "feat(api): project workflow returns latest run with runId, trace, and task links"
```

---

### Task 13: Implement `WorkflowRunsResourceImpl` (list + get-by-id)

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/rest/WorkflowRunsResourceImpl.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowRunsResourceTest.java`

**Interfaces:**
- Consumes: generated `WorkflowRunsResource` interface; beans `WorkflowRunSummary`,
  `WorkflowRunSearchResults`, `WorkflowInstance`. Reuses `ProjectsResourceImpl`'s bean-building? No —
  keep this resource self-contained; for get-by-id, build the same enriched `WorkflowInstance` shape via
  a shared helper. To avoid duplication, extract the run→`WorkflowInstance` bean logic.
- Produces: `listWorkflowRuns(projectId, status, page, limit)` and `getWorkflowRun(runId)`.

- [ ] **Step 1: Extract a shared run→bean builder**

To avoid duplicating `toWorkflowInstanceBean`, move it (and `toHistoryEntryBean`) into a new
`@ApplicationScoped` CDI bean `WorkflowRunBeanMapper` in
`app/src/main/java/io/apitomy/axiom/app/WorkflowRunBeanMapper.java`, injecting `ObjectMapper`. Give it a
public method:

```java
    /** Builds the enriched WorkflowInstance bean for a run. */
    public io.apitomy.axiom.api.beans.WorkflowInstance toBean(WorkflowRunEntity entity) { ... }
```

Move the body from `ProjectsResourceImpl.toWorkflowInstanceBean` (and the two-arg `toHistoryEntryBean`)
into this class verbatim, then have `ProjectsResourceImpl` inject `WorkflowRunBeanMapper` and delegate:

```java
    @Inject
    WorkflowRunBeanMapper runBeanMapper;

    private io.apitomy.axiom.api.beans.WorkflowInstance toWorkflowInstanceBean(
            WorkflowRunEntity entity) {
        return runBeanMapper.toBean(entity);
    }
```

- [ ] **Step 2: Write the failing test**

Add to `WorkflowRunsResourceTest`:

```java
    @Test
    void listAndGetWorkflowRuns() {
        int projectId = createProject("WF Runs List Project");
        int definitionId = createAndPublishActionWorkflow("Runs List WF");

        int runId = given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when().post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then().statusCode(200).extract().path("runId");

        given()
                .when().get("/api/v1/workflow-runs?projectId=" + projectId)
                .then()
                    .statusCode(200)
                    .body("totalCount", equalTo(1))
                    .body("items[0].runId", equalTo(runId))
                    .body("items[0].projectName", equalTo("WF Runs List Project"))
                    .body("items[0].definitionName", equalTo("Runs List WF"));

        given()
                .when().get("/api/v1/workflow-runs/" + runId)
                .then()
                    .statusCode(200)
                    .body("runId", equalTo(runId))
                    .body("projectId", equalTo(projectId));

        given()
                .when().get("/api/v1/workflow-runs/999999")
                .then().statusCode(404);
    }
```

- [ ] **Step 3: Developer checkpoint — verify it fails**

Ask developer to run `listAndGetWorkflowRuns`. Expected: FAIL/404 across the board (resource not
implemented).

- [ ] **Step 4: Implement the resource**

Create `app/src/main/java/io/apitomy/axiom/app/rest/WorkflowRunsResourceImpl.java`:

```java
package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.WorkflowRunsResource;
import io.apitomy.axiom.api.beans.WorkflowInstance;
import io.apitomy.axiom.api.beans.WorkflowRunSearchResults;
import io.apitomy.axiom.api.beans.WorkflowRunSummary;
import io.apitomy.axiom.app.WorkflowRunBeanMapper;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

import java.math.BigInteger;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST resource exposing workflow run history and detail.
 */
@ApplicationScoped
@RunOnVirtualThread
public class WorkflowRunsResourceImpl implements WorkflowRunsResource {

    @Inject
    WorkflowRunBeanMapper runBeanMapper;

    /**
     * {@inheritDoc}
     */
    @Override
    public WorkflowRunSearchResults listWorkflowRuns(BigInteger projectId,
                                                     String status,
                                                     BigInteger page,
                                                     BigInteger limit) {
        int pageNum = page != null ? Math.max(1, page.intValue()) : 1;
        int pageSize = limit != null ? Math.max(1, limit.intValue()) : 20;

        StringBuilder hql = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();
        if (projectId != null) {
            hql.append(" and projectId = :projectId");
            params.put("projectId", projectId.longValue());
        }
        if (status != null && !status.isBlank()) {
            hql.append(" and status in :statuses");
            params.put("statuses", List.of(status.split(",")));
        }

        long totalCount = WorkflowRunEntity.count(hql.toString(), params);
        List<WorkflowRunEntity> runs = WorkflowRunEntity
                .<WorkflowRunEntity>find(hql.toString(),
                        Sort.descending("startedOn"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list();

        Map<Long, String> projectNames = resolveProjectNames(runs);
        Map<Long, String> definitionNames = resolveDefinitionNames(runs);

        WorkflowRunSearchResults results = new WorkflowRunSearchResults();
        results.setItems(runs.stream()
                .map(r -> toSummary(r, projectNames, definitionNames))
                .toList());
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WorkflowInstance getWorkflowRun(long runId) {
        WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
        if (run == null) {
            throw new WebApplicationException("Workflow run not found: " + runId, 404);
        }
        return runBeanMapper.toBean(run);
    }

    private Map<Long, String> resolveProjectNames(List<WorkflowRunEntity> runs) {
        Set<Long> ids = runs.stream().map(r -> r.projectId)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return ProjectEntity.<ProjectEntity>find("id in :ids", Map.of("ids", ids))
                .list().stream()
                .collect(Collectors.toMap(p -> p.id, p -> p.name));
    }

    private Map<Long, String> resolveDefinitionNames(List<WorkflowRunEntity> runs) {
        Set<Long> ids = runs.stream().map(r -> r.definitionId)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        return WorkflowDefinitionEntity
                .<WorkflowDefinitionEntity>find("id in :ids", Map.of("ids", ids))
                .list().stream()
                .collect(Collectors.toMap(d -> d.id, d -> d.name));
    }

    private WorkflowRunSummary toSummary(WorkflowRunEntity run,
                                         Map<Long, String> projectNames,
                                         Map<Long, String> definitionNames) {
        WorkflowRunSummary summary = new WorkflowRunSummary();
        summary.setRunId(run.id);
        summary.setProjectId(run.projectId);
        summary.setProjectName(projectNames.getOrDefault(run.projectId, "Unknown"));
        summary.setDefinitionId(run.definitionId);
        summary.setDefinitionName(
                definitionNames.getOrDefault(run.definitionId, "Unknown"));
        summary.setDefinitionVersion(run.definitionVersion);
        summary.setStatus(run.status);
        if (run.traceId != null) {
            summary.setTraceId(run.traceId);
        }
        summary.setStartedOn(Date.from(run.startedOn));
        if (run.completedOn != null) {
            summary.setCompletedOn(Date.from(run.completedOn));
        }
        // currentNodeName is resolved on the detail bean; list omits it to
        // avoid per-row content loads. (Populate later if the UI needs it.)
        return summary;
    }
}
```

> The summary intentionally leaves `currentNodeName` unset in the list to avoid an N+1 content read per
> row; the detail bean (`getWorkflowRun`) carries `currentNodeName` via the mapper. If the list UI needs
> it, add a batch resolve in a follow-up — noted, not silently dropped.

- [ ] **Step 5: Developer checkpoint — verify it passes**

Ask developer to build and run `listAndGetWorkflowRuns`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/WorkflowRunBeanMapper.java \
        app/src/main/java/io/apitomy/axiom/app/rest/WorkflowRunsResourceImpl.java \
        app/src/main/java/io/apitomy/axiom/app/rest/ProjectsResourceImpl.java \
        app/src/test/java/io/apitomy/axiom/app/WorkflowRunsResourceTest.java
git commit -m "feat(api): implement /workflow-runs list and detail endpoints"
```

---

### Task 14: Implement `listWorkflowDefinitionRuns`

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/WorkflowDefinitionsResourceImpl.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowRunsResourceTest.java`

**Interfaces:**
- Consumes: generated `WorkflowResource.listWorkflowDefinitionRuns(long, String, BigInteger,
  BigInteger)`; `WorkflowRunEntity`; the same summary shape as Task 13.
- Produces: per-definition paginated runs.

To share the summary mapping, extract `toSummary` + `resolveProjectNames`/`resolveDefinitionNames` into
`WorkflowRunBeanMapper` as public methods and reuse from both resources.

- [ ] **Step 1: Move summary helpers into `WorkflowRunBeanMapper`**

Add to `WorkflowRunBeanMapper` public methods `toSummary(WorkflowRunEntity, Map, Map)`,
`resolveProjectNames(List)`, `resolveDefinitionNames(List)` (move bodies from
`WorkflowRunsResourceImpl`, make them public). Update `WorkflowRunsResourceImpl` to call
`runBeanMapper.toSummary(...)` etc.

- [ ] **Step 2: Write the failing test**

Add to `WorkflowRunsResourceTest`:

```java
    @Test
    void listRunsForDefinition() {
        int projectId = createProject("WF DefRuns Project");
        int definitionId = createAndPublishActionWorkflow("DefRuns WF");

        given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when().post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then().statusCode(200);

        given()
                .when().get(WORKFLOWS_PATH + "/" + definitionId + "/runs")
                .then()
                    .statusCode(200)
                    .body("totalCount", equalTo(1))
                    .body("items[0].definitionId", equalTo(definitionId));
    }
```

- [ ] **Step 3: Developer checkpoint — verify it fails**

Ask developer to run `listRunsForDefinition`. Expected: FAIL (method returns nothing / 500 until
implemented).

- [ ] **Step 4: Implement the method**

Add to `WorkflowDefinitionsResourceImpl` (inject `WorkflowRunBeanMapper runBeanMapper;`):

```java
    /**
     * {@inheritDoc}
     */
    @Override
    public WorkflowRunSearchResults listWorkflowDefinitionRuns(long workflowDefinitionId,
                                                               String status,
                                                               BigInteger page,
                                                               BigInteger limit) {
        findOrThrow(workflowDefinitionId);

        int pageNum = page != null ? Math.max(1, page.intValue()) : 1;
        int pageSize = limit != null ? Math.max(1, limit.intValue()) : 20;

        StringBuilder hql = new StringBuilder("definitionId = :definitionId");
        Map<String, Object> params = new HashMap<>();
        params.put("definitionId", workflowDefinitionId);
        if (status != null && !status.isBlank()) {
            hql.append(" and status in :statuses");
            params.put("statuses", List.of(status.split(",")));
        }

        long totalCount = WorkflowRunEntity.count(hql.toString(), params);
        List<WorkflowRunEntity> runs = WorkflowRunEntity
                .<WorkflowRunEntity>find(hql.toString(),
                        Sort.descending("startedOn"), params)
                .page(Page.of(pageNum - 1, pageSize))
                .list();

        Map<Long, String> projectNames = runBeanMapper.resolveProjectNames(runs);
        Map<Long, String> definitionNames = runBeanMapper.resolveDefinitionNames(runs);

        WorkflowRunSearchResults results = new WorkflowRunSearchResults();
        results.setItems(runs.stream()
                .map(r -> runBeanMapper.toSummary(r, projectNames, definitionNames))
                .toList());
        results.setTotalCount(totalCount);
        results.setPage(pageNum);
        results.setLimit(pageSize);
        return results;
    }
```

Add imports: `io.apitomy.axiom.api.beans.WorkflowRunSearchResults`,
`io.apitomy.axiom.core.entities.WorkflowRunEntity`, `io.apitomy.axiom.app.WorkflowRunBeanMapper`,
`io.quarkus.panache.common.Page`, `io.quarkus.panache.common.Sort`, `java.math.BigInteger`,
`java.util.HashMap`, `java.util.List`, `java.util.Map`.

- [ ] **Step 5: Developer checkpoint — verify it passes**

Ask developer to build and run `listRunsForDefinition`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/rest/WorkflowDefinitionsResourceImpl.java \
        app/src/main/java/io/apitomy/axiom/app/WorkflowRunBeanMapper.java \
        app/src/main/java/io/apitomy/axiom/app/rest/WorkflowRunsResourceImpl.java \
        app/src/test/java/io/apitomy/axiom/app/WorkflowRunsResourceTest.java
git commit -m "feat(api): implement per-definition workflow runs listing"
```

---

## Phase 4 — UI

> Frontend build/tests are also developer-run. UI tasks end with a "developer verifies in the browser"
> checkpoint rather than an auto-run.

### Task 15: api.ts — types + fetch functions; enrich existing types

**Files:**
- Modify: `ui/src/config/api.ts`

**Interfaces:**
- Produces: `WorkflowRunSummary` interface; `fetchWorkflowRuns(...)`, `getWorkflowRun(runId)`,
  `fetchWorkflowDefinitionRuns(...)`; enriched `WorkflowInstanceInfo` (`runId`, `traceId`),
  `HistoryEntryInfo` (`taskId`, `taskStatus`), `Task` (`workflowRunId`, `nodeId`). Reuses the existing
  `SearchResults<T>` wrapper.

- [ ] **Step 1: Enrich existing interfaces**

In `WorkflowInstanceInfo` add:

```ts
    runId?: number;
    traceId?: string;
```

In `HistoryEntryInfo` add:

```ts
    taskId?: number;
    taskStatus?: string;
```

In `Task` add:

```ts
    workflowRunId?: number;
    nodeId?: string;
```

- [ ] **Step 2: Add the summary interface + fetch functions**

```ts
export interface WorkflowRunSummary {
    runId: number;
    projectId: number;
    projectName?: string;
    definitionId: number;
    definitionName?: string;
    definitionVersion: number;
    status: string;
    currentNodeName?: string;
    traceId?: string;
    startedOn: string;
    completedOn?: string;
}

export async function fetchWorkflowRuns(
    page = 1, limit = 20, projectId?: number, status?: string
): Promise<SearchResults<WorkflowRunSummary>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (projectId != null) params.set("projectId", String(projectId));
    if (status) params.set("status", status);
    const response = await fetch(`${API}/workflow-runs?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch workflow runs: ${response.status}`);
    return response.json();
}

export async function getWorkflowRun(runId: number): Promise<WorkflowInstanceInfo> {
    const response = await fetch(`${API}/workflow-runs/${runId}`);
    if (!response.ok) throw new Error(`Failed to fetch workflow run: ${response.status}`);
    return response.json();
}

export async function fetchWorkflowDefinitionRuns(
    definitionId: number, page = 1, limit = 20, status?: string
): Promise<SearchResults<WorkflowRunSummary>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (status) params.set("status", status);
    const response = await fetch(
        `${API}/workflow-definitions/${definitionId}/runs?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch definition runs: ${response.status}`);
    return response.json();
}
```

- [ ] **Step 3: Developer checkpoint**

Ask developer to run the UI typecheck/build (`npm run build` in `ui/`). Expected: compiles.

- [ ] **Step 4: Commit**

```bash
git add ui/src/config/api.ts
git commit -m "feat(ui): add workflow-run API types and fetch functions"
```

---

### Task 16: WorkflowRunsPage (list) + route + sidebar nav

**Files:**
- Create: `ui/src/pages/WorkflowRunsPage.tsx`
- Modify: `ui/src/App.tsx`, `ui/src/components/AppSidebar.tsx`

**Interfaces:**
- Consumes: `fetchWorkflowRuns`, `WorkflowRunSummary`, `SearchResults`. SSE via `sseClient.subscribe`
  on `"workflow-updated"`.
- Produces: route `/logs/workflow-runs`; sidebar "Workflow Runs" item.

- [ ] **Step 1: Create the page** (model on `ScheduledJobRunsPage`)

Create `ui/src/pages/WorkflowRunsPage.tsx`:

```tsx
import { useState, useEffect, useCallback } from "react";
import { Link } from "react-router-dom";
import {
    Button,
    EmptyState,
    EmptyStateBody,
    Label,
    PageSection,
    Pagination,
    Title,
    Toolbar,
    ToolbarContent,
    ToolbarItem,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import { type WorkflowRunSummary, fetchWorkflowRuns } from "../config/api";
import { sseClient, type AxiomSseEvent } from "../config/sse";

const STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    running: "blue",
    waiting: "orange",
    completed: "green",
    failed: "red",
    cancelled: "grey",
};

function formatDuration(startedOn: string, completedOn?: string): string {
    if (!completedOn) return "—";
    const ms = new Date(completedOn).getTime() - new Date(startedOn).getTime();
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
    const mins = Math.floor(ms / 60_000);
    const secs = Math.round((ms % 60_000) / 1000);
    return `${mins}m ${secs}s`;
}

export function WorkflowRunsPage() {
    const [runs, setRuns] = useState<WorkflowRunSummary[]>([]);
    const [totalCount, setTotalCount] = useState(0);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(20);
    const [loading, setLoading] = useState(true);

    const loadData = useCallback(() => {
        setLoading(true);
        fetchWorkflowRuns(page, perPage)
            .then((results) => {
                setRuns(results.items);
                setTotalCount(results.totalCount);
            })
            .catch(console.error)
            .finally(() => setLoading(false));
    }, [page, perPage]);

    useEffect(() => { loadData(); }, [loadData]);

    useEffect(() => {
        let timeout: ReturnType<typeof setTimeout>;
        const unsubscribe = sseClient.subscribe((event: AxiomSseEvent) => {
            if (event.type === "workflow-updated") {
                clearTimeout(timeout);
                timeout = setTimeout(loadData, 300);
            }
        });
        return () => { clearTimeout(timeout); unsubscribe(); };
    }, [loadData]);

    return (
        <PageSection>
            <Title headingLevel="h1" size="lg" style={{ marginBottom: "16px" }}>
                Workflow Runs
            </Title>

            <Toolbar>
                <ToolbarContent>
                    <ToolbarItem>
                        <Button variant="control" aria-label="Refresh" onClick={loadData}>
                            <SyncAltIcon />
                        </Button>
                    </ToolbarItem>
                    <ToolbarItem variant="pagination" align={{ default: "alignEnd" }}>
                        <Pagination
                            itemCount={totalCount}
                            page={page}
                            perPage={perPage}
                            onSetPage={(_e, p) => setPage(p)}
                            onPerPageSelect={(_e, pp) => { setPerPage(pp); setPage(1); }}
                            isCompact
                        />
                    </ToolbarItem>
                </ToolbarContent>
            </Toolbar>

            {loading ? (
                <EmptyState><EmptyStateBody>Loading workflow runs...</EmptyStateBody></EmptyState>
            ) : runs.length === 0 ? (
                <EmptyState><EmptyStateBody>No workflow runs recorded yet.</EmptyStateBody></EmptyState>
            ) : (
                <Table aria-label="Workflow Runs" variant="compact">
                    <Thead>
                        <Tr>
                            <Th>Status</Th>
                            <Th>Project</Th>
                            <Th>Workflow</Th>
                            <Th>Started</Th>
                            <Th>Duration</Th>
                            <Th>Actions</Th>
                        </Tr>
                    </Thead>
                    <Tbody>
                        {runs.map((run) => (
                            <Tr key={run.runId}>
                                <Td>
                                    <Label isCompact color={STATUS_COLORS[run.status] || "grey"}>
                                        {run.status}
                                    </Label>
                                </Td>
                                <Td>
                                    <Link to={`/projects/${run.projectId}`}>
                                        {run.projectName || `Project #${run.projectId}`}
                                    </Link>
                                </Td>
                                <Td>
                                    {run.definitionName || `Definition #${run.definitionId}`}
                                    {" v"}{run.definitionVersion}
                                </Td>
                                <Td style={{ whiteSpace: "nowrap" }}>
                                    {new Date(run.startedOn).toLocaleString()}
                                </Td>
                                <Td style={{ whiteSpace: "nowrap" }}>
                                    {formatDuration(run.startedOn, run.completedOn)}
                                </Td>
                                <Td>
                                    <Link to={`/logs/workflow-runs/${run.runId}`}>
                                        View details
                                    </Link>
                                </Td>
                            </Tr>
                        ))}
                    </Tbody>
                </Table>
            )}
        </PageSection>
    );
}
```

> Note: `sseClient.subscribe` receives events only if the shared SSE client parses `workflow-updated`
> into `{type, data}`. If the shared client only handles named listeners (as WorkflowTab does via a raw
> `EventSource`), fall back to a raw `EventSource("/api/v1/sse")` + `addEventListener("workflow-updated",
> …)` exactly like `WorkflowTab.tsx` lines 57-77. Verify against `ui/src/config/sse.ts` during
> implementation and pick whichever the client actually supports.

- [ ] **Step 2: Register the route in `App.tsx`**

Add the import near the other page imports:

```tsx
import { WorkflowRunsPage } from "./pages/WorkflowRunsPage";
```

Add after the `/logs/traces/:traceId` route (line ~143):

```tsx
<Route path="/logs/workflow-runs" element={<WorkflowRunsPage />} />
```

(The `:runId` detail route is added in Task 17.)

- [ ] **Step 3: Add the sidebar nav item**

In `AppSidebar.tsx`, inside the Logs `NavExpandable`, after the Traces `NavItem`:

```tsx
<NavItem isActive={location.pathname.startsWith("/logs/workflow-runs")}
    onClick={() => navigate("/logs/workflow-runs")}>
    Workflow Runs
</NavItem>
```

- [ ] **Step 4: Developer checkpoint**

Ask developer to run the UI and navigate to Logs → Workflow Runs; confirm the list renders and rows
link out. Expected: runs list shows triggered runs.

- [ ] **Step 5: Commit**

```bash
git add ui/src/pages/WorkflowRunsPage.tsx ui/src/App.tsx ui/src/components/AppSidebar.tsx
git commit -m "feat(ui): add Logs > Workflow Runs list page"
```

---

### Task 17: WorkflowRunDetailPage (tabs) + step timeline + route

**Files:**
- Create: `ui/src/pages/WorkflowRunDetailPage.tsx`, `ui/src/components/WorkflowStepTimeline.tsx`
- Modify: `ui/src/App.tsx`

**Interfaces:**
- Consumes: `getWorkflowRun(runId)` → `WorkflowInstanceInfo` (with `history` carrying `taskId`,
  `taskStatus`), `TraceGraph` (`traceId`, `refreshKey`), `WorkflowViewer` (`workflow`, `instance`,
  `theme`), `ExecutionLogModal` (`projectId`, `taskId`). Tabs pattern from `ProjectDetailPage`.
- Produces: route `/logs/workflow-runs/:runId` with tabs Overview / Diagram / Timeline / Execution Trace.

- [ ] **Step 1: Create the step-timeline component**

Create `ui/src/components/WorkflowStepTimeline.tsx`:

```tsx
import { Button, Label } from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { Link } from "react-router-dom";
import type { HistoryEntryInfo } from "../config/api";

interface WorkflowStepTimelineProps {
    projectId: number;
    history: HistoryEntryInfo[];
    traceId?: string;
    onViewLog: (taskId: number) => void;
}

const TASK_STATUS_COLORS: Record<string, "blue" | "green" | "grey" | "red" | "orange"> = {
    Pending: "grey",
    InProgress: "blue",
    AwaitingInput: "orange",
    Completed: "green",
    Failed: "red",
    Cancelled: "grey",
};

function stepDuration(entry: HistoryEntryInfo): string {
    if (!entry.completedOn) return "—";
    const ms = new Date(entry.completedOn).getTime()
        - new Date(entry.enteredOn).getTime();
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
    const mins = Math.floor(ms / 60_000);
    const secs = Math.round((ms % 60_000) / 1000);
    return `${mins}m ${secs}s`;
}

export function WorkflowStepTimeline({
    history, traceId, onViewLog,
}: WorkflowStepTimelineProps) {
    if (history.length === 0) {
        return <p>No steps have executed yet.</p>;
    }
    return (
        <Table aria-label="Step Timeline" variant="compact">
            <Thead>
                <Tr>
                    <Th>Step</Th>
                    <Th>Task Status</Th>
                    <Th>Entered</Th>
                    <Th>Completed</Th>
                    <Th>Duration</Th>
                    <Th>Actions</Th>
                </Tr>
            </Thead>
            <Tbody>
                {history.map((entry, idx) => (
                    <Tr key={`${entry.nodeId}-${idx}`}>
                        <Td>{entry.nodeName || entry.nodeId}</Td>
                        <Td>
                            {entry.taskStatus && (
                                <Label isCompact
                                    color={TASK_STATUS_COLORS[entry.taskStatus] || "grey"}>
                                    {entry.taskStatus}
                                </Label>
                            )}
                        </Td>
                        <Td style={{ whiteSpace: "nowrap" }}>
                            {new Date(entry.enteredOn).toLocaleString()}
                        </Td>
                        <Td style={{ whiteSpace: "nowrap" }}>
                            {entry.completedOn
                                ? new Date(entry.completedOn).toLocaleString() : "—"}
                        </Td>
                        <Td style={{ whiteSpace: "nowrap" }}>{stepDuration(entry)}</Td>
                        <Td>
                            {entry.taskId != null && (
                                <Button variant="link" isInline
                                    onClick={() => onViewLog(entry.taskId!)}>
                                    View Log
                                </Button>
                            )}
                            {entry.taskId != null && traceId && " | "}
                            {traceId && (
                                <Link to={`/logs/traces/${traceId}`}>View Trace</Link>
                            )}
                        </Td>
                    </Tr>
                ))}
            </Tbody>
        </Table>
    );
}
```

- [ ] **Step 2: Create the detail page**

Create `ui/src/pages/WorkflowRunDetailPage.tsx` (tabs pattern from `ProjectDetailPage`; `TraceGraph`
embed from `TraceDetailPage`; `WorkflowViewer` + `viewerInstance` mapping from `WorkflowTab`):

```tsx
import { useState, useEffect, useCallback, useMemo } from "react";
import { useParams, Link } from "react-router-dom";
import {
    Breadcrumb, BreadcrumbItem, Button, Flex, FlexItem, Label,
    PageSection, Tab, TabTitleText, Tabs, Title,
} from "@patternfly/react-core";
import SyncAltIcon from "@patternfly/react-icons/dist/esm/icons/sync-alt-icon";
import { WorkflowViewer } from "@apitomy/flow-ui";
import type { Workflow, WorkflowInstance as FlowInstance } from "@apitomy/flow-ui";
import { TraceGraph } from "../components/TraceGraph";
import { WorkflowStepTimeline } from "../components/WorkflowStepTimeline";
import { ExecutionLogModal } from "../components/ExecutionLogModal";
import { getWorkflowRun, type WorkflowInstanceInfo } from "../config/api";
import { sseClient, type AxiomSseEvent } from "../config/sse";
import { useEffectiveTheme } from "../hooks/useEffectiveTheme";

const STATUS_COLORS: Record<string, "blue" | "green" | "orange" | "grey" | "red"> = {
    running: "blue", waiting: "orange", completed: "green",
    failed: "red", cancelled: "grey",
};

export function WorkflowRunDetailPage() {
    const { runId } = useParams<{ runId: string }>();
    const numericRunId = Number(runId);
    const [run, setRun] = useState<WorkflowInstanceInfo | null>(null);
    const [activeTab, setActiveTab] = useState(0);
    const [refreshKey, setRefreshKey] = useState(0);
    const [isLogOpen, setIsLogOpen] = useState(false);
    const [logTaskId, setLogTaskId] = useState<number | null>(null);
    const effectiveTheme = useEffectiveTheme();

    const load = useCallback(() => {
        getWorkflowRun(numericRunId).then(setRun).catch(console.error);
    }, [numericRunId]);

    useEffect(() => { load(); }, [load]);

    useEffect(() => {
        let timeout: ReturnType<typeof setTimeout>;
        const unsubscribe = sseClient.subscribe((event: AxiomSseEvent) => {
            if (event.type === "workflow-updated"
                    && (event.data as { runId?: number }).runId === numericRunId) {
                clearTimeout(timeout);
                timeout = setTimeout(() => { load(); setRefreshKey((k) => k + 1); }, 300);
            }
        });
        return () => { clearTimeout(timeout); unsubscribe(); };
    }, [numericRunId, load]);

    const workflowContent = useMemo(
        () => (run?.workflowContent as Workflow | undefined), [run]);
    const viewerInstance = useMemo<FlowInstance | undefined>(() => {
        if (!run) return undefined;
        return {
            currentNodeId: run.currentNodeId ?? "",
            status: run.status,
            history: (run.history ?? []).map((h) => ({
                nodeId: h.nodeId, nodeName: h.nodeName,
                edgeId: "", edgeCondition: "",
                enteredOn: h.enteredOn, completedOn: h.completedOn ?? "",
                output: h.output ?? {},
            })),
            createdOn: run.startedOn,
            updatedOn: run.completedOn ?? run.startedOn,
        } as FlowInstance;
    }, [run]);

    if (!run) {
        return <PageSection><p>Loading workflow run...</p></PageSection>;
    }

    const openLog = (taskId: number) => { setLogTaskId(taskId); setIsLogOpen(true); };

    return (
        <PageSection>
            <Breadcrumb style={{ marginBottom: "12px" }}>
                <BreadcrumbItem to="#" component={() => (
                    <Link to="/logs/workflow-runs">Workflow Runs</Link>)} />
                <BreadcrumbItem isActive>Run #{run.runId}</BreadcrumbItem>
            </Breadcrumb>

            <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}
                alignItems={{ default: "alignItemsCenter" }}
                style={{ marginBottom: "16px" }}>
                <FlexItem>
                    <Title headingLevel="h1" size="lg">
                        {run.definitionName} v{run.definitionVersion}{" "}
                        <Label color={STATUS_COLORS[run.status] || "grey"}>
                            {run.status}
                        </Label>
                    </Title>
                </FlexItem>
                <FlexItem>
                    <Button variant="control" aria-label="Refresh"
                        onClick={() => { load(); setRefreshKey((k) => k + 1); }}>
                        <SyncAltIcon />
                    </Button>
                </FlexItem>
            </Flex>

            <Tabs activeKey={activeTab} onSelect={(_e, k) => setActiveTab(k as number)}>
                <Tab eventKey={0} title={<TabTitleText>Overview</TabTitleText>} />
                <Tab eventKey={1} title={<TabTitleText>Diagram</TabTitleText>} />
                <Tab eventKey={2} title={<TabTitleText>Timeline</TabTitleText>} />
                <Tab eventKey={3} title={<TabTitleText>Execution Trace</TabTitleText>} />
            </Tabs>

            <div style={{ paddingTop: "16px" }}>
                {activeTab === 0 && (
                    <dl>
                        <dt><strong>Workflow</strong></dt>
                        <dd>{run.definitionName} v{run.definitionVersion}</dd>
                        <dt><strong>Status</strong></dt>
                        <dd>{run.status}</dd>
                        <dt><strong>Started</strong></dt>
                        <dd>{new Date(run.startedOn).toLocaleString()}</dd>
                        <dt><strong>Completed</strong></dt>
                        <dd>{run.completedOn
                            ? new Date(run.completedOn).toLocaleString() : "—"}</dd>
                        {run.failureReason && (
                            <>
                                <dt><strong>Failure</strong></dt>
                                <dd>{run.failureReason}</dd>
                            </>
                        )}
                        {run.traceId && (
                            <>
                                <dt><strong>Trace</strong></dt>
                                <dd>
                                    <Link to={`/logs/traces/${run.traceId}`}>
                                        View full execution trace →
                                    </Link>
                                </dd>
                            </>
                        )}
                    </dl>
                )}

                {activeTab === 1 && workflowContent && viewerInstance && (
                    <div style={{ height: "calc(100vh - 320px)", minHeight: "500px" }}>
                        <WorkflowViewer
                            workflow={workflowContent}
                            instance={viewerInstance}
                            theme={effectiveTheme === "dark" ? "dark" : "light"}
                        />
                    </div>
                )}

                {activeTab === 2 && (
                    <WorkflowStepTimeline
                        projectId={run.projectId}
                        history={run.history ?? []}
                        traceId={run.traceId}
                        onViewLog={openLog}
                    />
                )}

                {activeTab === 3 && run.traceId && (
                    <div style={{ height: "calc(100vh - 320px)", minHeight: "500px" }}>
                        <TraceGraph traceId={run.traceId} refreshKey={refreshKey} />
                    </div>
                )}
                {activeTab === 3 && !run.traceId && (
                    <p>No execution trace is available for this run.</p>
                )}
            </div>

            <ExecutionLogModal
                isOpen={isLogOpen}
                projectId={run.projectId}
                taskId={logTaskId}
                onClose={() => setIsLogOpen(false)}
            />
        </PageSection>
    );
}
```

> Verify the exact import path for the theme hook (`useEffectiveTheme`) — `WorkflowTab`/`TraceGraph` use
> it; copy their import. Verify `ExecutionLogModal` accepts `projectId` + `taskId` (it does, per the
> multi-source modal) and renders the task log. Verify `Breadcrumb`'s link rendering against an existing
> page (`TraceDetailPage`) and match its exact idiom.

- [ ] **Step 3: Register the route in `App.tsx`**

```tsx
import { WorkflowRunDetailPage } from "./pages/WorkflowRunDetailPage";
```

```tsx
<Route path="/logs/workflow-runs/:runId" element={<WorkflowRunDetailPage />} />
```

- [ ] **Step 4: Developer checkpoint**

Ask developer to run the UI, open a run from the list, and exercise all four tabs (Overview, Diagram,
Timeline with View Log/View Trace, Execution Trace). Expected: tabs render; log modal opens; trace graph
renders.

- [ ] **Step 5: Commit**

```bash
git add ui/src/pages/WorkflowRunDetailPage.tsx \
        ui/src/components/WorkflowStepTimeline.tsx ui/src/App.tsx
git commit -m "feat(ui): add tabbed workflow run detail page with step timeline"
```

---

### Task 18: WorkflowTab — "View run details →" link

**Files:**
- Modify: `ui/src/components/WorkflowTab.tsx`

**Interfaces:**
- Consumes: `instance.id` (the run id) from `WorkflowInstanceInfo`. No timeline is added here (spec:
  project page stays lightweight).

- [ ] **Step 1: Add the link in the header Flex**

In the loaded-instance header `Flex` (the block with the definition name + status labels + Cancel
button), add a `FlexItem` with a link to the run detail page. Place it next to the Cancel button:

```tsx
<FlexItem>
    <Link to={`/logs/workflow-runs/${instance.id}`}>
        View run details →
    </Link>
</FlexItem>
```

Add `import { Link } from "react-router-dom";` if not already imported.

- [ ] **Step 2: Developer checkpoint**

Ask developer to open a project's Workflow tab and confirm the "View run details →" link navigates to
the run detail page. Expected: link present and working; no timeline added on the project page.

- [ ] **Step 3: Commit**

```bash
git add ui/src/components/WorkflowTab.tsx
git commit -m "feat(ui): link project Workflow tab to the run detail page"
```

---

### Task 19: File Apitomy Flow enhancement issues

Not a code task — a required side-deliverable from the spec. File GitHub issues on `Apitomy/apitomy-flow`
for the enhancements the diagram drill-down depends on. Do this once the UI work confirms the exact gaps.

**Interfaces:**
- Produces: GitHub issues; note their URLs in the PR description.

- [ ] **Step 1: Confirm the flow-ui gap**

Inspect `@apitomy/flow-ui` `WorkflowViewer` types (node_modules or the flow repo) to confirm it exposes
no node-click / node-action callback. If it truly lacks one, that's issue #1.

- [ ] **Step 2: File the node-click callback issue**

Using `mcp__axiom-tools__fetch-github-repo-labels` to pick appropriate `type/`/`area/` labels, open an
issue titled e.g. *"WorkflowViewer: expose an onNodeClick callback for host-driven node actions"*,
describing the Axiom need (node→execution-log drill-down on the run detail Diagram tab), the current
props (`workflow`, `instance`, `theme`), and the desired API (`onNodeClick(nodeId: string)` or pluggable
node actions). Use the `fetch-github-issue`/labels tools; do not include Claude attribution.

- [ ] **Step 3: File the history edge-metadata issue**

Open a second issue: *"WorkflowInstance history should retain edgeId / edgeCondition for viewer edge
highlighting"* — Axiom currently maps `edgeId: ""`, `edgeCondition: ""` because the model/engine history
drops them. Describe the need for edge highlighting on the diagram.

- [ ] **Step 4: Record issue URLs**

Note both issue URLs in the eventual PR description and, if useful, as a code comment near the
`viewerInstance` mapping in `WorkflowRunDetailPage.tsx`.

---

## Self-Review

**Spec coverage check** (each spec section → task):

- Data model (rename, drop unique, `trace_id`, `task.node_id`, `workflow_run_id`, index, business rule) →
  Tasks 1-3 (schema/entities) + Task 4 (active-run guard).
- Backend trigger trace root → Task 4. Node task wiring (`workflowRunId`/`nodeId`/`traceId` + task node)
  → Task 5. Terminal `completeTrace` + `completedOn` → Task 6. Best-effort/additive tracing → Tasks 4-6
  (try/catch around all `TraceService` calls). The premature-completion hazard → Task 7. SSE enrichment
  → Task 8.
- API #1 (enriched `/projects/{id}/workflow`, latest run, history `taskId`/`taskStatus`) → Tasks 9 + 12.
  API #2 (`/workflow-runs` list + `{runId}`) → Tasks 10 + 13. API #4 (`/workflow-definitions/{id}/runs`)
  → Tasks 11 + 14. API #5 (`Task` bean `workflowRunId`/`nodeId`) → Task 9 (+ mapping in Task 12).
  `WorkflowRunSummary` schema → Task 10. Reuse of `/tasks/{id}/log` and `/traces/{id}` → Tasks 17
  (`ExecutionLogModal`, `TraceGraph`).
- UI project page link, no timeline → Task 18. Logs → Workflow Runs list → Task 16. Run detail with
  Overview/Diagram/Timeline/Execution Trace tabs → Task 17. Flow enhancement issues → Task 19.
  Deferred per-definition UI → correctly not built (endpoint only, Tasks 11/14).
- Testing (run history, active-run 409, trace wiring, REST list/detail/definition-runs, enriched project
  workflow) → Tasks 4, 5, 7, 12, 13, 14. Manual build/test execution → Global Constraints.

**Type consistency check:** `WorkflowRunEntity`, `workflowRunId`, `nodeId`, `traceId` used consistently
across Tasks 2-14. Bean field names (`runId`, `traceId`, `taskId`, `taskStatus`, `workflowRunId`,
`nodeId`, `WorkflowRunSummary.*`) match between openapi (Tasks 9-11), Java impls (Tasks 12-14), and
api.ts (Task 15). `WorkflowRunBeanMapper.toBean/toSummary/resolveProjectNames/resolveDefinitionNames`
are defined in Task 13, made public and reused in Task 14. `fetchWorkflowRuns`/`getWorkflowRun`/
`fetchWorkflowDefinitionRuns` defined in Task 15, consumed in Tasks 16-17.

**Placeholder scan:** No TBD/TODO. Each code step carries concrete code. Two flagged verification points
(SSE client subscribe vs raw EventSource; theme-hook import path) are explicit "verify against the actual
file" instructions with a concrete fallback, not placeholders.

**Known cross-task dependency:** Task 4's `traceId` test assertion depends on Tasks 9 & 12 (bean field +
mapping). The plan notes this and offers a staged assertion so tasks stay independently runnable.

---

## Execution Handoff

Plan complete and saved to
`docs/superpowers/plans/2026-08-27-workflow-execution-observability.md`.
