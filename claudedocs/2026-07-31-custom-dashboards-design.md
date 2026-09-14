# Custom Dashboards Design

## Overview

Replace the static Axiom dashboard with user-created custom dashboards. Users can create
multiple named dashboards, each with optional label-based filtering, and populate them with
widgets from a registry. Widgets are arranged on a drag-and-drop grid (react-grid-layout) and
support per-widget configuration.

## Requirements

- Users can create, rename, and delete multiple dashboards
- Each dashboard has a name, optional description, and optional label filters
- Dashboard-level labels are inherited by all widgets (used to filter API calls)
- Widgets are selected from a catalog and placed on a 12-column responsive grid
- Widgets are draggable and resizable only in Edit mode
- Widgets support per-widget settings (time windows, max rows, filters)
- Widgets support light inline actions (navigation links, quick actions)
- Dashboard layouts are persisted server-side via a new REST API
- New widget types can be added later by registering a component — no framework changes needed

## Data Model

### Dashboard

```
Dashboard {
  id: string (UUID)
  name: string                    // e.g. "Apicurio", "Apitomy", "Overview"
  description?: string
  labels: string[]                // dashboard-level filter, e.g. ["apicurio"]
  isDefault: boolean              // which dashboard loads first
  widgets: DashboardWidget[]
  createdOn: timestamp
  updatedOn: timestamp
}
```

### DashboardWidget

```
DashboardWidget {
  id: string (UUID)               // unique within the dashboard
  type: string                    // registry key, e.g. "project-status-summary"
  config: Record<string, any>     // per-widget settings (time window, max rows, etc.)
  layout: {                       // react-grid-layout position
    x: number                     // column (0-11 in a 12-col grid)
    y: number                     // row
    w: number                     // width in columns
    h: number                     // height in rows
  }
}
```

## REST API

Five new endpoints under `/api/v1/dashboards`:

| Method | Path | Returns | Description |
|--------|------|---------|-------------|
| GET | `/dashboards` | Dashboard[] | List all dashboards |
| POST | `/dashboards` | Dashboard | Create new dashboard |
| GET | `/dashboards/{id}` | Dashboard | Get single dashboard |
| PUT | `/dashboards/{id}` | Dashboard | Update (name, labels, widgets, layout) |
| DELETE | `/dashboards/{id}` | 204 | Delete dashboard |

The PUT endpoint handles all mutations. The full Dashboard object (including its widgets array)
is sent on every save. This avoids needing separate endpoints for widget CRUD and layout
updates.

## Backend

### Database Table

```sql
CREATE TABLE dashboards (
  id         VARCHAR PRIMARY KEY,
  name       VARCHAR NOT NULL,
  description VARCHAR,
  labels     JSON,           -- string array
  is_default BOOLEAN DEFAULT FALSE,
  widgets    JSON,           -- DashboardWidget array
  created_on TIMESTAMP NOT NULL,
  updated_on TIMESTAMP NOT NULL
);
```

The widgets JSON column holds the full array of widget definitions including type, config,
and layout positions. The dashboard is always loaded and saved as a whole unit.

### REST Resource

Standard Axiom patterns: JAX-RS interface generated from the OpenAPI spec, impl class in
`app/src/main/java/.../rest/`, Panache-based repository for persistence.

### Label Filtering on Existing APIs

Most endpoints already support label filtering (projects, events, reports). The following may
need a `labels` query parameter added:

- AI usage (`/usage/ai`) — for AI Cost widgets
- Activity log (`/activity`) — for Recent Activity widget
- Inbox (`/inbox`) — for Inbox widget

These are small additive changes: an optional query parameter and a WHERE clause.

## Widget Registry & Architecture

### Registry Structure

A static map of widget type keys to metadata and components:

```typescript
WidgetRegistryEntry {
  type: string                    // e.g. "project-status-summary"
  name: string                    // display name
  description: string             // shown in the Add Widget catalog
  category: string                // grouping: "Projects", "Operations", etc.
  defaultSize: { w: number, h: number }
  minSize?: { w: number, h: number }
  configSchema?: ConfigField[]    // defines the settings panel fields
  component: React.ComponentType<WidgetProps>
}
```

### Widget Props

Every widget component receives the same props interface:

```typescript
WidgetProps {
  config: Record<string, any>     // this widget's persisted settings
  labels: string[]                // dashboard-level label filters
  onConfigChange: (config: Record<string, any>) => void
}
```

Widgets are responsible for fetching their own data using the `labels` prop to filter API
calls. This keeps widgets self-contained — adding a new widget type is writing a component
and registering it.

### Widget Config Schema

Each widget declares its configurable settings as an array of `ConfigField` entries. The
dashboard framework renders a settings panel from this schema automatically — widget authors
don't build settings UI by hand.

```typescript
ConfigField {
  key: string                     // config property name, e.g. "timeWindow"
  label: string                   // display label, e.g. "Time Window"
  type: "select" | "number" | "multiselect" | "toggle"
  options?: { label: string, value: string }[]  // for select/multiselect
  default: any                    // default value used when widget is first added
}
```

## Initial Widget Catalog

### Category: Projects

#### 1. Project Status Summary
- Colored stat cards showing counts by status (Created, InProgress, Idle, Completed)
- Settings: time window (all-time, 7d, 30d, 90d)
- API: `fetchProjects` with label + status filters
- Label filtering: only count projects matching dashboard labels
- Default size: 6×2. Click a status card to navigate to filtered projects list.

