# Logging and Debugging

When something isn't working as expected — a report fails, a task produces the wrong
result, or an event seems to be ignored — Axiom provides several layers of logging
to help you trace what happened and why. This guide explains how to use them.

---

## The Processing Pipeline

Understanding Axiom's processing pipeline is the key to effective debugging. Every
piece of work follows a traceable path through the system:

**For event-driven automation:**

```
Event Source polls → Event created → Manager evaluates → Decision made → Task assigned → Actor executes
```

**For reports:**

```
Schedule triggers (or Run Now) → AI agent launched → Tools executed → Report generated
```

At each stage, Axiom records what happened. Debugging is a matter of finding where in
the pipeline things went wrong and examining the logs at that stage.

---

## Where to Look: A Quick Reference

| Symptom | Where to check first |
|---------|---------------------|
| No events appearing at all | Event Source detail page > Poll Log tab |
| Events appear but nothing happens | **Logs > Manager Decisions** — check decision type and log |
| Manager made wrong decision | Manager decision execution log — review the AI's reasoning |
| Task was created but failed | **Logs > Tasks** — click **View Log** on the failed task |
| Task completed but result is wrong | Project detail page > Tasks tab > **View Log** |
| Report failed | Report detail page > **View Log** |
| Report content is wrong or incomplete | Report detail page > **View Log** — check tool output |
| Don't know where to start | **Logs > All Activity** — scan the timeline |

---

## All Activity Log

**Logs > All Activity** is the unified timeline of everything Axiom has done. This is
the best starting point when you don't know where to look.

Every entry has a **type** label that tells you what happened:

| Entry Type | Meaning |
|------------|---------|
| `event-received` | An event was received from an event source |
| `manager-evaluated` | The Manager triaged an event and made a decision |
| `manager-error` | The Manager failed while evaluating an event |
| `manager-skipped` | The Manager skipped an event (e.g. duplicate) |
| `manager-escalation` | The Manager flagged a decision for human review |
| `manager-no-decision` | The Manager couldn't determine an action |
| `project-created` | A new project was created |
| `project-closed` | A project was marked completed |
| `project-reopened` | A completed project was reopened |
| `task-created` | A task was assigned to an actor |
| `task-started` | An actor began executing a task |
| `task-completed` | A task finished successfully |
| `task-failed` | A task failed |
| `task-awaiting-input` | The AI agent asked the user a question |
| `event-ignored` | The Manager decided to ignore an event |
| `report-generating` | A report started generating |
| `report-completed` | A report finished successfully |
| `report-failed` | A report failed |
| `pipeline-error` | An internal pipeline error occurred |

### Filtering the Activity Log

The activity log can be filtered by:

- **Event ID** — show all activity related to a specific event
- **Entry Type** — show only manager decisions, only task activity, etc.
- **Summary** — text search across activity summaries
- **Project ID** — show all activity for a specific project

Clicking an **Event ID** chip in the table adds it as a filter, letting you quickly
trace all the activity that followed a single event.

### Viewing Execution Logs

Some activity entries have a **View Log** link:

- **manager-evaluated** and **manager-error** entries link to the Manager's execution
  log — the full AI conversation showing how the Manager analyzed the event and arrived
  at its decision
- **task-completed** and **task-failed** entries link to the task's execution log — the
  full AI agent output showing what the actor did

---

## Tracing Event-Driven Automation

### Step 1: Verify Events Are Being Received

Navigate to **Logs > Events**. This page shows every event that Axiom has received from
its configured event sources. Each event shows:

- **Source** — which event source produced it (e.g. `github`)
- **Event Type** — what kind of activity it represents (e.g. `issue-created`,
  `comment-added`, `issue-updated`)
- **Repository** — which repository the event came from
- **Issue** — the issue reference (e.g. `owner/repo#42`)
- **Project** — the project this event was associated with (if any)

Click any event row to open the **Event Detail** modal, which shows the full raw JSON
payload. This is useful for verifying that the event source is producing the data you
expect.

**If no events appear**, check the event source configuration:

1. Navigate to **Configuration > Event Sources** and click on the source
2. Check the **Poll Log** tab — this shows the result of each poll cycle, including
   any errors
3. Verify the source is **Enabled**
4. Verify the **Authentication Secret** is set and the token is valid
5. Check the **Poll Interval** — a long interval means events may be delayed

### Step 2: Check the Manager's Decision

Navigate to **Logs > Manager Decisions**. This page shows only manager-related activity,
filtered from the main activity log.

