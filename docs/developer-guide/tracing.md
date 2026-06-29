# Tracing

Axiom's tracing subsystem records a hierarchical tree of every step that occurs during
an event pipeline run or report generation. Each trace is a directed graph of lightweight
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

All trace entities live in `core/src/main/java/io/apitomy/axiom/core/entities/`.

### TraceEntity

Root of a complete execution trace. Extends `PanacheEntityBase` (not `PanacheEntity`)
because it uses a UUID primary key instead of the standard auto-increment Long.

**Table:** `trace`

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `traceId` | `UUID` | No (PK) | Unique trace identifier |
| `traceType` | `String` | No | Trace category: `"event-pipeline"` or `"report-generation"` |
| `status` | `String` | No | Current status: `"in-progress"`, `"completed"`, or `"failed"` |
| `summary` | `String(1024)` | No | Human-readable description (truncated to 1024 chars) |
| `eventId` | `Long` | Yes | Associated event ID (for event pipeline traces) |
| `projectId` | `Long` | Yes | Associated project ID |
| `reportId` | `Long` | Yes | Associated report ID (for report generation traces) |
| `startedOn` | `Instant` | No | Trace start timestamp |
| `completedOn` | `Instant` | Yes | Trace completion timestamp |

### TraceNodeEntity

A single step/span within a trace tree. Extends `PanacheEntity` (auto-increment Long PK).

**Table:** `trace_node`

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `Long` | No (PK) | Auto-generated node ID |
| `traceId` | `UUID` | No | Parent trace UUID |
| `parentNodeId` | `Long` | Yes | Parent node ID (`null` for root nodes) |
| `nodeType` | `String` | No | Step type identifier (see Node Types below) |
| `status` | `String` | No | Current status: `"in-progress"`, `"completed"`, or `"failed"` |
| `summary` | `String(1024)` | No | Human-readable description |
| `startedOn` | `Instant` | No | Node start timestamp |
| `completedOn` | `Instant` | Yes | Node completion timestamp |
| `durationMs` | `Long` | Yes | Elapsed time in milliseconds (set at completion) |
| `entityType` | `String` | Yes | Type of the referenced detail entity |
| `entityId` | `Long` | Yes | ID of the referenced detail entity |

#### Node Types

| Node Type | Meaning | Typical Entity Reference |
|-----------|---------|--------------------------|
| `event-ingested` | Event received, processing began | `event` |
| `manager-evaluation` | AI Manager was invoked | `activity-log` |
| `decision-processed` | A Manager decision was executed | `activity-log` |
| `task` | A task was created and assigned | `task` |
| `tool-execution` | An MCP tool was invoked | `tool-execution` |
| `escalation` | Decision escalated for human review | `activity-log` |
| `event-ignored` | Manager decided to ignore the event | `activity-log` |
| `report-triggered` | Report generation started | `report` |
| `report-ai-invoked` | AI agent launched for report | `report` |

#### Entity Types

The `entityType` field determines which entity table the `entityId` references:

| Entity Type | Referenced Entity | Detail Content |
|-------------|-------------------|----------------|
| `event` | `EventEntity` | Raw event payload |
| `activity-log` | `ActivityLogEntity` | Log entry with type, summary, execution log |
| `task` | `TaskEntity` | Task details — action type, actor, status, output |
| `tool-execution` | `ToolExecutionEntity` | Full JSON input and output |
| `ai-usage` | `AiUsageEntity` | Token counts, cost, model |
| `report` | `ReportEntity` | Report metadata and content |

### ToolExecutionEntity

Detailed record of an MCP tool invocation. Stores the full JSON input and output for
debugging. Referenced by trace nodes via `entityType="tool-execution"`.

**Table:** `tool_execution`

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `Long` | No (PK) | Auto-generated ID |
| `traceId` | `UUID` | No | Associated trace UUID |
| `toolName` | `String` | No | Name of the MCP tool |
| `toolInput` | `String (TEXT)` | Yes | Tool input as JSON |
| `toolOutput` | `String (TEXT)` | Yes | Tool output as JSON |
| `status` | `String` | No | Execution status: `"in-progress"`, `"completed"`, or `"failed"` |
| `durationMs` | `Long` | Yes | Tool execution time in milliseconds |
| `createdOn` | `Instant` | No | Creation timestamp |

