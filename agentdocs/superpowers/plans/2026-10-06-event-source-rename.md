# Event Source Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Replace "Connection" naming for event sources with "EventSource" naming everywhere.

**Architecture:** Pure rename, contract-first. Task 1 renames the API, DB, backend, event modules, templates and
the UI's wire-level calls in one green commit. Task 2 renames UI code, routes and labels. Task 3 updates docs.

**Tech Stack:** Java/Quarkus, Panache, H2/Flyway (single baseline), OpenAPI JAX-RS codegen, React + Vitest.

**Spec:** `docs/superpowers/specs/2026-10-06-event-source-rename-design.md` (contains the binding naming map)

## Global Constraints

- The spec's **Naming Map** is binding; use its exact names.
- Do not rename non-domain "connection" uses: JDBC/database connections, HTTP/OpenCode client connections,
  MCP connection code, Jira `projects` config. `event.source` and `sourceType` are unchanged.
- No aliases, no compatibility shims. Edit `app/src/main/resources/db/migration/V1__baseline.sql` in place
  (it must remain the only migration).
- Contract-first: edit `common/api/src/main/resources/openapi.json` with targeted text edits (no JSON
  round-trip), regenerate with `mvn -q -pl common/api install -DskipTests`; impl classes implement generated
  interfaces, no `@Path` on impls.
- Use `git mv` for renamed files. 4-space indent, Javadoc on public methods, JUnit 5, Vitest for UI.
- `rg` is not installed; use `git grep`. No browser-based testing.
- Every task ends with `./build.sh -Pui` passing (takes ~10 min) and a conventional commit, no AI attribution.
- Exclude `docs/superpowers/` and `agentdocs/` (historical) from all renames and greps.

---

### Task 1: Rename API, database, backend, events modules, templates and UI wire calls

**Files (by area; find all with `git grep -n -i connection -- . ':!docs' ':!agentdocs' ':!ui/package-lock.json'`):**
- `common/api/src/main/resources/openapi.json` — paths `/connections…`, tag `Connections`, schemas, operation
  IDs, `filterConnectionId`, all `connectionId` properties (e.g. on `StreamEvent`, poll log).
- `app/src/main/resources/db/migration/V1__baseline.sql` — tables, sequences, columns, indexes, FKs.
- `core/src/main/java/io/apitomy/axiom/core/entities/EventSourceConnectionEntity.java` → `EventSourceEntity.java`;
  `ConnectionPollLogEntity.java` → `EventSourcePollLogEntity.java`; `StreamEventEntity` (`connectionId` →
  `eventSourceId`, column `event_source_id`).
- `core/src/main/java/io/apitomy/axiom/core/events/model/NormalizedEvent.java` (`connectionId` →
  `eventSourceId`) and other event model classes.
- `events/core`, `events/github` (`GitHubConnectionPoller` → `GitHubEventSourcePoller` + test),
  `events/jira` (`JiraConnectionPoller` → `JiraEventSourcePoller` + test).
- `app/src/main/java/io/apitomy/axiom/app/rest/ConnectionsResourceImpl.java` → `EventSourcesResourceImpl.java`;
  `StreamEventsResourceImpl` (`filterEventSourceId`); `SubscriptionsResourceImpl:~228`,
  `EventStreamOrchestrator:~732,~854`, `WorkflowEventMapper:~36` (event map key `eventSourceId`);
  `ImportExportService` (`connections` → `eventSources` pack section, conflict type label);
  assistant classes (`AssistantContextBuilder`, `AssistantItemValidator`, `AssistantSessionManager`:
  `connections/` dir → `event-sources/`).
- Templates: `app/src/main/resources/templates/axiom-assistant-mcp/server.js` (`axiom_list_connections` →
  `axiom_list_event_sources`, `axiom_get_connection` → `axiom_get_event_source`, param `connectionId` →
  `eventSourceId`, API path `/event-sources`), `templates/assistant-templates/axiom-config-assistant.json`
  (prose, directory, tool names, allowed-tools list), any seed data.
- Tests: `app/src/test/java/io/apitomy/axiom/app/ConnectionsResourceTest.java` → `EventSourcesResourceTest.java`;
  `WorkflowEventMapperTest`; `core/src/test/java/io/apitomy/axiom/core/filters/SubscriptionFilterEvaluatorTest.java`;
  all other tests that reference renamed names.
