# Apitomy Flow 2.x Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade Axiom from apitomy-flow-engine 1.0.4 / @apitomy/flow-ui 1.0.3 to 2.0.0, then adopt
parallel fork/join execution and the new flow-ui features (simulation, diff viewer, import/export).

**Architecture:** Axiom uses Flow as an in-process, stateless orchestration library. Instance state
is serialized JSON in `WorkflowRunEntity.instanceState`; a stub `NodeExecutorProvider` parks every
node as PENDING and Axiom's Task system does the real work. The 2.x upgrade touches one service
(`WorkflowExecutionService`), two mappers, one REST resource, the OpenAPI spec, and the UI pages
that render workflow runs.

**Tech Stack:** Java 17+/Quarkus, Maven, JUnit 5, Jackson; React/TypeScript UI with
`@apitomy/flow-ui`.

**Spec:** This document (analysis section below serves as the spec). Flow 2.x reference docs:
`~/git/apitomy/apitomy-flow/docs/user-guide/parallel-fork-join.md`.

## Global Constraints

- Flow coordinates: `io.apitomy:apitomy-flow-engine:2.0.0` and `@apitomy/flow-ui@2.0.0`.
  **Note:** as of writing, only `2.0.0-SNAPSHOT` exists locally (no `v2.0.0` tag in apitomy-flow).
  Confirm the release artifact is available before starting; use `2.0.0-SNAPSHOT` for local dev.
- API-first: any REST payload change starts in `common/api/src/main/resources/openapi.json`, then
  `mvn install` to regenerate beans, then implement (see CLAUDE.md).
- Back-compat: existing 1.x `instanceState` JSON in the DB must keep deserializing (Flow 2.x
  null-coerces missing `activeBranches`/`joinArrivals`; do not break this).
- 4-space indent, explicit types, Javadoc on public methods, JUnit 5.

---

## Background: What changed in Flow 2.0.0

**Breaking (affects Axiom):**

1. `WorkflowInstance` record gained `List<ActiveBranch> activeBranches` and
   `Map<String, List<String>> joinArrivals` (inserted before `failureReason`). Jackson round-trip of
   old JSON still works (nulls coerced to empty).
2. `instance.currentNodeId()` is now **nullable**: non-null only when exactly one branch is active.
   Axiom reads it in `WorkflowExecutionService` (task creation, `persistInstanceState`) and
   `WorkflowRunBeanMapper`.
3. `completeCurrentNode(...)` throws `IllegalStateException` when multiple branches are waiting.
   New canonical API: `completeNode(workflow, instance, nodeId, result)`.
4. `getActionInfo`/`getHumanTaskInfo` gained nodeId-addressed overloads:
   `getActionInfo(workflow, instance, nodeId)`. The 2-arg forms only work with a single active
   branch.
5. Validation: warning `UNCONDITIONAL_MULTIPLE_EDGES` removed; multiple unconditional edges now
   **fork**. New ERROR codes: `MIXED_FORK_EDGES`, `FORK_WITHOUT_JOIN`, `UNBALANCED_PARALLEL`,
   `CROSSING_PARALLEL_REGIONS`, `PARALLEL_BRANCH_REACHES_END`, `PARALLEL_REGION_CYCLE`.
6. `HistoryEntry` gained `branchId` (8th component); 7-arg constructor retained (branchId=null).
7. flow-ui TS types: `WorkflowInstance.currentNodeId: string | null`, plus required
   `activeBranches: ActiveBranch[]` and `joinArrivals: Record<string, string[]>`.

**New features:**

- Parallel fork/join execution (engine): `ActiveBranch`, `ParallelRegions.analyze(Workflow)`,
  branch-aware history.
- flow-ui: `SimulationPanel` (interactive simulation + EL condition testing), `WorkflowDiffViewer`,
  import/export (`serializeWorkflow`, `parseWorkflow`, `downloadWorkflowJson`), canvas PNG export,
  parallel-region rendering hints.

NodeType is unchanged (RECEIVE_EVENT/WAIT already existed in 1.0.4).

---

## Phase 1 — Mechanical upgrade (no new behavior)

### Task 1: Bump engine dependency and get a clean compile

**Files:**
- Modify: `app/pom.xml` (apitomy-flow-engine `1.0.4` → `2.0.0`)

**Interfaces:**
- Produces: compiling build against Flow 2.x; later tasks rely on `engine.completeNode(...)` and
  `instance.activeBranches()` being available.

- [ ] **Step 1:** Edit `app/pom.xml`, change the `apitomy-flow-engine` version to `2.0.0`
  (or `2.0.0-SNAPSHOT` until released).
- [ ] **Step 2:** Run `mvn -q compile -pl app -am`. Expected: compiles (Axiom builds instances via
  Jackson/engine, not positional constructors). If any positional `WorkflowInstance` or
  `HistoryEntry` construction fails, switch to the builder / 8-arg form.
- [ ] **Step 3:** Run `mvn -q test -pl app`. Record failures for Tasks 2–4; do not fix here.
- [ ] **Step 4:** Commit: `git commit -m "build: upgrade apitomy-flow-engine to 2.0.0"`

