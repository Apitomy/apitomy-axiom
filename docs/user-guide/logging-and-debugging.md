# Logging and Debugging

When something isn't working as expected — a report fails, a scheduled job produces
the wrong result, a task goes sideways, or an event seems to be ignored — Axiom
provides several layers of logging to help you trace what happened and why. This
guide explains how to use them.

---

## The Processing Pipeline

Understanding Axiom's processing pipeline is the key to effective debugging. Every
piece of work follows a traceable path through the system:

**For event-driven automation:**

```
Connection polls → Event recorded in stream → Subscription filter matches → Routing rule fires → Manager/workflow/action → Task assigned → Agent executes
```

**For reports:**

```
Schedule triggers (or Run Now) → Agent leased from pool → Tools executed → Report generated
```

**For scheduled jobs:**

```
Schedule triggers (or Run Now) → Agent or script launched → Run recorded
```

**For workflows:**

```
Manual trigger or subscription "create-workflow"/"workflow-dispatch" rule → Workflow instance starts → Nodes execute → Tasks created for action/human-task nodes
```

At each stage, Axiom records what happened. Debugging is a matter of finding where in
the pipeline things went wrong and examining the logs at that stage.

---

## Where to Look: A Quick Reference

| Symptom | Where to check first |
|---------|---------------------|
| No events appearing at all | Connection detail page > Poll Log tab |
| Events appear but no subscription matches | **Events > Subscriptions** — use Preview to test the filter expression |
| A subscription matched but nothing happened downstream | Subscription's routing rules — confirm a destination is configured |
| Events reach the Manager but nothing happens | **Logs > Manager Decisions** — check decision type and log |
| Manager made wrong decision | Manager decision execution log — review the AI's reasoning |
| Task was created but failed | **Logs > Tasks** — click **View Log** on the failed task |
| Task completed but result is wrong | Project detail page > Tasks tab > **View Log** |
| Report failed | Report detail page > **View Log** |
| Report content is wrong or incomplete | Report detail page > **View Log** — check tool output |
| Scheduled job failed | Scheduled job detail page > Runs tab > **View Log** |
| A run or report behaves differently from the current definition | Run or report detail > **Configuration used** |
| Scheduled job never runs | Scheduled job detail page — check **Enabled** and **Schedule** settings |
| Workflow run stuck | **Logs > Workflow Runs** — check current node and status |
| No agent picks up a task/report/job | **Configuration > Agents** — confirm an enabled agent's capabilities match |
| Want to see the full run tree for one manager evaluation, workflow run, or report | **Logs > Traces** |
| Don't know where to start | **Logs > All Activity** — scan the timeline |

---

## All Activity Log

**Logs > All Activity** is the unified timeline of everything Axiom has done. This is
the best starting point when you don't know where to look.

Every entry has a **type** label that tells you what happened, including:

| Entry Type | Meaning |
|------------|---------|
| `manager-evaluated` | The Manager triaged an event and made a decision |
| `manager-error` | The Manager failed while evaluating an event |
| `manager-skipped` | The Manager skipped an event (e.g. duplicate) |
| `manager-escalation` | The Manager flagged a decision for human review |
| `manager-no-decision` | The Manager couldn't determine an action |
| `project-created` | A new project was created |
| `task-created` | A task was assigned to an agent |
| `task-started` | An agent began executing a task |
| `task-completed` | A task finished successfully |
| `task-failed` | A task failed |
| `task-awaiting-input` | The task requires human input (Inbox) |
| `event-ignored` | The Manager decided to ignore an event |
| `report-generating` | A report started generating |
| `report-completed` | A report finished successfully |
| `report-failed` | A report failed |
| `workflow-started` | A workflow instance began |
| `pipeline-error` | An internal pipeline error occurred |

### Filtering the Activity Log

The activity log can be filtered by:

- **Entry Type** — show only manager decisions, only task activity, etc.
- **Summary** — text search across activity summaries
- **Project ID** — show all activity for a specific project

### Viewing Execution Logs

Some activity entries have a **View Log** link:

- **manager-evaluated** and **manager-error** entries link to the Manager's execution
  log — the full AI conversation showing how the Manager analyzed the event and arrived
  at its decision
- **task-completed** and **task-failed** entries link to the task's execution log — the
  full AI agent output showing what the agent did

---

## Tracing Event-Driven Automation

### Step 1: Verify Events Are Being Received

Navigate to **Events > Event Stream**. This page shows every event that Axiom has
recorded from its connections. Each event shows:

- **Source** — which connection produced it (e.g. `github`)
- **Type** — the normalized event type (e.g. `issue.created`, `pr.merged`)
- **Ref** — the full URL identifying the subject of the event
- **Timestamp** — when the activity occurred in the source system

Click any event row to open the detail view, which shows the full typed payload. This
is useful for verifying that the connection is producing the data you expect.

