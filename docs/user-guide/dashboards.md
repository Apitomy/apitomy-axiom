# Dashboards

Dashboards give you an at-a-glance view of what Axiom is doing: which workflow runs are running, failing or
waiting, which tasks need your input, how events are flowing through subscriptions, what AI usage costs, and
whether the application is healthy.

You can create as many dashboards as you like. Each dashboard has a name, an optional description, optional
labels, and one or more **tabs**. Each tab holds **widgets** arranged on a 12-column drag-and-drop grid.

## Opening dashboards

- **Dashboards** in the sidebar (`/dashboards`) opens the **default dashboard**. If no dashboard is the
  default, it shows the dashboards list instead.
- The dashboards list is always at `/dashboards/all`. Every dashboard has an **All dashboards** button that
  returns to it.

### The default dashboard

One dashboard at a time can be the default. Click the star icon in the dashboards list, or **Set as
Default** on a dashboard, to make it the default. The first dashboard you create becomes the default
automatically.

## Creating a dashboard

From the dashboards list:

- **Create Dashboard** creates an empty dashboard with one tab. Click **Edit**, then **Add Widget**, to fill
  it.
- **Create from Template** creates a dashboard that already contains a set of widgets. Choose a template,
  change the name if you want, and optionally add labels. The result is an ordinary dashboard: you can edit,
  rearrange or remove its widgets afterwards.

When there are no dashboards yet, the empty list offers the templates directly.

### Templates

| Template | Widgets |
|---|---|
| **Operations** | Runs by Status, Application Health, Tasks Awaiting Input, Recent Failed Runs, Waiting Runs, Upcoming Schedules |
| **Event Operations** | Event Source Health, Subscription Processing, Event Volume, Processing Failures, Recent Events |
| **AI Usage** | AI Cost Summary, AI Usage Breakdown (by workflow), AI Usage Breakdown (by model), AI Cost by Subject |

## Editing a dashboard

Click **Edit** to unlock the layout. In edit mode you can:

- Drag and resize widgets.
- Click a widget's gear icon to change its settings, or its X icon to remove it.
- Click **Add Widget** to open the widget catalog, which groups widgets by category and marks widgets that
  ignore dashboard labels.
- Add, rename and remove tabs.
- Change the dashboard's name, description and labels.

Click **Save** to keep your changes. **Refresh** reloads every widget.

## Label scoping

Dashboard labels are **subject labels**. When a dashboard has labels, widgets that show subject-related data
only include data for subjects that have all of those labels, for example only the runs, tasks and AI usage
of subjects labelled `team=a`. This uses the same label filter as the Subjects, Inbox, Activity and AI usage
pages.

Some data is not tied to a subject (event sources, subscriptions, schedules, system health), so those widgets
are **global** and always show everything. When a dashboard has labels, each widget shows a **Filtered** or
**Global** badge next to its title so you can tell which is which.

For AI usage widgets, usage that is not linked to any subject cannot match the labels, so it is left out.
These widgets tell you how much cost was excluded this way.

## Live refresh

Most widgets update by themselves:

- **Live** widgets reload when Axiom sends a matching server event, for example when a workflow run changes
  status, a human task is added or removed, a new stream event arrives, or event processing changes. Reloads
  are debounced (about 2 seconds), and during a steady stream of events a widget still reloads at least every
  10 seconds. Widgets also reload after the browser reconnects to the server.
- **Polling** widgets reload on a timer.
- The remaining widgets update when you click **Refresh** or reopen the dashboard.

## Widgets

In the tables below, **Scope** is either *Filtered* (dashboard labels apply) or *Global* (dashboard labels
are ignored). Time windows are 24 hours, 7 days, 30 days or 90 days.

### Runs