### Task 2: Branch-aware node completion in WorkflowExecutionService

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowExecutionServiceTest.java` (or nearest
  existing test class covering `onTaskCompleted`)

**Interfaces:**
- Consumes: `TaskEntity.nodeId` (already persisted per task at creation time).
- Produces: `onTaskCompleted` advances the correct branch via
  `engine.completeNode(workflow, instance, task.nodeId, result)`.

- [ ] **Step 1:** Write a failing test: build a linear workflow, start it, complete the task, and
  assert the run advances — but assert the service calls the nodeId-addressed path by using a
  workflow where the task's `nodeId` is passed explicitly (after Step 3 the old 3-arg call is gone,
  so an existing green test plus a new parallel test in Task 5 is acceptable coverage; at minimum
  assert `onTaskCompleted` still completes a single-branch run).
- [ ] **Step 2:** Run the test; expected FAIL/compile error if referencing new behavior.
- [ ] **Step 3:** In `onTaskCompleted` (~line 235), replace
  `workflowEngine.completeCurrentNode(workflow, instance, result)` with
  `workflowEngine.completeNode(workflow, instance, task.nodeId, result)`.
- [ ] **Step 4:** In the task-creation paths (~lines 339–396), replace the 2-arg
  `getHumanTaskInfo(workflow, instance)` / `getActionInfo(workflow, instance)` with the 3-arg
  nodeId overloads, and set `task.nodeId` from the branch's node id rather than
  `instance.currentNodeId()` (see Task 5 for the multi-branch loop; for now pass
  `instance.currentNodeId()` guarded non-null).
- [ ] **Step 5:** Run tests: `mvn -q test -pl app -Dtest=WorkflowExecutionServiceTest`. Expected: PASS.
- [ ] **Step 6:** Commit: `feat: use branch-addressed completeNode/getInfo APIs`

### Task 3: Nullable currentNodeId in persistence and mappers

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java` (~line 436,
  `persistInstanceState`)
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowRunBeanMapper.java` (lines 57, 84–88)
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowRunBeanMapperTest.java` (create if absent)

**Interfaces:**
- Produces: run summary/detail beans tolerate `currentNodeId == null` (renders no current node
  name); `WorkflowRunEntity.currentNodeId` column stores null when parallel-parked.

- [ ] **Step 1:** Write failing test: map a `WorkflowRunEntity` whose `currentNodeId` is null and
  whose instance JSON contains two `activeBranches`; assert no NPE and `currentNodeName` is null.
- [ ] **Step 2:** Run test; expected FAIL (NPE or missing handling).
- [ ] **Step 3:** Guard all `instance.currentNodeId()` dereferences with null checks; verify the DB
  column for `workflow_run.current_node_id` is nullable (check DDL under
  `app/src/main/resources/db`; add a migration only if it is NOT NULL).
- [ ] **Step 4:** Run test; expected PASS.
- [ ] **Step 5:** Commit: `fix: tolerate null currentNodeId for parallel-parked runs`

### Task 4: Publish-time validation — new parallel error codes

**Files:**
- Modify (if needed): `app/src/main/java/io/apitomy/axiom/app/rest/WorkflowDefinitionsResourceImpl.java`
- Test: existing publish/validation tests

- [ ] **Step 1:** Review the validator usage: Axiom already filters
  `ValidationSeverity.ERROR` generically, so the new codes flow through automatically. Add one test
  publishing a definition with a fork whose branches never rejoin; assert 400 and that the response
  body contains problem code `FORK_WITHOUT_JOIN`.
- [ ] **Step 2:** Run test; expected PASS with no code change (or minimal message mapping tweaks).
- [ ] **Step 3:** Remove any Axiom-side handling/suppression of the deleted
  `UNCONDITIONAL_MULTIPLE_EDGES` warning if present (grep for it).
- [ ] **Step 4:** Commit: `test: cover Flow 2.x parallel validation codes on publish`

### Task 5: flow-ui bump and TS type fixes

**Files:**
- Modify: `ui/package.json` (`@apitomy/flow-ui` `1.0.3` → `2.0.0`)
- Modify: `ui/src/pages/WorkflowRunDetailPage.tsx`, `ui/src/components/WorkflowTab.tsx` (nullable
  `currentNodeId`, new required `activeBranches`/`joinArrivals` on any locally-constructed
  `WorkflowInstance` values)

- [ ] **Step 1:** Bump the dependency, run `npm install` in `ui/`.
- [ ] **Step 2:** Run `npm run build` (or `tsc --noEmit`). Fix type errors: treat `currentNodeId`
  as `string | null`; when constructing instance objects for the viewer, include
  `activeBranches: []` and `joinArrivals: {}` defaults for legacy data.
- [ ] **Step 3:** Run UI tests/lint; expected PASS.
- [ ] **Step 4:** Commit: `build: upgrade @apitomy/flow-ui to 2.0.0`

---

## Phase 2 — Adopt parallel fork/join in Axiom

