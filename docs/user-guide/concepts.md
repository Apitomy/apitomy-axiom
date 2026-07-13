# Concepts

Apitomy Axiom is an event-driven orchestration platform that uses AI to automate
software development workflows. It has three primary capabilities:

- **Reports** — AI-generated reports about your repositories and projects, produced
  on a schedule or on demand
- **Event-driven automation** — monitor GitHub and Jira for activity, triage incoming
  events with an AI Manager, and delegate work to AI or human actors
- **AI Assistant** — an interactive conversational interface for arbitrary tasks, powered by
  customizable session templates

All three capabilities share a common set of configuration items — tools, secrets, MCP
servers, and more — that give the AI agents the capabilities they need. This guide
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
2. Axiom triggers the AI agent — either on a schedule (hourly, daily, weekly, monthly) or
   when you click **Run Now**
3. The agent executes the allowed tools (shell commands, MCP tools) to gather data
4. The agent produces a Markdown report based on your prompt template
5. The report is stored and viewable in the Axiom UI

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
| `{{repositories}}` | Comma-separated list of repositories from your event sources |
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

The second use-case is monitoring external systems for activity and responding
automatically. An AI Manager triages each event and decides what action to take —
creating a project, assigning a task to an actor, or ignoring the event entirely.

### The Event Pipeline

```
Event Source ──► Event Queue ──► AI Manager ──► Decision ──► Action
  (GitHub/Jira)    (normalized)    (triage)    (structured)   (create project,
                                                               assign task, ignore)
```

1. **Event Sources** poll GitHub or Jira repositories for new activity at a
   configurable interval
2. New activity is normalized into **Events** and placed in an internal queue
3. The **Pipeline Orchestrator** dequeues events and passes them to the **AI Manager**
4. The Manager analyzes the event and produces a structured **Decision**
5. The orchestrator executes the decision

### Event Sources

An event source connects Axiom to an external system. Each source is configured with:

| Field | Purpose |
|-------|---------|
| **Name** | Display name |
| **Source type** | `github` or `jira` |
| **Repository/Project URL** | Full URL to the GitHub repo or Jira project |
| **Poll interval** | How often to check for new activity (in seconds) |
| **Authentication secret** | Which secret to use for API access (or auto-detect) |
| **Enabled** | Whether polling is active |

When enabled, a GitHub event source polls for new issues, pull requests, comments, and
reviews. A Jira event source polls for new issues and comments.

### AI Manager

The Manager receives events and produces structured decisions. It uses the configured AI
engine (Claude Code or OpenCode) to analyze each event in context.

The Manager's behavior is controlled by two editable templates:

- **System prompt** — defines the Manager's role, personality, and output format. This
  is sent as system context for every evaluation.
- **Prompt template** — the per-event message sent to the AI. Placeholders are
  substituted at runtime:

| Placeholder | Value |
|-------------|-------|
| `{{actionTypes}}` | Formatted list of all configured action types |
| `{{actors}}` | Formatted list of all configured actors |
| `{{source}}` | Event source type (e.g. "github") |
| `{{eventType}}` | Event type (e.g. "issue-created", "comment-added") |
| `{{issueRef}}` | Issue reference (e.g. "owner/repo#42") |
| `{{repository}}` | Repository (e.g. "owner/repo") |
| `{{payload}}` | Raw event payload JSON |
| `{{projectContext}}` | Existing project details and recent task history |

#### Manager Decisions

The Manager produces one of these decision types:

| Decision | What Happens |
|----------|-------------|
| **create_project** | A new Project is created to track work for this issue |
| **assign_task** | A Task is created and assigned to an Actor using a specific Action Type |
| **update_project** | An existing Project's status or details are updated |
| **ignore** | No action — the event is logged but nothing else happens |
| **request_info** | The Manager needs more context before deciding |

Each decision includes a **confidence score** (0.0–1.0). Only decisions above the
configured threshold (default 0.7) are auto-executed. Lower-confidence decisions are
flagged for human review in the UI.

### Projects

A Project is a long-lived entity that tracks all work related to an issue. When the
Manager decides to create a project for a GitHub issue, Axiom:

1. Creates a Project record linked to the issue reference (e.g. `owner/repo#42`)
2. Sets up a workspace directory for the Project
3. Tracks all tasks, events, and activity related to that issue

Projects follow a lifecycle state machine:

```
Created ──► In Progress ──► Completed
                         ──► Failed
          ──► Idle       ──► In Progress
          ──► Cancelled
```

The project detail page shows:

- **Summary** — status, issue reference, repository, labels, creation date
- **Tasks** — all tasks assigned within this project
- **Thread** — a chronological log of all activity (events, decisions, task results)
- **Events** — raw events associated with this project
- **Metrics** — AI cost, token usage, and disk usage for this project

