# UI: Connections Management & Event Stream Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add UI pages for managing EventSourceConnections (CRUD) and browsing the
normalized event stream, replacing the old Event Sources UI.

**Architecture:** Two new page groups following existing PatternFly 6 patterns: a
Connections list page with create/edit modal and a detail page with tabs (Info, Status),
plus an Event Stream page replacing the old Events log. All API calls go through new
functions in `api.ts`. Navigation updated in the sidebar under Components.

**Tech Stack:** React 19, TypeScript, PatternFly 6, Vite, `@apitomy/common-ui-components`
(ChipFilterInput/FilterChips), raw `fetch()` API calls.

**Spec:** `docs/developer-guide/event-sourcing-design.md`

## Global Constraints

- Follow existing page structure patterns exactly (see `EventSourcesPage.tsx` for list pages,
  `EventSourceDetailPage.tsx` for detail pages).
- All API types and functions go in `ui/src/config/api.ts`.
- Pages go in `ui/src/pages/`, components in `ui/src/components/`.
- Routes defined in `ui/src/App.tsx`, nav items in `ui/src/components/AppSidebar.tsx`.
- Use PatternFly 6 components exclusively. No custom CSS unless matching existing patterns.
- Forms use local `useState`, no form library.
- Pagination follows the existing `SearchResults<T>` pattern with `page`/`limit`/`total`.
- The old Event Sources pages (`EventSourcesPage.tsx`, `EventSourceDetailPage.tsx`) and their
  routes/nav items will be removed and replaced by the new Connections pages.
- The old Events page (`EventsPage.tsx`) will be replaced by the new Event Stream page.

---

### Task 1: API types and functions

**Files:**
- Modify: `ui/src/config/api.ts`

**Interfaces:**
- Consumes: REST API at `/api/v1/connections`, `/api/v1/stream/events`.
- Produces: TypeScript interfaces and fetch functions used by all subsequent tasks.

- [ ] **Step 1: Add Connection types**

Add these interfaces after the existing event source types in `api.ts`:

```typescript
// ─── Connections ────────────────────────────────────────────────────
export interface Connection {
    id: string;
    name: string;
    description?: string;
    sourceType: string;
    enabled: boolean;
    baseUrl: string;
    secretName?: string;
    pollInterval?: number;
    configuration?: Record<string, unknown>;
    createdOn?: string;
    modifiedOn?: string;
}

export interface NewConnection {
    id: string;
    name: string;
    description?: string;
    sourceType: string;
    enabled: boolean;
    baseUrl: string;
    secretName?: string;
    pollInterval?: number;
    configuration?: Record<string, unknown>;
}

export interface ConnectionStatus {
    connectionId: string;
    enabled: boolean;
    lastPolledAt?: string;
    totalEventsProduced: number;
    lastError?: string;
    lastErrorAt?: string;
}
```

- [ ] **Step 2: Add Connection API functions**

```typescript
export async function fetchConnections(
    page = 1, limit = 20, filterName?: string, filterType?: string
): Promise<SearchResults<Connection>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterName) params.set("filterName", filterName);
    if (filterType) params.set("filterType", filterType);
    const resp = await fetch(`${getApiBaseUrl()}/api/v1/connections?${params}`);
    if (!resp.ok) throw new Error(await extractErrorMessage(resp, "Failed to fetch connections"));
    return resp.json();
}

export async function fetchConnection(connectionId: string): Promise<Connection> {
    const resp = await fetch(`${getApiBaseUrl()}/api/v1/connections/${encodeURIComponent(connectionId)}`);
    if (!resp.ok) throw new Error(await extractErrorMessage(resp, "Failed to fetch connection"));
    return resp.json();
}

export async function createConnection(data: NewConnection): Promise<Connection> {
    const resp = await fetch(`${getApiBaseUrl()}/api/v1/connections`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!resp.ok) throw new Error(await extractErrorMessage(resp, "Failed to create connection"));
    return resp.json();
}

export async function updateConnection(connectionId: string, data: NewConnection): Promise<Connection> {
    const resp = await fetch(`${getApiBaseUrl()}/api/v1/connections/${encodeURIComponent(connectionId)}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(data),
    });
    if (!resp.ok) throw new Error(await extractErrorMessage(resp, "Failed to update connection"));
    return resp.json();
}