- UI wire level only: `ui/src/config/api.ts` request URLs (`/connections` → `/event-sources`), JSON field
  names (`connectionId` → `eventSourceId`, `filterConnectionId` → `filterEventSourceId`) and the
  `ui/src/pages/EventStreamPage.tsx` filter key. Keep TS type/function/component names for Task 2.

**Interfaces:**
- Produces: REST `GET/POST /api/v1/event-sources`, `GET/PUT/DELETE /api/v1/event-sources/{eventSourceId}`,
  `GET …/{eventSourceId}/status`, `GET …/{eventSourceId}/logs`; `GET /api/v1/stream/events?filterEventSourceId=`;
  event JSON/map key `eventSourceId`; generated interface `EventSourcesResource`.

- [ ] **Step 1: Write failing tests**

Rename the test class with `git mv app/src/test/java/io/apitomy/axiom/app/ConnectionsResourceTest.java
app/src/test/java/io/apitomy/axiom/app/EventSourcesResourceTest.java`, rename the class to
`EventSourcesResourceTest`, change the Javadoc to "Tests for the Event Sources REST API endpoints.", change the
path constant to `private static final String EVENT_SOURCES_PATH = "/api/v1/event-sources";` (update all uses),
and add:

```java
    /**
     * The old Connections endpoint was renamed to Event Sources and must no longer exist.
     */
    @Test
    void oldConnectionsPathIsGone() {
        given().when().get("/api/v1/connections").then().statusCode(404);
    }

    /**
     * The Event Sources list endpoint responds.
     */
    @Test
    void eventSourcesPathResponds() {
        given().when().get(EVENT_SOURCES_PATH).then().statusCode(200);
    }
```

In `WorkflowEventMapperTest.mapsAllFieldsIncludingParsedPayload`, change `event.connectionId = "conn-1";` to
`event.eventSourceId = "conn-1";` and the assertion to:

```java
        assertEquals("conn-1", map.get("eventSourceId"));
        assertFalse(map.containsKey("connectionId"));
```

(do the same field rename in the other tests of that class; import `assertFalse` if needed).

In `SubscriptionFilterEvaluatorTest`, rename the helper parameter and key to `eventSourceId`
(`map.put("eventSourceId", eventSourceId);`), rename `connectionMatch` to `eventSourceMatch`, and use
`"event.eventSourceId == 'github-com'"` in it and in `andLogicBothMatch` (and any other expressions using
`event.connectionId`).

- [ ] **Step 2: Run — expect FAIL** (compile errors for `eventSourceId` field; 404 on `/event-sources`)

Run: `mvn -q -pl app -am test -Dtest='EventSourcesResourceTest,WorkflowEventMapperTest' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: openapi.json + regenerate**

Apply the spec's naming map to paths, tag, schemas, operation IDs, path param `{eventSourceId}`, query param
`filterEventSourceId`, and every `connectionId` property. Update descriptions ("connection" → "event source").
Run `mvn -q -pl common/api install -DskipTests`.

- [ ] **Step 4: Rename backend code**

`git mv` the entity, resource, poller and test files per the spec; rename classes, fields, locals, log
messages, Javadoc and Panache query strings (e.g. `StreamEventEntity.count("eventSourceId", eventSourceId)`).
Update `V1__baseline.sql`: `event_source_connection` → `event_source`, `connection_poll_log` →
`event_source_poll_log`, `connection_poll_log_SEQ` → `event_source_poll_log_SEQ`, `connection_id` →
`event_source_id`, indexes `idx_cpl_connection` → `idx_espl_event_source`, `idx_cpl_created` →
`idx_espl_created`, `idx_stream_event_connection` → `idx_stream_event_event_source`, and FK references. Fix
compile errors with `mvn -q -DskipTests compile test-compile`.

- [ ] **Step 5: Templates, assistant, import/export, UI wire calls** as listed under Files.

- [ ] **Step 6: Verify leftovers**

Run: `git grep -n -i connection -- . ':!docs' ':!agentdocs' ':!ui/src/pages' ':!ui/src/components' ':!ui/src/App.tsx' ':!ui/package-lock.json'`
Expected: only non-domain uses (JDBC `Connection`, HTTP/OpenCode/MCP client code, `api.ts` TS names left for
Task 2). List them in the report.

- [ ] **Step 7: Run tests**

Run: `mvn -q -pl app -am test -Dtest='EventSourcesResourceTest,WorkflowEventMapperTest,MigrationSchemaValidationTest' -Dsurefire.failIfNoSpecifiedTests=false`
and `mvn -q -pl core test -Dtest=SubscriptionFilterEvaluatorTest`. Expected: PASS.
Then `./build.sh -Pui` — BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: rename event source connections to event sources (API, DB, backend)"
```