### Action Types

An Action Type defines a kind of work that can be performed. It is the bridge between
a Manager decision ("assign a task") and the actual execution. Each action type
specifies:

| Field | Purpose |
|-------|---------|
| **Name** | Identifies the action type (e.g. "Implement Feature", "Code Review") |
| **Execution mode** | `actor` (AI agent or human) or `script` (bash script) |
| **Prompt template** | Instructions for the AI agent (actor mode) |
| **Script template** | Bash script to execute (script mode) |
| **Allowed tools** | Which tools the AI agent may use |
| **Environment** | Custom environment variables for the subprocess |
| **Model / Engine** | Override the global AI model or engine for this action type |
| **User triggerable** | Can be manually triggered from the project detail page |
| **Manager triggerable** | Can be selected by the AI Manager during triage |
| **Emits event** | Whether completing this action creates an internal event (enabling chained actions) |

#### Execution Modes

**Actor mode** — the action is executed by an AI agent (or a human actor). The agent
receives the prompt template with placeholders substituted:

| Placeholder | Value |
|-------------|-------|
| `{{managerInput}}` | Instructions/context from the Manager's decision |
| `{{actionType}}` | The action type name |
| `{{issueRef}}` | Issue reference |
| `{{repository}}` | Repository |
| `{{projectName}}` | Project name |

**Script mode** — a bash script runs directly. The script template supports the same
placeholders plus additional ones:

| Placeholder | Value |
|-------------|-------|
| `{{projectId}}` | Internal project ID |
| `{{eventId}}` | The triggering event ID |
| `{{taskId}}` | The task ID |
| `{{apiBaseUrl}}` | Axiom's own API base URL (for callbacks) |

### Tasks

A Task is a single unit of work within a Project. Tasks are created when:

- The Manager assigns work via an action type
- A user manually triggers an action type from the project detail page

Each task records its assigned actor, action type, execution output, status, cost, and
duration. Task statuses:

| Status | Meaning |
|--------|---------|
| **Pending** | Queued, waiting for an available actor |
| **In Progress** | Currently being executed |
| **Awaiting Input** | The AI agent asked the user a question (actor mode) |
| **Completed** | Finished successfully |
| **Failed** | Execution failed |
| **Cancelled** | Cancelled before completion |

### Actors

An Actor is an entity that performs tasks. Axiom supports two types:

**AI Agent** — uses the configured AI engine (Claude Code or OpenCode) as a subprocess.
The agent receives the action type's prompt template, runs with the allowed tools, and
reports back with results.

**Human** — sends notifications (via Slack or Telegram, when configured) to a team
member. The task remains in a pending state until the human updates it through the UI.

Each actor has:

- **Name** and **description**
- **Type** — `ai-agent` or `human`
- **Capabilities** — tags that help the Manager choose the right actor for a task
  (e.g. "analyze", "implement", "review")

#### Why Create Multiple AI Agent Actors?

Each AI agent actor can only execute one task at a time. Creating multiple AI agent
actors serves two purposes:

1. **Control concurrency** — the number of AI agent actors determines how many AI tasks
   can run simultaneously. Three AI agent actors means at most three tasks executing in
   parallel. If all actors are busy, new tasks queue until one becomes available.

2. **Restrict which actors handle which work** — an actor's capabilities are matched
   against the action type when the Manager assigns a task. By giving different actors
   different capabilities, you control which actors are eligible for which types of work.
   For example, you might create a single actor with the capability "security-review" so
   that security review tasks always run one at a time, while having three actors with
   "implement" capability to allow three feature tasks in parallel.

---

## Supporting Configuration

Both reports and event-driven automation share the following configuration items.

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

Secrets are also used by Event Sources for API authentication when polling.

### AI Engine

Axiom's AI engine is pluggable. The global engine setting determines which CLI is used
for Manager evaluations and actor task execution:

| Engine | CLI | Description |
|--------|-----|-------------|
| **Claude Code** | `claude` | Anthropic's Claude Code CLI (default) |
| **OpenCode** | `opencode` | OpenCode CLI with multi-provider support |

Individual action types can override the global engine and model selection, allowing you
to use different models for different types of work.

The AI Engine page in the UI shows the active engine, health checks, and available models.

### Configuration Packs

Configuration Packs let you export and import bundles of configuration items as JSON
files. A pack can include any combination of:

- Action types
- Tools
- Toolsets
- MCP servers
- Report definitions

This is useful for sharing configurations between Axiom instances, backing up
configuration, or distributing pre-built setups.

---

