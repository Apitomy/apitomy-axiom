# Lineage

The lineage API (#430) answers "where did this come from, and what did it lead to?" for any unit of work. It
follows the links described in [Correlation](correlation.md), [Tracing](tracing.md) and the
[event sourcing design](event-sourcing-design.md) in both directions, so a user does not have to hop between
the event, ledger, trace, workflow run, task and usage pages.

The implementation is `LineageService` (`app/src/main/java/io/apitomy/axiom/app/LineageService.java`), exposed
by `LineageResourceImpl`. The UI component is `LineagePanel` (`ui/src/components/LineagePanel.tsx`).

## API

```
GET /api/v1/lineage/{entityType}/{id}?direction=both&depth=5&maxNodes=200
```

| Parameter    | Values                                                                          | Default |
|--------------|---------------------------------------------------------------------------------|---------|
| `entityType` | `event`, `trace`, `workflow-run`, `task`, `scheduled-job-run`, `report`         | —       |
| `id`         | UUID for `event` and `trace`, a number otherwise                                | —       |
| `direction`  | `upstream` (origin), `downstream` (results) or `both`                           | `both`  |
| `depth`      | Hops walked in each direction, 1–10                                             | 5       |
| `maxNodes`   | Maximum number of nodes, 1–1000                                                 | 200     |

An unknown entity type, a malformed ID or an out-of-range parameter returns `400`. A root that does not exist
returns `404`. Projects are not a root type: a project holds too much unrelated work to give a useful graph.

The response is a `LineageGraph`:

| Field          | Meaning                                                                            |
|----------------|------------------------------------------------------------------------------------|
| `root`         | Key of the root node                                                               |
| `nodes`        | `LineageNode` list                                                                 |
| `edges`        | `LineageEdge` list                                                                 |
| `truncated`    | `true` when the depth or node limit cut the walk short                             |
| `depth`, `maxNodes` | The limits that were applied                                                  |
| `totalCostUsd` | Sum of the nodes' `costUsd`                                                        |

Each `LineageNode` has a `key` (`type:id`, used by edges), `type`, `id`, `label`, `status`, `subtype` (trace
type, action type, trigger or outcome item type), `linkPath` (UI route), `costUsd`, `direction` (`root`,
`upstream` or `downstream`), `depth` (hops from the root), `available` and `createdOn`.

Node types are the six root types plus `outcome`: a routing outcome item that is not a task or a workflow run
(`ignored`, `escalated`, `decision`, `no-match`).

Each `LineageEdge` has `from`, `to` and `relation`. Edges always point **downstream**, from the cause to the
result, whichever direction was walked:

| Relation             | From → to                                                                     |
|----------------------|-------------------------------------------------------------------------------|
| `triggered`          | event → Manager (or other) trace, event → workflow run                         |
| `created`            | trace / workflow run / job run / report / task → task                          |
| `resumed`            | event → workflow run it resumed                                               |
| `produced`           | trace or event → outcome item (ignored, escalated, ...)                       |
| `part-of`            | owning run or report → its trace (only when a trace is the root)              |
| `triggered-by-agent` | caller (trace or its owner) → job run or report started through the REST API  |

### Link paths

| Type                | `linkPath`                         |
|---------------------|------------------------------------|
| `event`             | `/events/stream/{id}`              |
| `trace`             | `/logs/traces/{id}`                |
| `workflow-run`      | `/logs/workflow-runs/{id}`         |
| `task`              | `/logs/tasks?taskId={id}`          |
| `scheduled-job-run` | `/logs/job-runs?runId={id}`        |
| `report`            | `/reports/{id}`                    |
| `outcome`           | none                               |

Tasks and job runs have no detail page; the Tasks and Job Runs pages open a lineage modal for `?taskId=` and
`?runId=`.

## Traces and Their Owners

Workflow runs, scheduled job runs and reports each own a trace (`workflow`, `scheduled-job-execution` and
`report-generation` trace types). Such a trace is shown as its owner, not as a separate node, so a job run and
its trace never appear twice. Traces without an owner entity (`manager`, `manager-dry-run`, `user-action` and
any other type) are `trace` nodes. If an owned trace's owner was deleted, the trace itself is shown.

When an owned trace is the root (`GET /lineage/trace/{id}`), the trace stays the root and its owner is
linked upstream with `part-of`.

## Traversal Rules

### Upstream (origin)

Each node has at most one origin, except workflow runs, which can also have resume events.

| Node                | Origin                                                                                     |
|---------------------|--------------------------------------------------------------------------------------------|
| task                | First that applies: `workflowRunId` → workflow run; the parent of the task's `task` trace node is another task's node → that task (a task created by an agent task, #427); `traceId` → trace or its owner; `eventId` → event |
| trace               | Owned: its owner (`part-of`). Otherwise `eventId` → event                                    |
| workflow run        | `trigger_event_id` → event (`triggered`); each `workflow_run_resume.event_id` → event (`resumed`) |
| scheduled job run, report | `triggered_by_trace_id` → trace or its owner (`triggered-by-agent`)                  |
| event               | none                                                                                        |

The caller of `triggered_by_trace_id` is recorded as a trace, not as a task: several tasks can share a trace,
and the parent node of the request is not stored. The graph therefore links the job run or report to the
trace (or to the run, job run or report that owns it).

### Downstream (results)

| Node                | Results                                                                                    |
|---------------------|--------------------------------------------------------------------------------------------|
| event               | traces with `eventId` (owned ones as their owner); workflow runs with `trigger_event_id`; runs resumed by the event; `routing_outcome_item` rows reached through `event_processing_ledger` → `routing_outcome`: `workflow-run` and `workflow-resumed` items link the run; items of outcomes **without** a trace link a task or an `outcome` node |
| trace               | the work in the trace (below); items of `routing_outcome` rows with this `trace_id` (tasks and `outcome` nodes) |
| workflow run        | tasks with `workflow_run_id`; the work in its trace                                         |
| scheduled job run, report | the work in its trace                                                                 |
| task                | tasks whose `task` trace node is a child of this task's node (created by its agent)          |

"The work in a trace" is every task with that `trace_id` (including tasks that joined the trace through
`POST /projects/{id}/tasks`, #427) and every job run or report whose `triggered_by_trace_id` is that trace. A
task whose trace node is a child of another task's node is linked to that task instead of to the trace owner.

Nodes reached from more than one place are included once, with every edge. With `direction=both` the upstream
walk runs first, so a node found by both walks keeps `upstream`.

## Limits and Truncation

The walk is breadth-first. `depth` limits the hops in each direction. When a new node would exceed
`maxNodes`, it is skipped and `truncated` is set; edges to skipped nodes are left out, so every edge refers to
a node in the response. When the depth limit is reached and the last level still has unvisited neighbours,
`truncated` is also set (this costs one extra round of queries for that level).

## Cost

`costUsd` is the **direct** AI cost of a node, so costs can be added up without counting anything twice:

| Node                         | Sum of `ai_usage.cost_usd` where                                        |
|------------------------------|-------------------------------------------------------------------------|
| task                         | `task_id` = the task                                                    |
| scheduled job run            | `scheduled_job_run_id` = the run                                        |
| report                       | `report_id` = the report                                                |
| trace, workflow run          | `trace_id` = the (run's) trace and `task_id`, `scheduled_job_run_id` and `report_id` are null (e.g. the Manager evaluation) |
| event, outcome               | no cost                                                                 |

## Query Batching (No N+1)

Every level of the walk does a fixed number of queries per node type, using `IN (...)` lists:

1. Load the frontier's entities: one query per type (`stream_event`, `trace`, `workflow_run`, `task`,
   `scheduled_job_run`, `report`, `routing_outcome_item`), plus one for the scheduled job names.
2. Resolve trace owners: one query per owned trace type present.
3. Expand: for example, for events one query each for traces, workflow runs, resumes, ledger rows, outcomes
   and items; for traces and owners one query each for tasks, the tasks' trace nodes and their parent nodes,
   job runs and reports triggered from the traces.

Entities found while expanding are cached, so they are not loaded again in the next level. Costs are computed
once at the end with one grouped query per cost key (task, job run, report, trace). The number of queries
therefore depends on the depth and the node types, not on the number of nodes. `LineageTest` checks this with
Hibernate statistics (`%test.quarkus.hibernate-orm.statistics=true`): a graph with 2 tasks and one with 12
tasks must use the same number of statements.

## Dangling References

All the links are soft references, and retention deletes events, traces, runs and reports independently (see
[Data Retention](data-retention.md)). A reference to a missing row becomes a node with `available: false`,
`status: "unavailable"`, a label such as `Event 3f2a... (deleted or unavailable)` and no `linkPath`. The walk
does not continue from such a node. Only a missing root returns `404`.

## UI

`LineagePanel` takes an `entityType` and `id`. It has **Show origin** (`direction=upstream`) and **Show
results** (`direction=downstream`) views, rendered as an indented tree from the root with a link, status and
cost on each node. Nothing is fetched until the panel is opened, and each view is fetched once. A node reached
twice is expanded only the first time.

| Page                              | Placement                                         |
|-----------------------------------|---------------------------------------------------|
| Event detail                      | Expandable **Lineage** section below the tabs     |
| Workflow run detail               | **Lineage** tab                                   |
| Report detail                     | Expandable **Lineage** section below the summary  |
| Logs > Tasks, project Tasks tab   | **Lineage** button, opens `LineageModal`          |
| Logs > Job Runs                   | **Lineage** button, opens `LineageModal`          |
