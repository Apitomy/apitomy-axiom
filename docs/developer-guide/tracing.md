# Tracing

Axiom's tracing subsystem records a hierarchical tree of every step that occurs during
a workflow run. Each trace is a tree of lightweight
**trace nodes** that reference more detailed records elsewhere in the system, keeping
nodes small and fast to query while providing full drill-down capability.

This guide covers the data model, service API, REST endpoints, and how to instrument
new pipeline stages with tracing.

---

## Design Principles

- **Lightweight breadcrumbs** — trace nodes carry a summary, status, and timing, but
  delegate detail to referenced entities (activity logs, tasks, tool executions, etc.)
- **Non-fatal** — all trace operations are wrapped in exception handlers. Tracing never
  interrupts the main pipeline.
- **Independent transactions** — trace writes use `QuarkusTransaction.requiringNew()`
  so data persists even if the caller's transaction rolls back
- **Real-time** — every trace mutation fires an SSE event for live UI updates
- **Correlation by UUID** — `TraceEntity` uses a UUID primary key that doubles as the
  correlation identifier threaded through environment variables and API callbacks

---

## Data Model

Trace entities live in `core/src/main/java/io/apitomy/axiom/core/entities/`. Read
`TraceEntity.java`, `TraceNodeEntity.java`, and `ToolExecutionEntity.java` directly for
the exact field list — the concepts below are what you need to work with the API and
build new instrumentation.

### TraceEntity

The root of a complete execution trace (table `trace`). It uses a UUID primary key
(`traceId`) instead of the standard auto-increment `Long` used elsewhere, because that
ID doubles as the correlation identifier threaded through subprocess environment
variables and API callbacks. Each trace has a `traceType` (see below), a status
(`in-progress`, `completed`, or `failed`), a human-readable summary, optional
`eventId`/`subjectId` associations, and start/completion timestamps. `eventId` is the
UUID of the originating `stream_event` row. It is a soft reference (no foreign key): stream events are
purged by the event retention policy while traces are retained, so the UUID may outlive its event.

Every trace is currently of type `workflow`, created when a workflow run starts (manually, by a
`create-workflow` routing rule, or by a schedule trigger). Tasks created by the run's action and human-task
nodes, and tool calls made by their agents, are recorded as nodes of the run's trace.

### TraceNodeEntity

A single step/span within a trace tree (table `trace_node`). Nodes form a parent-child
tree via `parentNodeId` (null for the root node). Each node has a `nodeType`, a status,
a summary, start/completion timestamps with a computed `durationMs`, and an optional
`entityType` + `entityId` pair that points to a more detailed record elsewhere in the
system. `entityId` is stored as a string (`VARCHAR`) so it can hold either a numeric ID
(e.g. `task`, `activity-log`) or a UUID (e.g. `event`, which references a stream event).

#### Node Types

Node type strings are chosen by whichever service creates the node — there is no
enum, so treat this as the currently observed set rather than an exhaustive contract:

| Node Type | Meaning | Typical Entity Reference |
|-----------|---------|--------------------------|
| `workflow` | Workflow run started (root node of a `workflow` trace) | `workflow-run` |
| `task` | A workflow step: a task created by an action or human-task node, or a run parked at a `wait` or `receive-event` node | `task`, `workflow-wait`, or `workflow-event-subscription` |
| `tool-execution` | An MCP tool was invoked | `tool-execution` |

#### Entity Types

The `entityType` field determines which entity table the `entityId` references:

| Entity Type | Referenced Entity | Detail Content |
|-------------|-------------------|----------------|
| `event` | `StreamEventEntity` | Raw normalized event payload |
| `activity-log` | `ActivityLogEntity` | Log entry with type, summary, execution log |
| `task` | `TaskEntity` | Task details — action type, agent, status, output |
| `tool-execution` | `ToolExecutionEntity` | Full JSON input and output |
| `ai-usage` | `AiUsageEntity` | Token counts, cost, model |
| `workflow-run` | `WorkflowRunEntity` | Run status, current node, times, definition and subject names |
| `workflow-wait` | `WorkflowWaitEntity` | Wait type, resume time, status (`waiting`/`resolved`/`cancelled`), run |
| `workflow-event-subscription` | `WorkflowEventSubscriptionEntity` | Awaited event type, status, run, subject |

