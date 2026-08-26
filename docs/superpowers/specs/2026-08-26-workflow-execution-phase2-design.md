# Workflow Execution — Phase 2 Design

**Date:** 2026-08-26
**Epic:** Custom Workflow Feature for Axiom
**Prerequisite:** Phase 1 (Workflow Definitions) — complete and merged

## Summary

Make workflow definitions runnable. Users trigger a published workflow on a project, and Axiom
executes it — advancing through action nodes by creating Axiom tasks, tracking progress in a
persisted workflow instance, and displaying real-time execution state in a `WorkflowViewer` on
the project detail page.

Phase 2 supports **start**, **end**, and **action** node types only. Human-task, receive-event,
and wait nodes are deferred to later phases; workflows containing those node types are rejected
at trigger time.

Apitomy Flow provides:
- **Java engine** (`io.apitomy:apitomy-flow-engine`) — stateless execution engine with async
  support via `NodeResult(PENDING)`
- **React UI** (`@apitomy/flow-ui`) — `WorkflowViewer` component for read-only instance
  visualization with execution history

## Data Model

### `workflow_instance` table

Stores one workflow instance per project. The instance state is the serialized Flow
`WorkflowInstance` record, with key fields denormalized for efficient queries.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | `bigint` | PK, auto | |
| `project_id` | `bigint` | FK → project, NOT NULL, UNIQUE | One instance per project |
| `definition_id` | `bigint` | FK → workflow_definition, NOT NULL | Which definition was used |
| `definition_version` | `int` | NOT NULL | Pinned version at trigger time |
| `instance_state` | `text` | NOT NULL | Serialized Flow `WorkflowInstance` JSON |
| `status` | `varchar` | NOT NULL | Denormalized: running, waiting, completed, failed, cancelled |
| `current_node_id` | `varchar` | | Denormalized for queries |
| `failure_reason` | `text` | | Set on failure |
| `started_on` | `timestamp` | NOT NULL | |
| `completed_on` | `timestamp` | | |

**Sequence:** `workflow_instance_SEQ START WITH 1 INCREMENT BY 50`

**Constraints:**
- `UNIQUE (project_id)` — enforces one-instance-per-project
- FK `project_id` → `project(id)`
- FK `definition_id` → `workflow_definition(id)`

### Modification to `task` table

Add a nullable column to link workflow-spawned tasks back to their instance:

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `workflow_instance_id` | `bigint` | FK → workflow_instance(id), nullable | Links task to its workflow instance |

### Persistence pattern

After every engine call (`startWorkflow`, `completeCurrentNode`, `cancelWorkflow`), the
returned `WorkflowInstance` is serialized to JSON via Jackson and saved to `instance_state`.
The denormalized `status`, `current_node_id`, and `failure_reason` columns are updated at the
same time.

To retrieve the workflow definition content at runtime, join through
`definition_id` + `definition_version` → `workflow_definition_version.content`.

## API Design

### Workflow Instance endpoints