export async function deleteConnection(connectionId: string): Promise<void> {
    const resp = await fetch(`${getApiBaseUrl()}/api/v1/connections/${encodeURIComponent(connectionId)}`, {
        method: "DELETE",
    });
    if (!resp.ok) throw new Error(await extractErrorMessage(resp, "Failed to delete connection"));
}

export async function fetchConnectionStatus(connectionId: string): Promise<ConnectionStatus> {
    const resp = await fetch(`${getApiBaseUrl()}/api/v1/connections/${encodeURIComponent(connectionId)}/status`);
    if (!resp.ok) throw new Error(await extractErrorMessage(resp, "Failed to fetch connection status"));
    return resp.json();
}
```

- [ ] **Step 3: Add StreamEvent types and functions**

```typescript
// ─── Stream Events ─────────────────────────────────────────────────
export interface StreamEvent {
    id: string;
    sourceEventId?: string;
    source: string;
    connectionId: string;
    type: string;
    ref: string;
    timestamp: string;
    actor?: Record<string, unknown>;
    payload?: Record<string, unknown>;
    sourceData?: Record<string, unknown>;
    createdOn?: string;
}

export async function fetchStreamEvents(
    page = 1, limit = 20,
    filterType?: string, filterConnectionId?: string, filterRef?: string
): Promise<SearchResults<StreamEvent>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterType) params.set("filterType", filterType);
    if (filterConnectionId) params.set("filterConnectionId", filterConnectionId);
    if (filterRef) params.set("filterRef", filterRef);
    const resp = await fetch(`${getApiBaseUrl()}/api/v1/stream/events?${params}`);
    if (!resp.ok) throw new Error(await extractErrorMessage(resp, "Failed to fetch stream events"));
    return resp.json();
}

