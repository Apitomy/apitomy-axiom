# Workflows

Workflows let you define a multi-step automation — including branching, human
approvals, timed waits, and event-triggered branches — as a versioned, visually
authored graph of nodes. Every workflow run operates on a single **subject** (an issue, a pull
request, a Jira ticket, or a named subject created by a schedule trigger) and drives that run's tasks.

If you haven't read [Concepts](concepts.md) yet, it introduces subjects, runs, events, and schedule
triggers.

---

## Node Types

| Node Type | Purpose |
|-----------|---------|
| `start` | Workflow entry point; declares the inputs the workflow expects (see [Workflow Input Contract](../developer-guide/workflow-input-contract.md)) |
| `end` | Workflow termination |
| `action` | Creates a task for an action type and waits for it to complete before advancing. This is the only way an action type is ever executed; every action type appears in the editor palette. |
| `human-task` | Pauses execution until a user completes a form through the Inbox (see [Human-Task Nodes](../developer-guide/workflow-human-task-nodes.md)) |
| `wait` | Pauses the instance for a fixed duration, then resumes automatically |
| `receive-event` | Pauses the instance until a matching stream event is dispatched to it |

Edges connect nodes and may carry a priority and an EL condition, so a node can branch
to different next steps based on the values already in the workflow's context (e.g. an
action's output, or a human-task's submitted answers).

## Authoring a Workflow

Navigate to **Workflows** and click **Create Workflow**. New definitions
are scaffolded with a Start node (declaring `subjectId`, `ref`, and an optional `event`
input) connected directly to an End node — extend the graph from there.

