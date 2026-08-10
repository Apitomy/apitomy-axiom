# Navigating the UI

Axiom's web interface is organized around a sidebar navigation, a top masthead, and a
central content area. This guide gives you a quick tour of the UI so you know where to
find things.

---

## Masthead

The masthead runs across the top of every page. It contains:

- **Apitomy Axiom** — click the title to return to the Dashboards page from any page
- **Robot icon** — opens the [AI Assistant](ai-assistant.md) (visible only when the
  Claude Code engine is active)
- **Question mark icon** — opens the About dialog showing the application version, active
  AI engine, and links to the source repository

---

## Dashboards

Dashboards are the landing page. Rather than a single fixed view, Axiom supports
multiple **user-configurable dashboards**, each populated with widgets chosen from a
built-in catalog.

### Dashboard List

The **Dashboards** page shows all dashboards with their name, description, labels, and
creation date. From here you can:

- **Create** a new dashboard with a name, description, and optional labels
- **Set as default** — click the star icon to designate a dashboard as the default
  landing page (only one dashboard can be default at a time)
- **Delete** — remove a dashboard with a confirmation prompt

Click any dashboard to open its view page.

### Dashboard View

Each dashboard displays its widgets on a responsive grid. The view page has two modes:

- **Normal mode** (default) — widgets are displayed read-only; the layout is locked
- **Edit mode** — click **Edit** in the toolbar to enable drag-and-drop repositioning,
  widget resizing, per-widget configuration (gear icon), and widget removal (X icon).
  You can also edit the dashboard's name, description, and labels inline.

Click **Add Widget** in edit mode to open the widget catalog, which lets you browse and
search widgets organized by category.

### Dashboard Labels

Dashboards support labels for organization. Dashboard labels also serve as **data
filters** — when a dashboard has labels, its widgets filter their data to show only
items matching those labels. For example, a dashboard labeled `"team-a"` would show
only projects, events, and activity associated with that label.

### Widget Catalog

Axiom ships with 12 built-in widget types across four categories:

| Category | Widgets |
|----------|---------|
| **Projects** | Project Status Summary, Active Projects, Project Spotlight |
| **Operations** | Recent Activity, Inbox, Recent Events |
| **AI & Cost** | AI Cost Summary, AI Cost by Project |
| **Reports** | Recent Reports |
| **System** | System Status, Event Source Health, Disk Usage Breakdown |

Each widget has a default size but can be resized on the grid. Many widgets support
per-widget configuration — for example, the Active Projects widget has a configurable
maximum row count, and the AI Cost Summary widget lets you choose a time window
(24 hours, 7 days, 30 days, or 90 days). Click the gear icon on a widget in edit mode
to adjust its settings.

---

## Sidebar Navigation

The sidebar organizes the UI into six sections.

### Dashboards

See [Dashboards](#dashboards) above.

### Inbox

The **Inbox** page shows Manager decisions that require human review — typically
low-confidence decisions that were not auto-executed. A badge on the sidebar shows
the count of unreviewed items. Click any item to review the Manager's reasoning and
approve or reject the proposed action.

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
| **Action Types** | Define kinds of work — prompt templates, allowed tools, execution mode, and labels for event source scoping |
| **Actors** | Register AI agents and human actors with capabilities |
| **Manager** | Edit the Manager's system prompt and prompt template |
| **MCP Servers** | Register external MCP tool servers (HTTP or stdio) |
| **Report Definitions** | Create report templates with schedules, time windows, and prompts |
| **Event Sources** | Connect to GitHub or Jira repositories for event polling, with labels and event filters |
| **Secrets** | Store encrypted credentials injected into subprocesses |
| **Tools** | Define script-based tools with parameters and bash templates |
| **Toolsets** | Group tools into named collections for reuse |
| **Session Templates** | Define AI Assistant session templates with system prompts, tools, and MCP servers |
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