export async function fetchStreamEvent(eventId: string): Promise<StreamEvent> {
    const resp = await fetch(`${getApiBaseUrl()}/api/v1/stream/events/${encodeURIComponent(eventId)}`);
    if (!resp.ok) throw new Error(await extractErrorMessage(resp, "Failed to fetch stream event"));
    return resp.json();
}
```

- [ ] **Step 4: Verify TypeScript compiles**

Run: `cd ui && npx tsc --noEmit 2>&1 | head -20`

- [ ] **Step 5: Commit**

```bash
git add ui/src/config/api.ts
git commit -m "feat(ui): add API types and functions for connections and stream events"
```

---

### Task 2: Connections list page

**Files:**
- Create: `ui/src/pages/ConnectionsPage.tsx`

**Interfaces:**
- Consumes: `Connection`, `NewConnection`, `Secret`, `fetchConnections`, `createConnection`,
  `deleteConnection`, `fetchSecrets` from `api.ts`.
- Produces: `ConnectionsPage` component used by App.tsx route.

Model this page on `EventSourcesPage.tsx` but adapted for the new Connection model.

- [ ] **Step 1: Create ConnectionsPage.tsx**

The page should include:

**List view:**
- Table columns: ID (slug), Name, Type (github/jira label), Base URL, Enabled (boolean icon), Poll Interval
- Clickable rows navigate to `/connections/:connectionId`
- Pagination toolbar with `ChipFilterInput` (filter types: Name, Type)
- Refresh button
- "Add Connection" button opens the create modal
- Delete button per row with `ConfirmDeleteModal`

**Create modal** with fields:
- ID (slug) — text input with helper text "Lowercase letters, numbers, and dashes only"
  with validation pattern `^[a-z0-9-]+$`
- Name — text input (required)
- Source Type — dropdown: github, jira
- Base URL — text input with placeholder based on source type:
  - GitHub: "https://api.github.com"
  - Jira: "https://your-org.atlassian.net"
- Repositories (shown when sourceType is github) — text area, one `owner/repo` per line
- Projects (shown when sourceType is jira) — text area, one project key per line
- Authentication Secret — dropdown from secrets store
- Enabled — switch, default true
- Poll Interval — number input, optional

The create modal builds the `configuration` object from the repos/projects fields:
- GitHub: `{ repositories: ["owner/repo1", "owner/repo2"] }`
- Jira: `{ projects: ["PROJ1", "PROJ2"] }`

**Source type label colors:** github = blue, jira = green (same as existing events page).

- [ ] **Step 2: Verify it renders**

Run: `cd ui && npx tsc --noEmit 2>&1 | head -20`

- [ ] **Step 3: Commit**

```bash
git add ui/src/pages/ConnectionsPage.tsx
git commit -m "feat(ui): add connections list page with create modal"
```

---

### Task 3: Connection detail page

**Files:**
- Create: `ui/src/pages/ConnectionDetailPage.tsx`

**Interfaces:**
- Consumes: `Connection`, `NewConnection`, `ConnectionStatus`, `Secret`, `StreamEvent`,
  `fetchConnection`, `updateConnection`, `fetchConnectionStatus`, `fetchSecrets`,
  `fetchStreamEvents` from `api.ts`.
- Produces: `ConnectionDetailPage` component used by App.tsx route.

Model this on `EventSourceDetailPage.tsx` — tabbed layout with breadcrumbs and save button.

- [ ] **Step 1: Create ConnectionDetailPage.tsx**

**Two tabs:**

**Info tab:**
- Read-only display of ID (slug) — cannot be changed after creation
- Editable fields: Name, Description (text area), Source Type (read-only display),
  Base URL, Repositories or Projects (text area, conditional on source type),
  Enabled (switch), Poll Interval, Authentication Secret (dropdown)
- Save button at top persists changes via `updateConnection()`

**Status tab:**
- Fetches `ConnectionStatus` via `fetchConnectionStatus()`
- Displays: Enabled status, Last Polled At (formatted timestamp), Total Events Produced,
  Last Error (if any), Last Error At
- Uses `DescriptionList` for the metadata display
- "Recent Events" section: table of the last 10 stream events from this connection
  (uses `fetchStreamEvents` with `filterConnectionId` set to the connection slug)

**Breadcrumb:** Connections > {connection.name}

- [ ] **Step 2: Verify it renders**

Run: `cd ui && npx tsc --noEmit 2>&1 | head -20`

- [ ] **Step 3: Commit**

```bash
git add ui/src/pages/ConnectionDetailPage.tsx
git commit -m "feat(ui): add connection detail page with info and status tabs"
```

---

### Task 4: Event stream page

**Files:**
- Create: `ui/src/pages/EventStreamPage.tsx`
- Create: `ui/src/components/StreamEventDetailModal.tsx`

**Interfaces:**
- Consumes: `StreamEvent`, `fetchStreamEvents` from `api.ts`.
- Produces: `EventStreamPage` and `StreamEventDetailModal` components.

Model `EventStreamPage` on the existing `EventsPage.tsx` but for the new stream event model.

- [ ] **Step 1: Create StreamEventDetailModal.tsx**

A modal showing stream event details:
- Metadata card with: Source (colored label), Connection ID, Event Type, Ref (as a clickable link),
  Timestamp (formatted), Actor (login + display name)
- Payload section: JSON displayed in a read-only `CodeEditor` (same pattern as `EventDetailModal`)
- Source Data section: JSON displayed in a second `CodeEditor` (collapsed by default, expandable)
- Use dark/light theme support matching `EventDetailModal`

- [ ] **Step 2: Create EventStreamPage.tsx**

**Table columns:** Time (formatted relative/absolute), Source (colored label), Connection,
Event Type, Ref (truncated, linkable), Actor

**Toolbar:**
- `ChipFilterInput` with filter types: Type, Connection, Ref
- Refresh button
- Pagination

**Row click:** Opens `StreamEventDetailModal` with the full event

**Color coding for source:** github = blue, jira = green (reuse `SOURCE_COLORS` pattern)

**Event type display:** Show as a `Label` with compact styling

**Ref display:** Truncate long URLs, show as clickable external link (opens in new tab)

**Sorting:** Default newest-first (the API returns by timestamp descending)

- [ ] **Step 3: Verify both compile**

Run: `cd ui && npx tsc --noEmit 2>&1 | head -20`

- [ ] **Step 4: Commit**

```bash
git add ui/src/pages/EventStreamPage.tsx ui/src/components/StreamEventDetailModal.tsx
git commit -m "feat(ui): add event stream page with detail modal"
```

---

### Task 5: Navigation and routing

**Files:**
- Modify: `ui/src/App.tsx`
- Modify: `ui/src/components/AppSidebar.tsx`

**Interfaces:**
- Consumes: all page components from Tasks 2-4.
- Produces: working routes and navigation for the new pages.

- [ ] **Step 1: Update App.tsx**

Add imports for the new page components and add routes:

```tsx
<Route path="/connections" element={<ConnectionsPage />} />
<Route path="/connections/:connectionId" element={<ConnectionDetailPage />} />
<Route path="/logs/event-stream" element={<EventStreamPage />} />
```

Remove the old event source routes:
```tsx
// REMOVE these two lines:
<Route path="/event-sources" element={<EventSourcesPage />} />
<Route path="/event-sources/:eventSourceId" element={<EventSourceDetailPage />} />
```

Remove the old imports for `EventSourcesPage` and `EventSourceDetailPage`.

Update the old events route to point to the new stream page:
```tsx
// CHANGE this:
<Route path="/logs/events" element={<EventsPage />} />
// TO this:
<Route path="/logs/events" element={<EventStreamPage />} />
```

- [ ] **Step 2: Update AppSidebar.tsx**

In the `COMPONENT_PATHS` array, replace `/event-sources` with `/connections`:
```typescript
const COMPONENT_PATHS = ["/action-types", "/agents", "/session-templates", "/connections", ...];
```

In the Components nav section, replace the Event Sources nav item:
```tsx
// CHANGE:
<NavItem isActive={location.pathname.startsWith("/event-sources")} onClick={() => navigate("/event-sources")}>
    Event Sources