| Widget | Shows | Scope | Refresh | Settings |
|---|---|---|---|---|
| **Runs by Status** | Number of runs that are running, waiting, completed, failed and cancelled. Running and waiting are current; the others cover the time window. | Filtered | Live (run changes) | Time window (default 24 hours) |
| **Recent Failed Runs** | The most recently failed runs, with their subject and a **Re-run** button. | Filtered | Live (run changes) | Time window (default 7 days), max rows (1-50, default 10) |
| **Waiting Runs** | Runs waiting on a timer, an event or human input, longest waiting first, with what they wait for and any timeout. | Filtered | Live (run and inbox changes) | Max rows (1-200, default 10) |
| **Upcoming Schedules** | Schedule triggers ordered by next firing time, with the last firing outcome. | Global | Live (run changes) | Max rows (1-500, default 10), show disabled schedules (default off) |
| **Workflows** | Workflow definitions with their published state, enabled schedules and most recent run. | Global | Live (run changes) | Max rows (1-100, default 10) |
| **Recent Activity** | Latest workflow, task and subject activity, with links to the subject, trace and event. | Filtered | Live (activity) | Max entries (default 15) |

### Inbox

| Widget | Shows | Scope | Refresh | Settings |
|---|---|---|---|---|
| **Tasks Awaiting Input** | Human tasks waiting for input, with their subject, workflow run and age. Click a row to open the task in the Inbox. | Filtered | Live (inbox changes) | Max rows (default 10) |

### Events

| Widget | Shows | Scope | Refresh | Settings |
|---|---|---|---|---|
| **Event Source Health** | Every event source with its state (healthy, failing, baseline, disabled or never polled), last success, last error and events received in the last 24 hours. Problems are listed first. | Global | Polls every minute | None |
| **Subscription Processing** | Processing outcomes per subscription (pending, completed, failed, skipped) and the last error. | Global | Live (processing changes) | Time window (default 24 hours), hide subscriptions with no activity (default on) |
| **Processing Failures** | Failed, exhausted and filter-error processing entries, newest first, with a **Retry** button. | Global | Live (processing changes) | Max rows (1-200, default 10) |
| **Event Volume** | Bar chart of stream events received over time: hourly for windows up to 7 days, daily beyond. | Global | Polls every 5 minutes | Time window (default 24 hours), event source (default all) |
| **Recent Events** | Latest stream events with their source and processing state. Click a row to open the event. | Global | Live (new events and processing changes) | Max rows (1-100, default 10), event source (default all) |

### AI & Cost

| Widget | Shows | Scope | Refresh | Settings |
|---|---|---|---|---|
| **AI Cost Summary** | Total AI cost, input and output tokens and invocations. | Filtered | Manual | Time window (default 7 days) |
| **AI Usage Breakdown** | AI cost, tokens and calls grouped by subject, workflow, engine, model or action type, highest cost first. | Filtered | Manual | Group by (default workflow), time window (default 7 days), max rows (1-100, default 10) |
| **AI Cost by Subject** | Bar chart of the subjects with the highest AI cost. | Filtered | Manual | Time window (default 7 days), max subjects (default 5) |

AI usage is linked to the workflow run and workflow that caused it. Usage recorded by an earlier 3.0 build,
before this link existed, is grouped under "(none)" when you group by workflow.

### System

| Widget | Shows | Scope | Refresh | Settings |
|---|---|---|---|---|
| **Application Health** | Overall status (UP, DEGRADED or DOWN) and the database, event source, engine and scheduler checks. | Global | Polls every 30 seconds | None |
| **Disk Usage Breakdown** | Subjects using the most disk space, with a link to the disk usage details. | Global | Manual | Max subjects (1-20, default 10) |
| **Action Types** | Action types and their execution mode. | Global | Manual | Max rows (1-100, default 10) |

### Subjects

| Widget | Shows | Scope | Refresh | Settings |
|---|---|---|---|---|
| **Active Subjects** | Subjects with a running or waiting workflow run. | Filtered | Live (subject changes) | Max rows (default 8) |
| **Subject Spotlight** | One chosen subject: its runs by status, the runs waiting now and its total AI cost. | Global (shows the chosen subject) | Live (run and subject changes) | Subject |

## Sharing dashboards

Dashboards can be included in [configuration packs](concepts.md#configuration-packs). On the export page,
select them in the **Dashboards** section. On import, dashboards are matched by name like other items: an
existing dashboard with the same name is reported as a conflict. An imported dashboard becomes the default
only if it was the default when exported and no dashboard is already the default.