All scoped under projects since instances are one-per-project.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/projects/{projectId}/workflow` | Trigger a workflow on this project |
| `GET` | `/api/v1/projects/{projectId}/workflow` | Get the workflow instance for this project |
| `DELETE` | `/api/v1/projects/{projectId}/workflow` | Cancel a running/waiting instance |

### Trigger request

`POST /api/v1/projects/{projectId}/workflow`

**Request body** (`TriggerWorkflow`):
```json
{
    "workflowDefinitionId": 5
}
```

**Behavior:**
1. Check no instance already exists for this project → 409 Conflict if one does.
2. Load the workflow definition and its current published version → 400 if never published.
3. Deserialize the version content into a Flow `Workflow` object.
4. Validate the workflow contains only supported node types (start, end, action) → 400 with
   error message if it contains human-task, receive-event, or wait nodes.
5. Build initial context from project data: `projectId`, `projectName`, `repository`, `ref`.
6. Call `WorkflowEngine.startWorkflow(workflow, context)`.
7. Persist the `WorkflowInstanceEntity`.
8. If the engine advanced to an action node and returned WAITING, call
   `createTaskForCurrentNode()` to enqueue the first Axiom task.
9. Return the `WorkflowInstance` response.

**Response:** 200 with `WorkflowInstance`.

**Error responses:**
- 404 — project not found
- 400 — definition not published, or workflow contains unsupported node types
- 409 — project already has a workflow instance

### Get instance

`GET /api/v1/projects/{projectId}/workflow`

**Response:** 200 with `WorkflowInstance`. 404 if no instance exists.

### Cancel instance

`DELETE /api/v1/projects/{projectId}/workflow`

**Behavior:**
1. Load the instance → 404 if not found.
2. If status is already completed, failed, or cancelled → 409.
3. Call `WorkflowEngine.cancelWorkflow()`.
4. Persist updated state.
5. Cancel any in-progress Axiom task linked to the instance.

**Response:** 204 No Content.

### Response schemas

**WorkflowInstance:**
```json
{
    "id": 1,
    "projectId": 42,
    "definitionId": 5,
    "definitionVersion": 3,
    "definitionName": "CVE Triage",
    "status": "waiting",
    "currentNodeId": "action-1",
    "currentNodeName": "Analyze Issue",
    "failureReason": null,
    "workflowContent": {
        "id": "wf-1",
        "name": "CVE Triage",
        "nodes": [],
        "edges": []
    },
    "context": { "projectName": "...", "repository": "..." },
    "history": [
        {
            "nodeId": "start-1",
            "nodeName": "Start",
            "enteredOn": "2026-08-26T12:00:00Z",
            "completedOn": "2026-08-26T12:00:01Z",
            "output": null
        }
    ],
    "startedOn": "2026-08-26T12:00:00Z",
    "completedOn": null
}
```

The `context` and `history` fields come directly from the deserialized Flow `WorkflowInstance`.
The `workflowContent` is the frozen workflow definition from the pinned version — the UI needs
this to render the `WorkflowViewer` graph alongside the instance state.
The `definitionName` and `currentNodeName` are convenience fields resolved at response time.

**TriggerWorkflow:**
```json
{
    "workflowDefinitionId": 5
}
```

### Modification to existing Project response

Add a boolean field `hasWorkflowInstance` to the existing `Project` bean so the UI knows
whether to show workflow status on the project detail page without an extra API call.

## Backend Architecture

### `WorkflowExecutionService`

New `@ApplicationScoped` service in the `app` module. Central orchestrator for all workflow
execution.

**Key methods:**

- **`triggerWorkflow(long projectId, long definitionId)`** — Full trigger flow as described in
  the API section above. Loads the published version content, validates node types, builds
  context, calls `WorkflowEngine.startWorkflow()`, persists the instance, and creates the
  first task if the engine stopped at an action node.

- **`onTaskCompleted(long taskId)`** — Called from `TaskExecutionService.onTaskCompleted()`
  when a task with a non-null `workflowInstanceId` finishes. Deserializes the stored instance
  state and the workflow content (from the version table), then builds a `NodeResult`:
  - If task status is "Completed": `NodeResult(COMPLETED, parsedOutputMap)` where the task's
    output string is parsed as JSON into a `Map<String, Object>`.
  - If task status is "Failed": `NodeResult(FAILED, Map.of())`. The engine's
    `DefaultErrorHandler` will set the workflow instance to FAILED with the error details.
  Calls `WorkflowEngine.completeCurrentNode()`, persists the updated state, and if the engine
  advanced to another action node, creates the next task via `createTaskForCurrentNode()`.
  If the engine reached END, marks the instance completed. If the engine returned FAILED,
  marks the instance failed with the failure reason.

- **`cancelWorkflow(long projectId)`** — Loads the instance, calls
  `WorkflowEngine.cancelWorkflow()`, updates instance status, and cancels any in-progress
  task linked to the instance.

- **`createTaskForCurrentNode(WorkflowInstanceEntity, Workflow, WorkflowInstance)`** — Uses
  `WorkflowEngine.getActionInfo()` to get the action type name and resolved inputs from the
  current node. Creates a `TaskEntity` with `workflowInstanceId` set, `actionType` from the
  node config, and `input` from the resolved inputs. The task enters the normal Axiom task
  queue and gets picked up by `TaskQueuePoller` like any other task.

### `WorkflowEngine` instantiation

The engine requires a `NodeExecutorProvider`. We create a single `AxiomNodeExecutor` that
handles all action types:
- Uses a custom `NodeExecutorProvider` that returns the same executor for any action type
- `execute()` always returns `NodeResult(PENDING, Map.of())` — all Axiom action execution
  is async via the task system
- Actual task creation happens in `WorkflowExecutionService` after the engine returns WAITING

The engine also receives an `AxiomWorkflowEventListener` (see below).

The `WorkflowEngine` instance is created as a CDI `@ApplicationScoped` bean so it can be
injected into `WorkflowExecutionService`.

### `AxiomWorkflowEventListener`

Implements `WorkflowEventListener` to fire SSE events and log activity on key lifecycle
transitions:

| Callback | Action |
|----------|--------|
| `onNodeEntered` | Fire SSE `workflowUpdated(projectId)` |
| `onWorkflowCompleted` | Fire SSE `workflowUpdated(projectId)`, log activity |
| `onWorkflowFailed` | Fire SSE `workflowUpdated(projectId)`, log activity, fire notification |
| `onWorkflowCancelled` | Fire SSE `workflowUpdated(projectId)`, log activity |

### Integration with existing `TaskExecutionService`

The only modification to existing code is in `TaskExecutionService.onTaskCompleted()`. After
the existing completion logic (AI usage recording, activity logging, thread entries, SSE
events), add:

```java
if (task.workflowInstanceId != null) {
    workflowExecutionService.onTaskCompleted(task.id);
}
```

This is the sole touch point into existing code. Everything else is additive.

### Task completion → workflow advancement flow

```
TaskQueuePoller picks up task
  → TaskExecutionService.executeTask()
    → Agent/Script executes
      → TaskExecutionService.onTaskCompleted()
        → [existing logic: AI usage, activity, SSE, etc.]
        → if task.workflowInstanceId != null:
          → WorkflowExecutionService.onTaskCompleted(taskId)
            → Deserialize instance_state + workflow content
            → Build NodeResult from task output
            → WorkflowEngine.completeCurrentNode()
            → Persist updated instance_state
            → If engine stopped at next action node:
              → createTaskForCurrentNode() [queues next task]
            → If engine reached END:
              → Mark instance completed