The workflow editor lets you add and connect nodes, configure each node's inputs and
outputs, and edit conditional edges. An `action` node must map every required input of its action type,
usually from the run context (`context.subjectId`, `context.subjectName`, `context.ref`,
`context.repository`, `context.event`) or from outputs of earlier nodes; optional inputs may be left
unmapped. See [Inputs, Placeholders and Mappings](concepts.md#inputs-placeholders-and-mappings).
Changes are saved as **draft content** on the
definition; they are not usable by running instances until you **publish**.

### Publishing and Versions

Click **Publish** on the workflow detail page to validate the current draft and create
a new immutable version. Publishing fails with a description of the problem if:

- The graph is structurally invalid (e.g. disconnected nodes, missing Start/End)
- The graph contains a node type Axiom doesn't support
- The Start node declares an input whose name is not a valid identifier, or marks
  `event`, `subjectName` or `repository` as required (see the
  [Workflow Input Contract](../developer-guide/workflow-input-contract.md))

Each publish creates a new version. The **Versions** tab lists every published version,
lets you diff any two versions side by side, and shows which version is currently
active (`currentVersion`). Existing runs continue to use the version they were started
with — publishing a new version never changes the definition an in-flight run is
executing.

## Triggering a Workflow

A workflow run can be started in three ways:

- **Manually** — from a subject's detail page, choose **Run Workflow** and select a published
  definition. You may supply a run title, labels, and inputs. The run starts with `subjectId`, `subjectName`,
  `ref` and `repository` in its context, plus any supplied inputs.
- **From a subscription** — a subscription routing rule of type `create-workflow` finds or creates a
  subject from the triggering event's `ref`, then starts the specified definition with `subjectId`, `ref`,
  and `event` (the triggering event's envelope) in its context.
- **From a schedule trigger** — see [Schedule Triggers](#schedule-triggers) below.

### Run Title, Labels, and Inputs

Every run has a **title**, which defaults to `<definition name> — <subject name>` and may be at most 500
characters. Runs also carry free-form key/value **labels** (runs started by a schedule trigger are
labeled `trigger: schedule`) and the **inputs** they were started with. Supplied inputs join the run's
initial context and are validated against the Start node's declared inputs (see
[Workflow Input Contract](../developer-guide/workflow-input-contract.md)); `subjectId`, `subjectName`, `ref`,
`repository` and `event` are protected and cannot be overridden by supplied inputs.

Besides the built-in inputs (`subjectId`, `ref`, `event`, `subjectName`, `repository`), the Start node may
declare **custom inputs** such as `environment`. Custom input names must be valid identifiers (letters,
digits and underscores, not starting with a digit) and may be marked required; a run started without a
required custom input fails to start (a schedule trigger records `failed: <reason>` as its last outcome).
Supplied custom inputs are available in the run's context alongside the built-in inputs.

Workflow runs have **no outputs**. The result of each step is available from the task output of the
corresponding `action` node (and the submitted answers of each `human-task` node).

### Concurrency Rule

A subject can have at most **one active run per workflow definition** (`running` or `waiting` status).
Sequential runs of the same definition on a subject are fine once the previous run finishes, and runs of
*different* definitions may be active on the same subject at the same time.

A trigger that would violate the rule is handled as follows:

| Trigger | Behavior |
|---------|----------|
| Manual (**Run Workflow**) | Rejected with HTTP 409 |
| `create-workflow` routing rule | Skipped (not queued); the skip is recorded in the routing outcome |
| Schedule trigger | Skipped (not queued); the trigger's last outcome is recorded as `skipped` |

### Schedule Triggers

A workflow definition can have zero or more schedule triggers, managed on the **Triggers** tab of the
workflow definition page (REST:
`/api/v1/workflow/definitions/{workflowDefinitionId}/triggers/schedules`). Each trigger has:

| Field | Purpose |
|-------|---------|
| **Cron** | A 5-field Unix cron expression (`minute hour day-of-month month day-of-week`), evaluated in UTC |
| **Subject name** | Required; the fixed subject every run attaches to (at most 246 characters) |
| **Inputs** | Optional static inputs passed to every run |
| **Enabled** | Whether the trigger fires |

The subject is created on the first firing (with ref `schedule:<subject name>`) and reused on every later
firing, so run history accumulates on one subject. Each trigger also reports:

- **Next run** (`nextRunAt`) — when the trigger fires next; empty while disabled
- **Last run** (`lastRunAt`) — when the trigger last fired
- **Last outcome** (`lastOutcome`) — `started`, `skipped` (the subject already had an active run of the
  definition), or `failed: <reason>`

Axiom checks for due triggers every `axiom.workflow.schedule-poll-interval` (default `30s`). Firing
assumes a single Axiom node; running multiple nodes against one database is not supported for schedule
triggers.

### Dispatching Events to a Waiting Workflow

Independent of `create-workflow`, a subscription routing rule of type `workflow-dispatch` **broadcasts**
matching events to every workflow run currently parked at a `receive-event` node — regardless of which
subscription originally started that run. Matching considers the parked node's declared event type and EL
condition. When the event carries a recognizable `ref`, delivery is scoped to waiting runs on the
correlated subject (all of them, across definitions); uncorrelated events are offered to every parked
`receive-event` node of a matching type.

A `receive-event` node can also declare:

- **`correlationKey`** — `{subscriptionKey, eventKey}` EL expressions. The subscription key is evaluated
  against the run context when the node is reached and stored with the parked branch; only events whose
  evaluated event key is equal can match. `match` conditions still apply.
- **`lookback`** — which already-stored events can satisfy the node when it is reached: `run-start`
  (default; events since the run started), `none` (only events that arrive later), or an ISO-8601
  duration such as `PT10M` (never earlier than the run's start). Only events that matched the filter of a
  subscription with a `workflow-dispatch` rule are eligible, and an event never resumes the same run twice.
- **`timeout`** — an ISO-8601 duration. If no matching event arrives in time, the run follows the node's
  timeout edge (`isTimeout: true`). Timeouts are checked every `axiom.workflow.wait-poll-interval`.

A `human-task` node's optional **`title`** EL expression (e.g. `'Review ' += context.ref`) becomes the
task's subject in the Inbox; it falls back to the node name.

## Following a Run

**Runs** is Axiom's landing page (`/runs`). It lists every workflow run across all subjects with its
title, status, definition, subject, and labels, and can be filtered by status, definition, subject, and
label (`key:value`). The same filters are available on `GET /api/v1/workflow/runs` as the `status`,
`definitionId`, `subjectId`, and `label` query parameters. Each run detail page shows:

- The full node graph with the current node highlighted
- The run's status: `running`, `waiting`, `completed`, `failed`, or `cancelled`
- The run's inputs, labels, and triggering event (when started by a subscription)
- Any tasks created by `action` or `human-task` nodes, with their outputs and links to their execution
  logs
- A link to the run's execution trace, which records every node transition — see
  [Tracing](../developer-guide/tracing.md)

The subject detail page shows a timeline of every run (active and past) on that subject.

A run parked at a `human-task` node surfaces its pending task in the **Inbox**; a run parked at a `wait`
node resumes automatically once its resume time elapses; a run parked at a `receive-event` node resumes
once a matching event is dispatched to it.

### Cancelling a Run

Cancellation is per run: use **Cancel** on the run detail page (REST:
`POST /api/v1/workflow/runs/{runId}/cancel`). Cancelling marks the run `cancelled`, fails any in-progress
task the run created, and removes any pending wait or event-subscription state associated with it. A
cancelled run cannot be resumed. Other runs on the same subject are unaffected.

## Axiom SDK Functions

The **Axiom SDK** is a set of functions for reading and updating Axiom data. They are available in two ways:

- **SDK action nodes** — every SDK function appears in the workflow editor palette (as `SDK: …`, stored as
  `sdk:<function>`) and runs directly, without an AI agent
- **Agent tools** — an action type or session template can allow `mcp__axiom-sdk__<function>` in its
  Allowed Tools, making the function available to the agent through the `axiom-sdk` MCP server

The authoritative list lives in `core/src/main/resources/axiom-sdk-functions.json`:

| Function | Purpose |
|----------|---------|
| `axiom_list_subjects` | List subjects, filtered by name, labels, or ref; each result includes its derived `active` flag |
| `axiom_get_subject` | Get a subject (`subjectId`) including metadata, ref, and `active` |
| `axiom_create_subject` | Create a subject |
| `axiom_update_subject` | Update a subject's name, body, or labels |
| `axiom_update_subject_body` | Replace a subject's Markdown body |
| `axiom_add_subject_label` / `axiom_remove_subject_label` | Add or remove a subject label |
| `axiom_get_subject_thread` | Read a subject's thread |
| `axiom_add_thread_entry` | Post a message to a subject's thread |
| `axiom_list_events` | List events related to a subject |
| `axiom_get_task_status` | Get the status and details of a task on a subject |
| `axiom_respond_to_task` | Submit a response to a task awaiting human input |
| `axiom_get_workflow_run` | Get a workflow run (`runId`, required): title, status, subject, definition and version, labels, inputs, current node. In an `sdk:` action node, `runId` is filled in automatically with the node's own run. |
| `axiom_list_subject_runs` | List workflow runs for a subject (`subjectId`, required; optional `status`), newest first, at most 50 |
| `axiom_fire_event` | Fire a new event into Axiom for processing by subscriptions and workflows |
| `axiom_get_activity_log` | Read the activity log, optionally filtered by subject |
| `axiom_list_tools` / `axiom_list_action_types` / `axiom_list_agents` | List configured tools, action types, and agents |

There are no SDK functions for creating tasks directly, closing or reopening subjects, or reports — work is
started only by workflow runs, and subjects have no lifecycle of their own.

## Workflows in Configuration Packs

Workflow definitions (including their published version history) are included in
[Configuration Packs](concepts.md#configuration-packs) export/import, and can be
generated by the [AI Assistant's Configuration Assistant](ai-assistant.md#configuration-assistant).
Workflows in an imported pack or applied by the assistant are validated and published; unchanged workflows
are not re-published.

Subscriptions that start workflows (`create-workflow` routing rules) may reference the workflow by name with
`workflowDefinitionName` instead of `workflowDefinitionId`. Axiom resolves the name to the workflow's id
when the subscription is created, updated, imported, or applied, which lets a pack or assistant session
create a workflow and the subscription that starts it together.
