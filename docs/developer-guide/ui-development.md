# UI Development

This guide covers the frontend architecture, development workflow, and patterns used
in the Axiom React UI.

---

## Tech Stack

| Technology | Version | Purpose |
|-----------|---------|---------|
| React | 19 | UI framework |
| PatternFly | 6 | Component library (Red Hat's design system) |
| TypeScript | 5.9 | Type-safe JavaScript |
| Vite | 6.4 | Build tool and dev server |
| Monaco Editor | 0.55 | Code editors (prompt templates, scripts, logs) |
| react-markdown | 10 | Markdown rendering (reports, thread entries) |
| Biome | — | Linter and formatter |
| `@apitomy/common-ui-components` | 4.1 | Shared filtering components |

---

## Project Structure

```
ui/src/
├── main.tsx               Entry point — React root, BrowserRouter
├── App.tsx                 Route definitions, SSE client init, health check
├── config/
│   ├── api.ts              REST API client layer (all fetch functions + types)
│   └── sse.ts              Server-Sent Events client (auto-reconnect)
├── pages/                  One component per route
│   ├── DashboardPage.tsx
│   ├── ProjectsPage.tsx
│   ├── ProjectDetailPage.tsx
│   ├── ActionTypesPage.tsx
│   ├── ...
│   └── AssistantSessionPage.tsx
├── components/             Reusable UI components
│   ├── AppMasthead.tsx
│   ├── AppSidebar.tsx
│   ├── ExecutionLogModal.tsx
│   ├── EventDetailModal.tsx
│   └── assistant/          AI Assistant chat components
└── vite-env.d.ts           Vite type declarations
```

---

## API Layer (`api.ts`)

All backend communication goes through `ui/src/config/api.ts`. This file contains:

- **TypeScript interfaces** for every API entity (`Project`, `Task`, `ActionType`,
  `ToolDefinition`, etc.)
- **Fetch functions** for every REST endpoint (`fetchProjects()`, `createTool()`,
  `updateActionType()`, etc.)
- **Pagination type** — `SearchResults<T>` with `items`, `totalCount`, `page`, `limit`

### Base URL Resolution

```typescript
function getApiBaseUrl(): string {
    // Dev: empty string (Vite proxy handles /api)
    // Prod: window.AXIOM_API_URL injected by backend
}

const API_BASE = `${getApiBaseUrl()}/api/v1`;
```

### API Function Pattern

Every function follows the same pattern:

```typescript
export async function fetchProjects(
    page: number,
    limit: number,
    name?: string,
): Promise<SearchResults<Project>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (name) params.set("name", name);

    const resp = await fetch(`${API_BASE}/projects?${params}`);
    if (!resp.ok) throw new Error("Failed to fetch projects");
    return resp.json();
}
```

When adding a new API function, follow this pattern — plain `fetch()`, check `resp.ok`,
return typed JSON.

---

## SSE System

Axiom uses Server-Sent Events for real-time UI updates (task status changes, new
activity log entries, report progress, etc.).

### Frontend: `SseClient` (`sse.ts`)

A class-based singleton managing a persistent `EventSource` connection:

- Auto-reconnects with exponential backoff (1 second to 30 seconds max)
- Parses nested JSON (data serialized as a string within JSON)
- Subscriber pattern — components subscribe to events and receive callbacks

```typescript
// In App.tsx:
sseClient.connect();

// In a component:
const unsub = sseClient.subscribe((event) => {
    if (event.type === "task-completed") {
        // refresh data
    }
});
```

### Backend: `SseResource.java`

- Endpoint: `GET /api/v1/sse` (produces `text/event-stream`)
- Maintains a map of active client emitters
- Observes CDI `SseEvent` events and broadcasts to all connected clients
- Any service can fire an SSE event: `sseEvents.fire(new SseEvent(type, data))`

---

## Adding a New Page

### 1. Create the Page Component

Create a new file in `ui/src/pages/`:

```typescript
// ui/src/pages/MyFeaturePage.tsx
import { PageSection, Title } from "@patternfly/react-core";

export function MyFeaturePage() {
    return (
        <PageSection>
            <Title headingLevel="h1" size="lg">My Feature</Title>
            {/* page content */}
        </PageSection>
    );
}
```

### 2. Add the Route

In `App.tsx`, import the component and add a `<Route>`:

```typescript
import { MyFeaturePage } from "./pages/MyFeaturePage";

// Inside <Routes>:
<Route path="/my-feature" element={<MyFeaturePage />} />
```

### 3. Add Sidebar Navigation

In `AppSidebar.tsx`, add a `<NavItem>`:

```typescript
<NavItem
    isActive={location.pathname.startsWith("/my-feature")}
    onClick={() => navigate("/my-feature")}
>
    My Feature
</NavItem>
```

### 4. Add API Functions

In `api.ts`, add TypeScript interfaces and fetch functions for any new endpoints.

---

## Common UI Patterns

### Filtering with Chips

List pages use `ChipFilterInput` and `FilterChips` from `@apitomy/common-ui-components`:

```typescript
const FILTER_TYPES: ChipFilterType[] = [
    { value: "name", label: "Name", testId: "filter-name" },
    { value: "status", label: "Status", testId: "filter-status" },
];

<ChipFilterInput filterTypes={FILTER_TYPES} onAddCriteria={onAddFilterCriteria} />
<FilterChips criteria={filters} onClearAllCriteria={onClearAll}
    onRemoveCriteria={onRemove} />
```

### Pagination

```typescript
<Pagination
    itemCount={totalCount}
    page={page}
    perPage={perPage}
    onSetPage={(_e, p) => setPage(p)}
    onPerPageSelect={(_e, pp) => { setPerPage(pp); setPage(1); }}
    isCompact
/>
```

### Clickable Table Rows

```typescript
<Tr isClickable onRowClick={() => navigate(`/items/${item.id}`)}>
```

### Create/Delete Modals

Create and delete operations use PatternFly `<Modal>` with confirmation:

```typescript
<Modal isOpen={isOpen} onClose={() => setIsOpen(false)} variant="small">
    <ModalHeader title="Delete Item" />
    <ModalBody>Are you sure?</ModalBody>
    <ModalFooter>
        <Button variant="danger" onClick={handleDelete}>Delete</Button>
        <Button variant="link" onClick={() => setIsOpen(false)}>Cancel</Button>
    </ModalFooter>
</Modal>
```

### Code Editor

For prompt templates, script editors, and log viewers:

```typescript
import { CodeEditor, Language } from "@patternfly/react-code-editor";

<CodeEditor
    code={value}
    onCodeChange={(v) => onChange(v)}
    language={Language.markdown}
    height="500px"
    isLineNumbersVisible
    isReadOnly={false}
/>
```

---

## Development Workflow

1. Start the UI dev server: `cd ui && npm run dev` (or use `./dev.sh` which starts
   both)
2. The Vite dev server runs on port 9191 and proxies `/api` requests to the backend
   on port 9090
3. Hot module replacement (HMR) updates the browser instantly when you save a file
4. Run `npm run lint` to check code quality before committing
