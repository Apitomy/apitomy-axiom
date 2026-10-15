# Logging and Debugging

When something isn't working as expected — a workflow run fails, a task goes sideways, an event seems to be
ignored, or a schedule trigger doesn't start a run — Axiom provides several layers of logging to help you
trace what happened and why. This guide explains how to use them.

---

## The Processing Pipeline

Understanding Axiom's processing pipeline is the key to effective debugging. Every piece of work happens
inside a workflow run, which is started or resumed along one of these paths:

**Events:**

```
Event Source polls → Event recorded in stream → Subscription filter matches → Routing rule fires
  → create-workflow (start a run) or workflow-dispatch (resume waiting runs)
```

**Schedules:**

```
Schedule trigger due → Fixed subject found or created → Run started (or skipped)
```

**Runs:**

```
Run starts → Nodes execute → Tasks created for action/human-task nodes → Agent or script executes
```

At each stage, Axiom records what happened. Debugging is a matter of finding where in the pipeline things
went wrong and examining the logs at that stage.

---

## Where to Look: A Quick Reference

| Symptom | Where to check first |
|---------|---------------------|
| No events appearing at all | **Events > Event Sources** > event source detail page > **Poll Logs** tab |
| Events appear but no subscription matches | **Events > Subscriptions** — use the **Preview** on the **Filter** tab to test the filter expression |
| A subscription matched but no run started | Event detail page > **Processing** tab — check the routing outcome (a `skipped` run means the subject already had an active run of that definition); confirm a `create-workflow` rule is configured |
| A waiting run never received an event | Confirm a `workflow-dispatch` rule matches the event and the `receive-event` node's event type and condition match |
| A schedule trigger didn't start a run | Workflow definition > **Triggers** tab — check **Enabled**, **Next run**, and **Last outcome** (`skipped` or `failed: …`) |
| Workflow run stuck or failed | **Runs** — open the run, check the current node, status, and failure reason |
| Which runs did this event start? | Event detail page > **Runs** tab |
| A run failed and you fixed the cause | Subject detail page > **Runs** tab > **Re-run** |
| Task was created but failed | **Logs > Tasks** — click **View Log** on the failed task |
| Task completed but result is wrong | Run detail page **Overview** tab (**log** link of the task) or subject **Tasks** tab > **View Log** |
| No agent picks up a task | **Components > Agents** — confirm an enabled agent's capabilities match `action:<name>` |
| Want to see the full run tree for one workflow run | Run detail page > **Execution Trace** tab, or **Logs > Traces** |
| Want to see where a task or run came from and everything it led to | **Lineage** panel (see below) |
| Don't know where to start | **Logs > All Activity** — scan the timeline |

---

## All Activity Log

**Logs > All Activity** is the unified timeline of everything Axiom has done. This is the best starting
point when you don't know where to look.

Every entry has a **type** label that tells you what happened, including:

| Entry Type | Meaning |
|------------|---------|
| `subject-created` | A new subject was created from an event |
| `workflow-started` | A workflow run began |
| `workflow-completed` | A workflow run finished successfully |
| `workflow-failed` | A workflow run failed |
| `workflow-cancelled` | A workflow run was cancelled |
| `task-started` | An agent began executing a task |
| `task-completed` | A task finished successfully |
| `task-failed` | A task failed |
| `task-awaiting-input` | The task requires human input (Inbox) |

### Filtering the Activity Log

The activity log can be filtered with filter chips by:

- **Entry Type** — show only workflow activity, only task activity, etc.
- **Summary** — text search across activity summaries
- **Subject ID** — show all activity for a specific subject
- **Event ID** and **Trace ID** — show the activity of one event or one trace

### Viewing Execution Logs

**task-completed** and **task-failed** entries have a **View Log** link to the task's execution log — the
full AI agent output showing what the agent did.

---

## Tracing Event-Driven Runs

### Step 1: Verify Events Are Being Received

Navigate to **Events > Event Stream**. This page shows every event that Axiom has recorded from its event
sources. Each event shows:

- **Source** — which event source produced it (e.g. `github`)
- **Type** — the normalized event type (e.g. `issue.created`, `pr.merged`)
- **Ref** — the full URL identifying the subject of the event
- **Timestamp** — when the activity occurred in the source system

Narrow the list with filter chips for **Event Type**, **Event Source**, **Ref** and **Actor** (the exact
login, case-insensitive). Clicking an event's type, source or actor label adds it as a filter.

Click any event row to open the event detail page. It has three tabs:

