# Workflow Execution Observability — Design

- **Date:** 2026-08-27
- **Status:** Approved (pending implementation plan)
- **Branch:** `feat/workflow-execution-observability`

## Problem

Users cannot effectively determine what happened during a workflow execution — neither while it is
running nor after it completes. The current state:

- A workflow run persists as a single `WorkflowInstanceEntity` per project (unique `project_id`), so
  re-triggering overwrites the previous run. There is no run history.
- The execution `history` (per-node timestamps + output) lives only inside an opaque serialized
  `instance_state` blob.
- Workflow action nodes run as `TaskEntity` rows with a rich `executionLog`, but tasks store **no**
  `nodeId`, and there is no UI path from a workflow node to the task's log. The log the user actually
  needs is unreachable from the workflow view.
- The tracing subsystem (`TraceEntity` / `TraceNodeEntity` / `TraceService`) — which already builds a
  tree of task and MCP-tool-call activity — is completely disconnected from workflows. Workflow-spawned
  tasks are created without a `traceId`, so they never join a trace tree.
- The UI shows only a status badge, the current node, and a diagram; there is no step timeline, no
  node→log drill-down, and no view of past runs.

## Goal

Let a user review a workflow execution — live and after completion — including which node is running,
which nodes already ran and their outcomes, and the full per-node execution log and tool activity.
Provide run history via a new **Logs → Workflow Runs** area, while keeping the project details page a
lightweight current-run glance.

## Approach

**Hybrid (approach C):** a run-history table is the source of truth for workflow-run identity/status,
wired into the existing tracing subsystem so the deep agent-activity drill-down comes for free.

- Run-history persistence owns workflow identity, status, current node, and history.
- Each run gets a trace root; workflow-spawned tasks receive a `traceId` and a task trace node, so
  MCP tool calls nest under the node automatically — reusing existing task/tool instrumentation.
- Precise **node → task → execution-log** linkage via a new `task.node_id`.

Rejected alternatives: (A) dedicated workflow-run persistence with no tracing — loses the deep
tool-call view; (B) trace-centric where a run *is* a trace — traces do not naturally hold
workflow-specific identity/lifecycle.

## Data Model

New Flyway migration `V54` (repurposing the day-old `V53` schema):

- Rename `workflow_instance` → **`workflow_run`**; entity `WorkflowInstanceEntity` →
  **`WorkflowRunEntity`**. This also resolves the name collision between the engine's
  `WorkflowInstance`, the API bean `WorkflowInstance`, and the entity.
- **Drop `UNIQUE(project_id)`** — many runs per project.
- Add **`trace_id UUID`** (nullable) — links a run to its execution trace.
- Keep `instance_state`, `status`, `current_node_id`, `failure_reason`, `started_on`, `completed_on`,
  `definition_id`, `definition_version`.
- Rename `task.workflow_instance_id` → **`task.workflow_run_id`**; add **`task.node_id VARCHAR(255)`**.
- Rename the sequence; add index **`(project_id, started_on DESC)`** for latest-run and per-project
  filtering.

**Business rule:** at most one *active* (non-terminal) run per project; terminal runs accumulate as
history. Enforced in `triggerWorkflow`.

**"Current run" for the project page** = the most recent run for the project by `started_on`. No
separate pointer column.

## Backend: Run Lifecycle & Trace Wiring

All changes centered in `WorkflowExecutionService`.

**On trigger (`triggerWorkflow`):**
- Reject with **409** if an active run already exists for the project.
- Insert a new `WorkflowRunEntity`.
- Call `TraceService.createTrace(traceType="workflow", summary=<definition name>, projectId,
  rootNodeType="workflow", rootNodeSummary=<definition name>, rootEntityType="workflow-run",
  rootEntityId=run.id)` and store `run.traceId`.

**On node task creation (`createTaskForCurrentNode`):**
- Set `task.workflowRunId = run.id`, `task.nodeId = instance.currentNodeId()`,
  `task.traceId = run.traceId`.
- Create a **`task` trace node** under the run's root node (mirroring how `PipelineOrchestrator` sets
  up event-pipeline task nodes), summarized with the node name + action type.
- `TaskExecutionService` already looks up the task trace node by `traceId`/`entityId`, injects
  `AXIOM_TRACE_ID` / `AXIOM_PARENT_NODE_ID`, and completes it — so MCP tool calls nest automatically
  with no new instrumentation.

**On run terminal (`onTaskCompleted` / `cancelWorkflow`):**
- Set `run.completedOn` and call `TraceService.completeTrace(run.traceId, status)`.
- Cancellation additionally marks the active task failed (existing behavior).

**Trace is best-effort/additive:** `TraceService` runs in `requiringNew()`. If trace creation fails,
the run proceeds with `trace_id = null`; node→log drill-down still works via `taskId`.

**SSE:** extend `workflow-updated` from `{projectId}` to `{projectId, runId, status}` (additive;
existing `WorkflowTab` filter-by-`projectId` keeps working). `trace-updated` already fires from
`TraceService`.