```

### Node type validation at trigger time

Before starting a workflow, iterate over `workflow.nodes()` and reject if any node has a type
other than `START`, `END`, or `ACTION`. Return 400 with a message listing the unsupported
node types found.

### Initial context

When triggering a workflow, the initial context map is populated from the project:

| Key | Source |
|-----|--------|
| `projectId` | `project.id` |
| `projectName` | `project.name` |
| `repository` | `project.repository` |
| `ref` | `project.ref` |

This context is available to action node input expressions (Jakarta EL), e.g.,
`${context.repository}` in an action node's input mapping.

## REST Resource

### `WorkflowInstanceResourceImpl`

New REST resource implementing the generated `WorkflowInstanceResource` interface. Follows the
existing pattern: `@ApplicationScoped`, no `@Path` annotations on the impl class.

Delegates all logic to `WorkflowExecutionService`:

- `triggerWorkflow(projectId, TriggerWorkflow)` → `workflowExecutionService.triggerWorkflow()`
- `getWorkflowInstance(projectId)` → query + `toBean()`
- `cancelWorkflow(projectId)` → `workflowExecutionService.cancelWorkflow()`

### SSE event type

Add a new `workflowUpdated` factory method to `SseEvent`:

```java
public static SseEvent workflowUpdated(long projectId) {
    return new SseEvent("workflow-updated",
        String.format("{\"projectId\":%d}", projectId));
}
```

## UI Implementation

### Workflow tab on ProjectDetailPage

Add a new "Workflow" tab to `ProjectDetailPage.tsx`, alongside the existing tabs (Tasks,
Thread, Events, Metrics, etc.).

**Three states:**

1. **No instance** — Empty state with a "Run Workflow" button. Button opens a trigger modal
   (see below).

2. **Active instance** (running/waiting) — Embeds the `WorkflowViewer` component from
   `@apitomy/flow-ui`. The viewer shows the workflow graph with nodes color-coded by execution
   state (completed, current, unvisited). The right-side panel shows instance context and node
   details when clicked. A "Cancel Workflow" button appears in the header area.

3. **Terminal instance** (completed/failed/cancelled) — Same `WorkflowViewer` but as a
   historical view showing the full execution path. For failed instances, the failure reason
   is displayed prominently. No cancel button.

### Trigger modal

Follows the existing "Trigger Action" modal pattern on `ProjectDetailPage`:

- `FormSelect` dropdown listing available workflow definitions (fetched from
  `GET /api/v1/workflow-definitions`, filtered client-side to only those with
  `currentVersion != null`)
- "Run Workflow" primary button
- Cancel link button
- On success, reload the workflow tab to show the new instance

### WorkflowViewer integration

```tsx
<WorkflowViewer
    workflow={workflowContent}
    instance={instanceData}
    theme={effectiveTheme === "dark" ? "dark" : "light"}
