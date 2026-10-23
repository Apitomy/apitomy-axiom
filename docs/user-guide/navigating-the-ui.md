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

Axiom supports multiple **user-configurable dashboards**, each with tabs of widgets chosen from a built-in
catalog. **Dashboards** in the sidebar (`/dashboards`) opens the default dashboard, or the dashboards list
when no dashboard is the default. See [Dashboards](dashboards.md) for every widget and its settings.

### Dashboard List

The dashboards list (`/dashboards/all`) shows all dashboards with their name, labels, number of widgets and
last update. From here you can:

- **Create Dashboard**: create an empty dashboard with a name, description and optional labels
- **Create from Template**: create a dashboard from the Operations, Event Operations or AI Usage template
- **Set as default**: click the star icon (only one dashboard can be the default)
- **Delete**: remove a dashboard after confirming

When there are no dashboards, the list offers the templates directly. Click a dashboard to open it.

### Dashboard View

Each dashboard shows its widgets on a responsive grid. **All dashboards** returns to the list and
**Refresh** reloads every widget. The view has two modes:

- **Normal mode** (default): widgets are read-only and the layout is locked
- **Edit mode**: click **Edit** to drag and resize widgets, change a widget's settings (gear icon), remove
  widgets (X icon), manage tabs, and edit the dashboard's name, description and labels

In edit mode, **Add Widget** opens the widget catalog, grouped by category (Runs, Inbox, Events, AI & Cost,
System and Subjects).

### Dashboard Labels

Dashboard labels are **subject labels**. Widgets that show subject-related data (runs, tasks, activity,
subjects and AI usage) only include subjects with those labels. Widgets for event sources, subscriptions,
schedules and system health are global and ignore them. When a dashboard has labels, each widget shows a
**Filtered** or **Global** badge.

---

## Sidebar Navigation

The sidebar lists, in order: Runs, Subjects, Inbox, Dashboards, the **Events** section (Event Sources,
Subscriptions, Event Stream), Workbench, and the **Logs**, **Metrics**, **Components** and **Settings**
sections.

### Runs

**Runs** is the landing page (`/runs`). It lists every workflow run across all subjects with its status,
start time and duration. The **Run** column names each run as `<subject> — <workflow> v<version>`, with the
run's title below it when the run has one. Filter chips narrow the list by **Status**, **Workflow** (name or
ID), **Subject ID**, and **Label** (`key:value`; add several labels to require all of them). Clicking a run's
status adds it as a filter. Click anywhere else on a row to open the run's detail page, which shows the
workflow graph with the current node highlighted, the run's resolved standard inputs (the `subject`; the
event is shown as the triggering event instead) and labels, the triggering event, the tasks created by its
nodes (with their outputs), and a **Cancel** button for active runs.
See [Workflows](workflows.md#following-a-run).

### Subjects

The **Subjects** page lists all subjects with their name, derived active state, ref, labels, and last
update. A subject is *active* when it has a running or waiting workflow run. You can filter by name or
labels.

Click any subject to open its detail page, which has several tabs:

- **Runs** — a timeline of every workflow run on this subject, active and past. Failed runs have a
  **Re-run** button; see [Re-running a Failed Run](workflows.md#re-running-a-failed-run)
- **Body** — the subject's Markdown body
- **Tasks** — all tasks created by runs on this subject, with status and execution details
- **Thread** — a chronological log of everything that happened on the subject (events received, task
  results, notes)
- **Events** — raw stream events from event sources associated with this subject
- **Metrics** — AI cost, token usage, and disk usage for this subject

From the subject detail page you can also edit labels and delete the subject.

### Inbox

The **Inbox** page shows tasks with status **AwaitingInput** — tasks created by workflow `human-task`
nodes that are waiting for a person. A badge on the sidebar shows the count of pending items. Click any
item to review its context and respond; the waiting workflow run then resumes.

### Dashboards

See [Dashboards](#dashboards) above.

### Events

The **Events** section covers the event pipeline described in
[Events and Subscriptions](concepts.md#events-and-subscriptions):

| Page | What it shows |
|------|--------------|
| **Event Sources** | Configured event sources to GitHub/Jira, their poll status, and poll log history |
| **Subscriptions** | Filtered, routed views over the event stream (`create-workflow` and `workflow-dispatch` rules) — create, edit, and preview filter expressions here |
| **Event Stream** | Every normalized event Axiom has recorded from its event sources. Filter chips narrow it by **Event Type**, **Event Source**, **Ref** and **Actor** (exact login, case-insensitive); clicking a type, source or actor in a row adds it as a filter. Click a row to open the event. |

On a subscription's **Filter** tab, the **Event fields** panel below the expression editor lists the fields
you can use in a filter expression, built from the event schemas Axiom validates events against. Each row
shows the EL path (for example `event.payload.issue.state`), its type, a description and an example value.
Click a path to append it to the expression; the editor's autocompletion uses the same fields.

The panel shows the event types your expression compares `event.type` to with `==` (for example
`event.type == 'issue.closed'`). If the expression names no type, pick one from the **Event type** list; it
starts on `issue.created`. Array elements appear as `[0]` (for example
`event.payload.issue.assignees[0].login`), and `event.sourceData` is listed once as source-specific because
its shape depends on the event source.

The event detail page shows the full typed payload (**Event** tab), how subscriptions processed it
(**Processing** tab), and the runs started from it (**Runs** tab). **Run workflow…** starts a run of any
published workflow with the event, listing suggested workflows first; such runs get the label
`trigger:manual`.

Axiom checks every incoming event against the schema for its type. An event that does not match is still
stored and processed, and its detail page shows a warning listing each problem with the JSON path of the
field (for example `$.payload.issue.state: must be one of [open, closed]`). Subscription filters that use
those fields may not behave as expected for that event. Events recorded before schema validation existed
show no warning.

### Workbench

The **Workbench** (`/workbench`) lists compositions. Open one to design a workflow together with its
action types and tools, with an AI Assistant chat beside the canvas, test script and Node.js items, review
the composition's runs, and publish the drafts. See [Workbench](workbench.md).

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

The **Components** section contains workflows and the building blocks they and the AI Assistant use:

| Page | Purpose |
|------|---------|
| **Action Types** | Define the kinds of work that workflow `action` nodes run — execution mode (agent, script or Node.js), prompt, script or code template, inputs and outputs, allowed tools, and model/engine overrides |
| **Agents** | Register AI agent slots with capabilities that determine which work they're eligible for |
| **AI Assistant** (Session Templates) | Define AI Assistant session templates with system prompts, agent type, tools, and MCP servers |
| **MCP Servers** | Register external MCP tool servers (HTTP or stdio) |
| **Secrets** | Store encrypted credentials; processes receive only the secrets their environment declares |
| **Tools** | Define script-based tools with parameters and bash templates |
| **Toolsets** | Group tools into named collections for reuse |
| **Workflows** | Workflow definitions (`/components/workflows`); each detail page has the **Design**, **Versions**, **Runs** and **Triggers** (schedule triggers) tabs. See [Workflows](workflows.md) |

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
