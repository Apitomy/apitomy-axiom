# UI Development

This guide covers the frontend architecture, development workflow, and patterns used
in the Axiom React UI.

---

## Tech Stack

| Technology | Purpose |
|-----------|---------|
| React 19 | UI framework |
| PatternFly 6 | Component library (Red Hat's design system) |
| TypeScript | Type-safe JavaScript |
| Vite | Build tool and dev server |
| React Router | Client-side routing |
| `@apitomy/flow-ui` 3.0.2 | Workflow editor and viewer (`WorkflowViewer`) |
| Monaco Editor | Code editors (prompt templates, scripts, logs) |
| react-markdown | Markdown rendering (subject bodies, thread entries) |
| Vitest + Testing Library | Unit and component tests (`npm test`) |
| `@apitomy/common-ui-components` | Shared filtering components |

---

## Project Structure

```
ui/src/
├── main.tsx               Entry point — React root, BrowserRouter
├── App.tsx                 Route definitions, SSE client init, health check
├── config/
│   ├── api.ts              REST API client layer (fetch functions + types)
│   ├── compositions.ts     Workflow Workbench API client (/compositions) + types
│   ├── sse.ts              Server-Sent Events client (auto-reconnect)
│   └── sse-worker.ts       SharedWorker that owns the single SSE connection
├── hooks/                  Shared hooks (useTheme, useWorkflowEditorSpi)
├── pages/                  One component per route
│   ├── WorkflowRunsPage.tsx     Runs list — the landing page (/runs)
│   ├── WorkflowRunDetailPage.tsx
│   ├── SubjectsPage.tsx
│   ├── SubjectDetailPage.tsx
│   ├── ActionTypesPage.tsx
│   ├── AssistantPage.tsx
│   ├── AssistantSessionPage.tsx
│   ├── SessionTemplatesPage.tsx
│   ├── SessionTemplateDetailPage.tsx
│   ├── WorkbenchListPage.tsx    Workbench composition list (/workbench)
│   ├── workbench/               Workflow Workbench (/workbench/:compositionId)
│   │   ├── WorkbenchPage.tsx        Three-pane layout, toolbar, bottom Tests/Runs panel
│   │   ├── useWorkbench.ts          Composition state, saves, SSE handling
│   │   ├── CompositionTree.tsx      Components tree
│   │   ├── WorkflowTab.tsx, ItemTab.tsx   Centre editor tabs
│   │   ├── ChatPane.tsx             AI Assistant chat
│   │   ├── TestRunDialog.tsx, TestRunDetailModal.tsx, RunsList.tsx, RunDetailDialog.tsx
│   │   └── PublishDialog.tsx
│   └── ...
├── test/                   Vitest setup (setup.ts) and helpers (renderWithRouter.tsx)
├── components/             Reusable UI components
│   ├── AppMasthead.tsx
│   ├── AppSidebar.tsx
│   ├── ExecutionLogModal.tsx
│   ├── StreamEventDetailModal.tsx
│   └── assistant/          AI Assistant components
│       ├── AssistantChatPanel.tsx      SSE-connected chat orchestrator
│       ├── AssistantMessageList.tsx    Message rendering (markdown, tools, permissions)
│       ├── AssistantMessageInput.tsx   Input with slash-command autocomplete
│       ├── AssistantToolUseBlock.tsx   Expandable tool use display + permission UX
│       ├── AssistantPermissionPrompt.tsx  Permission allow/deny UI
│       ├── AssistantAskUserQuestion.tsx   Structured question rendering
│       └── AssistantGeneratedItems.tsx    Config Assistant sidebar
└── vite-env.d.ts           Vite type declarations
```

---

## API Layer (`api.ts`)

Backend communication goes through `ui/src/config/api.ts`. The Workflow Workbench API (`/compositions`) has
its own client in `ui/src/config/compositions.ts`. `api.ts` contains:

- **TypeScript interfaces** for every API entity (`Subject`, `Task`, `ActionType`,
  `ToolDefinition`, etc.)
- **Fetch functions** for every REST endpoint (`fetchSubjects()`, `createTool()`,
  `updateActionType()`, etc.)
- **Pagination type** — `SearchResults<T>` with `items`, `totalCount`, `page`, `limit`

### Base URL Resolution

```typescript
export function getApiBaseUrl(): string {
    // window.AXIOM_API_URL if defined, otherwise "" (same origin;
    // in development the Vite proxy forwards /api to the backend)
}

const API = `${getApiBaseUrl()}/api/v1`;
```

### API Function Pattern

Functions follow the same pattern:

```typescript
export async function fetchSubjects(
    page = 1, limit = 20, filterName?: string, filterActive?: boolean, filterLabels?: string
): Promise<SearchResults<Subject>> {
    const params = new URLSearchParams();
    params.set("page", String(page));
    params.set("limit", String(limit));
    if (filterName) params.set("filterName", filterName);
    if (filterActive !== undefined) params.set("filterActive", String(filterActive));
    if (filterLabels) params.set("filterLabels", filterLabels);
    const response = await fetch(`${API}/subjects?${params}`);
    if (!response.ok) throw new Error(`Failed to fetch subjects: ${response.status}`);
    return response.json();
}
```

When adding a new API function, follow this pattern — plain `fetch()`, check `response.ok`,
return typed JSON. Use `extractErrorMessage()` when the server returns a useful error message (for example a
422 validation error).

---

## SSE System

Axiom uses Server-Sent Events for real-time UI updates (task status changes, new
activity log entries, workflow run updates, etc.).

### Frontend: `SseClient` (`sse.ts`)

A class-based singleton (`sseClient`) managing a persistent `EventSource` connection:

- When the browser supports `SharedWorker`, one worker (`sse-worker.ts`) owns the connection and broadcasts
  events to all tabs, so there is exactly one SSE connection per browser; otherwise each tab opens its own
- Auto-reconnects with exponential backoff (1 second to 30 seconds max)
- Parses nested JSON (data serialized as a string within JSON)
- Subscriber pattern — components subscribe to events and receive callbacks; `subscribeSession()` delivers
  the events of one AI Assistant session

```typescript
// In App.tsx:
sseClient.connect();

// In a component:
const unsub = sseClient.subscribe((event) => {
    if (event.type === "task-updated") {
        // refresh data
    }
});
```

### Backend: `SseResource.java`

- Endpoint: `GET /api/v1/sse` (produces `text/event-stream`)
- Maintains a map of active client emitters
- Observes CDI `SseEvent` events and broadcasts to all connected clients
- Any service can fire an SSE event with one of the `SseEvent` factory methods, e.g.
  `sseEvents.fire(SseEvent.traceUpdated(traceId))`
- Event types include `subject-updated`, `task-updated`, `workflow-updated`, `stream-event`,
  `assistant-session-event` and the workbench events `composition-item-changed`, `composition-turn`,
  `composition-changeset` and `composition-test-run`

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

In `AppSidebar.tsx`, add a `<NavItem>` (inside a `<NavExpandable>` if it belongs to a group such as
Events or Components):

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
4. Run `npm run build` (which runs `tsc -b && vite build`) to type-check before committing
5. Run `npm test` (Vitest, configured in `vitest.config.ts`) to run the UI unit and component tests.
   Tests live next to the code they cover as `*.test.ts(x)` files (e.g. `pages/WorkflowRunsPage.test.tsx`).
   `./build.sh -Pui` builds the UI and runs these tests as part of the Maven build
