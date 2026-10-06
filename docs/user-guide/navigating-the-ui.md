# Navigating the UI

Axiom's web interface is organized around a sidebar navigation, a top masthead, and a
central content area. This guide gives you a quick tour of the UI so you know where to
find things.

---

## Masthead

The masthead runs across the top of every page. It contains:

- **Apitomy Axiom** — click the title to return to the Runs page (the landing page) from any page
- **Robot icon** — opens the [AI Assistant](ai-assistant.md) (visible only when at
  least one registered agent supports interactive sessions)
- **Theme toggle** — cycles between light, dark, and system theme
- **Question mark icon** — opens the About dialog showing the application version, active
  AI engine, and links to the source repository

---

## Dashboards

Rather than a single fixed view, Axiom supports multiple **user-configurable dashboards**, each populated with
widgets chosen from a built-in catalog.

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
only subjects, events, and activity associated with that label.

### Widget Catalog

Axiom ships with built-in widget types across several categories:

| Category | Widgets |
|----------|---------|
| **Subjects** | Active Subjects, Subject Spotlight |
| **Operations** | Recent Activity, Inbox, Recent Events |
| **AI & Cost** | AI Cost Summary, AI Cost by Subject |
| **System** | System Status, Action Types, Disk Usage Breakdown |

Each widget has a default size but can be resized on the grid. Many widgets support
per-widget configuration — for example, the Active Subjects widget has a configurable
maximum row count, and the AI Cost Summary widget lets you choose a time window
(24 hours, 7 days, 30 days, or 90 days). Click the gear icon on a widget in edit mode
to adjust its settings.

---

## Sidebar Navigation

The sidebar lists the primary pages first — Runs, Subjects, Workflows, Action Types, Event Sources,
Subscriptions, and Inbox — followed by Dashboards, Event Stream, and the Logs, Metrics, Components, and
Settings sections.

### Runs

**Runs** is the landing page (`/runs`). It lists every workflow run across all subjects with its title,
status, definition, subject, labels, and start time. Filter by status, definition, subject, or label
(`key:value`). Click a run to open its detail page, which shows the workflow graph with the current node
highlighted, the run's inputs and labels, the triggering event, the tasks created by its nodes (with their
outputs), and a **Cancel** button for active runs. See [Workflows](workflows.md#following-a-run).

### Subjects

The **Subjects** page lists all subjects with their name, derived active state, ref, labels, and last
update. A subject is *active* when it has a running or waiting workflow run. You can filter by name or
labels.

Click any subject to open its detail page, which has several tabs:

- **Runs** — a timeline of every workflow run on this subject, active and past
- **Body** — the subject's Markdown body
- **Tasks** — all tasks created by runs on this subject, with status and execution details
- **Thread** — a chronological log of everything that happened on the subject (events received, task
  results, notes)
- **Events** — raw stream events from connections associated with this subject
- **Metrics** — AI cost, token usage, and disk usage for this subject
- **Workflow** — start a run of a published workflow definition (**Run Workflow**), optionally with a
  title, labels, and inputs

From the subject detail page you can also edit labels and delete the subject.

### Workflows

The **Workflows** page lists workflow definitions. Each definition's detail page has the visual editor,
the **Versions** tab, and the **Triggers** tab for schedule triggers. See [Workflows](workflows.md).

### Action Types

The **Action Types** page defines the kinds of work that workflow `action` nodes run — prompt or script
templates, allowed tools, execution mode, and model/engine overrides. Action types are run only from
workflow action nodes.

### Event Sources, Subscriptions, and Event Stream

These pages cover the connection-based event pipeline described in
[Events and Subscriptions](concepts.md#events-and-subscriptions):

| Page | What it shows |
|------|--------------|
| **Event Sources** | Configured connections to GitHub/Jira, their poll status, and poll log history |
| **Subscriptions** | Filtered, routed views over the event stream (`create-workflow` and `workflow-dispatch` rules) — create, edit, and preview filter expressions here |
| **Event Stream** | Every normalized event Axiom has recorded from its connections, browsable and filterable. Click a row to view the full typed payload. |

### Inbox

The **Inbox** page shows tasks with status **AwaitingInput** — tasks created by workflow `human-task`
nodes that are waiting for a person. A badge on the sidebar shows the count of pending items. Click any
item to review its context and respond; the waiting workflow run then resumes.

### Dashboards

See [Dashboards](#dashboards) above.

### Logs

The **Logs** section has several sub-pages, each showing a different slice of system
activity:

| Page | What it shows |
|------|--------------|
| **All Activity** | Unified feed of all system events — subject creation, workflow runs, task execution |
| **Tasks** | Task execution history — which agent ran what action type, duration, cost, and outcome |
| **Traces** | Browse and filter workflow run execution traces |

Each log page supports filtering and pagination.

The **Traces** page deserves special mention. While the other log pages show tabular
data, a trace detail page renders the node tree as an interactive tree. Each node
represents a step in the run (node entered, task created, tool executed, etc.) and is
color-coded by status. Click any node to open a detail view showing the full data for
that step, including tool input/output for tool executions.

You can also navigate to a trace directly from a workflow run or a subject's tasks.

### Metrics

The **Metrics** section has two sub-pages:

| Page | What it shows |
|------|--------------|
| **AI Usage** | Token costs broken down by invocation type, action type, and date range. Shows summary totals at the top. |
| **Disk Usage** | Workspace storage consumed by each subject's workspace. Shows total disk usage and per-subject breakdown. |

### Components

The **Components** section contains the supporting configuration used by action types and the AI
Assistant:

| Page | Purpose |
|------|---------|
| **Agents** | Register AI agent slots with capabilities that determine which work they're eligible for |
| **AI Assistant** (Session Templates) | Define AI Assistant session templates with system prompts, agent type, tools, and MCP servers |
| **MCP Servers** | Register external MCP tool servers (HTTP or stdio) |
| **Secrets** | Store encrypted credentials injected into subprocesses |
| **Tools** | Define script-based tools with parameters and bash templates |
| **Toolsets** | Group tools into named collections for reuse |

### Settings

The **Settings** section contains instance-wide configuration:

| Page | Purpose |
|------|---------|
| **AI Engine** | View and change the default agent type, health checks, and available models per registered agent |
| **Data Retention** | Configure how long inactive subjects, traces, events, workflow runs, activity and AI usage are kept before automatic cleanup (0 keeps history forever) |
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
  Activity feeds, run and task statuses update automatically without
  refreshing the page.