</NavItem>
// TO:
<NavItem isActive={location.pathname.startsWith("/connections")} onClick={() => navigate("/connections")}>
    Connections
</NavItem>
```

In the Logs nav section, update the Events label:
```tsx
// CHANGE:
<NavItem isActive={location.pathname === "/logs/events"} onClick={() => navigate("/logs/events")}>
    Events
</NavItem>
// TO:
<NavItem isActive={location.pathname === "/logs/events"} onClick={() => navigate("/logs/events")}>
    Event Stream
</NavItem>
```

- [ ] **Step 3: Verify everything compiles**

Run: `cd ui && npx tsc --noEmit 2>&1 | head -20`

- [ ] **Step 4: Commit**

```bash
git add ui/src/App.tsx ui/src/components/AppSidebar.tsx
git commit -m "feat(ui): update routing and navigation for connections and event stream"
```

---

### Task 6: Remove old Event Sources pages

**Files:**
- Delete: `ui/src/pages/EventSourcesPage.tsx`
- Delete: `ui/src/pages/EventSourceDetailPage.tsx`

- [ ] **Step 1: Verify no remaining imports**

Run: `grep -rn "EventSourcesPage\|EventSourceDetailPage" ui/src/ --include="*.tsx" --include="*.ts"`

Should return nothing after Task 5's changes. If anything remains, fix it.

- [ ] **Step 2: Delete the old files**

```bash
rm ui/src/pages/EventSourcesPage.tsx
rm ui/src/pages/EventSourceDetailPage.tsx
```

- [ ] **Step 3: Verify build**

Run: `cd ui && npx tsc --noEmit 2>&1 | head -20`

- [ ] **Step 4: Commit**

```bash
git add -u ui/src/pages/EventSourcesPage.tsx ui/src/pages/EventSourceDetailPage.tsx
git commit -m "chore(ui): remove old event sources pages"
```

---

## Phasing Summary

| Task | Description | Independently testable? |
|------|-------------|------------------------|
| 1 | API types and functions in api.ts | Yes — TypeScript compiles |
| 2 | Connections list page with create modal | Yes — page renders |
| 3 | Connection detail page with info/status tabs | Yes — page renders |
| 4 | Event stream page with detail modal | Yes — page renders |
| 5 | Routing and navigation wiring | Yes — full navigation works |
| 6 | Remove old Event Sources pages | Yes — clean build |

Tasks 2, 3, and 4 are independent of each other (all depend only on Task 1).
Task 5 depends on Tasks 2-4. Task 6 depends on Task 5.