/>
```

Where:
- `workflowContent` is the `Workflow` object from the definition version's content
- `instanceData` is the `WorkflowInstance` object from the instance's state, mapped to the
  flow-ui `WorkflowInstance` type
- Theme matches Axiom's current light/dark mode via `useEffectiveTheme()`

### Real-time updates

The workflow tab subscribes to SSE `workflow-updated` events matching the current project ID.
On receiving an event, it reloads the instance data from
`GET /api/v1/projects/{projectId}/workflow`. This means the viewer automatically reflects
progress as tasks complete and the workflow advances through nodes.

### `hasWorkflowInstance` indicator

The existing project response gains a `hasWorkflowInstance` boolean. The project detail page
uses this to show a status indicator on the Workflow tab label — a small badge or dot when an
active instance exists.

### API client additions

Add to `ui/src/config/api.ts`:

```typescript
export interface WorkflowInstanceInfo {
    id: number;
    projectId: number;
    definitionId: number;
    definitionVersion: number;
    definitionName: string;
    status: string;
    currentNodeId?: string;
    currentNodeName?: string;
    failureReason?: string;
    workflowContent: any;
    context: Record<string, any>;
    history: HistoryEntryInfo[];
    startedOn: string;
    completedOn?: string;
}

export interface HistoryEntryInfo {
    nodeId: string;
    nodeName: string;
    enteredOn: string;
    completedOn?: string;
    output?: any;
}

export interface TriggerWorkflow {
    workflowDefinitionId: number;
}
```

Functions: `triggerWorkflow(projectId, data)`, `getWorkflowInstance(projectId)`,
`cancelWorkflow(projectId)`.

## Testing

### Backend integration tests

**`WorkflowInstanceResourceTest`** — REST integration test following the existing
`WorkflowDefinitionsResourceTest` pattern:

- Trigger a workflow on a project — verify 200, instance fields populated, status is
  running or waiting
- Get a workflow instance — verify fields match
- Trigger when instance already exists — verify 409
- Trigger with unpublished definition — verify 400
- Trigger with unsupported node types — verify 400
- Cancel a running workflow — verify 204, status becomes cancelled
- Cancel an already-completed workflow — verify 409
- Get instance for project with no instance — verify 404

### Manual browser testing

- Navigate to a project, open the Workflow tab
- Verify empty state with "Run Workflow" button
- Trigger a workflow, verify the viewer appears with the graph
- Watch the workflow advance as tasks complete (real-time via SSE)
- Verify node colors update correctly (completed vs current vs unvisited)
- Click nodes to see details in the side panel
- Cancel a running workflow, verify status updates
- Verify light/dark theme consistency on the viewer
- Verify the trigger modal only shows published definitions

## Future phases

This spec covers Phase 2 only. Subsequent phases:

- **Phase 3:** Human task nodes — inbox integration, task form rendering from workflow node
  config
- **Phase 4:** Event correlation — receive-event nodes (integrate with Axiom's event
  pipeline + `matchesEvent()`), wait nodes (use Axiom's scheduled job mechanism)
- **Phase 5:** Polish — audit/history view, error handling improvements, auto-triggering
  from events