`GET /traces/{traceId}/nodes/{nodeId}` resolves these into the node's `detail` object (see
`TraceResourceImpl.resolveDetail`). An unknown entity type, a malformed ID or a deleted record yields the
node with a null `detail`. The one exception is workflow waits and event subscriptions: their rows are
deleted when the parked node resumes, so the detail is rebuilt from the trace node and its workflow run,
with status `resolved` (node completed) or `cancelled`. The event that satisfied a subscription is not
recorded, so `matchedEventId` is always null. The `workflow-run` detail omits the engine's
`instanceState`.

### ToolExecutionEntity

Detailed record of an MCP tool invocation (table `tool_execution`), storing the full
JSON input and output for debugging. Referenced by trace nodes via
`entityType="tool-execution"`. Each record carries the associated `traceId`, the tool
name, its JSON input/output, a status, and a duration.

---

## TraceService API

`TraceService` is the central service for creating and managing traces. It lives in
the `core` module so any module can use it without circular dependencies.

**Location:** `core/src/main/java/io/apitomy/axiom/core/tracing/TraceService.java`

### createTrace()

Creates a new trace and its root node in a single transaction, then fires an SSE event.

```java
public TraceContext createTrace(
    String traceType,        // e.g. "workflow"
    String summary,          // human-readable trace description
    UUID eventId,            // associated stream event ID (nullable)
    Long subjectId,          // associated subject ID (nullable)
    String rootNodeType,     // node type for the root node
    String rootNodeSummary,  // summary for the root node
    String rootEntityType,   // entity type for the root node (nullable)
    Long rootEntityId        // entity ID for the root node (nullable)
)
```

Returns a `TraceContext` with the root node already pushed onto the stack.

### addNode()

Adds a child node under the current parent (top of the context stack).

```java
public Long addNode(
    TraceContext ctx,   // current trace context
    String nodeType,    // node type identifier
    String status,      // initial status (e.g. "in-progress", "completed")
    String summary,     // human-readable description
    String entityType,  // referenced entity type (nullable)
    Long entityId       // referenced entity ID (nullable)
)
```

Returns the new node's ID. The parent is determined by `ctx.currentParentNodeId()`.

To reference a UUID-keyed entity (such as a stream event, `entityType="event"`), use
`addUuidEntityNode()`, which has the same parameters except that `entityId` is a `UUID`:

```java
Long nodeId = traceService.addUuidEntityNode(traceCtx, "event-received", "completed",
    "Event received: " + event.type, "event", event.id);
```

### completeNode()

Marks a node as completed with a final status and calculated duration. Has two overloads:

```java
// Basic completion
public void completeNode(Long nodeId, String status)

// Completion with entity reference (when entity isn't known at creation time)
public void completeNode(Long nodeId, String status,
    String entityType, Long entityId)
```

### completeTrace()

Marks the trace itself as completed or failed.

```java
public void completeTrace(UUID traceId, String status)
```

### Trace lifecycle rules

Every unit of work ends with exactly one trace in a final state (`completed` or `failed`). No trace or
`task` node may stay `in-progress` once its work item is final, and a trace is never completed twice.

| Work item | Who completes the trace |
|---|---|
| Workflow run | `WorkflowExecutionService` when the run is terminal (`completed`, `failed`, or `cancelled`), including runs that finish synchronously on start |

`TaskTraceFinalizer` is used by both the agent and script task paths (including `failTask`). It always
completes the task's `task` node and never completes a workflow-owned trace — the workflow run owns its
trace lifecycle.

### Workflow traces and events

