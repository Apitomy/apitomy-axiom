# Concepts

Apitomy Axiom is an event-driven orchestration platform that automates software development work with
**workflows**. Everything that Axiom executes on your behalf happens inside a workflow run:

- **Event → Create Workflow** — an event from GitHub or Jira starts a new workflow run on the subject the
  event is about (for example, an issue or pull request)
- **Event → Dispatch to Workflow** — an event is delivered to workflow runs that are already waiting for
  it at a `receive-event` node
- **Schedule → Create Workflow** — a cron schedule starts a workflow run on a fixed subject

Workflow runs are the primary object in the UI — the **Runs** page (`/runs`) is the landing page. Alongside
workflows, the **AI Assistant** provides an interactive conversational interface for arbitrary tasks.

Workflows share a common set of configuration items — tools, secrets, MCP servers, and more — that give AI
agents the capabilities they need. This guide explains each concept and how they relate.

---

## Subjects

A **Subject** is the thing a workflow run is about: a GitHub issue, a pull request, a Jira ticket, or a
named subject created by a schedule trigger. Every workflow run requires a subject. A subject records:

- **Name**, **body**, **type**, **ref** (the URL that identifies it), **repository**, and **labels**
- A **workspace directory** (`subject-<id>`) shared by every run and task on the subject
- A **thread** — a chronological log of activity (events, task results, notes)

Subjects are found or created automatically: a `create-workflow` routing rule finds or creates the subject
from the event's `ref` URL, and a schedule trigger creates its fixed subject on first firing.

