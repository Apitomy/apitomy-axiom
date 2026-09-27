# Workflows

Workflows let you define a multi-step automation — including branching, human
approvals, timed waits, and event-triggered branches — as a versioned, visually
authored graph of nodes. A workflow instance always runs against a single project and
drives that project's tasks.

If you haven't read [Concepts](concepts.md) yet, it introduces where workflows fit
alongside reports, event-driven automation, and scheduled jobs.

---

## Node Types

| Node Type | Purpose |
|-----------|---------|
| `start` | Workflow entry point; declares the inputs the workflow expects (see [Workflow Input Contract](../developer-guide/workflow-input-contract.md)) |
| `end` | Workflow termination |
| `action` | Creates a task for an action type and waits for it to complete before advancing |
| `human-task` | Pauses execution until a user completes a form through the Inbox (see [Human-Task Nodes](../developer-guide/workflow-human-task-nodes.md)) |
| `wait` | Pauses the instance for a fixed duration, then resumes automatically |
| `receive-event` | Pauses the instance until a matching stream event is dispatched to it |

Edges connect nodes and may carry a priority and an EL condition, so a node can branch
to different next steps based on the values already in the workflow's context (e.g. an
action's output, or a human-task's submitted answers).

## Authoring a Workflow

Navigate to **Components > Workflows** and click **Create Workflow**. New definitions
are scaffolded with a Start node (declaring `projectId`, `ref`, and an optional `event`
input) connected directly to an End node — extend the graph from there.

The workflow editor lets you add and connect nodes, configure each node's inputs and
outputs, and edit conditional edges. Changes are saved as **draft content** on the
definition; they are not usable by running instances until you **publish**.

### Publishing and Versions

Click **Publish** on the workflow detail page to validate the current draft and create
a new immutable version. Publishing fails with a description of the problem if:

- The graph is structurally invalid (e.g. disconnected nodes, missing Start/End)
- The graph contains a node type Axiom doesn't support
- The Start node declares an input outside the
  [canonical input contract](../developer-guide/workflow-input-contract.md), or marks
  an input other than `projectId`/`ref` as required

Each publish creates a new version. The **Versions** tab lists every published version,
lets you diff any two versions side by side, and shows which version is currently
active (`currentVersion`). Existing runs continue to use the version they were started
with — publishing a new version never changes the definition an in-flight run is
executing.

## Triggering a Workflow

A workflow can be started in two ways:

- **Manually** — from a project's detail page, choose **Run Workflow** and select a
  published definition. The instance starts with only `projectId` and `ref` in its
  context.
- **From a subscription** — a subscription routing rule with destination
  **Create workflow** finds or creates a project from the triggering event's `ref`,
  then starts the specified definition with `projectId`, `ref`, and `event` (the
  triggering event's envelope) in its context.

A project can have only **one active workflow run** at a time (`running` or `waiting`
status). Triggering a workflow on a project that already has an active run fails.

### Dispatching Events to a Waiting Workflow

Independent of `create-workflow`, a subscription routing rule with destination
**Workflow dispatch** offers matching events to any workflow instance currently parked
at a `receive-event` node — regardless of which subscription originally started that
run. Matching considers the parked node's declared event type and EL condition, and
event correlation is scoped to the run's project when the event carries a recognizable
`ref`; uncorrelated events are offered to every parked `receive-event` node of a
matching type.

## Following a Run

**Logs > Workflow Runs** lists every workflow instance across all projects, with its
status, current node, and a link to the parent project. Each run detail page shows:

- The full node graph with the current node highlighted
- The run's status: `running`, `waiting`, `completed`, `failed`, or `cancelled`
- Any tasks created by `action` or `human-task` nodes, with links to their execution
  logs
- A link to the run's execution trace, which records every node transition — see
  [Tracing](../developer-guide/tracing.md)

A run parked at a `human-task` node surfaces its pending task in the **Inbox**; a run
parked at a `wait` node resumes automatically once its resume time elapses; a run
parked at a `receive-event` node resumes once a matching event is dispatched to it.

### Cancelling a Run

From the project detail page, you can cancel a project's active workflow run. Cancelling
marks the run `cancelled`, fails any in-progress task the run created, and removes any
pending wait or event-subscription state associated with it. A cancelled run cannot be
resumed.

## Workflows in Configuration Packs

Workflow definitions (including their published version history) are included in
[Configuration Packs](concepts.md#configuration-packs) export/import, and can be
generated by the [AI Assistant's Configuration Assistant](ai-assistant.md#configuration-assistant).