- A run started by a `create-workflow` routing rule gets a `workflow` trace with `eventId` set to the
  stream event, so the trace appears in `GET /traces?filterEventId=...`. The routing outcome's `traceId`
  is the run's trace. Runs started manually or by a schedule trigger have no `eventId`.
- A `create-workflow` rule that is skipped because the subject already has an active run of the definition
  creates no run and no trace.
- When a stream event resumes a run parked at a receive-event step, the receive-event `task` node
  (entity type `workflow-event-subscription`) is completed and its summary gets
  "— resumed by event <id>". The `workflow-resumed` routing outcome item's `trace_node_id` points to that
  node, and a `workflow_run_resume` row records the event (see the event sourcing design).

---

## TraceContext

`TraceContext` is a mutable context object threaded through workflow and task
processing. It tracks the current position in the trace tree using an internal stack.

**Location:** `core/src/main/java/io/apitomy/axiom/core/tracing/TraceContext.java`

| Method | Description |
|--------|-------------|
| `UUID traceId()` | Returns the trace UUID |
| `Long currentParentNodeId()` | Returns the top of the stack (current parent) |
| `void push(Long nodeId)` | Pushes a node onto the stack, making it the current parent |
| `Long pop()` | Pops the current parent, returning to the previous level |

### Push/Pop Pattern

When descending into a child scope, push the new node onto the stack so that any nodes
created within that scope become its children. Pop when returning to the parent level.

```java
// Create a parent node
Long parentId = traceService.addNode(traceCtx, "my-step",
    "in-progress", "Doing something", null, null);
traceCtx.push(parentId);

try {
    // Any nodes created here become children of parentId
    traceService.addNode(traceCtx, "my-child-step",
        "completed", "Child step", "activity-log", logId);
} finally {
    traceCtx.pop();
    traceService.completeNode(parentId, "completed");
}
```

---

## REST API Reference

All trace endpoints are defined in the OpenAPI specification at
`common/api/src/main/resources/openapi.json` and implemented in
`app/src/main/java/io/apitomy/axiom/app/rest/TraceResourceImpl.java`.

### List Traces

```
GET /api/v1/traces
```

Returns a paginated list of traces with optional filtering.

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `page` | integer | 1 | Page number (1-indexed) |
| `limit` | integer | 20 | Page size |
| `filterTraceType` | string | — | Filter by trace type (currently always `"workflow"`) |
| `filterStatus` | string | — | Filter by status (comma-separated, e.g. `"in-progress,completed"`) |
| `filterEventId` | string (UUID) | — | Filter by stream event ID (400 if not a valid UUID) |
| `filterSubjectId` | integer | — | Filter by subject ID |

**Response:** `TraceSearchResults`

```json
{
  "items": [
    {
      "traceId": "a1b2c3d4-...",
      "traceType": "workflow",
      "status": "completed",
      "summary": "Workflow: Auto-Label New Issues",
      "eventId": null,
      "subjectId": 7,
      "startedOn": "2026-06-29T10:00:00Z",
      "completedOn": "2026-06-29T10:00:05Z"
    }
  ],
  "totalCount": 1,
  "page": 1,
  "limit": 20
}
```

### Convenience: List Traces by Entity

The API also provides a convenience endpoint that returns the traces for a specific subject as a JSON
array of `Trace` objects (not paginated):

```
GET /api/v1/subjects/{subjectId}/traces
```

This is equivalent to calling `GET /traces?filterSubjectId=...`,
but are more convenient when you already have the entity ID.

### Get Trace Detail

```
GET /api/v1/traces/{traceId}
```

Returns a trace and all of its nodes.

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `traceId` | string (UUID) | The trace UUID |

**Response:** `TraceDetail`