Subjects have **no lifecycle of their own**. A subject is **active** when any of its workflow runs is
`running` or `waiting`, and **inactive** otherwise. There is nothing to close or reopen — when the work is
done, the runs finish and the subject becomes inactive. An inactive subject is deleted (along with its
workspace) once its most recent run finished longer ago than the **Inactive subjects** retention setting
(see [Data Retention](../getting-started/configuring-axiom.md#data-retention)).

The subject detail page shows the subject header (ref, metadata, derived active state), a timeline of all
runs on the subject (active and past), the thread, and workspace/metrics information.

---

## Workflows

A **Workflow Definition** is a versioned, visually authored graph of nodes — actions, human tasks,
branches, waits, and `receive-event` nodes. For the full authoring and run-time reference, see
[Workflows](workflows.md).

### Workflow Runs

A **Workflow Run** is one execution of a workflow definition against a subject. Each run has:

| Field | Purpose |
|-------|---------|
| **Title** | Defaults to `<definition name> — <subject name>`; at most 500 characters |
| **Status** | `running`, `waiting`, `completed`, `failed`, or `cancelled` |
| **Subject** | The subject the run operates on (required) |
| **Definition / version** | The workflow definition and the version that was started |
| **Labels** | Key/value labels for organization and filtering |
| **Inputs** | The values passed to the run when it was started |

Runs have **no outputs**. The result of each step is available from the task output of each action node.
The Runs page filters runs by status, definition, subject, and label; individual runs can be cancelled
from the run detail page.

### Concurrency Rule

At most **one active run per (subject, workflow definition)** is allowed. This permits:

- **Sequential runs** of the same definition on a subject (for example, a re-run after the first run
  completes), sharing the subject's workspace and history
- **Concurrent runs of different definitions** on the same subject

A trigger that would start a second active run of the same definition on the same subject — a
`create-workflow` routing rule or a schedule trigger — is **skipped, not queued**, and the skip is
recorded (in the routing outcome or the schedule trigger's last outcome).

Tasks on a subject execute one at a time, so concurrent runs of different definitions on the same subject
take turns running their action nodes.

### Schedule Triggers

A workflow definition can have zero or more **schedule triggers**, configured on the **Triggers** tab of the
workflow definition page:

| Field | Purpose |
|-------|---------|
| **Cron** | A 5-field Unix cron expression, evaluated in UTC |
| **Subject name** | A fixed subject (at most 246 characters); created on first firing and reused afterwards |
| **Inputs** | Optional static inputs passed to every run |
| **Enabled** | Whether the trigger fires |

Because every firing reuses the same subject, run history accumulates on that subject. If the subject
already has an active run of the definition, the firing is skipped. See
[Workflows](workflows.md#schedule-triggers) for details.

---

## Action Types

An Action Type defines a kind of work that a workflow `action` node performs. Action types are invoked
**only** from workflow action nodes — there is no event, UI, or API entry point that runs an action type
directly. Every action type is available in the workflow editor palette. Each action type specifies:

| Field | Purpose |
|-------|---------|
| **Name** | Identifies the action type (e.g. "Implement Feature", "Code Review") |
| **Execution mode** | `agent` (AI agent or human-completed task) or `script` (bash script) |
| **Prompt template** | Instructions for the AI agent (agent mode) |
| **Script template** | Bash script to execute (script mode) |
| **Inputs / outputs** | The typed contract between the action and its workflow node |
| **Allowed tools** | Which tools the AI agent may use |
| **Environment** | Custom environment variables for the subprocess |
| **Model / Engine** | Override the global AI model or agent type for this action type |
| **Limits** | Optional max steps, max budget, and timeout |

### Execution Modes

**Agent mode** — the action is executed by an AI agent. **Script mode** — a bash script runs directly.

### Inputs, Placeholders and Mappings

An action type declares everything it operates on as **inputs** (for example an issue reference, a
repository, or a subject id). The only template placeholder is `{{inputs.NAME}}`, where `NAME` must be a
declared input; any other `{{...}}` placeholder is rejected by validation. Action types never see ambient
subject data — values reach them only through inputs.

Each workflow `action` node maps values into the action type's inputs, typically from the run context
(`context.*`) or from outputs of earlier nodes. The run context always provides:

| Context key | Value |
|-------------|-------|
| `subjectId` | Internal subject ID |
| `subjectName` | Subject name |
| `ref` | Full URL identifying the subject (empty when unknown) |
| `repository` | Repository, when applicable (empty otherwise) |
| `event` | The triggering event, for event-started runs |

These keys cannot be overridden by supplied run inputs. Mapping rules:

- Every **required** input must be mapped on the node.
- Mapping a name that is not a declared input is an error.
- An **optional** input may be left unmapped; it is then absent, omitted from the Inputs section, and
  `{{inputs.NAME}}` renders as an empty string.
- A required input may not be mapped to an empty expression, and every mapping must be valid EL.
- Mappings read the run context, so use `context.event`, not a bare `event.` reference.
- A `context.KEY` reference must name a key the workflow provides: a key from the table above, a Start node
  input, or an output that a node writes to the context. An unknown key is an error for a required input and
  a warning for an optional one.
- Validation does not consider branch order, and an expression can still evaluate to nothing at run time (for
  example a missing payload field). A task whose required input has no value fails when it starts.

For example, an action type declaring a required `ref` input and an optional `repository` input, used by a
node that maps `ref` from `context.ref` and `repository` from `context.repository`:

```
Triage the issue at {{inputs.ref}}.
```

**Agent prompt.** The agent receives the rendered prompt template followed by an `## Inputs` section that
lists every declared input that has a value (in declaration order, even if it is also referenced inline),
and then a `## Required output` contract derived from the declared outputs. Do not write output-format
instructions in the prompt — Axiom appends the contract automatically.

**Script environment.** Scripts receive the plumbing environment variables `AXIOM_API_URL` (Axiom's
API base URL), `AXIOM_TASK_ID` and `AXIOM_WORK_DIR`, plus secrets and the action type's environment.
Agents receive none of these; they run in the task workspace and get data only through declared inputs.
Subject data is not passed through the environment; declare an input instead, e.g.
`curl "$AXIOM_API_URL/subjects/{{inputs.subjectId}}"`.

> **Input values are treated as literal data.** In script mode an `{{inputs.NAME}}` placeholder is not
> inlined into the script text. Instead its value is passed as the environment variable
> `AXIOM_INPUT_NAME` and the placeholder expands to a reference to that variable — for example
> `echo {{inputs.ref}}` becomes `echo "${AXIOM_INPUT_ref}"`. A value containing shell metacharacters
> (`$(...)`, backticks, `;`, `&&`, newlines, etc.) is never executed as code. The expansion adapts to the
> surrounding quotes, so a placeholder can go wherever you'd use a shell variable — unquoted, inside double
> quotes (`"Fix: {{inputs.ref}}"`), or inside single quotes (`'{{inputs.ref}}'`).

### Tasks

A Task is a single unit of work within a workflow run. Tasks are created only when a workflow reaches an
`action` or `human-task` node. Each task records its assigned agent, action type, execution output,
status, cost, and duration. Task statuses:

| Status | Meaning |
|--------|---------|
| **Pending** | Queued, waiting for an available agent |
| **InProgress** | Currently being executed |
| **AwaitingInput** | The task requires human input and is visible in the Inbox |
| **Completed** | Finished successfully |
| **Failed** | Execution failed |
| **Cancelled** | Cancelled before completion |

### AI Agents

An Agent is a configured slot in Axiom's agent pool that executes tasks. Each configured agent has:

- **Name** and **description**
- **Agent type** — `claude-code`, `opencode`, or `copilot`
- **Capabilities** — glob patterns that determine which work this agent is eligible for (e.g. `action:*`,
  `action:code-review`, or `*` for anything)
- **Enabled** — whether the agent is currently available for new work

A task for action type `X` requests the capability `action:X`. Axiom leases the first enabled, idle agent
whose capability patterns match. Each agent executes one task at a time; if no matching agent is idle, the
task stays queued until one becomes available. The number of configured agents — and how their
capabilities are scoped — therefore determines how much work can run in parallel. For example, scoping
`action:security-review` to a single agent guarantees that sensitive work runs one at a time while
general work (`action:*` on several agents) runs in parallel.

---

## Events and Subscriptions

Axiom polls GitHub and Jira through **Event Sources**, normalizes activity into a single **Event Stream**,
and evaluates each event against your **Subscriptions** to decide which workflows to start or resume.

### The Event Pipeline

```
Event Source ──► Event Stream ──► Subscription filter ──► Routing rule(s) ──► create-workflow
 (GitHub/Jira)   (normalized,     (EL expression,          (ordered list)     or workflow-dispatch
   poller)        deduplicated)    per subscription)
```

1. An **Event Source** polls GitHub or Jira for new activity at a configurable interval
2. New activity is normalized into typed **stream events** and persisted to the event stream, deduplicated
   by source event ID
3. Every enabled **Subscription** is evaluated against each new event using a filter expression
4. When an event matches a subscription's filter, the subscription's **routing rules** run in order
5. A durable processing ledger records the result of every (event, subscription) pair, so processing
   survives restarts and failed routing is retried automatically. Events that match no subscription are
   recorded in the ledger only.

### Event Sources

An Event Source is an authenticated link to an external system. Each event source is configured with:

| Field | Purpose |
|-------|---------|
| **ID** | A URL-safe slug that identifies the event source (e.g. `github-com`) |
| **Name** | Display name |
| **Source type** | `github` or `jira` |
| **Base URL** | The human-readable URL of the system (e.g. `https://github.com`) |
| **Repositories / Jira projects** | Which GitHub repositories or Jira projects to watch |
| **Poll interval** | How often to check for new activity |
| **Authentication secret** | Which secret to use for API access, or fall back to a default provider secret or environment variable |
| **Enabled** | Whether polling is active |

You can create multiple event sources of the same type — for example, one for `github.com` and another for
a GitHub Enterprise instance.

### Event Stream

Every event an event source detects is normalized into a single, browsable stream of typed events (issue,
pull request, and repository activity for GitHub; issue activity for Jira). Events are deduplicated so the
same source activity is never recorded twice. You can browse the raw stream from **Event Stream**
in the UI, independent of whether any subscription has matched it yet.

### Subscriptions

A Subscription is a filtered view over the event stream with configurable routing. Each subscription has:

| Field | Purpose |
|-------|---------|
| **Name** | Display name |
| **Filter expression** | An EL expression evaluated against each event (e.g. `event.type.startsWith('pr.') && event.eventSourceId == 'github-com'`). An empty expression matches every event. |
| **Process events from** | The cutoff timestamp; events that occurred before this are never evaluated against this subscription (defaults to the moment the subscription is created) |
| **Routing rules** | An ordered list of destinations for matched events |
| **Labels** | Free-form strings for organization |
| **Enabled** | Subscriptions must be explicitly enabled to process events |

Use **Preview** on the subscription editor to test a filter expression against existing events before
saving.

#### Routing Rule Types

| Type | What Happens |
|------|--------------|
| **`create-workflow`** | Finds or creates the subject from the event's `ref` URL, then starts a run of the configured workflow definition on that subject (with `subjectId`, `ref`, and `event` in context). If the subject already has an active run of that definition, the run is skipped and the skip is recorded in the routing outcome. |
| **`workflow-dispatch`** | Broadcasts the event to **every** waiting run on the correlated subject that is parked at a `receive-event` node with a matching event type (and EL match). If the event does not correlate to a subject, it is delivered to all waiting runs with a matching event type. |

A subscription with no routing rules matches events silently, which is useful for previewing matches
before committing to a destination.

---

## Inbox

The Inbox lists tasks in **AwaitingInput** status — tasks created by workflow `human-task` nodes (and
agent-mode tasks that need a human answer). A person completes the task in the UI, the result is recorded,
and the waiting workflow run resumes. See [Navigating the UI](navigating-the-ui.md#inbox).

---

## Supporting Configuration

Workflows (and the AI Assistant) share the following configuration items.

### Tools

Tools serve two purposes:

1. **Encapsulate deterministic behavior** — a tool wraps a scripted operation (a shell command, an API
   call, a data query) into a single named unit of work. Instead of relying on the AI agent to improvise a
   sequence of raw commands, you define a tool that performs the operation consistently every time. This
   makes AI-driven tasks more reliable and repeatable.

2. **Control what AI agents can do** — an action type's Allowed Tools list is a strict allowlist. The AI
   agent can *only* use tools that are explicitly permitted — everything else is denied. A "code review"
   action type might allow read-only tools, while an "implement feature" action type might also allow
   write operations.

Each tool has:

- **Name** — a unique identifier, often matching a CLI pattern (e.g. `Bash(gh pr list *)`) or an MCP tool
  name (e.g. `mcp__axiom-tools__github_open_prs`)
- **Description** — what the tool does (helps the AI agent decide when to use it)
- **Labels** — for organization and filtering

Tools are referenced in the **Allowed Tools** list of action types. Only tools explicitly listed are
available to the agent — unlisted tools are denied.

!!! tip "Tool Patterns"
    You can use glob-style patterns for shell commands. For example, `Bash(git log *)` allows any
    `git log` command. `Bash(gh *)` allows any GitHub CLI command.

### Toolsets

A Toolset is a named collection of tools. Instead of listing every tool individually in an action type,
you can reference a toolset using the `@` prefix:

```
@Read-Only Tools
@GitHub Tools
```

When an action type includes `@GitHub Tools` in its allowed tools, all tools in that toolset are made
available to the agent. This keeps tool lists manageable and consistent across multiple action types.

### MCP Servers

An MCP (Model Context Protocol) Server provides tools to AI agents via the MCP standard. Axiom can connect
to MCP servers using two transport modes:

- **stdio** — Axiom launches the server as a subprocess using a configured command and arguments
- **HTTP** — Axiom connects to a running server at a configured URL

MCP servers extend the tool ecosystem beyond built-in shell commands. For example, you might run a custom
MCP server that provides database query tools, API clients, or domain-specific analysis capabilities.

### Secrets

Secrets are encrypted key-value pairs injected as environment variables into AI agent and script
subprocesses. Common uses:

- `GH_TOKEN` — GitHub API authentication
- `JIRA_API_TOKEN` — Jira API authentication
- Provider-specific tokens for other integrations

Secret values are encrypted at rest and never returned by the API. They are automatically injected into
all subprocesses by default. For fine-grained control, action types can specify a custom **Environment**
that selectively references secrets using `${secret:SECRET_NAME}` syntax.

Secrets are also used by Event Sources for API authentication when polling.

### AI Agent Pool

Axiom's AI agent support is pluggable. See [AI Agents](#ai-agents) above for how tasks are matched to
configured agents, and the [AI Assistant](ai-assistant.md) guide for the separate interactive-session use
case.

| Agent Type | CLI | Description |
|------------|-----|-------------|
| **Claude Code** | `claude` | Anthropic's Claude Code CLI (default) |
| **OpenCode** | `opencode` | OpenCode CLI with multi-provider support |
| **GitHub Copilot CLI** | `copilot` | GitHub's Copilot CLI |

Individual action types can override the default agent type and model selection, allowing you to use
different models for different types of work. The AI Engine page in the UI shows the default agent type,
health checks, and available models for each registered agent type.

### Configuration Packs

Configuration Packs let you export and import bundles of configuration items as JSON files. A pack can
include any combination of:

- Action types
- Tools
- Toolsets
- MCP servers
- Session templates
- Event Sources
- Subscriptions
- Workflow definitions

This is useful for sharing configurations between Axiom instances, backing up configuration, or
distributing pre-built setups. A configuration pack is not a full instance backup — see
[Upgrading and Backups](../developer-guide/upgrading-and-backups.md) for what else is needed to fully back
up and restore an instance.

---

## AI Assistant

The AI Assistant is an interactive conversational interface. You can use it for arbitrary tasks — from
creating Axiom configuration items to general-purpose coding, analysis, or exploration.

Sessions are created from **Session Templates**, which define the assistant's system prompt, agent type,
available tools, MCP servers, and working directory. Axiom ships with built-in templates (including the
Configuration Assistant for creating and updating tools, action types, toolsets, workflows, and session
templates), and you can create your own templates.

For full details, see the [AI Assistant](ai-assistant.md) guide.

---

## Dashboards

Dashboards provide a customizable at-a-glance view of your Axiom instance. You can create multiple
dashboards, each with a different set of **widgets** arranged on a drag-and-drop grid.

### Widgets

Axiom ships with a catalog of built-in widgets organized by category:

| Category | Widgets |
|----------|---------|
| **Subjects** | Active Subjects, Subject Spotlight |
| **Operations** | Recent Activity, Inbox, Recent Events |
| **AI & Cost** | AI Cost Summary, AI Cost by Subject |
| **System** | System Status, Action Types, Disk Usage Breakdown |

Each widget is self-contained — it fetches its own data and renders independently. Many widgets support
per-widget configuration (e.g. time window, maximum rows, or which subject to spotlight).

### Dashboard Labels and Filtering

Dashboards support labels for organization. When a dashboard has labels, those labels are passed to all
its widgets as **data filters**. Widgets use these labels to scope their data — for example, a dashboard
labeled `"team-a"` would show only subjects, events, and activity associated with that label.

### Default Dashboard

One dashboard can be designated as the **default**. The default dashboard is displayed when you navigate
to the Dashboards page. If no default is set, the dashboards list is shown instead.

For details on the dashboard UI, see [Navigating the UI](navigating-the-ui.md#dashboards).

---

## How It All Fits Together

```
Event Source ──► Event Stream ──► Subscription ──┬─ create-workflow ───┐
  └─ secret                                    └─ workflow-dispatch ─┼──► waiting runs (receive-event)
                                                                     ▼
Schedule trigger (cron, fixed subject) ───────────────────────► Workflow Run ──► Subject
                                                                     │            (workspace, thread)
                                                     action node ────┼──► Task ──► Agent Pool / script
                                                                     │             uses Tools, @Toolsets,
                                                  human-task node ───┴──► Inbox    MCP Servers, Secrets
```

**Shared building blocks**: Tools, Toolsets, MCP Servers, Secrets, and the Agent Pool are configured once
and referenced from any number of action types.

**Triggers** start runs: `create-workflow` routing rules for events, and schedule triggers for cron
schedules. `workflow-dispatch` rules resume runs that are already waiting.

**Runs** do the work: each action node creates a task that draws from the agent pool (or runs a script),
and each human-task node waits in the Inbox. All runs on a subject share its workspace and thread.

---

## Traces

A **Trace** is a hierarchical record of every step that occurred during a single workflow run. While the
[Activity Log](logging-and-debugging.md) shows a flat timeline of events across all runs, a trace shows
the *tree structure* of one run — which steps led to which, how long each took, and whether each succeeded
or failed.

### When Traces Are Created

Axiom creates a trace (type `workflow`) automatically whenever a workflow run starts. Each trace has a
status (`in-progress`, `completed`, or `failed`), timestamps, and a human-readable summary. Traces with
asynchronous tasks (e.g. AI agent execution) remain `in-progress` until all tasks complete.
### Trace Nodes

Each step in a run creates a **trace node** — a lightweight breadcrumb that
records what happened at that point. Nodes form a parent-child tree: the root node is
the trigger, and child nodes represent subsequent steps such as decision processing,
task creation, and tool execution.

Every node has:

- **Node type** — identifies the kind of step
- **Status** — `in-progress`, `completed`, or `failed`
- **Summary** — a brief description of what happened
- **Duration** — how long the step took (calculated at completion)
- **Entity reference** — a pointer to a more detailed record elsewhere in the system
  (an activity log entry, a task, an event, a tool execution record, etc.)

The entity reference pattern keeps trace nodes small and fast to query. When you click a
node in the UI, the detail is fetched from the referenced entity on demand.

### Tool Call Tracing

When Axiom launches an AI agent to execute a task, it passes trace
correlation data to the subprocess via environment variables (`AXIOM_TRACE_ID` and
`AXIOM_PARENT_NODE_ID`). MCP tool servers that Axiom generates for the agent read these
variables and call back to Axiom's API to register each tool invocation as a trace node.
This creates a detailed record of every tool the agent called, including the full JSON
input and output.

Tool call tracing is non-intrusive — if the callback fails, the tool execution continues
normally. Tracing never interrupts the agent's work.

### Viewing Traces

Traces are accessible from several places in the UI:

- **Logs > Traces** — browse and filter all traces
- **Workflow run detail page** — view the run's trace
- **Subject detail page** — view traces associated with the subject's runs

The trace detail page renders the node tree as an interactive tree. Click any
node to view its full detail, including referenced entity data. See
[Logging and Debugging](logging-and-debugging.md) for a full guide to reading traces.