- **Event** — the full typed payload. This is useful for verifying that the event source is producing the
  data you expect.
- **Processing** — the event processing trail: one entry per subscription that evaluated the event, with
  its status, attempts and routing outcome (see [Step 2](#step-2-verify-a-subscription-matched)).
- **Runs** — the workflow runs started from this event, by routing rules or manually.

Use **Run workflow…** on the event detail page to start any published workflow from the event by hand,
for example to test a workflow against a real event. See
[Triggering a Workflow](workflows.md#triggering-a-workflow).

**If no events appear**, check the event source configuration:

1. Navigate to **Events > Event Sources** and click on the event source
2. Check the **Poll Logs** tab — this shows the result of each poll cycle, including any errors
3. Verify the event source is **Enabled**
4. Verify the **Authentication Secret** is set and the token is valid
5. Check the **Poll Interval** — a long interval means events may be delayed

### Step 2: Verify a Subscription Matched

If events are appearing in the stream but nothing downstream is happening, check **Events >
Subscriptions**:

1. Open the subscription you expect to match the event
2. On the **Filter** tab, use **Preview** to test the filter expression against recent events and confirm
   it matches
3. Verify the subscription is **Enabled**
4. Check **Process Events From** — events that occurred before this cutoff are never evaluated against the
   subscription
5. Confirm at least one routing rule is configured on the **Routing** tab with the type you expect
   (`create-workflow` or `workflow-dispatch`)

Each (event, subscription) pair is tracked in a processing ledger, shown on the event's **Processing**
tab. Its status is `skipped` (filter didn't match), `completed` (routing succeeded), `failed` (routing
threw an exception and will be retried), or `exhausted` (all attempts failed). A failed entry is retried
with a growing delay, up to `axiom.stream-pipeline.max-attempts` attempts in total (default 3). The entry
shows its attempts, last attempt and next retry; use **Retry now** (or **Retry once more** for an
exhausted entry) to retry immediately. A `create-workflow` rule whose subject already has an active run of
the same definition completes with a **skipped** run item in its routing outcome — no run is queued.

### Step 3: Check the Run

Open the **Runs** page and filter by subject, workflow, status or label. The run detail page shows:

- On the **Overview** tab: the run's status, any failure reason, the triggering event, the events that
  resumed it, its inputs and labels, and the tasks created by its nodes with their output and log
- On the **Diagram** tab: where the run is in the graph — whether it's waiting on a timer (`wait` node), an
  external event (`receive-event` node), or a human response (`human-task` node, visible in the Inbox)
- On the **Timeline** tab: the nodes the run passed through, in order
- On the **Execution Trace** tab: the run's trace (see [Using Traces](#using-traces))

If you fix the cause of a failed run, start it again with **Re-run** on the subject's **Runs** tab. See
[Re-running a Failed Run](workflows.md#re-running-a-failed-run).

See [Workflows](workflows.md) for the full authoring and run-time reference.

### Step 4: Check the Task

Navigate to **Logs > Tasks**. This page shows all tasks across all subjects, with:

- **Action Type** — which action type was used
- **Subject** — link to the subject
- **Status** — Pending, InProgress, Completed, Failed, AwaitingInput, etc.

Click **View Log** on a completed or failed task to see the full execution log — the AI agent's
conversation, tool calls, and output. This tells you exactly what the agent did (or tried to do).

**Common task issues:**

- **Failed** — check the execution log for errors. Common causes: tool script failures, agent subprocess
  timeouts, missing secrets.
- **Pending** — no matching agent is idle. Check **Components > Agents** for an enabled agent whose
  capabilities match `action:<action-type-name>`.
- **AwaitingInput** — the task needs a human response. Navigate to the **Inbox** to respond.

---

## Tracing Schedule Triggers

Open the workflow definition and go to the **Triggers** tab. Each schedule trigger shows:

- **Next run** — when it fires next (empty while disabled). Cron expressions are evaluated in **UTC**.
- **Last run** and **Last outcome** — `started`, `skipped` (the fixed subject already had an active run of
  this definition), or `failed: <reason>`

Runs started by a schedule trigger carry the label `trigger: schedule`, so you can find them on the **Runs**
page with the label filter `trigger:schedule`, or open the trigger's fixed subject to see its run timeline.

---

## Using the Subject Detail Page

The subject detail page brings together all the information about a single subject. Navigate to
**Subjects** and click on a subject.

### Runs Tab

A list of every workflow run on the subject, active and past, newest first. Click a run to open its detail
page. Failed runs have a **Re-run** button that starts a new run of the latest published version of the
workflow with the same title, labels and inputs (see
[Re-running a Failed Run](workflows.md#re-running-a-failed-run)).

### Tasks Tab

Shows every task created by runs on this subject. Each task lists its action type, assigned agent, status,
and timestamps. Click **View Log** on any completed or failed task to see exactly what the agent did, or
**Lineage** to see where the task came from.

### Thread Tab

The thread is a chronological narrative of everything that happened on the subject. Each entry has an
**author type** (agent, user, or system) and an **entry type** (task-result, event, etc.). The content is
rendered as Markdown.

### Events Tab

Shows all stream events (from event sources) that were associated with this subject.

---

## Following the Chain of Work (Lineage)

The **Lineage** panel shows the chain of work an item belongs to, so you do not have to hop between the event,
trace, workflow run, task and usage pages. It is available on:

- the event detail page (**Lineage** tab),
- the workflow run detail page (**Lineage** tab),
- the **Logs > Tasks** page and the subject **Tasks** tab (**Lineage** button on each task).

The panel has two views:

- **Show origin** walks back to what caused the item, for example task ← workflow run ← event.
- **Show results** walks forward to what the item produced, for example event → workflow run → tasks.

Every item links to its own page and shows its status and its direct AI cost; the total AI cost of the items
shown is displayed below the tree. Items whose records were removed by data retention are shown in italics as
"deleted or unavailable". Very large chains are cut off after a fixed number of levels and items; the panel
says so when this happens.

---

## Using Traces

While the activity log, event stream, and task log each show one dimension of what happened, a **trace** shows
the complete tree structure of a single workflow run. Traces answer the question: "What exactly happened, in
what order, and how long did each step take?"

### When to Use Traces vs. the Activity Log

- Use the **Activity Log** when you want a timeline view across multiple events and
  subjects — "what has the system been doing?"
- Use a **Trace** when you want to drill into one specific run — "what happened step by
  step for this workflow run?"

### Accessing Traces

- **Logs > Traces** — browse all traces, with filter chips for type and status
- The workflow run detail page's **Execution Trace** tab — the trace of that run
- The **Logs > All Activity** page — the **Trace** column links each entry to its trace, and the subject
  **Tasks** tab has a **Trace** column for each task

### Reading the Trace Graph

The trace detail page renders the node tree as a left-to-right interactive tree:

- **Nodes** represent pipeline steps — each shows an icon, a status label, a summary,
  and timing information
- **Edges** connect parent nodes to their children, showing the execution flow
- Node color reflects status: green for completed, blue for in-progress, red for
  failed

### Viewing Node Details

Click any node in the graph to open a detail view. The content depends on the node's
referenced entity type — for example, an `event` node shows the raw event payload, a
`task` node shows action type/agent/status/output, and a `tool-execution` node shows
the full JSON input and output. The tool execution detail is especially useful for
debugging — you can see exactly what data each tool received and returned.

### Real-Time Updates

When a trace is still in progress, the graph updates automatically as new nodes are
added. You don't need to refresh the page — the UI receives server-sent events (SSE)
and re-renders the graph when the trace changes.

---

## Debugging Checklist

When something isn't working, work through the pipeline in order:

1. **Is the event source polling?** Check the event source detail page > **Poll Logs** tab.
2. **Are events being recorded?** Check **Events > Event Stream**.
3. **Does a subscription match?** Check **Events > Subscriptions** and use Preview.
4. **Did the routing rule fire?** Check the event's **Processing** tab for the ledger status and routing
   outcome of that (event, subscription) pair — including `skipped` run items — and its **Runs** tab, or
   look for the run on the **Runs** page.
5. **For schedules, did the trigger fire?** Check the **Triggers** tab's last outcome.
6. **Where is the run?** Open the run and check its current node, status, and failure reason.
7. **Was an agent available?** Check **Components > Agents** — is there an enabled agent with matching
   capabilities?
8. **Did the task execute?** Check **Logs > Tasks** or the run's tasks. Read the execution log.
9. **Did the tools work?** The task execution log shows each tool call and its output. If a tool failed,
   check its script template under **Components > Tools**.
10. **Were secrets available?** If a tool needs API credentials, verify the secret exists under
    **Components > Secrets** and the action type's environment is configured correctly.

!!! tip "Use a Trace for the Full Picture"
    For any workflow run, opening its trace gives you the entire run tree in one view — every step, every
    tool call. This is often faster than checking each log page individually, especially when you need to
    understand the relationship between steps.