---

## TraceService API

`TraceService` is the central service for creating and managing traces. It lives in
the `core` module so both `app` and `manager` can use it without circular dependencies.

**Location:** `core/src/main/java/io/apitomy/axiom/core/tracing/TraceService.java`

### createTrace()

Creates a new trace and its root node in a single transaction, then fires an SSE event.

```java
public TraceContext createTrace(
    String traceType,        // e.g. "event-pipeline", "report-generation"
    String summary,          // human-readable trace description
    Long eventId,            // associated event ID (nullable)
    Long projectId,          // associated project ID (nullable)
    Long reportId,           // associated report ID (nullable)
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

---

## TraceContext

`TraceContext` is a mutable context object threaded through pipeline and report
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
Long parentId = traceService.addNode(traceCtx, "manager-evaluation",
    "in-progress", "Evaluating event", null, null);
traceCtx.push(parentId);

try {
    // Any nodes created here become children of parentId
    traceService.addNode(traceCtx, "decision-processed",
        "completed", "Decision: create task", "activity-log", logId);
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
| `filterTraceType` | string | — | Filter by trace type (e.g. `"event-pipeline"`) |
| `filterStatus` | string | — | Filter by status (comma-separated, e.g. `"in-progress,completed"`) |
| `filterEventId` | integer | — | Filter by event ID |
| `filterProjectId` | integer | — | Filter by project ID |
| `filterReportId` | integer | — | Filter by report ID |

**Response:** `TraceSearchResults`

```json
{
  "items": [
    {
      "traceId": "a1b2c3d4-...",
      "traceType": "event-pipeline",
      "status": "completed",
      "summary": "Processing event #42: issue-created",
      "eventId": 42,
      "projectId": null,
      "reportId": null,
      "startedOn": "2026-06-29T10:00:00Z",
      "completedOn": "2026-06-29T10:00:05Z"
    }
  ],
  "totalCount": 1,
  "page": 1,
  "limit": 20
}
```

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
    "traceType": "event-pipeline",
    "status": "completed",
    "summary": "Processing event #42: issue-created",
    "eventId": 42,
    "startedOn": "2026-06-29T10:00:00Z",
    "completedOn": "2026-06-29T10:00:05Z"
  },
  "nodes": [
    {
      "id": 1,
      "traceId": "a1b2c3d4-...",
      "parentNodeId": null,
      "nodeType": "event-ingested",
      "status": "completed",
      "summary": "Event received: issue-created",
      "startedOn": "2026-06-29T10:00:00Z",
      "completedOn": "2026-06-29T10:00:00Z",
      "durationMs": 0,
      "entityType": "event",
      "entityId": 42
    },
    {
      "id": 2,
      "traceId": "a1b2c3d4-...",
      "parentNodeId": 1,
      "nodeType": "manager-evaluation",
      "status": "completed",
      "summary": "Manager evaluation: issue-created",
      "startedOn": "2026-06-29T10:00:00Z",
      "completedOn": "2026-06-29T10:00:03Z",
      "durationMs": 3000,
      "entityType": "activity-log",
      "entityId": 15
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
    "entityId": 7
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
MCP tool wrappers during task or report execution.

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

If your code is the entry point for a new pipeline (like `PipelineOrchestrator` or
`ReportExecutionService`), create a new trace:

```java
TraceContext traceCtx = traceService.createTrace(
    "my-pipeline",
    "Processing something: " + description,
    eventId, projectId, reportId,
    "my-root-node", "Root: " + description,
    "event", eventId);
```

If your code runs within an existing trace (like `ManagerService`), receive the
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

This flow is implemented in `TaskExecutionService` (for tasks) and
`ReportExecutionService` (for reports). Both look up the trace node ID for the current
task or report AI invocation and inject it as `AXIOM_PARENT_NODE_ID` so tool calls
become children of the correct node in the tree.

If the trace callback fails (network error, missing trace, etc.), the tool execution
continues normally — tracing is best-effort and never blocks work.