#### 2. Active Projects
- Compact table of non-completed projects: name, status, issue ref, last updated
- Settings: max rows (default 8), status filter (multi-select)
- API: `fetchProjects` with label + status filters
- Label filtering: only show projects matching dashboard labels
- Default size: 6×3. Light action: project name links to project detail page.

#### 3. Project Spotlight
- Single-project deep view: status, task breakdown, total cost, recent activity
- Settings: project selector (dropdown)
- API: `fetchProject`, `fetchProjectTasks`, `fetchProjectMetrics`
- Label filtering: project selector dropdown filtered to projects matching dashboard labels
- Default size: 4×4. Useful for pinning a project you're actively watching.

### Category: Operations

#### 4. Recent Activity
- Time-ordered feed of activity log entries with type badges and timestamps
- Settings: max entries (default 15), entry type filter (multi-select)
- API: `fetchActivityLog` with label filters
- Label filtering: only show activity for projects matching dashboard labels
- Default size: 4×4.

#### 5. Inbox
- Pending human tasks awaiting input with project name, action type, and age
- Settings: max rows (default 10)
- API: `fetchInboxItems`
- Label filtering: only show inbox items for projects matching dashboard labels
- Default size: 4×3. Light action: click to navigate to inbox item detail.

#### 6. Recent Events
- Latest events from GitHub/Jira with source, type, repository, timestamp
- Settings: max rows (default 10), source filter (github/jira), event type filter
- API: `fetchEvents` with label filters
- Label filtering: only show events matching dashboard labels
- Default size: 6×3.

### Category: AI & Cost

#### 7. AI Cost Summary
- Total AI spending for a time period: cost, token counts, invocation count
- Settings: time window (24h, 7d, 30d, 90d)
- API: `fetchUsage` with label filters
- Label filtering: only include usage for projects matching dashboard labels
- Default size: 4×2.

#### 8. AI Cost by Project
- Top N projects ranked by AI cost, shown as a bar chart or ranked list
- Settings: time window (24h, 7d, 30d, 90d), max projects (default 5)
- API: `fetchUsage` grouped by project, with label filters
- Label filtering: only include projects matching dashboard labels
- Default size: 4×3.

### Category: Reports

#### 9. Recent Reports
- Table of recently generated reports: title, status, duration, cost
- Settings: max rows (default 10), time window (24h, 7d, 30d), definition filter
- API: `fetchReports` with label filters
- Label filtering: only show reports matching dashboard labels
- Default size: 6×3. Light action: report title links to report detail.

### Category: System

#### 10. System Status
- Simple UP/DOWN health indicator with version and timestamp
- Settings: none
- API: `fetchSystemHealth`
- Label filtering: not applicable (system-wide)
- Default size: 2×1. Small, always-glanceable.

#### 11. Event Source Health
- List of event sources with enabled/disabled status and last poll result
- Settings: none
- API: `fetchEventSources`, `fetchEventSourceLogs`
- Label filtering: only show event sources matching dashboard labels
- Default size: 4×3. Surfaces polling errors at a glance.

## UI Flow & Layout

### Dashboard List Page (`/dashboards`)

A standard PatternFly list/table page showing all dashboards. Each row shows the dashboard
name, labels, widget count, and last updated. Actions: click a row to view that dashboard,
plus Create and Delete actions. When no dashboards exist, show an EmptyState with a "Create
Dashboard" button and a short description of what dashboards are.

Sidebar navigation has a single "Dashboards" nav item linking to `/dashboards`. No expansion,
no sub-items.

### Dashboard View Page (`/dashboards/{id}`)

Two modes controlled by a toggle in the top bar:

**Normal Mode (default):**
- Top bar: Dashboard name as title, label badges, and an "Edit" toggle/button to enter
  edit mode.
- Grid area: Widgets render in their saved positions but are not draggable or resizable.
  No gear icons, no remove buttons. Purely a read/interact experience.
- Widgets still support their light actions (clicking links, navigating to detail pages).

**Edit Mode:**
- Top bar: Dashboard name (editable inline), label editor, "Add Widget" button, "Delete
  Dashboard" button (with confirmation), "Save" button, and "Cancel" button.
- Grid area: Widgets become draggable and resizable. Each widget card gains a gear icon
  (settings) and an X button (remove with confirmation) in its header.
- "Save" persists all changes via `PUT /dashboards/{id}` and switches back to Normal mode.
- "Cancel" discards all unsaved changes and switches back to Normal mode.

### Add Widget Modal

A PatternFly Modal listing available widget types grouped by category. Each entry shows the
widget name, description, and default size. Selecting one adds it to the dashboard with default
settings and default position (bottom of the grid). The user can then drag/reposition it in
Edit mode.

## Technology

- **react-grid-layout** — drag-and-drop grid for widget positioning and resizing
- **PatternFly 6** — Card, Grid, Modal, EmptyState, Label, and chart components
- **Existing Axiom patterns** — JAX-RS + Panache backend, OpenAPI-first API design,
  TypeScript API client functions

## Future Extensions

The widget registry is purely additive. Future widgets could include:
- Task pipeline (tasks by status across projects)
- Disk usage breakdown
- Event volume over time (chart)
- Actor workload (tasks per actor)
- Custom metric widgets with user-defined queries