### Task 6: Spawn one task per active branch

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowExecutionParallelTest.java` (new)

**Interfaces:**
- Consumes: `instance.activeBranches()` → `List<ActiveBranch>` where
  `ActiveBranch(String branchId, String nodeId)`.
- Produces: after `startWorkflow`/`completeNode` returns `InstanceStatus.WAITING`, Axiom creates a
  `TaskEntity` for **every** active branch node that does not already have an open task
  (idempotent on nodeId), instead of a single task for `currentNodeId`.

- [ ] **Step 1:** Write failing test: workflow `start → fork {actionA, actionB} → join → end`.
  Trigger it; assert two open `TaskEntity` rows (nodeIds actionA and actionB). Complete actionA;
  assert run still WAITING and actionB's task still open. Complete actionB; assert the join fires
  and the run reaches COMPLETED (or the post-join node's task is created).
- [ ] **Step 2:** Run test; expected FAIL (only one task created today).
- [ ] **Step 3:** Refactor the "create next task" logic into
  `private void createTasksForActiveBranches(Workflow workflow, WorkflowInstance instance, WorkflowRunEntity run)`
  that loops `instance.activeBranches()`, skips nodes with an existing open task for the run, and
  uses `getActionInfo(workflow, instance, branch.nodeId())` /
  `getHumanTaskInfo(workflow, instance, branch.nodeId())` per node type. Call it from both the
  trigger path and the `onTaskCompleted` WAITING branch.
- [ ] **Step 4:** Run test; expected PASS. Run full `mvn -q test -pl app`.
- [ ] **Step 5:** Commit: `feat: parallel fork/join — one task per active branch`

### Task 7: Expose active branches over the REST API (API-first)

**Files:**
- Modify: `common/api/src/main/resources/openapi.json` — add to the workflow-run detail schema:
  `activeBranches: [{ branchId: string, nodeId: string, nodeName?: string }]`, make
  `currentNodeId`/`currentNodeName` nullable, and add optional `branchId` to the run history entry
  schema.
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowRunBeanMapper.java`
- Test: extend `WorkflowRunBeanMapperTest`

- [ ] **Step 1:** Edit the OpenAPI spec (prefer apicurio-data-models MCP tools), run `mvn install`
  to regenerate beans in `common/api/target/generated-sources/jaxrs/`.
- [ ] **Step 2:** Write failing mapper test: entity with two active branches → bean lists both with
  resolved node names via `workflow.findNodeById(...)`; history entries carry `branchId`.
- [ ] **Step 3:** Implement mapping in `WorkflowRunBeanMapper` from
  `instance.activeBranches()` and `HistoryEntry.branchId()`.
- [ ] **Step 4:** Run tests; expected PASS.
- [ ] **Step 5:** Commit: `feat(api): expose active branches and branch-aware history on runs`

### Task 8: Run-detail UI shows parallel state

**Files:**
- Modify: `ui/src/pages/WorkflowRunDetailPage.tsx`, `ui/src/components/WorkflowTab.tsx`

- [ ] **Step 1:** Pass the full instance (with `activeBranches`/`joinArrivals`) to
  `WorkflowViewer` so flow-ui's parallel-region rendering highlights all active nodes.
- [ ] **Step 2:** Replace single "current node" labels with a list of active branch node names when
  `activeBranches.length > 1`.
- [ ] **Step 3:** Build, lint, visual check (Chrome DevTools MCP against the dev server).
- [ ] **Step 4:** Commit: `feat(ui): render parallel branches on run detail`

---

## Phase 3 — Optional new flow-ui features (independent; prioritize with user)

### Task 9: Workflow simulation panel in the definition editor
- Add flow-ui's `SimulationPanel` to `ui/src/pages/WorkflowDefinitionDetailPage.tsx` (e.g. a
  "Simulate" toggle next to the editor) so authors can dry-run definitions and test EL conditions
  before publishing. No backend changes.

### Task 10: Visual diff between definition versions
- Axiom already versions definitions (`WorkflowDefinitionVersionEntity`). Add a "Compare versions"
  view using `WorkflowDiffViewer` + `workflowDiff`, fed by two version-content payloads from the
  existing definition-version endpoints.

### Task 11: Import/export of definitions
- Wire `downloadWorkflowJson`/`workflowFileName` for export and `parseWorkflow` for import on the
  definition detail page; on import, PUT the parsed content through the existing draft-update
  endpoint so Flow validation still gates publishing. Optionally add canvas PNG export via
  flow-ui's `exportImage` utility.

---

## Risks / notes

- **Release availability:** apitomy-flow HEAD is `2.0.0-SNAPSHOT` with no `v2.0.0` tag; confirm
  the 2.0.0 release exists in the repository Axiom resolves from before merging.
- **In-flight runs:** old serialized instances deserialize fine (empty branch collections), and
  single-branch semantics are unchanged, so no data migration is needed.
- **DB:** verify `workflow_run.current_node_id` is nullable before Phase 2 ships.
- **Ordering:** Phase 1 tasks 1–5 must land together (one PR is fine); Phase 2 and each Phase 3
  task are independently shippable.