**If no events appear**, check the connection configuration:

1. Navigate to **Events > Connections** and click on the connection
2. Check the **Poll Log** tab — this shows the result of each poll cycle, including
   any errors
3. Verify the connection is **Enabled**
4. Verify the **Authentication Secret** is set and the token is valid
5. Check the **Poll Interval** — a long interval means events may be delayed

### Step 2: Verify a Subscription Matched

If events are appearing in the stream but nothing downstream is happening, check
**Events > Subscriptions**:

1. Open the subscription you expect to match the event
2. Use **Preview** to test the filter expression against recent events and confirm it
   matches
3. Verify the subscription is **Enabled**
4. Check **Process Events From** — events that occurred before this cutoff are never
   evaluated against the subscription
5. Confirm at least one routing rule is configured with the destination you expect
   (Manager, workflow dispatch, create workflow, or invoke action)

Each (event, subscription) pair is tracked in a processing ledger with a status of
`skipped` (filter didn't match), `completed` (routing succeeded), or `failed` (routing
threw an exception, retried automatically on each tick).

### Step 3: Check the Manager's Decision

Navigate to **Logs > Manager Decisions**. This page shows only manager-related activity,
filtered from the main activity log.

For each decision, you can see:

- **Type** — the decision outcome (`manager-evaluated`, `manager-error`,
  `manager-skipped`, etc.)
- **Summary** — a brief description of what the Manager decided

Click **View Log** to open the Manager's execution log. This shows the full AI
conversation: the prompt that was sent (with all placeholders substituted), the Manager's
reasoning, and the structured decision it produced. This is the most useful log for
understanding *why* the Manager chose to create a task, run a script action, escalate,
or ignore an event.

**Common Manager issues:**

- **`manager-skipped`** — the Manager determined this was a duplicate or irrelevant
  event. Check the log to see its reasoning.
- **`manager-no-decision`** — the Manager couldn't determine what to do. This often
  means the Manager's prompt template needs refinement, or the event type isn't covered
  by any manager-triggerable action type.
- **`manager-error`** — the Manager subprocess failed. Check the log for error details
  (timeout, API failure, malformed response).
- **`manager-escalation`** — the Manager's confidence was below the threshold. The
  decision was logged and escalated to the Inbox instead of being auto-executed.

### Step 4: Check the Task

Navigate to **Logs > Tasks**. This page shows all tasks across all projects, with:

- **Action Type** — which action type was used
- **Project** — link to the parent project
- **Status** — Pending, InProgress, Completed, Failed, AwaitingInput, etc.
- **Created By** — whether the Manager, a workflow, or a user triggered it

Click **View Log** on a completed or failed task to see the full execution log — the
AI agent's conversation, tool calls, and output. This tells you exactly what the agent
did (or tried to do).

**Common task issues:**

- **Failed** — check the execution log for errors. Common causes: tool script failures,
  agent subprocess timeouts, missing secrets.
- **Pending** — no matching agent is idle. Check **Configuration > Agents** for an
  enabled agent whose capabilities match `action:<action-type-name>`.
- **AwaitingInput** — the task needs a human response. Navigate to the **Inbox** or the
  project's Tasks tab to respond.

---

## Configuration Used by a Run or Report

Scheduled job and report definitions can be edited at any time, so a run or report may have used a different
prompt, model, tool list or script than the definition has now. Axiom records the execution configuration each
scheduled job run and report was created with, both for scheduled and **Run Now** triggers.

- **Scheduled jobs:** open the job, go to the **Runs** tab and expand a run.
- **Reports:** open the report detail page.

The **Configuration used** section shows a label: **Matches current definition**, or **Changed since this
run/report**. Expand it to see each field. When the definition has changed, a second column shows the current
value, changed fields are marked, and **Only changed fields** hides the rest.

Recorded fields:

| Definition | Fields |
|------------|--------|
| Scheduled job | execution mode, prompt template, script template, engine, model, allowed tools, max steps, max budget, timeout, environment |
| Report | prompt template, title template, time window, engine, model, allowed tools, max steps, max budget, timeout, environment |

Names, descriptions, schedules, the enabled flag and labels are not recorded: they do not change what a run
does. Secret values are never stored. In the environment, a value that is exactly a `${secret:NAME}` reference
is shown as is; every other value is shown as `[redacted]`, so a change to a literal environment value is not
detected. Runs and reports created before this feature have no recorded configuration.

---

## Tracing Reports

### Step 1: Check the Report Status

Navigate to **Reports** in the sidebar. Find your report and check its status:

- **Completed** — the report generated successfully. Click it to view the content.
- **Failed** — the report generation failed. Click it and then click **View Log** to
  see the execution log.
- **Generating** — the report is still running. Wait or check the activity log for
  progress.
- **Pending** — the report is queued, usually because no matching agent is currently
  idle.

