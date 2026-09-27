# Concepts

Apitomy Axiom is an event-driven orchestration platform that uses AI to automate
software development workflows. It has four primary capabilities:

- **Reports** — AI-generated reports about your repositories and projects, produced
  on a schedule or on demand
- **Event-driven automation** — poll GitHub and Jira for activity, filter and route the
  resulting event stream through subscriptions, and let the AI Manager or a workflow
  decide what to do
- **Scheduled Jobs** — CRON-style automation that runs on a configurable schedule,
  independent of events or reports
- **AI Assistant** — an interactive conversational interface for arbitrary tasks,
  powered by customizable session templates

All four capabilities share a common set of configuration items — tools, secrets, MCP
servers, and more — that give AI agents the capabilities they need. This guide
explains each concept and how they relate.

---

## Reports

Reports are AI-generated documents that summarize activity, status, or analysis across
your repositories. A weekly status report, a dependency audit, a PR review summary — any
recurring or ad-hoc analysis that an AI agent can produce by running shell commands and
reading data from your systems.

### How Reports Work

```
Report Definition ──► Axiom triggers AI agent ──► Agent runs tools ──► Report (Markdown)
     (template)         (on schedule or ad hoc)      (shell, MCP)        (stored + viewable)
```

1. You create a **Report Definition** that describes what the report should contain
2. Axiom triggers an AI agent — either on a schedule (hourly, daily, weekly, monthly) or
   when you click **Run Now**
3. The agent executes the allowed tools (shell commands, MCP tools) to gather data
4. The agent produces a Markdown report based on your prompt template
5. The report is stored and viewable in the Axiom UI