**Known limitation (upstream):** run *status* is derived from the engine instance, so until
[apitomy-flow#38](https://github.com/Apitomy/apitomy-flow/issues/38) is fixed and a new Flow released,
a failed node yields a "completed" run badge. The failed task and its trace node are recorded
truthfully regardless, so drill-down is honest even before the fix. We will inherit the corrected run
status from the new Flow release rather than duplicate engine logic in Axiom.

## API Surface (contract-first)

All operations are added to `openapi.json` first, then implemented against the generated interfaces.

1. **`GET /projects/{projectId}/workflow`** (kept) — now returns the *latest* run. The
   `WorkflowInstance` bean gains **`runId`** and **`traceId`**; each `HistoryEntry` gains **`taskId`**
   and **`taskStatus`** (populated by joining the run's tasks on `node_id`).
2. **`GET /workflow-runs`** (new, tag `WorkflowRuns`) — global paginated list, mirroring
   `/scheduled-jobs/runs`. Query params: `projectId?`, `status?`, `page`, `pageSize`. Returns
   `WorkflowRunSummary[]` + total.
3. **`GET /workflow-runs/{runId}`** (new) — full run detail; same enriched shape as #1.
4. **`GET /workflow-definitions/{workflowDefinitionId}/runs`** (new) — paginated runs for a specific
   definition across all versions. Query params: `status?`, `page`, `pageSize`. Same
   `WorkflowRunSummary[]` + total.
5. **`Task` bean** gains **`workflowRunId`** and **`nodeId`**.

**`WorkflowRunSummary`** schema: `{ runId, projectId, projectName, definitionName, definitionVersion,
status, currentNodeName, startedOn, completedOn, traceId }`.

**Reuse, not rebuild:** per-node execution logs stay on the existing
`GET /projects/{projectId}/tasks/{taskId}/log`; the deep tool-call tree stays on
`GET /traces/{traceId}`. The new endpoints expose run identity + the node→task→trace linkage that ties
them together.

**Impls:** enrich `toWorkflowInstanceBean` in `ProjectsResourceImpl`; add a new
`WorkflowRunsResourceImpl` for #2/#3; add #4 to the existing `WorkflowDefinitionsResourceImpl`, reusing
a shared run-summary query/mapper.

## UI

### Apitomy Flow dependency

The node→log drill-down on the diagram likely requires enhancements to `@apitomy/flow-ui` (the
`WorkflowViewer` renders its own node-detail panel with no host callback; we need something like an
`onNodeClick(nodeId)` callback or pluggable node actions). There may also be a flow-engine/model item:
history currently drops `edgeId` / `edgeCondition`, which the viewer needs for edge highlighting.

**Deliverable:** during implementation, identify each required Apitomy Flow enhancement and file it as
a GitHub issue on `Apitomy/apitomy-flow`.

**Resilience:** the step-timeline is our own component, so it can offer node→log drill-down independent
of flow-ui. If the flow-ui callback is not yet available, users still get drill-down via the Timeline
tab while the diagram drill-down waits on a Flow release.

### Project details page (`WorkflowTab`)

Kept lightweight — current-run diagram, status badge, current node, cancel button, and **no timeline**.
Add a prominent **"View run details →"** link to `/logs/workflow-runs/{runId}` for the current run.
Rich review lives on the run detail page.

### New Logs → Workflow Runs pages

Registered in `AppSidebar` under **Logs** (route `/logs/workflow-runs`), following the Job Runs /
Traces templates.

- **`WorkflowRunsPage`** (list): table from `GET /workflow-runs` — project, definition + version,
  status badge, current node, started/completed, duration. Filters for project + status; row click →
  detail. Live via `workflow-updated` SSE (now carrying `runId` / `status`).
- **`WorkflowRunDetailPage`** (`/logs/workflow-runs/:runId`), sourced from `GET /workflow-runs/{runId}`,
  with PatternFly tabs (same pattern as `ProjectDetailPage`):
  - **Overview** (default) — run metadata: definition + version, status, started/completed, duration,
    trigger input/context summary, failure reason, aggregate cost/tokens across the run's tasks, and a
    "View full execution trace →" link to `/logs/traces/{traceId}`.
  - **Diagram** — the `WorkflowViewer` with node→log drill-down (pending the flow-ui enhancement).
  - **Timeline** — full ordered step timeline (node name, status, timestamps, duration, View-log /
    View-trace per step). Our own component; drill-down here is not blocked on flow-ui.
  - **Execution Trace** — the full trace tree (run → node → task → MCP tool calls) embedded inline,
    reusing the Traces detail view.

**Reuse:** `ExecutionLogModal` and `WorkflowViewer` are reused as-is; the step-timeline is the one
genuinely new shared component.

**Deferred:** a per-definition runs UI. The `/workflow-definitions/{id}/runs` endpoint ships, but no
page is built in this pass.

## Testing

JUnit 5 / `@QuarkusTest`, matching the existing `WorkflowInstanceResourceTest`:

- Run history: multiple runs persist; "latest run for project" query; active-run **409** guard.
- Trace wiring: trigger creates a trace root; `createTaskForCurrentNode` sets `node_id` /
  `workflow_run_id` / `trace_id` and a task trace node under the root; terminal run calls
  `completeTrace` and sets `completedOn`.
- REST: `/workflow-runs` (filters + pagination), `/workflow-runs/{id}`,
  `/workflow-definitions/{id}/runs`, and enriched `/projects/{id}/workflow` (history entries carry
  `taskId`).
- Update existing workflow tests for the rename.
- Frontend: follow existing UI test conventions (if present) for the two new pages, the SSE handler,
  and node-click → log modal.

Compilation, bean regeneration, and test runs are handled manually by the developer.

## Out of Scope

- Fixing the run-status-on-node-failure defect (upstream [apitomy-flow#38](https://github.com/Apitomy/apitomy-flow/issues/38)).
- A per-definition runs UI page.
- Per-node retry semantics (multiple tasks per node) — the model is forward-compatible but retries are
  not built here.