---

### Task 2: Rename UI code, routes and labels

**Files:**
- `git mv ui/src/pages/ConnectionsPage.tsx ui/src/pages/EventSourcesPage.tsx`,
  `git mv ui/src/pages/ConnectionDetailPage.tsx ui/src/pages/EventSourceDetailPage.tsx`
- `ui/src/App.tsx` routes: `/events/connections` → `/events/sources`,
  `/events/connections/:connectionId` → `/events/sources/:eventSourceId`
- `ui/src/components/AppSidebar.tsx`: Event Sources item navigates to and is active on `/events/sources`
- `ui/src/config/api.ts`: types (`Connection` → `EventSource`, `NewConnection` → `NewEventSource`,
  `ConnectionStatus` → `EventSourceStatus`, `ConnectionPollLog` → `EventSourcePollLog`, search results),
  functions (`fetchConnections` → `fetchEventSources`, etc.)
- All other UI files linking to `/events/connections` or showing "Connection" for event sources
  (`git grep -n -i connection -- ui/src`), e.g. `EventStreamPage.tsx` filter label "Connection" →
  "Event Source", subscription/event detail pages.
- Test: `ui/src/components/AppSidebar.test.tsx`

- [ ] **Step 1: Failing test** — add to `AppSidebar.test.tsx` (it already mocks api/sse and imports
  `userEvent`-free helpers; add the import shown):

```tsx
import { Route, Routes, useLocation } from "react-router-dom";
import userEvent from "@testing-library/user-event";

function LocationProbe() {
    const location = useLocation();
    return <div data-testid="location">{location.pathname}</div>;
}
```

```tsx
    it("navigates Event Sources to /events/sources", async () => {
        renderWithRouter(
            <Routes>
                <Route path="*" element={<><AppSidebar /><LocationProbe /></>} />
            </Routes>,
            "/events/stream"
        );
        await userEvent.click(await screen.findByText("Event Sources"));
        expect(screen.getByTestId("location")).toHaveTextContent("/events/sources");
    });
```

Run: `cd ui && npx vitest run src/components/AppSidebar.test.tsx` — expect FAIL (`/events/connections`).

- [ ] **Step 2: Rename** files, routes, types, functions, labels as listed.

- [ ] **Step 3: Verify** `git grep -n -i connection -- ui/src` — expected: no event-source matches (report any
  non-domain matches).

- [ ] **Step 4: Run** `cd ui && npm test && npm run build` — PASS; then `./build.sh -Pui` — BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(ui): rename connections to event sources"
```

---

### Task 3: Documentation

**Files:** `README.md`, `docs/user-guide/*.md`, `docs/developer-guide/*.md`, `docs/getting-started/*.md`
(find with `git grep -l -i connection -- README.md docs ':!docs/superpowers'`).

- [ ] **Step 1: Edit** — "connection(s)" meaning event source → "event source(s)"; paths `/connections` →
  `/event-sources`; `event.connectionId` → `event.eventSourceId`; class/table names per the spec; UI path
  `/events/sources`; assistant tools `axiom_list_event_sources` / `axiom_get_event_source`. Wrap prose at 110
  columns (tables excepted).
- [ ] **Step 2: Verify** `git grep -n -i connection -- README.md docs ':!docs/superpowers'` — only non-domain
  matches. If `mkdocs` is installed, `mkdocs build --strict` passes.
- [ ] **Step 3: Commit** — `git add -A && git commit -m "docs: rename connections to event sources"`

---

## Final Verification

- [ ] `./build.sh -Pui` — BUILD SUCCESS.
- [ ] `git grep -n -i connection -- . ':!docs/superpowers' ':!agentdocs' ':!ui/package-lock.json'` — only the
  non-domain uses listed in the spec's Non-Goals.
- [ ] `ls app/src/main/resources/db/migration` — exactly `V1__baseline.sql`.