Report generation shares the same pool of AI agents used for tasks and scheduled jobs
(see [AI Agents](#ai-agents) below) — an agent whose capabilities match the report is
leased for the duration of the run.

### Report Definitions

A report definition is the template and configuration for a recurring report. Each
definition includes:

| Field | Purpose |
|-------|---------|
| **Name** | Display name for the definition and its generated reports |
| **Prompt template** | Instructions for the AI agent — what to analyze, how to structure the output |
| **Schedule** | When to run: hourly, daily, weekly, monthly, or not scheduled (ad hoc only) |
| **Time window** | Data range: since last run, last 24 hours, last 7 days, or last 30 days |
| **Allowed tools** | Shell commands and MCP tools the agent may use |
| **Environment** | Custom environment variables injected into the subprocess |
| **Initial labels** | Labels automatically applied to generated reports |
| **Enabled** | Whether the schedule is active |

#### Prompt Template Placeholders

The prompt template supports runtime placeholders that Axiom substitutes before sending
to the AI agent:

| Placeholder | Value |
|-------------|-------|
| `{{timeRangeStart}}` | Start of the report time window (ISO date) |
| `{{timeRangeEnd}}` | End of the report time window (ISO date) |
| `{{timeWindow}}` | Human-readable time window description (e.g. "last 7 days") |

#### Example

A weekly status report definition might look like:

- **Schedule**: Weekly (Monday at 08:00)
- **Time window**: Last 7 days
- **Prompt template**: Instructions to summarize merged PRs, open PRs, releases, and
  suggest next-week priorities
- **Allowed tools**: `Bash(gh *)` to query GitHub, plus toolset references like
  `@Report Tools`

### Generated Reports

Each time a report definition runs, it produces a **Report** — a stored Markdown document
with metadata:

- **Title** — derived from the definition name and time range
- **Status** — Pending, Generating, Completed, or Failed
- **Time range** — the start and end dates covered
- **Cost** — AI token cost in USD
- **Duration** — how long generation took
- **Labels** — inherited from the definition, editable per report

Reports are browsable, filterable, and searchable in the UI.

---

## Event-Driven Automation

The second use-case is monitoring external systems for activity and reacting
automatically. Axiom polls GitHub and Jira through **Connections**, normalizes activity
into a single **Event Stream**, and evaluates each event against your **Subscriptions**
to decide what happens next.

### The Event Pipeline

```
Connection ──► Event Stream ──► Subscription filter ──► Routing rule(s) ──► Destination
 (GitHub/Jira)   (normalized,     (EL expression,          (ordered list)     (Manager, workflow,
   poller)        deduplicated)    per subscription)                          or action)
```

1. A **Connection** polls GitHub or Jira for new activity at a configurable interval
2. New activity is normalized into typed **stream events** and persisted to the event
   stream, deduplicated by source event ID
3. Every enabled **Subscription** is evaluated against each new event using a filter
   expression
4. When an event matches a subscription's filter, the subscription's **routing rules**
   run in order, sending the event to one or more destinations
5. A durable processing ledger tracks the result of every (event, subscription) pair,
   so processing survives restarts and failed routing is retried automatically

### Connections

A Connection is an authenticated link to an external system. Each connection is
configured with:

| Field | Purpose |
|-------|---------|
| **ID** | A URL-safe slug that identifies the connection (e.g. `github-com`) |
| **Name** | Display name |
| **Source type** | `github` or `jira` |
| **Base URL** | The human-readable URL of the system (e.g. `https://github.com`) |
| **Repositories / Projects** | Which repositories (GitHub) or projects (Jira) to watch |
| **Poll interval** | How often to check for new activity |
| **Authentication secret** | Which secret to use for API access, or fall back to a default provider secret or environment variable |
| **Enabled** | Whether polling is active |

You can create multiple connections of the same type — for example, one for
`github.com` and another for a GitHub Enterprise instance.

### Event Stream

Every event a connection detects is normalized into a single, browsable stream of
typed events (issue, pull request, and repository activity for GitHub; issue activity
for Jira). Events are deduplicated so the same source activity is never recorded twice.
You can browse the raw stream from **Events > Event Stream** in the UI, independent of
whether any subscription has matched it yet.

### Subscriptions

A Subscription is a filtered view over the event stream with configurable routing. Each
subscription has:

| Field | Purpose |
|-------|---------|
| **Name** | Display name |
| **Filter expression** | An EL expression evaluated against each event (e.g. `event.type.startsWith('pr.') && event.connectionId == 'github-com'`). An empty expression matches every event. |
| **Process events from** | The cutoff timestamp; events that occurred before this are never evaluated against this subscription (defaults to the moment the subscription is created) |
| **Routing rules** | An ordered list of destinations for matched events |
| **Labels** | Free-form strings for organization |
| **Enabled** | Subscriptions must be explicitly enabled to process events |

Use **Preview** on the subscription editor to test a filter expression against existing
events before saving.

#### Routing Rule Destinations

| Destination | What Happens |
|-------------|--------------|
| **Manager** | Sends the event to the AI Manager for triage (see [AI Manager](#ai-manager) below) |
| **Workflow dispatch** | Offers the event to any running workflow instances parked at a `receive-event` node whose event-type and EL match succeed |
| **Create workflow** | Finds or creates a project from the event's `ref` URL, then starts a new instance of the specified workflow definition on that project |
| **Invoke action** | Finds or creates a project, then creates a task for the specified action type directly — bypassing the Manager |

A subscription with no routing rules matches events silently, which is useful for
previewing matches before committing to a destination.

### AI Manager

The Manager receives events routed to it and produces structured decisions. It uses the
configured AI agent to analyze each event in context.

The Manager's behavior is controlled by two editable templates:

- **System prompt** — defines the Manager's role, personality, and output format. This
  is sent as system context for every evaluation.
- **Prompt template** — the per-event message sent to the AI. Placeholders are
  substituted at runtime:

| Placeholder | Value |
|-------------|-------|
| `{{actionTypes}}` | Formatted list of manager-triggerable action types |
| `{{agents}}` | Formatted list of all configured agents |
| `{{source}}` | Event source type (e.g. "github") |
| `{{eventType}}` | Normalized event type (e.g. "issue.created", "pr.merged") |
| `{{ref}}` | Full URL identifying the subject of the event |
| `{{payload}}` | Typed event payload JSON |
| `{{projectContext}}` | Existing project details and recent task history, when the event matches an existing project |

#### Manager Decisions

The Manager produces one of these decision types for each event it evaluates:

| Decision | What Happens |
|----------|-------------|
| **create_task** | A Task is created (and a Project found or created) for the chosen action type |
| **script_action** | Same as `create_task`, but for a script-mode action type |
| **ignore** | No action — the event is logged but nothing else happens |
| **escalate** | The Manager flags the event for human review without taking action |

Each decision includes a **confidence score** (0.0–1.0). Only decisions above the
configured threshold (default 0.7) are auto-executed. Lower-confidence decisions are
automatically escalated to the Inbox for human review.

### Projects

A Project is a long-lived entity that tracks all work related to an issue. When the
Manager (or a routing rule) creates a project for a GitHub issue or Jira ticket, Axiom:

1. Creates a Project record linked to the issue's `ref` URL
2. Sets up a workspace directory for the Project
3. Tracks all tasks, workflow runs, and activity related to that issue

Projects follow a lifecycle state machine:

```
Created ──► InProgress ──► Completed
        ──► Idle       ──► InProgress
```

The project detail page shows:

- **Summary** — status, ref, repository, labels, creation date
- **Tasks** — all tasks assigned within this project
- **Thread** — a chronological log of all activity (events, decisions, task results)
- **Events** — raw stream events associated with this project
- **Metrics** — AI cost, token usage, and disk usage for this project

### Action Types

An Action Type defines a kind of work that can be performed. It is the bridge between
a Manager decision (or a direct `invoke-action` routing rule) and the actual execution.
Each action type specifies:

| Field | Purpose |
|-------|---------|
| **Name** | Identifies the action type (e.g. "Implement Feature", "Code Review") |
| **Execution mode** | `agent` (AI agent or human-completed task) or `script` (bash script) |
| **Prompt template** | Instructions for the AI agent (agent mode) |
| **Script template** | Bash script to execute (script mode) |
| **Allowed tools** | Which tools the AI agent may use |
| **Environment** | Custom environment variables for the subprocess |
| **Model / Engine** | Override the global AI model or agent type for this action type |
| **User triggerable** | Can be manually triggered from the project detail page |
| **Manager triggerable** | Can be selected by the AI Manager during triage |
| **Emits event** | Whether completing this action creates an internal event (enabling chained actions) |

#### Execution Modes

**Agent mode** — the action is executed by an AI agent. The agent receives the prompt
template with placeholders substituted:

| Placeholder | Value |
|-------------|-------|
| `{{managerInput}}` | Instructions/context from the Manager's decision |
| `{{actionType}}` | The action type name |
| `{{ref}}` | Full URL identifying the project's subject |
| `{{repository}}` | Repository, when applicable |
| `{{projectName}}` | Project name |
| `{{event}}` | Raw event payload JSON, when the task originated from an event |
| `{{workDir}}` | The project's workspace directory |
| `{{inputs.NAME}}` | A named workflow input (workflow tasks only) |

**Script mode** — a bash script runs directly. The script template supports the same
placeholders plus additional ones:

| Placeholder | Value |
|-------------|-------|
| `{{projectId}}` | Internal project ID |
| `{{eventId}}` | The triggering event ID |
| `{{taskId}}` | The task ID |
| `{{apiBaseUrl}}` | Axiom's own API base URL (for callbacks) |

> **Placeholder values are treated as literal data.** In script mode each
> placeholder is not inlined into the script text. Instead its value is passed to
> the script as an environment variable and the placeholder expands to a
> reference to that variable — for example `echo {{managerInput}}` becomes
> `echo "${AXIOM_MANAGER_INPUT}"`. This means a value containing shell
> metacharacters (`$(...)`, backticks, `;`, `&&`, newlines, etc.) is never
> executed as code. The expansion adapts to the surrounding quotes, so you can
> place a placeholder wherever you'd use a shell variable and values containing
> whitespace stay a single argument — whether it is unquoted (`{{managerInput}}`),
> inside double quotes (`"Fix: {{managerInput}}"`), or inside single quotes
> (`'{{managerInput}}'`). The matching environment variables — `AXIOM_PROJECT_ID`,
> `AXIOM_EVENT_ID`, `AXIOM_TASK_ID`, `AXIOM_REF`, `AXIOM_REPOSITORY`,
> `AXIOM_PROJECT_NAME`, `AXIOM_MANAGER_INPUT`, `AXIOM_API_URL`, `AXIOM_WORK_DIR`,
> and `AXIOM_INPUT_<NAME>` for workflow inputs — are also available directly.

### Tasks

A Task is a single unit of work within a Project. Tasks are created when:

- The Manager assigns work via an action type
- A routing rule with an `invoke-action` destination fires
- A user manually triggers an action type from the project detail page
- A workflow reaches an `action` or `human-task` node

Each task records its assigned agent, action type, execution output, status, cost, and
duration. Task statuses:

| Status | Meaning |
|--------|---------|
| **Pending** | Queued, waiting for an available agent |
| **InProgress** | Currently being executed |
| **AwaitingInput** | The task requires human input and is visible in the Inbox |
| **Completed** | Finished successfully |
| **Failed** | Execution failed |
| **Cancelled** | Cancelled before completion |

Tasks in **AwaitingInput** status appear in the Inbox — either because the Manager
escalated a low-confidence decision, an AI agent asked a question, or a workflow
reached a human-task node. See [Navigating the UI](navigating-the-ui.md#inbox).

### AI Agents

An Agent is a configured slot in Axiom's agent pool that executes work — tasks, report
generation, and agent-mode scheduled jobs all draw from the same pool. Each configured
agent has:

- **Name** and **description**
- **Agent type** — `claude-code`, `opencode`, or `copilot`
- **Capabilities** — glob patterns that determine which work this agent is eligible for
  (e.g. `action:*`, `report:weekly-status`, or `*` for anything)
- **Enabled** — whether the agent is currently available for new work

#### How Work Is Matched to Agents

Each unit of work requests a capability string when it needs an agent:

| Work | Capability requested |
|------|------------------------|
| Task for action type `X` | `action:X` |
| Report definition with slug `X` | `report:X` |
| Scheduled job with slug `X` | `job:X` |

Axiom leases the first enabled, idle agent whose capability patterns match. Each agent
can execute only one unit of work at a time; if no matching agent is idle, the work
stays queued until one becomes available. This means the number of configured agents —
and how their capabilities are scoped — determines how much work can run in parallel
and which agents are eligible for which kind of work.

#### Why Create Multiple Agents?

1. **Control concurrency** — the number of agents with a matching capability
   determines how many units of that kind of work can run simultaneously.
2. **Restrict which agents handle which work** — by scoping capabilities narrowly
   (e.g. `action:security-review` on a single agent, `action:*` on several others),
   you can guarantee sensitive work runs one at a time while general work runs in
   parallel.

#### Human-Completed Tasks

Not every task is executed by an AI agent. Some action types produce tasks that require
a human to respond — these tasks move to **AwaitingInput** status and appear in the
Inbox until a user completes them through the UI, at which point the task result is
recorded and any downstream automation (e.g. a waiting workflow) resumes.

---

## Scheduled Jobs

Scheduled Jobs provide CRON-style automation that runs on a configurable schedule,
independent of the event pipeline or report system. Use them for recurring maintenance
tasks, periodic data syncs, automated cleanups, or any work that should happen on a
fixed cadence.

### How Scheduled Jobs Work

```
Scheduled Job ──► Axiom triggers execution ──► Agent or script runs ──► Run record
  (definition)      (on schedule or ad hoc)      (agent or bash)        (stored + viewable)
```

1. You create a **Scheduled Job** that defines what to do and when to do it
2. Axiom triggers execution on the configured schedule, or when you click **Run Now**
3. The job runs using the configured execution mode — an AI agent (agent mode) or a
   bash script (script mode)
4. The result is recorded as a **Run** with status, output, cost, and duration

### Scheduled Job Configuration

Each scheduled job includes:

| Field | Purpose |
|-------|---------|
| **Name** | Display name for the job |
| **Description** | What the job does |
| **Schedule** | When to run: hourly, daily, weekly, monthly, or none (manual only) |
| **Time of day** | Time to run (e.g. `08:00`) |
| **Day of week** | For weekly schedules (e.g. `monday`) |
| **Execution mode** | `agent` (AI agent) or `script` (bash script) |
| **Prompt template** | Instructions for the AI agent (agent mode) |
| **Script template** | Bash script to execute (script mode) |
| **Allowed tools** | Tools the AI agent may use (agent mode) |
| **Environment** | Custom environment variables with `${secret:NAME}` support |
| **Model / Engine** | Override the global AI model or agent type |
| **Max steps** | Optional limit on agent turns |
| **Max budget** | Optional cost limit in USD |
| **Labels** | Free-form labels for organization |
| **Enabled** | Whether the schedule is active |

#### Execution Modes

**Agent mode** — the job is executed by an AI agent drawn from the shared agent pool
(capability `job:<slug>`). The agent receives the prompt template with placeholders
substituted:

| Placeholder | Value |
|-------------|-------|
| `{{jobName}}` | The scheduled job name |
| `{{apiBaseUrl}}` | Axiom's own API base URL |

**Script mode** — a bash script runs directly. The script template supports
additional placeholders:

| Placeholder | Value |
|-------------|-------|
| `{{jobName}}` | The scheduled job name |
| `{{jobId}}` | Internal job ID |
| `{{runId}}` | The current run ID |
| `{{apiBaseUrl}}` | Axiom's own API base URL |

### Job Runs

Each time a scheduled job executes, it produces a **Run** — a record of the execution
with metadata:

- **Status** — Pending, Running, Completed, or Failed
- **Trigger** — whether the run was triggered by the schedule or manually
- **Output** — the execution result
- **Cost** — AI token cost in USD (agent mode)
- **Duration** — how long execution took
- **Execution log** — full transcript for debugging

Runs are viewable from the scheduled job detail page and from **Logs > Job Runs**.

### Differences from Reports and Action Types

Scheduled Jobs fill a gap between reports and action types:

- **Unlike reports**, scheduled jobs do not produce a Markdown document or use time
  window placeholders. They are designed for executing tasks, not generating
  documents.
- **Unlike action types**, scheduled jobs are not triggered by events or tied to
  projects. They run on a fixed schedule and are global to the Axiom instance.
- **Like both**, scheduled jobs draw from the shared agent pool and support allowed
  tools, environment variables, and model/engine overrides.

---

## Workflows

Workflows let you define a multi-step automation — including branching, human
approvals, waits, and event-triggered branches — as a versioned, visually authored
graph of nodes. A workflow runs against a single project and drives that project's
tasks.

For the full authoring and run-time reference, see
[Workflows](workflows.md).

---

## Supporting Configuration

Reports, event-driven automation, scheduled jobs, and workflows share the following
configuration items.

### Tools

Tools serve two purposes:

1. **Encapsulate deterministic behavior** — a tool wraps a scripted operation (a shell
   command, an API call, a data query) into a single named unit of work. Instead of
   relying on the AI agent to improvise a sequence of raw commands, you define a tool
   that performs the operation consistently every time. This makes AI-driven tasks more
   reliable and repeatable.

2. **Control what AI agents can do** — an action type's Allowed Tools list is a strict
   allowlist. The AI agent can *only* use tools that are explicitly permitted — everything
   else is denied. This gives you fine-grained control over what each type of work is
   allowed to touch. A "code review" action type might allow read-only tools, while an
   "implement feature" action type might also allow write operations.

Each tool has:

- **Name** — a unique identifier, often matching a CLI pattern (e.g. `Bash(gh pr list *)`)
  or an MCP tool name (e.g. `mcp__axiom-tools__github_open_prs`)
- **Description** — what the tool does (helps the AI agent decide when to use it)
- **Labels** — for organization and filtering

Tools are referenced in the **Allowed Tools** list of action types and report definitions.
Only tools explicitly listed are available to the agent — unlisted tools are denied.

!!! tip "Tool Patterns"
    You can use glob-style patterns for shell commands. For example, `Bash(git log *)`
    allows any `git log` command. `Bash(gh *)` allows any GitHub CLI command.

### Toolsets

A Toolset is a named collection of tools. Instead of listing every tool individually in
an action type or report definition, you can reference a toolset using the `@` prefix:

```
@Read-Only Tools
@Report Tools
@GitHub Tools
```

When an action type includes `@Report Tools` in its allowed tools, all tools in that
toolset are made available to the agent. This keeps tool lists manageable and consistent
across multiple action types and report definitions.

### MCP Servers

An MCP (Model Context Protocol) Server provides tools to AI agents via the MCP
standard. Axiom can connect to MCP servers using two transport modes:

- **stdio** — Axiom launches the server as a subprocess using a configured command and
  arguments
- **HTTP** — Axiom connects to a running server at a configured URL

MCP servers extend the tool ecosystem beyond built-in shell commands. For example, you
might run a custom MCP server that provides database query tools, API clients, or
domain-specific analysis capabilities.

### Secrets

Secrets are encrypted key-value pairs injected as environment variables into AI agent and
script subprocesses. Common uses:

- `GH_TOKEN` — GitHub API authentication
- `JIRA_API_TOKEN` — Jira API authentication
- Provider-specific tokens for other integrations

Secret values are encrypted at rest and never returned by the API. They are automatically
injected into all subprocesses by default. For fine-grained control, action types and
report definitions can specify a custom **Environment** that selectively references
secrets using `${secret:SECRET_NAME}` syntax.

Secrets are also used by Connections for API authentication when polling.

### AI Agent Pool

Axiom's AI agent support is pluggable. See [AI Agents](#ai-agents) above for how work is
matched to configured agents, and the [AI Assistant](ai-assistant.md) guide for the
separate interactive-session use case.

| Agent Type | CLI | Description |
|------------|-----|-------------|
| **Claude Code** | `claude` | Anthropic's Claude Code CLI (default) |
| **OpenCode** | `opencode` | OpenCode CLI with multi-provider support |
| **GitHub Copilot CLI** | `copilot` | GitHub's Copilot CLI |

Individual action types, report definitions, and scheduled jobs can override the
default agent type and model selection, allowing you to use different models for
different types of work.

The AI Engine page in the UI shows the default agent type, health checks, and available
models for each registered agent type.

### Configuration Packs

Configuration Packs let you export and import bundles of configuration items as JSON
files. A pack can include any combination of:

- Action types
- Tools
- Toolsets
- MCP servers
- Report definitions
- Scheduled jobs
- Session templates
- Connections
- Subscriptions
- Workflow definitions

This is useful for sharing configurations between Axiom instances, backing up
configuration, or distributing pre-built setups. A configuration pack is not a full
instance backup — see
[Upgrading and Backups](../developer-guide/upgrading-and-backups.md) for what else is
needed to fully back up and restore an instance.

---

## AI Assistant

The AI Assistant is an interactive conversational interface. You can use it for
arbitrary tasks — from creating Axiom configuration items to general-purpose coding,
analysis, or exploration.

Sessions are created from **Session Templates**, which define the assistant's system
prompt, agent type, available tools, MCP servers, and working directory. Axiom ships
with built-in templates (including the Configuration Assistant for creating and
updating tools, action types, report definitions, scheduled jobs, toolsets, workflows,
and session templates), and you can create your own templates for custom workflows.

For full details, see the [AI Assistant](ai-assistant.md) guide.

---

## Dashboards

Dashboards provide a customizable at-a-glance view of your Axiom instance. You can
create multiple dashboards, each with a different set of **widgets** arranged on a
drag-and-drop grid.

### Widgets

Axiom ships with a catalog of built-in widgets organized by category:

| Category | Widgets |
|----------|---------|
| **Projects** | Project Status Summary, Active Projects, Project Spotlight |
| **Operations** | Recent Activity, Inbox, Recent Events |
| **AI & Cost** | AI Cost Summary, AI Cost by Project |
| **Reports** | Recent Reports |
| **System** | System Status, Event Source Health, Disk Usage Breakdown |

Each widget is self-contained — it fetches its own data and renders independently.
Many widgets support per-widget configuration (e.g. time window, maximum rows, or
which project to spotlight).

### Dashboard Labels and Filtering

Dashboards support labels for organization. When a dashboard has labels, those labels
are passed to all its widgets as **data filters**. Widgets use these labels to scope
their data — for example, a dashboard labeled `"team-a"` would show only projects,
events, and activity associated with that label.

### Default Dashboard

One dashboard can be designated as the **default**. The default dashboard is displayed
when you navigate to the Dashboards page. If no default is set, the dashboards list
is shown instead.

For details on the dashboard UI, see
[Navigating the UI](navigating-the-ui.md#dashboards).

---

## How It All Fits Together

The diagram below shows how the concepts relate:

```
┌─────────────────────────────────────────────────────────────────────┐
│                        REPORTS                                      │
│                                                                     │
│  Report Definition ──► AI Agent ──► Report                          │
│    ├─ prompt template       │                                       │
│    ├─ schedule              │ uses                                   │
│    ├─ allowed tools ────────┼──► Tools / @Toolsets / MCP Servers     │
│    └─ environment ──────────┼──► Secrets                             │
│                             │                                       │
│                       Agent Pool (Claude Code / OpenCode / Copilot)   │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│                   EVENT-DRIVEN AUTOMATION                            │
│                                                                     │
│  Connection ──► Event Stream ──► Subscription ──► Routing ──► ...    │
│    └─ secret                          │            ├─ Manager ──► Task ──► Agent
│                                       │            ├─ Workflow dispatch
│                                       │            ├─ Create workflow
│                                       │            └─ Invoke action ──► Task ──► Agent
│                                                                │     │
│                             Tools / @Toolsets / MCP Servers ◄──┘     │
│                             Secrets ◄──────────────────────────┘     │
│                                                                     │
│                       Agent Pool (Claude Code / OpenCode / Copilot)   │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│                      SCHEDULED JOBS                                  │
│                                                                     │
│  Scheduled Job ──► AI Agent or Script ──► Run                        │
│    ├─ prompt / script      │                                         │
│    ├─ schedule             │ uses                                     │
│    ├─ allowed tools ───────┼──► Tools / @Toolsets / MCP Servers       │
│    └─ environment ─────────┼──► Secrets                               │
│                            │                                         │
│                      Agent Pool (Claude Code / OpenCode / Copilot)    │
└─────────────────────────────────────────────────────────────────────┘
```

**Shared building blocks**: Tools, Toolsets, MCP Servers, Secrets, and the Agent Pool
are shared across all use-cases. Configure them once and reference them from any
number of report definitions, action types, scheduled jobs, and workflows.

**Reports** are self-contained — they only need a report definition with a prompt
template, tools, and a matching agent. No connections, projects, or workflows are
required.

**Event-driven automation** uses the full pipeline — connections feed the event
stream, subscriptions filter and route matching events, and the Manager (or a direct
`invoke-action` rule) creates projects and tasks that draw from the agent pool.

**Scheduled Jobs** are self-contained like reports — they need a job definition with
a schedule and either a prompt template (agent mode) or a script template (script
mode). They run independently of the event pipeline and do not produce Markdown
reports.

---

## Traces

A **Trace** is a hierarchical record of every step that occurred during a single
manager evaluation, workflow run, scheduled job run, or report generation. While the
[Activity Log](logging-and-debugging.md) shows a flat timeline of events across all
runs, a trace shows the *tree structure* of one run — which steps led to which, how
long each took, and whether each succeeded or failed.

### When Traces Are Created

Axiom creates a trace automatically for:

| Trace Type | Trigger |
|------------|---------|
| `manager` | A subscription routes an event to the Manager for evaluation |
| `workflow` | A workflow instance is triggered |
| `scheduled-job-execution` | A scheduled job run starts |
| `report-generation` | A report definition runs (scheduled or ad hoc) |

Each trace has a status (`in-progress`, `completed`, or `failed`), timestamps, and a
human-readable summary. Traces with asynchronous tasks (e.g. AI agent execution) remain
`in-progress` until all tasks complete.

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

When Axiom launches an AI agent to execute a task or generate a report, it passes trace
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
- **Project detail page** — view traces associated with the project
- **Report detail page** — click **View Execution Trace** to see the report's trace

The trace detail page renders the node tree as an interactive tree. Click any
node to view its full detail, including referenced entity data. See
[Logging and Debugging](logging-and-debugging.md) for a full guide to reading traces.