### Step 2: View the Execution Log

On the report detail page, click **View Log** to see the full AI agent conversation.
This shows:

- The prompt that was sent (with placeholders like `{{timeRangeStart}}` substituted)
- Each tool the agent called and its output
- The agent's reasoning and final report content

**Common report issues:**

- **Tool script failed** — the execution log will show the tool name and the error
  output. Check the tool's script template for bugs.
- **Wrong data in the report** — check the tool output in the execution log. The tool
  may be returning unexpected data, or the prompt template may need to be more specific
  about how to interpret the data.
- **Definition edited since the report ran** — see
  [Configuration Used by a Run or Report](#configuration-used-by-a-run-or-report).
- **Report stuck Pending** — check **Configuration > Agents** for an enabled agent whose
  capabilities match `report:<definition-slug>`.
- **Report never runs** — check the report definition: is it **Enabled**? Is the
  **Schedule** set correctly? Is the **Time of Day** in the future?

---

## Tracing Workflows

Navigate to **Logs > Workflow Runs** to see every workflow instance across all
projects, with its status and current node. Click a run to see:

- The node the run is currently at (or where it stopped)
- Whether it's waiting on a timer (`wait` node), an external event (`receive-event`
  node), or a human response (`human-task` node, visible in the Inbox)
- A link to the run's execution trace

See [Workflows](workflows.md) for the full authoring and run-time reference.

---

## Using the Project Detail Page

For event-driven automation, the project detail page brings together all the information
about a single piece of work. Navigate to **Projects** and click on a project.

### Tasks Tab

Shows every task assigned within this project. Each task lists its action type, assigned
agent, status, who created it, and timestamps. Click **View Log** on any completed or
failed task to see exactly what the agent did.

If a task is in **AwaitingInput** status, you can respond directly from this tab.

### Thread Tab

The thread is a chronological narrative of everything that happened in the project.
Each entry has an **author type** (manager, agent, user, or system) and an **entry type**
(decision, task-result, event, etc.). The content is rendered as Markdown.

The thread is the best place to understand the full story of a project — why it was
created, what decisions were made, what work was done, and what the results were.

### Events Tab

Shows all stream events (from connections) that were associated with this project.
This helps you understand what activity triggered the Manager's decisions.

---

## Using Traces

While the activity log, event stream, and task log each show one dimension of what
happened, a **trace** shows the complete tree structure of a single manager evaluation,
workflow run, scheduled job run, or report generation. Traces answer the question: "What
exactly happened, in what order, and how long did each step take?"

### When to Use Traces vs. the Activity Log

- Use the **Activity Log** when you want a timeline view across multiple events and
  projects — "what has the system been doing?"
- Use a **Trace** when you want to drill into one specific run — "what happened step by
  step for this evaluation, workflow, job run, or report?"

### Accessing Traces

- **Logs > Traces** — browse all traces with filtering by trace type
  (`manager`, `workflow`, `scheduled-job-execution`, or `report-generation`) and status
- **Report detail page** — click **View Execution Trace** on a completed or failed
  report
- **Project detail page** and **workflow run detail page** — traces associated with
  that project or run

### Reading the Trace Graph

The trace detail page renders the node tree as a left-to-right interactive tree:

- **Nodes** represent pipeline steps — each shows an icon, a status label, a summary,
  and timing information
- **Edges** connect parent nodes to their children, showing the execution flow
- Node color reflects status: green for completed, blue for in-progress, red for
  failed, with additional colors for special node types like escalations

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

1. **Is the connection polling?** Check the connection detail page > Poll Log tab.
2. **Are events being recorded?** Check **Events > Event Stream**.
3. **Does a subscription match?** Check **Events > Subscriptions** and use Preview.
4. **Did the routing rule fire?** Check the ledger status for that (event, subscription)
   pair, or look for downstream activity (Manager decision, workflow run, task).
5. **Did the Manager evaluate the event?** Check **Logs > Manager Decisions**. Read
   the execution log to understand its reasoning.
6. **Was the right action type selected?** The Manager's execution log shows which
   action type it chose and why.
7. **Was an agent available?** Check **Configuration > Agents** — is there an enabled
   agent with matching capabilities?
8. **Did the task execute?** Check **Logs > Tasks** or the project's Tasks tab. Read
   the execution log.
9. **Did the tools work?** The task execution log shows each tool call and its output.
   If a tool failed, check its script template under **Configuration > Tools**.
10. **Were secrets available?** If a tool needs API credentials, verify the secret exists
    under **Configuration > Secrets** and the action type's environment is configured
    correctly.

!!! tip "Use a Trace for the Full Picture"
    For any manager evaluation, workflow run, scheduled job run, or report, opening its
    trace gives you the entire run tree in one view — every step, every tool call, every
    decision. This is often faster than checking each log page individually, especially
    when you need to understand the relationship between steps.