```json
{
  "trace": {
    "traceId": "a1b2c3d4-...",
    "traceType": "workflow",
    "status": "completed",
    "summary": "Workflow: Auto-Label New Issues",
    "subjectId": 7,
    "startedOn": "2026-06-29T10:00:00Z",
    "completedOn": "2026-06-29T10:00:05Z"
  },
  "nodes": [
    {
      "id": 1,
      "traceId": "a1b2c3d4-...",
      "parentNodeId": null,
      "nodeType": "workflow",
      "status": "completed",
      "summary": "Workflow: Auto-Label New Issues",
      "startedOn": "2026-06-29T10:00:00Z",
      "completedOn": "2026-06-29T10:00:00Z",
      "durationMs": 0,
      "entityType": null,
      "entityId": null
    },
    {
      "id": 2,
      "traceId": "a1b2c3d4-...",
      "parentNodeId": 1,
      "nodeType": "task",
      "status": "completed",
      "summary": "Task: Auto-Label Issue",
      "startedOn": "2026-06-29T10:00:03Z",
      "completedOn": "2026-06-29T10:00:05Z",
      "durationMs": 2000,
      "entityType": "task",
      "entityId": "15"
    }
  ]
}
```

### Get Trace Node Detail

```
GET /api/v1/traces/{traceId}/nodes/{nodeId}
```

Returns a trace node with its resolved entity detail. The detail object's structure
depends on the node's `entityType`.

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `traceId` | string (UUID) | The trace UUID |
| `nodeId` | integer | The node ID |

**Response:** `TraceNodeDetail`

```json
{
  "node": {
    "id": 5,
    "traceId": "a1b2c3d4-...",
    "parentNodeId": 3,
    "nodeType": "tool-execution",
    "status": "completed",
    "summary": "Tool: github_list_issues",
    "durationMs": 1200,
    "entityType": "tool-execution",
    "entityId": "7"
  },
  "detail": {
    "toolName": "github_list_issues",
    "toolInput": "{\"repo\": \"owner/repo\", \"state\": \"open\"}",
    "toolOutput": "[{\"number\": 1, \"title\": \"Bug report\"}]",
    "status": "completed",
    "durationMs": 1200
  }
}
```

### Create Tool Call

```
POST /api/v1/traces/tool-calls
```

Creates a new tool-call trace node and its associated `ToolExecutionEntity`. Called by
MCP tool wrappers during task execution.

**Request Body:** `ToolCallRequest`