## AI Assistant

The AI Assistant is an interactive conversational interface built on top of Claude Code. You
can use it for arbitrary tasks — from creating Axiom configuration items to general-purpose
coding, analysis, or exploration.

Sessions are created from **Session Templates**, which define the assistant's system prompt,
available tools, MCP servers, and working directory. Axiom ships with built-in templates
(including the Configuration Assistant for creating tools, action types, and report
definitions), and you can create your own templates for custom workflows.

For full details, see the [AI Assistant](ai-assistant.md) guide.

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
│                         AI Engine (Claude Code / OpenCode)           │
│                                                                     │
├─────────────────────────────────────────────────────────────────────┤
│                   EVENT-DRIVEN AUTOMATION                            │
│                                                                     │
│  Event Source ──► Event ──► Manager ──► Decision ──► Task ──► Actor  │
│    └─ secret          ├─ system prompt    │           │        │     │
│                       └─ prompt template  │           │        │     │
│                                           │           │     uses     │
│                                      Action Type      │        │     │
│                                        ├─ prompt ─────┘        │     │
│                                        ├─ tools ───────────────┼──►  │
│                                        └─ environment ─────────┼──►  │
│                                                                │     │
│                             Tools / @Toolsets / MCP Servers ◄──┘     │
│                             Secrets ◄──────────────────────────┘     │
│                                                                     │
│                         AI Engine (Claude Code / OpenCode)           │
└─────────────────────────────────────────────────────────────────────┘
```

**Shared building blocks**: Tools, Toolsets, MCP Servers, Secrets, and the AI Engine
are shared across both use-cases. Configure them once and reference them from any
number of report definitions and action types.

**Reports** are self-contained — they only need a report definition with a prompt
template and tools. No event sources, projects, or actors are required.

**Event-driven automation** uses the full pipeline — event sources feed events to the
Manager, which creates projects and assigns tasks to actors using action types. The
action types determine what tools and prompts the actors use.

---

## Traces

A **Trace** is a hierarchical record of every step that occurred during a single pipeline
run or report generation. While the [Activity Log](logging-and-debugging.md) shows a flat
timeline of events across all runs, a trace shows the *tree structure* of one run — which
steps led to which, how long each took, and whether each succeeded or failed.

### When Traces Are Created

Axiom creates a trace automatically whenever it processes an event or generates a report:

| Trace Type | Trigger | Root Node |
|------------|---------|-----------|
| `event-pipeline` | An event is dequeued for processing | `event-ingested` |
| `report-generation` | A report definition runs (scheduled or ad hoc) | `report-triggered` |

Each trace has a status (`in-progress`, `completed`, or `failed`), timestamps, and a
human-readable summary. Traces with asynchronous tasks (e.g. AI agent execution) remain
`in-progress` until all tasks complete.

### Trace Nodes

Each step in the pipeline creates a **trace node** — a lightweight breadcrumb that
records what happened at that point. Nodes form a parent-child tree: the root node is the
trigger (event received or report triggered), and child nodes represent subsequent steps
like manager evaluation, decision processing, task creation, and tool execution.

Every node has:

- **Node type** — identifies the kind of step
- **Status** — `in-progress`, `completed`, or `failed`
- **Summary** — a brief description of what happened
- **Duration** — how long the step took (calculated at completion)
- **Entity reference** — a pointer to a more detailed record elsewhere in the system
  (an activity log entry, a task, an event, a tool execution record, etc.)

The entity reference pattern keeps trace nodes small and fast to query. When you click a
node in the UI, the detail is fetched from the referenced entity on demand.

### Node Types

| Node Type | Meaning | Appears In |
|-----------|---------|------------|
| `event-ingested` | An event was received and processing began | Event pipeline (root) |
| `manager-evaluation` | The AI Manager was invoked to triage the event | Event pipeline |
| `decision-processed` | A single decision from the Manager was executed | Event pipeline |
| `task` | A task was created and assigned to an actor | Event pipeline |
| `tool-execution` | An MCP tool was invoked during task or report execution | Both |
| `escalation` | The Manager escalated a decision for human review | Event pipeline |
| `event-ignored` | The Manager decided to ignore the event | Event pipeline |
| `report-triggered` | A report generation was triggered | Report (root) |
| `report-ai-invoked` | The AI agent was launched to generate the report | Report |

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
- **Events page** — click **View Trace** on an event row to jump to its pipeline trace
- **Report detail page** — click **View Execution Trace** to see the report's trace

The trace detail page renders the node tree as an interactive tree. Click any
node to view its full detail, including referenced entity data. See
[Logging and Debugging](logging-and-debugging.md) for a full guide to reading traces.