For each decision, you can see:

- **Event** — the event ID that was evaluated (click to view the event payload)
- **Type** — the decision outcome (`manager-evaluated`, `manager-error`,
  `manager-skipped`, etc.)
- **Summary** — a brief description of what the Manager decided

Click **View Log** to open the Manager's execution log. This shows the full AI
conversation: the prompt that was sent (with all placeholders substituted), the Manager's
reasoning, and the structured decision it produced. This is the most useful log for
understanding *why* the Manager chose to create a project, assign a particular action
type, or ignore an event.

**Common Manager issues:**

- **`manager-skipped`** — the Manager determined this was a duplicate or irrelevant
  event. Check the log to see its reasoning.
- **`manager-no-decision`** — the Manager couldn't determine what to do. This often
  means the Manager's prompt template needs refinement, or the event type isn't covered
  by any action type.
- **`manager-error`** — the Manager subprocess failed. Check the log for error details
  (timeout, API failure, malformed response).
- **`manager-escalation`** — the Manager's confidence was below the threshold. The
  decision was logged but not executed.

### Step 3: Check the Task

Navigate to **Logs > Tasks**. This page shows all tasks across all projects, with:

- **Action Type** — which action type was used
- **Project** — link to the parent project
- **Status** — Pending, InProgress, Completed, Failed, etc.
- **Created By** — whether the Manager or a user triggered it

Click **View Log** on a completed or failed task to see the full execution log — the
AI agent's conversation, tool calls, and output. This tells you exactly what the actor
did (or tried to do).

**Common task issues:**

- **Failed** — check the execution log for errors. Common causes: tool script failures,
  AI engine timeouts, missing secrets.
- **Pending** — no actor is available. Check that you have AI agent actors configured
  and that their capabilities match the action type.
- **AwaitingInput** — the AI agent asked a question. Navigate to the project's Tasks tab
  to respond.

---

## Tracing Reports

### Step 1: Check the Report Status

Navigate to **Reports** in the sidebar. Find your report and check its status:

- **Completed** — the report generated successfully. Click it to view the content.
- **Failed** — the report generation failed. Click it and then click **View Log** to
  see the execution log.
- **Generating** — the report is still running. Wait or check the activity log for
  progress.
- **Pending** — the report is queued but hasn't started yet.

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
- **Report never runs** — check the report definition: is it **Enabled**? Is the
  **Schedule** set correctly? Is the **Time of Day** in the future?

---

## Using the Project Detail Page

For event-driven automation, the project detail page brings together all the information
about a single piece of work. Navigate to **Projects** and click on a project.

### Tasks Tab

Shows every task assigned within this project. Each task lists its action type, assigned
actor, status, who created it, and timestamps. Click **View Log** on any completed or
failed task to see exactly what the actor did.

If a task is in **AwaitingInput** status, you can respond directly from this tab — the
AI agent asked a question and is waiting for your answer.

### Thread Tab

The thread is a chronological narrative of everything that happened in the project.
Each entry has an **author type** (manager, actor, user, or system) and an **entry type**
(decision, task-result, event, etc.). The content is rendered as Markdown.

The thread is the best place to understand the full story of a project — why it was
created, what decisions were made, what work was done, and what the results were.

### Events Tab

Shows all external events (from GitHub, Jira, or internal) that were associated with
this project. This helps you understand what activity triggered the Manager's decisions.

### Metrics Tab

Shows resource usage for this project: disk usage (workspace size), total AI cost,
number of AI invocations, and input/output token counts.

---

## Debugging Checklist

When something isn't working, work through the pipeline in order:

1. **Is the event source polling?** Check the event source detail page > Poll Log tab.
2. **Are events being created?** Check **Logs > Events**.
3. **Did the Manager evaluate the event?** Check **Logs > Manager Decisions**. Read
   the execution log to understand its reasoning.
4. **Was the right action type selected?** The Manager's execution log shows which
   action type it chose and why.
5. **Was an actor available?** Check **Configuration > Actors** — is there an actor
   with matching capabilities?
6. **Did the task execute?** Check **Logs > Tasks** or the project's Tasks tab. Read
   the execution log.
7. **Did the tools work?** The task execution log shows each tool call and its output.
   If a tool failed, check its script template under **Configuration > Tools**.
8. **Were secrets available?** If a tool needs API credentials, verify the secret exists
   under **Configuration > Secrets** and the action type's environment is configured
   correctly.