```json
{
  "traceId": "a1b2c3d4-...",
  "parentNodeId": 3,
  "toolName": "github_list_issues",
  "toolInput": "{\"repo\": \"owner/repo\", \"state\": \"open\"}"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `traceId` | string (UUID) | Yes | The parent trace UUID |
| `parentNodeId` | integer | No | Parent node ID for tree nesting |
| `toolName` | string | Yes | Name of the MCP tool being invoked |
| `toolInput` | string | No | Tool input as JSON |

**Response:** `ToolCallCreated`

```json
{
  "nodeId": 5
}
```

### Complete Tool Call

```
PUT /api/v1/traces/tool-calls/{nodeId}
```

Completes a tool-call trace node with the execution result.

**Path Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `nodeId` | integer | The trace node ID returned by the create call |

**Request Body:** `ToolCallCompletion`

```json
{
  "toolOutput": "[{\"number\": 1, \"title\": \"Bug report\"}]",
  "status": "completed",
  "durationMs": 1200
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `toolOutput` | string | No | Tool output as JSON |
| `status` | string | No | Final status (`"completed"` or `"failed"`) |
| `durationMs` | integer | No | Execution duration in milliseconds |

---

## Instrumenting New Pipeline Stages

To add tracing to a new pipeline stage or service:

### Step 1: Inject TraceService

```java
@Inject
TraceService traceService;
```

### Step 2: Create a Trace or Add a Node

If your code is the entry point for a new kind of traced work (like `WorkflowExecutionService`), create a
new trace:

```java
TraceContext traceCtx = traceService.createTrace(
    "my-pipeline",
    "Processing something: " + description,
    eventId, subjectId,   // eventId is the stream event UUID (nullable)
    "my-root-node", "Root: " + description,
    null, null);
```

If your code runs within an existing trace (like task execution within a workflow run), receive the
`TraceContext` as a parameter and add nodes to it:

```java
Long nodeId = traceService.addNode(traceCtx, "my-step", "in-progress",
    "Doing something important", null, null);
```

### Step 3: Push/Pop for Child Scopes

If your node will have child nodes, push it onto the stack:

```java
traceCtx.push(nodeId);
try {
    // child nodes created here become children of nodeId
    doWork(traceCtx);
} finally {
    traceCtx.pop();
}
```

### Step 4: Complete Nodes and the Trace

```java
// Complete a node
traceService.completeNode(nodeId, "completed");

// Complete a node and set its entity reference
traceService.completeNode(nodeId, "completed", "activity-log", logEntry.id);

// Complete the trace
traceService.completeTrace(traceCtx.traceId(), "completed");
```

### Step 5: Pass Correlation Data to Subprocesses

If your stage launches a subprocess that should report tool calls back to the trace,
inject the trace correlation data as environment variables:

```java
Map<String, String> env = new HashMap<>();
env.put("AXIOM_TRACE_ID", traceCtx.traceId().toString());
env.put("AXIOM_PARENT_NODE_ID", String.valueOf(nodeId));
```

The subprocess (or its MCP tool wrapper) reads these variables and calls back to
`POST /api/v1/traces/tool-calls` and `PUT /api/v1/traces/tool-calls/{nodeId}`.

### Non-Fatal Pattern

Always wrap trace operations in try/catch when called from the main pipeline:

```java
Long nodeId = null;
if (traceCtx != null) {
    try {
        nodeId = traceService.addNode(traceCtx, "my-step", "in-progress",
            "Description", null, null);
        traceCtx.push(nodeId);
    } catch (Exception e) {
        LOG.warnf(e, "Failed to add trace node");
    }
}

// ... do the actual work ...

if (traceCtx != null && nodeId != null) {
    try {
        traceCtx.pop();
        traceService.completeNode(nodeId, "completed");
    } catch (Exception e) {
        LOG.warnf(e, "Failed to complete trace node");
    }
}
```

---

## MCP Tool Call Integration

When Axiom generates MCP server configurations for AI agent tasks, it includes the trace
correlation environment variables (`AXIOM_TRACE_ID` and `AXIOM_PARENT_NODE_ID`). The
generated MCP tool wrappers use these to register tool calls as trace nodes:

```
AI Agent starts task
  │
  ├─ Axiom sets AXIOM_TRACE_ID and AXIOM_PARENT_NODE_ID in subprocess env
  │
  ▼
MCP Tool Wrapper executes
  │
  ├─ Reads AXIOM_TRACE_ID and AXIOM_PARENT_NODE_ID from environment
  ├─ POST /api/v1/traces/tool-calls  →  receives nodeId
  ├─ Executes the actual tool
  └─ PUT /api/v1/traces/tool-calls/{nodeId}  →  sends output + status
```

This flow is implemented in `TaskExecutionService`, which looks up the trace node ID for the current task
and injects it as `AXIOM_PARENT_NODE_ID` so tool calls become children of the correct node in the tree.

If the trace callback fails (network error, missing trace, etc.), the tool execution
continues normally — tracing is best-effort and never blocks work.

## Correlation

The trace ID is also Axiom's correlation ID. Activity log rows, AI usage rows, tasks and workflow runs store
the trace ID of the work that produced them, and agents can join their caller's trace through the
`X-Axiom-Trace-Id` and `X-Axiom-Parent-Node-Id` headers. See [Correlation by Trace ID](correlation.md).

Server log lines also carry the trace ID (MDC key `traceId`), so a trace can be followed in the logs with
`grep traceId=<uuid>`. See [Correlation by Trace ID](correlation.md#server-logs-correlation-ids-in-the-mdc).
