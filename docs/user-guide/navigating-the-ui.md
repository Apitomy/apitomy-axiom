# Navigating the UI

Axiom's web interface is organized around a sidebar navigation, a top masthead, and a
central content area. This guide gives you a quick tour of the UI so you know where to
find things.

---

## Masthead

The masthead runs across the top of every page. It contains:

- **Apitomy Axiom** — click the title to return to the Dashboard from any page
- **Robot icon** — opens the [AI Assistant](ai-assistant.md) (visible only when the
  Claude Code engine is active)
- **Question mark icon** — opens the About dialog showing the application version, active
  AI engine, and links to the source repository

---

## Dashboard

The Dashboard is the landing page. It gives you a snapshot of the system at a glance:

- **Project status cards** — counts of total, in-progress, idle, created, and completed
  projects
- **Active Projects** — a table of projects that aren't completed, with links to their
  detail pages
- **Recent Reports** — reports generated in the last 24 hours, with status, duration,
  and cost
- **Recent Activity** — a chronological feed of system events (tasks created, projects
  opened, reports generated, etc.)
- **Configuration summary** — counts of event sources, actors, action types, tools, and
  report definitions, each linking to its configuration page

If required configuration is missing (e.g. no actors or no action types), the Dashboard
shows a **Setup Incomplete** warning with links to the relevant configuration pages.

---

## Sidebar Navigation

The sidebar organizes the UI into four sections.

### Reports

The **Reports** page lists all generated reports with their title, status, time range,
labels, and generation date. You can filter by title, status, or labels and paginate
through results.

Click any report to view its full rendered content, metadata (cost, duration, time
range), and execution log.

### Projects

The **Projects** page lists all projects with their name, status, issue reference,
labels, and last update. You can filter by name, status, or labels.

Click any project to open its detail page, which has several tabs:

- **Summary** — project status, issue reference, repository, labels, and key metrics
- **Tasks** — all tasks assigned within this project, with status and execution details
- **Thread** — a chronological log of everything that happened in the project (events
  received, manager decisions, task results)
- **Events** — raw events from external sources associated with this project

From the project detail page you can also:

- **Trigger an action** — manually run a user-triggerable action type against this
  project
- **Edit labels** — add or remove labels for organization and filtering
- **Delete** — remove a completed project and all its data

### Logs

The **Logs** section has four sub-pages, each showing a different slice of system
activity:

| Page | What it shows |
|------|--------------|
| **All Activity** | Unified feed of all system events — project creation, task execution, report generation, manager decisions |
| **Events** | Raw events received from event sources (GitHub issues, PRs, comments) |
| **Manager Decisions** | The AI Manager's triage results — what action it chose for each event and its confidence score |
| **Tasks** | Task execution history — which actor ran what action type, duration, cost, and outcome |
| **Traces** | Visual graph of pipeline execution — shows every step from event ingestion through manager evaluation, decision processing, and task execution |

Each log page supports filtering and pagination.

The **Traces** page deserves special mention. While the other log pages show tabular data,
traces render as an interactive directed graph. Each node in the graph represents a step
in the pipeline (event received, manager evaluated, task created, tool executed, etc.).
Nodes are color-coded by status — green for completed, blue for in-progress, red for
failed. Click any node to open a detail modal showing the full data for that step,
including tool input/output for tool executions and AI reasoning for manager evaluations.

You can also navigate to a trace directly from:

- An **event row** on the Events page — click **View Trace** to see the full pipeline
  trace for that event
- A **report detail page** — click **View Execution Trace** to see the report generation
  trace

### Metrics

The **Metrics** section has two sub-pages:

| Page | What it shows |
|------|--------------|
| **AI Usage** | Token costs broken down by invocation type (task vs. manager), action type, and date range. Shows summary totals at the top. |
| **Disk Usage** | Workspace storage consumed by each project's git clone. Shows total disk usage and per-project breakdown. |

### Configuration

The **Configuration** section contains all the setup pages. Each page lets you create,
edit, and delete configuration items:

| Page | Purpose |
|------|---------|
| **AI Engine** | View the active engine, health checks, and available models |
| **Action Types** | Define kinds of work — prompt templates, allowed tools, execution mode |
| **Actors** | Register AI agents and human actors with capabilities |
| **Manager** | Edit the Manager's system prompt and prompt template |
| **MCP Servers** | Register external MCP tool servers (HTTP or stdio) |
| **Report Definitions** | Create report templates with schedules, time windows, and prompts |
| **Event Sources** | Connect to GitHub or Jira repositories for event polling |
| **Secrets** | Store encrypted credentials injected into subprocesses |
| **Tools** | Define script-based tools with parameters and bash templates |
| **Toolsets** | Group tools into named collections for reuse |
| **Configuration Packs** | Export and import bundles of configuration as JSON |

Most configuration pages follow the same pattern: a list view with a **Create** button,
and a detail page with tabbed sections for editing. Changes on detail pages are not saved
until you click **Save Changes**.

---

## Common Patterns

A few interactions work the same way throughout the UI:

- **Filtering** — list pages have a filter bar where you can add filter criteria by
  type (name, status, labels). Active filters appear as removable chips below the
  toolbar.
- **Pagination** — large lists are paginated. Use the controls in the toolbar to change
  page size or navigate between pages.
- **Clickable rows** — table rows are clickable and navigate to the item's detail page.
- **Labels** — many items support labels for organization. Clicking a label chip in a
  table adds it as a filter. Labels can be edited from detail pages.
- **Delete confirmation** — destructive actions always show a confirmation dialog before
  proceeding.
- **Real-time updates** — the UI receives server-sent events (SSE) for live updates.
  Activity feeds, task statuses, and report progress update automatically without
  refreshing the page.
