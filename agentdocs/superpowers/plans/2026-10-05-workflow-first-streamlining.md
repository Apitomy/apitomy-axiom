# Workflow-First Streamlining Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Refocus Axiom on workflows: remove reports, scheduled jobs, the AI Manager, `invoke-action` and all
non-workflow action invocation; rename Project → Subject; enrich workflow runs; add schedule triggers.

**Architecture:** Mostly subtractive refactoring of a Quarkus (Panache active-record) backend + React/PatternFly
UI, followed by a rename and two small additive features. The DB schema is reset first to a single
`V1__baseline.sql` that every later task edits in place; `MigrationSchemaValidationTest` keeps it in sync with
the entity model. REST changes are contract-first in `openapi.json`.

**Tech Stack:** Java 21, Quarkus, Hibernate Panache, H2, Flyway, JUnit 5 + RestAssured (`@QuarkusTest`),
cron-utils (transitive via quarkus-scheduler), React 19 + Vite + PatternFly 6.

**Spec:** `docs/superpowers/specs/2026-10-05-workflow-first-streamlining-design.md`

## Global Constraints

- Contract-first: every REST change starts in `common/api/src/main/resources/openapi.json`; impl classes
  implement generated interfaces; no `@Path` on impl classes; use generated beans from
  `io.apitomy.axiom.api.beans`.
- No data migration, no backward compatibility, no API aliases. Exactly one Flyway file:
  `app/src/main/resources/db/migration/V1__baseline.sql`.
- Ignore `.worktrees/` and `.claude/worktrees/` when grepping/deleting (stale repo copies).
- 4-space indentation, Javadoc on public methods, explicit types (no ambiguous `var`), JUnit 5.
- Every task ends with a green build: `./build.sh` (= `mvn clean package`) passes, and
  `cd ui && npm run build` passes for tasks touching the UI.
- Commit messages: conventional style (`refactor:`, `feat:`, `docs:`), no AI attribution.
- Action Types are invoked **only** from workflow `action` nodes.
- Every workflow run **requires** a subject. At most one active (`running`/`waiting`) run per
  (subject, workflow definition); violations are **skipped and recorded**, not queued.
- Workflow runs have **no outputs**.
- `rg` is **not installed**. Every `rg -n [-i] 'PATTERN' --glob '!X' …` in this plan means
  `git grep -n [-i] -E 'PATTERN' -- . ':!X' …` (git grep only searches tracked files, so `.worktrees/` and
  `.claude/` are already excluded).
- **No browser-based manual testing.** Verification is automated only: Maven tests, `npm run build`, and
  `npm test` (Vitest, set up in Task 1b).
- From Task 1b on, every task touching `ui/` must keep `cd ui && npm test` green.
- **Axiom SDK** = the functions agents call via MCP: `core/src/main/resources/axiom-sdk-functions.json`
  (definitions), `core/.../SdkFunctionRegistry.java`, `app/.../SdkCallService.java` (implementations),
  `app/.../rest/SdkResource.java`, `app/src/main/resources/templates/axiom-mcp-server/sdk-server.js`,
  the assistant templates (`templates/axiom-assistant-mcp/server.js`,
  `templates/assistant-templates/axiom-config-assistant.json`), `core/.../services/ActionTypeValidator.java`
  (validates SDK tool names in allowed tools), UI `ui/src/config/sdkTools.ts` and
  `ui/src/components/assistant/toolNames.ts`, tests `SdkResourceTest`, `McpConfigGeneratorTest`,
  `ActionTypeValidatorTest`. Every SDK change must update **all** of these consistently.

## Axiom SDK Changes (summary)

| Function | Change | Task |
|----------|--------|------|
| `axiom_add_report_label`, `axiom_remove_report_label`, `axiom_list_report_definitions`, `axiom_list_reports` | Remove | 4 |
| `axiom_create_task` | Remove | 6 |
| `axiom_close_project`, `axiom_reopen_project` | Remove | 7 |
| `axiom_list_projects`, `axiom_get_project`, `axiom_create_project`, `axiom_update_project`, `axiom_update_project_body`, `axiom_add_project_label`, `axiom_remove_project_label`, `axiom_get_project_thread` | Rename `project` → `subject` (names, params `projectId` → `subjectId`, descriptions) | 8 |
| `axiom_get_workflow_run` | **Add**: returns the caller's (or a given `runId`'s) run: id, title, status, definitionId, definitionVersion, subjectId, labels, inputs, currentNodeId | 10 |
| `axiom_list_subject_runs` | **Add**: lists runs for a `subjectId` (id, title, status, definitionId, startedOn, completedOn) | 10 |
| `axiom_fire_event`, `axiom_get_task_status`, `axiom_add_thread_entry`, `axiom_list_tools`, `axiom_list_action_types`, `axiom_list_agents`, `axiom_list_events`, `axiom_respond_to_task`, `axiom_get_activity_log` | Keep; update descriptions/params mentioning projects, reports or the Manager | 2, 4, 8 |

## Execution Order

| # | Task | Area |
|---|------|------|
| 1 | Reset schema to single baseline | DB |
| 1b | UI test harness (Vitest + Testing Library) | UI |
| 2 | Remove AI Manager | backend, UI, API |
| 3 | Remove `invoke-action` routing | backend, UI, API |
| 4 | Remove reports | backend, UI, API |
| 5 | Remove scheduled jobs | backend, UI, API |
| 6 | Remove action type flags and non-workflow action invocation | backend, UI, API |
| 7 | Drop project lifecycle; per-(project, definition) run rule | backend, UI, API |
| 8 | Rename Project → Subject (backend + API) | backend, API |
| 9 | Rename Project → Subject (UI) | UI |
| 10 | Workflow run `title`, `labels`, `inputs` + run filters | backend, API |
| 11 | Schedule triggers (backend) | backend, API |
| 12 | Runs-first UI, subject timeline, Triggers tab | UI |
| 13 | Documentation | docs |

Removal tasks share a technique: **delete owned files, then compile and fix every remaining reference.** Use
`mvn -q -DskipTests compile test-compile` for the fast loop and `rg -n '<Term>' --glob '!.worktrees' --glob
'!.claude'` to confirm zero matches before the full build.

---

### Task 1: Reset schema to a single baseline

**Files:**
- Delete: `app/src/main/resources/db/migration/V2__*.sql` … `V73__*.sql` (every file except V1)
- Replace: `app/src/main/resources/db/migration/V1__baseline.sql`
- Modify: `app/src/test/java/io/apitomy/axiom/app/db/MigrationSchemaValidationTest.java`
- Create (temporary, not committed): `/tmp/opencode/GenerateBaseline.java` (see step 2)

**Interfaces:**
- Produces: a `V1__baseline.sql` that exactly matches the current entity model; later tasks edit it in place.

- [ ] **Step 1: Remove the upgrade-path test**

Delete the `v73NeverLowersExistingAgentSequence` test method and its now-unused imports (`Path`, `TempDir`,
`assertEquals`) from `MigrationSchemaValidationTest`. It tests an upgrade path that no longer exists.

- [ ] **Step 2: Generate DDL from the entity model**

Temporarily add this test method to `MigrationSchemaValidationTest` (it reuses the class's `entities`,
`buildRegistry` and `buildMetadata` helpers), run it once, then delete it:

```java
@Test
void dumpBaseline() throws Exception {
    StandardServiceRegistry registry = buildRegistry(
            "jdbc:h2:mem:dump-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "none");
    try {
        Metadata metadata = buildMetadata(registry);
        org.hibernate.tool.hbm2ddl.SchemaExport export = new org.hibernate.tool.hbm2ddl.SchemaExport();
        export.setFormat(true);
        export.setDelimiter(";");
        export.setOutputFile("/tmp/opencode/baseline.sql");
        export.createOnly(java.util.EnumSet.of(org.hibernate.tool.schema.TargetType.SCRIPT), metadata);
    } finally {
        StandardServiceRegistryBuilder.destroy(registry);
    }
}
```

Run: `mvn -q -pl app -am test -Dtest=MigrationSchemaValidationTest#dumpBaseline -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `/tmp/opencode/baseline.sql` exists and contains `create table` for every entity.

- [ ] **Step 3: Write the new baseline**

Before deleting old migrations, grep them for non-DDL content that the app depends on:
`rg -n 'INSERT|insert into' app/src/main/resources/db/migration`. Seed rows are normally created by
`SeedDataInitializer`; carry over any INSERT that is not, plus any explicit indexes (`rg -n 'CREATE.*INDEX'`)
that Hibernate did not generate.

Write `V1__baseline.sql` = header comment + generated DDL + carried-over indexes/inserts:

```sql
-- Apitomy Axiom baseline schema.
-- The schema was reset on 2026-10-05 (workflow-first streamlining); no upgrade path from earlier versions.
```

Then delete every other migration file and the temporary `dumpBaseline` method.

- [ ] **Step 4: Verify**

Run: `mvn -q -pl app -am test -Dtest=MigrationSchemaValidationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (`migrationsMatchEntityModel`, `everyEntitySequenceExists`).

Run: `./build.sh`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/resources/db/migration app/src/test/java/io/apitomy/axiom/app/db
git commit -m "refactor(db): reset schema to a single baseline migration"
```

**Note for all later tasks:** whenever an entity/table/column is added, removed or renamed, edit
`V1__baseline.sql` directly (never add V2). `MigrationSchemaValidationTest` is the guard.

---

### Task 1b: UI test harness (Vitest + Testing Library)

Basic, not comprehensive: a working harness plus a smoke test of the sidebar. Later tasks add a few focused
tests.

**Files:**
- Modify: `ui/package.json` (devDependencies + `"test": "vitest run"` script)
- Create: `ui/vitest.config.ts`, `ui/src/test/setup.ts`, `ui/src/test/renderWithRouter.tsx`
- Create: `ui/src/components/AppSidebar.test.tsx`
- Modify: `ui/tsconfig.json` — add `"types": ["vitest/globals", "@testing-library/jest-dom"]` to
  `compilerOptions` (keep test files type-checked by `tsc -b`)
- Modify: `ui-bundle/pom.xml` — after the npm `build` execution, add an npm `test` execution (same plugin/
  pattern as the existing build execution) so `./build.sh -Pui` runs UI tests

**Interfaces:**
- Produces: `renderWithRouter(ui: ReactElement, path?: string)` helper (wraps in `MemoryRouter` with
  `initialEntries={[path ?? "/"]}`), used by all later UI tests.
- Produces: convention — tests live next to the component as `*.test.tsx`; API calls are mocked with
  `vi.mock("../config/api", ...)`; SSE with `vi.mock("../config/sse", ...)`.

- [ ] **Step 1: Install dev dependencies**

Run (in `ui/`): `npm install --save-dev --save-exact vitest jsdom @testing-library/react @testing-library/jest-dom @testing-library/user-event`
Expected: `package.json` devDependencies updated, `package-lock.json` updated.

- [ ] **Step 2: Config files**

`ui/vitest.config.ts`:

```ts
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
    plugins: [react()],
    test: {
        environment: "jsdom",
        globals: true,
        setupFiles: ["./src/test/setup.ts"],
        css: false,
    },
});
```

`ui/src/test/setup.ts`:

```ts
import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

afterEach(() => {
    cleanup();
});
```

`ui/src/test/renderWithRouter.tsx`:

```tsx
import type { ReactElement } from "react";
import { render, type RenderResult } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

/**
 * Renders a component inside a MemoryRouter for tests.
 *
 * @param ui the element to render
 * @param path the initial route (defaults to "/")
 * @returns the Testing Library render result
 */
export function renderWithRouter(ui: ReactElement, path: string = "/"): RenderResult {
    return render(<MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>);
}
```

Add `"test": "vitest run"` to `scripts` in `ui/package.json`.

- [ ] **Step 3: Sidebar smoke test** — `ui/src/components/AppSidebar.test.tsx`:

```tsx
import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AppSidebar } from "./AppSidebar";
import { renderWithRouter } from "../test/renderWithRouter";

vi.mock("../config/api", () => ({
    fetchInboxCount: vi.fn().mockResolvedValue({ count: 0 }),
}));
vi.mock("../config/sse", () => ({
    sseClient: { subscribe: vi.fn(() => () => {}) },
}));

describe("AppSidebar", () => {
    it("renders the Inbox navigation item", async () => {
        renderWithRouter(<AppSidebar />);
        expect(await screen.findByText("Inbox")).toBeInTheDocument();
    });
});
```

If the sidebar renders expandable groups collapsed, assert only on top-level labels. If PatternFly needs
`window.matchMedia` in jsdom, add a stub to `setup.ts`:

```ts
Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: (query: string) => ({
        matches: false, media: query, onchange: null,
        addListener: () => {}, removeListener: () => {},
        addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false,
    }),
});
```

- [ ] **Step 4: Run**

Run: `cd ui && npm test` — expected: 1 test passed.
Run: `cd ui && npm run build` — expected: success.
Run: `./build.sh -Pui` — expected: BUILD SUCCESS (UI tests run in the build).

- [ ] **Step 5: Commit**

```bash
git add ui/package.json ui/package-lock.json ui/vitest.config.ts ui/tsconfig.json ui/src/test ui/src/components/AppSidebar.test.tsx ui-bundle/pom.xml
git commit -m "test(ui): add Vitest and Testing Library harness"
```

---

### Task 2: Remove the AI Manager

**Files:**
- Delete module: `manager/` (entire directory)
- Modify: `pom.xml` (remove `<module>manager</module>` and the `apitomy-axiom-manager` dependencyManagement
  entry at ~line 86), `app/pom.xml` (remove dependency at ~line 30)
- Delete: `app/src/main/java/io/apitomy/axiom/app/rest/ManagerResourceImpl.java`,
  `app/src/main/java/io/apitomy/axiom/app/ManagerTraceRecorder.java`,
  `core/src/main/java/io/apitomy/axiom/core/entities/ManagerConfigEntity.java`
- Delete tests: `app/src/test/java/io/apitomy/axiom/app/ManagerResourceTest.java`,
  `ManagerServiceEvaluationTest.java`, `ManagerDryRunTest.java`, `ManagerTracingTest.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/EventStreamOrchestrator.java` (imports 23-25, field 82,
  `routeToManager` ~601 and its helpers, manager trace ~689, `createdBy="manager"` ~929, thread entries ~957/~997)
- Modify: `app/src/main/java/io/apitomy/axiom/app/SeedDataInitializer.java` (manager config seeding, line 13 import)
- Modify: `core/src/main/java/io/apitomy/axiom/core/events/model/RoutingRule.java` (remove `TYPE_MANAGER`)
- Modify (comments/Javadoc/enum values): `EventSubscriptionEntity.java:45`, `AiUsageEntity.java:25`,
  `RoutingOutcomeItemEntity.java:19`, `ScriptAiService.java`, `ScriptExecutionService.java`,
  `ImportExportService.java`, `rest/ActionResourceImpl.java`
- Modify tests that mock `ManagerService` or use routing/trace type `"manager"`: `TraceCorrelationTest`,
  `RetryBackoffTest`, `RoutingOutcomeItemsTest`, `TraceLifecycleTest`, `TraceResourceTest`,
  `ProjectDeletionServiceTest:101`, `TraceCleanupTest:257`, `LineageTest`, `EventStreamOrchestratorTest`,
  `ScriptExecutionServiceTest`, `assistant/AssistantItemValidatorTest`
- Modify: `openapi.json` — delete paths `/manager/config`, `/manager/evaluate/{eventId}`; schemas
  `ManagerConfig`, `ManagerDecision`; remove `"manager"` from enums (~lines 6514, 7542, 10566, 10680)
- Modify templates: `app/src/main/resources/templates/axiom-assistant-mcp/server.js`,
  `templates/assistant-templates/axiom-config-assistant.json` (drop manager tools/text)
- Modify: `V1__baseline.sql` (drop `manager_config` table and sequence)
- UI: delete `ui/src/pages/ManagerConfigPage.tsx`, `ManagerDecisionsPage.tsx`; edit `ui/src/App.tsx`,
  `ui/src/components/AppSidebar.tsx`, `ui/src/config/api.ts:~2496`, `ui/src/pages/SubscriptionDetailPage.tsx`
  (~839, 858, 944), `ui/src/components/PlaceholderCompletionProvider.ts:~138`
- Inbox: find manager-originated inbox writes with `rg -n 'InboxItem|inbox' app/src/main/java` and delete
  them; Inbox keeps only human-task items.

**Interfaces:**
- Produces: `RoutingRule` supports only `create-workflow`, `workflow-dispatch`, `invoke-action` (the last is
  removed in Task 3).

- [ ] **Step 1: Add a regression test that `manager` routing is rejected**

In `app/src/test/java/io/apitomy/axiom/app/SubscriptionsResourceTest.java` add:

```java
/**
 * The AI Manager was removed; a subscription using the {@code manager} routing type must be rejected.
 */
@Test
void managerRoutingTypeIsRejected() {
    given()
        .contentType("application/json")
        .body("""
            {"name": "mgr-sub", "connectionId": 1, "eventTypes": ["issue.opened"],
             "routing": {"type": "manager"}}
            """)
    .when()
        .post("/api/subscriptions")
    .then()
        .statusCode(400);
}
```

Check the request shape against an existing passing test in the same class and adapt field names (e.g.
connection id fixture) before running.

- [ ] **Step 2: Run it — expect FAIL** (currently accepted)

Run: `mvn -q -pl app -am test -Dtest=SubscriptionsResourceTest#managerRoutingTypeIsRejected -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Remove openapi entries, regenerate**

Edit `openapi.json` as listed, then run `mvn -q -pl common/api install -DskipTests`.

- [ ] **Step 4: Delete files and fix references**

Delete the listed files/module, then iterate `mvn -q -DskipTests compile test-compile` until clean. In
`EventStreamOrchestrator`, the routing switch must end with a default branch that throws
`IllegalStateException("Unsupported routing type: " + type)`; in `SubscriptionsResourceImpl` validation
(~line 343) reject unknown types with 400. Edit `V1__baseline.sql`.

Confirm: `rg -n -i 'managerservice|ManagerConfig|TYPE_MANAGER|io\.apitomy\.axiom\.manager' --glob '!.worktrees' --glob '!.claude' --glob '!agentdocs' --glob '!docs'`
Expected: no matches.

- [ ] **Step 5: UI cleanup**

Delete the pages, remove routes/nav, remove `'manager'` from the routing type union in `api.ts` and from
`SubscriptionDetailPage` options. Remove `"/manager"` from `SETTINGS_PATHS` in `AppSidebar.tsx`. Add to
`AppSidebar.test.tsx`:

```tsx
    it("has no Manager navigation item", async () => {
        renderWithRouter(<AppSidebar />, "/engine");
        await screen.findByText("Inbox");
        expect(screen.queryByText(/manager/i)).not.toBeInTheDocument();
    });
```

Run `cd ui && npm test && npm run build` — expected success.

- [ ] **Step 5b: SDK** — update descriptions in `axiom-sdk-functions.json`, `sdk-server.js` and the assistant
  templates that mention the Manager. No functions are removed in this task.

- [ ] **Step 6: Run tests**

Run the step-1 test (PASS), then `./build.sh` (BUILD SUCCESS).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: remove AI Manager"
```

---

### Task 3: Remove `invoke-action` routing

**Files:**
- Modify: `core/.../events/model/RoutingRule.java` (remove `TYPE_INVOKE_ACTION` and `actionTypeId` component)
- Modify: `app/.../EventStreamOrchestrator.java` (dispatch ~606; delete `routeToInvokeAction` and its input
  builder ~1109-1261)
- Modify: `app/.../TaskExecutionService.java:~323-337` (`managerInput`/invoke-action input handling)
- Modify: `app/.../rest/SubscriptionsResourceImpl.java:~343`
- Modify: `core/.../entities/RoutingOutcomeItemEntity.java:19` (comment)
- Tests: remove invoke-action cases in `EventStreamOrchestratorTest` (~266-517), `RoutingOutcomeItemsTest`
  (~152-316), `WorkflowDispatchFailureTest` (~103, 120 — switch to create-workflow if the test is about
  failures in general), `TraceLifecycleTest:~180`, `TraceCorrelationTest:~114`, `LogCorrelationTest`
  (~112, 173), `LineageTest:~322`
- openapi.json: remove `invoke-action` from `RoutingRule.type` enum (~10566, ~10680) and `actionTypeId`
  (~10691)
- UI: `config/api.ts:~2496`, `pages/SubscriptionDetailPage.tsx` (~842, 871, 932), `pages/EventDetailPage.tsx:~214`,
  `components/PlaceholderCompletionProvider.ts` (~138-139)

- [ ] **Step 1: Failing test** — copy `managerRoutingTypeIsRejected` in `SubscriptionsResourceTest` as
  `invokeActionRoutingTypeIsRejected` with `"routing": {"type": "invoke-action", "actionTypeId": 1}`.
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Edit openapi, regenerate (`mvn -q -pl common/api install -DskipTests`).**
- [ ] **Step 4: Remove code and fix references** until
  `rg -n 'invoke-action|INVOKE_ACTION|routeToInvokeAction' --glob '!.worktrees' --glob '!.claude' --glob '!*.md'`
  returns nothing.
- [ ] **Step 5: UI cleanup; `cd ui && npm run build`.**
- [ ] **Step 6: `./build.sh` — BUILD SUCCESS; new test PASS.**
- [ ] **Step 7: Commit** — `git commit -am "refactor: remove invoke-action routing"` (use `git add -A` first
  if files were deleted).

---

### Task 4: Remove reports

**Files:**
- Delete main: `app/.../ReportAiService.java`, `ReportScheduler.java`, `ReportExecutionService.java`,
  `ReportQueueConsumer.java`, `rest/ReportsResourceImpl.java`; `core/.../entities/ReportDefinitionEntity.java`,
  `ReportDefinitionVersionEntity.java`, `ReportEntity.java`; `core/.../services/ReportDefinitionValidator.java`;
  `prompts/weekly_status_report.md`
- Delete tests: `app/src/test/.../core/services/ReportDefinitionValidatorTest.java`,
  `app/src/test/.../app/RunReportTraceabilityTest.java`
- Delete UI: `ui/src/pages/ReportsPage.tsx`, `ReportDefinitionsPage.tsx`, `ReportDefinitionDetailPage.tsx`,
  `ReportDetailPage.tsx`; `ui/src/components/ReportAiModal.tsx`, `RenderedReport.tsx`, `RenderedReport.css`,
  `assistant/ReportDefinitionDetailModal.tsx`, `dashboards/widgets/RecentReportsWidget.tsx`
- Modify main (remove report branches): `rest/TraceMapper`, `rest/ActionResourceImpl`,
  `rest/AssistantResourceImpl`, `rest/ActivityResourceImpl`, `rest/TraceResourceImpl`, `rest/UsageResourceImpl`,
  `assistant/AssistantItemValidator`, `assistant/AssistantSessionManager`, `ActionTypeAiService`,
  `ImportExportService`, `TaskTraceFinalizer`, `ConfigSnapshotService`, `HistoryCleanup`, `TraceCleanup`,
  `LineageService`; core `TraceEntity`, `ActivityLogEntity`, `AiUsageEntity`, `SseEvent`, `TraceService`,
  `LogContext`, `core/src/main/resources/axiom-sdk-functions.json`
- Modify resources: `templates/axiom-mcp-server/sdk-server.js`, `templates/axiom-assistant-mcp/server.js`,
  `templates/assistant-templates/axiom-config-assistant.json`, `application.properties` (`axiom.reports.*`)
- Modify tests: `assistant/AssistantItemValidatorTest`, `SdkResourceTest`, `TraceLifecycleTest`,
  `TraceServiceTest`, `TraceCorrelationTest`, `ConfigSnapshotFailureTest`, `ConfigSnapshotTest`,
  `HistoryCleanupTest`, `TraceCleanupTest`, `LineageTest`, `core/src/test/.../logging/LogContextTest`
- Modify UI: `components/assistant/AssistantGeneratedItems`, `EnvironmentTab`, `ToolListEditor`,
  `ValidationProblemsPanel`, `ExecutionLogModal`, `AppSidebar`, `TraceNodeDetailModal`, `RunSourceLink`,
  `dashboards/widgets/index.ts`, `config/api.ts`, `App.tsx`, `pages/AgentDetailPage`,
  `ConfigurationPacksPage`, `AssistantSessionPage`, `ActivityLogPage`, `AiUsagePage`
- openapi.json: delete all `/reports...` paths and schemas `Report`, `ReportDefinition`,
  `NewReportDefinition`, `ReportSearchResults`. **Rename** (do not delete) `ReportAiEditRequest` →
  `AiEditPromptRequest` and `ReportAiEditResponse` → `AiEditPromptResponse`; they are still used by
  `/action-types/ai-edit-prompt`. Remove `"report"` from source/kind enums.
- `V1__baseline.sql`: drop report tables, label/collection tables and sequences; drop report columns from
  trace/activity/usage tables if those entities lose fields.

**Interfaces:**
- Produces: `AiEditPromptRequest` / `AiEditPromptResponse` generated beans (used by `ActionResourceImpl`).

- [ ] **Step 1: Failing test** — in `app/src/test/.../app/rest/` (or an existing REST test class such as
  `SystemResourceTest`) add:

```java
/**
 * Reports were removed; the endpoint must no longer exist.
 */
@Test
void reportsEndpointIsGone() {
    given().when().get("/api/reports").then().statusCode(404);
}
```

  Use the same base path prefix as sibling tests in that class.
- [ ] **Step 2: Run — expect FAIL (200).**
- [ ] **Step 3: Edit openapi, regenerate.**
- [ ] **Step 4: Delete files, fix references** until
  `rg -n -i 'reportdefinition|ReportEntity|ReportScheduler|reportId|axiom\.reports' --glob '!.worktrees' --glob '!.claude' --glob '!*.md'`
  returns nothing. Keep any `TraceEntity`/`ActivityLogEntity` "source type" enum consistent (remove `report`).
- [ ] **Step 4b: SDK** — remove `axiom_add_report_label`, `axiom_remove_report_label`,
  `axiom_list_report_definitions`, `axiom_list_reports` from every SDK file listed in Global Constraints
  (definitions JSON, `SdkCallService` handlers, `sdk-server.js`, assistant templates, `sdkTools.ts`,
  `toolNames.ts`, `ActionTypeValidator` allow-lists). In `SdkResourceTest` add
  `removedReportFunctionsAreUnknown`: calling `axiom_list_reports` through the SDK endpoint returns the
  same error status the endpoint uses for unknown functions (check `SdkResource` for the exact code). Update
  `McpConfigGeneratorTest`/`ActionTypeValidatorTest` expectations.
- [ ] **Step 5: UI cleanup; `cd ui && npm test && npm run build`.** Remove `"/report-definitions"` from
  `COMPONENT_PATHS` and add an `AppSidebar.test.tsx` case asserting no "Reports" / "Report Definitions" item
  (same pattern as Task 2).
- [ ] **Step 6: `./build.sh` — BUILD SUCCESS.**
- [ ] **Step 7: Commit** — `git add -A && git commit -m "refactor: remove reports"`.

---

### Task 5: Remove scheduled jobs

**Files:**
- Delete main: `app/.../ScheduledJobScheduler.java`, `ScheduledJobExecutionService.java`,
  `ScheduledJobQueueConsumer.java`, `rest/ScheduledJobsResourceImpl.java`; `core/.../entities/ScheduledJobEntity.java`,
  `ScheduledJobRunEntity.java`, `ScheduledJobVersionEntity.java`; `core/.../services/ScheduledJobValidator.java`
- Delete tests: `app/src/test/.../core/services/ScheduledJobValidatorTest.java`,
  `app/src/test/.../app/ScheduledJobScriptCleanupTest.java`
- Delete UI: `ui/src/pages/ScheduledJobsPage.tsx`, `ScheduledJobDetailPage.tsx`, `ScheduledJobRunsPage.tsx`
- Modify: the same cross-cutting main files as Task 4 (whatever still references scheduled jobs) plus
  `rest/SystemResourceImpl`, `assistant/AssistantContextBuilder`, `SeedDataInitializer`,
  `core/.../entities/RetentionConfigEntity` (scheduled-job retention fields)
- Modify tests: remaining references plus `CallerTraceTest`, `TraceResourceTest`, `SystemResourceTest`
- Modify UI: remaining references plus `pages/DataRetentionPage.tsx`
- openapi.json: delete `/scheduled-jobs...` paths; schemas `ScheduledJob`, `NewScheduledJob`, `ScheduledJobRun`,
  `ScheduledJobRunSearchResults`; scheduled-job fields in `RetentionConfig`; `"scheduled-job"` enum values
- `V1__baseline.sql`: drop scheduled job tables and sequences; drop retention columns removed from the entity

- [ ] **Step 1: Failing test** — `scheduledJobsEndpointIsGone()` asserting `GET /api/scheduled-jobs` → 404
  (same style as Task 4).
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Edit openapi, regenerate.**
- [ ] **Step 4: Delete files, fix references** until
  `git grep -n -i -E 'scheduledjob|scheduled-job|scheduled_job' -- . ':!*.md'`
  returns nothing.
- [ ] **Step 5: UI cleanup; `cd ui && npm test && npm run build`.** Remove `"/scheduled-jobs"` from
  `COMPONENT_PATHS` and add an `AppSidebar.test.tsx` case asserting no "Scheduled Jobs" item.
- [ ] **Step 6: `./build.sh` — BUILD SUCCESS.**
- [ ] **Step 7: Commit** — `git add -A && git commit -m "refactor: remove scheduled jobs"`.

---

### Task 6: Remove action type flags and non-workflow action invocation

Action types may only run from workflow `action` nodes. This removes the four flags and every path that creates
a task outside a workflow.

**Files:**
- Modify: `core/.../entities/ActionTypeEntity.java` — delete `userTriggerable` (32-33), `workflowEnabled`
  (35-36), `managerTriggerable` (87-88), `emitsEvent` (98-99)
- Modify: `app/.../rest/ActionResourceImpl.java` (90, 240-242, 256, 278-280, 294), `ImportExportService.java`
  (442-445, 585-588, 764-767), `SeedDataInitializer.java` (190-209)
- Modify: `app/.../rest/ProjectsResourceImpl.java` — delete `createTask` (~line 282)
- Modify: `app/.../SdkCallService.java` — delete `axiom_create_task` (~62, ~167) and remove it from
  `core/src/main/resources/axiom-sdk-functions.json` and `templates/axiom-mcp-server/sdk-server.js`
- Modify: `TaskExecutionService` — any public "create task for action type" entry that is no longer called
  except by `WorkflowExecutionService` should be narrowed (package-private or documented as workflow-only).
  Find callers with `rg -n 'createTask\(|enqueueTask\(' app/src/main/java`.
- Templates: `axiom-assistant-mcp/server.js:79-80`, `axiom-config-assistant.json:5`
- Tests: `ActionResourceTest` (64-186), `EventStreamOrchestratorTest` (remaining flag uses),
  `RoutingOutcomeItemsTest` (359-362), `assistant/AssistantItemValidatorTest` (170-171), `ProjectsResourceTest`
  (any `POST .../tasks` cases), `SdkResourceTest` (`axiom_create_task` cases)
- openapi.json: remove the four properties from `ActionType`/`NewActionType` (~6670-6854); delete
  `POST /projects/{projectId}/tasks` (keep `GET` list and the per-task GET/cancel/log paths)
- UI: `config/api.ts` (406-419 and `createTask`), `pages/ActionTypeDetailPage.tsx`,
  `pages/ActionTypesPage.tsx` (146-148, 279-294), `pages/ProjectDetailPage.tsx` (173-177, 192 — remove the
  "run action" UI), `components/assistant/ActionTypeDetailModal.tsx`,
  `components/dashboards/widgets/ActionTypesWidget.tsx` (50-52)
- `V1__baseline.sql`: drop the four columns from `action_type`

- [ ] **Step 1: Failing tests** — in `ProjectsResourceTest`:

```java
/**
 * Action types can only be invoked from workflows; creating a task directly on a project is not allowed.
 */
@Test
void creatingTaskDirectlyOnProjectIsNotAllowed() {
    long projectId = createProject("direct-task");   // use the class's existing fixture helper
    given()
        .contentType("application/json")
        .body("{\"actionType\": \"anything\"}")
    .when()
        .post("/api/projects/" + projectId + "/tasks")
    .then()
        .statusCode(405);
}
```

  If the class has no fixture helper, create the project inline with `QuarkusTransaction.requiringNew()` as
  in `WorkflowExecutionServiceTest`. (JAX-RS returns 405 when the path exists for GET but not POST.)
- [ ] **Step 2: Run — expect FAIL.**
- [ ] **Step 3: Edit openapi, regenerate.**
- [ ] **Step 4: Remove fields/paths and fix references** until
  `rg -n -i 'userTriggerable|workflowEnabled|managerTriggerable|emitsEvent|user_triggerable|workflow_enabled|manager_triggerable|emits_event|axiom_create_task' --glob '!.worktrees' --glob '!.claude' --glob '!*.md'`
  returns nothing.
- [ ] **Step 4b: SDK** — `axiom_create_task` removal (above) must cover every SDK file in Global
  Constraints, including `sdkTools.ts`, `toolNames.ts` and `ActionTypeValidator`. In `SdkResourceTest` add
  `createTaskFunctionIsUnknown` (unknown-function error, as in Task 4). Update descriptions of
  `axiom_list_action_types` to say action types run only from workflow action nodes.
- [ ] **Step 5: UI cleanup; `cd ui && npm test && npm run build`.**
- [ ] **Step 6: `./build.sh` — BUILD SUCCESS.**
- [ ] **Step 7: Commit** — `git add -A && git commit -m "refactor: action types are invoked only from workflows"`.

---

### Task 7: Drop project lifecycle; one active run per (project, definition)

Done before the rename so behavior and naming changes are reviewed separately.

**Files:**
- Delete: `core/.../lifecycle/ProjectLifecycle.java`, `ProjectStatus.java`,
  `InvalidStatusTransitionException.java`; `app/.../ClosedProjectCleanup.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/InactiveProjectCleanup.java`
- Modify: `core/.../entities/ProjectEntity.java` (remove `status`), `ProjectsResourceImpl.java` (29-30, 162,
  197-200: remove close/reopen; add derived `active`), `EventStreamOrchestrator.java` (19, 1282),
  `ScriptExecutionService.java` (474, 487, 489), `SdkCallService.java` (9, 84, 94, 256),
  `TaskExecutionService.java` (96, 541, 575, 584, 586), `TaskQueuePoller.java` (14, 42 — keep per-project task
  serialization; only remove lifecycle references)
- Modify: `WorkflowExecutionService.java` — `startRun` (160-167) and `cancelWorkflow(projectId)` (~530)
- Modify: `EventStreamOrchestrator.routeToCreateWorkflow` (1056-1107) — skip and record instead of failing
- Modify: `RetentionConfigEntity` (`closedProjectRetentionDays` → `inactiveProjectRetentionDays`),
  `SystemResourceImpl` (229-296), `SeedDataInitializer:162`
- openapi.json: delete `/projects/{projectId}/close`, `/projects/{projectId}/reopen`; remove `status` from
  `Project`/`NewProject`/`UpdateProject`; add read-only `active: boolean` to `Project`; rename
  `RetentionConfig.closedProjectRetentionDays` → `inactiveProjectRetentionDays`; change
  `DELETE /projects/{projectId}/workflow` (cancel) to take a `definitionId` query param if it exists
- UI: delete `components/dashboards/widgets/ProjectStatusSummaryWidget.tsx` (+ `widgets/index.ts:1`); remove
  close/reopen buttons and status badges in `ProjectsPage.tsx`/`ProjectDetailPage.tsx`, show an
  "Active"/"Inactive" label from `active`; `DataRetentionPage.tsx` (155-161), `api.ts:886`
- Tests: fix `SystemResourceTest` (149-241), `TraceCleanupTest` (72, 129), `RoutingOutcomeItemsTest:219`;
  remove `project.status = ...` from fixtures (`rg -n 'project\.status' app/src/test`)
- Test (create): `app/src/test/java/io/apitomy/axiom/app/WorkflowRunConcurrencyTest.java`
- Test (create): `app/src/test/java/io/apitomy/axiom/app/InactiveProjectCleanupTest.java`
- `V1__baseline.sql`: drop `project.status`; rename retention column

**Interfaces:**
- Produces: `WorkflowExecutionService.triggerWorkflow(long projectId, long definitionId, ...)` throws
  `WorkflowRunConflictException` (new, `app/.../WorkflowRunConflictException.java`, extends
  `WebApplicationException` with status 409) when an active run of the same definition exists on the project.
- Produces: `ProjectEntity.isActive(long projectId)` static helper returning
  `WorkflowRunEntity.count("projectId = ?1 and status in ?2", projectId, List.of("running", "waiting")) > 0`.
- Produces: routing outcome item status `"skipped"` with summary
  `"Skipped: subject already has an active run of definition <id>"`.

- [ ] **Step 1: Write failing concurrency tests**

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the "one active run per (project, workflow definition)" rule.
 */
@QuarkusTest
class WorkflowRunConcurrencyTest {

    private static final String WAIT_CONTENT = """
        {
            "id": "wait-wf", "name": "Wait WF",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start", "config": {}, "position": {"x": 0, "y": 0}},
                {"id": "w1", "type": "wait", "name": "Hold", "config": {"duration": "PT1H"},
                 "position": {"x": 0, "y": 100}},
                {"id": "e1", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 200}}
            ],
            "edges": [
                {"id": "x1", "source": "s1", "target": "w1", "priority": 0, "isDefault": true},
                {"id": "x2", "source": "w1", "target": "e1", "priority": 0, "isDefault": true}
            ]
        }
        """;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    /**
     * Two different definitions may run concurrently on the same project.
     */
    @Test
    void differentDefinitionsRunConcurrentlyOnSameProject() {
        long projectId = createProject("concurrent-different");
        long defA = createDefinition("Def A");
        long defB = createDefinition("Def B");

        WorkflowRunEntity runA = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(projectId, defA));
        WorkflowRunEntity runB = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(projectId, defB));

        assertEquals("waiting", runA.status);
        assertEquals("waiting", runB.status);
        assertNotEquals(runA.id, runB.id);
    }

    /**
     * A second active run of the same definition on the same project is rejected.
     */
    @Test
    void sameDefinitionTwiceOnSameProjectConflicts() {
        long projectId = createProject("concurrent-same");
        long def = createDefinition("Def Same");

        QuarkusTransaction.requiringNew().call(() -> workflowExecutionService.triggerWorkflow(projectId, def));

        WorkflowRunConflictException ex = assertThrows(WorkflowRunConflictException.class, () ->
                QuarkusTransaction.requiringNew().call(() ->
                        workflowExecutionService.triggerWorkflow(projectId, def)));
        assertEquals(409, ex.getResponse().getStatus());
    }

    /**
     * Once the earlier run is no longer active, the same definition may run again (sequential runs).
     */
    @Test
    void sameDefinitionMayRunAgainAfterPreviousRunEnds() {
        long projectId = createProject("sequential-same");
        long def = createDefinition("Def Sequential");

        WorkflowRunEntity first = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(projectId, def));
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunEntity run = WorkflowRunEntity.findById(first.id);
            run.status = "completed";
            run.completedOn = Instant.now();
        });

        WorkflowRunEntity second = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(projectId, def));
        assertEquals("waiting", second.status);
        assertTrue(second.id > first.id);
    }

    private long createProject(String ref) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ProjectEntity project = new ProjectEntity();
            project.name = ref;
            project.type = "other";
            project.ref = "test/" + ref;
            project.createdOn = Instant.now();
            project.updatedOn = Instant.now();
            project.persist();
            return project.id;
        });
    }

    private long createDefinition(String name) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = name;
            def.content = WAIT_CONTENT;
            def.currentVersion = 1;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();
            WorkflowDefinitionVersionEntity version = new WorkflowDefinitionVersionEntity();
            version.definitionId = def.id;
            version.version = 1;
            version.content = WAIT_CONTENT;
            version.createdOn = Instant.now();
            version.persist();
            return def.id;
        });
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (compile error: `WorkflowRunConflictException` missing; then
  `differentDefinitionsRunConcurrentlyOnSameProject` fails with 409).

Run: `mvn -q -pl app -am test -Dtest=WorkflowRunConcurrencyTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement the rule**

Create `WorkflowRunConflictException`:

```java
package io.apitomy.axiom.app;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Thrown when a workflow run is requested for a (subject, definition) pair that already has an active run.
 */
public class WorkflowRunConflictException extends WebApplicationException {

    /**
     * Creates the exception.
     *
     * @param projectId    the project (subject) id
     * @param definitionId the workflow definition id
     */
    public WorkflowRunConflictException(long projectId, long definitionId) {
        super(Response.status(409)
                .entity(Map.of("message", "Project " + projectId
                        + " already has an active run of workflow definition " + definitionId))
                .build());
    }
}
```

Replace `WorkflowExecutionService.java:160-167` with:

```java
        WorkflowRunEntity activeRun = WorkflowRunEntity
                .find("projectId = ?1 and definitionId = ?2 and status in ?3",
                        projectId, definitionId, List.of("running", "waiting"))
                .firstResult();
        if (activeRun != null) {
            throw new WorkflowRunConflictException(projectId, definitionId);
        }
```

Change `cancelWorkflow(projectId)` (~530) to `cancelWorkflowRun(long runId)` — cancelling by project is
ambiguous now. Update its REST caller (`ProjectsResourceImpl`, path `/projects/{projectId}/workflow`) to use the
existing `/workflow/runs/{runId}` cancel if one exists, otherwise add `POST /workflow/runs/{runId}/cancel` in
openapi and implement it in the workflow runs resource; delete the project-level cancel path.

Also update the Javadoc on `WorkflowRunEntity` ("at most one non-terminal run exists per project" →
"per (project, definition)").

- [ ] **Step 4: Run step-1 tests — expect PASS.**

- [ ] **Step 5: create-workflow skip — failing test**

In `EventWorkflowLinksTest` (or `RoutingOutcomeItemsTest`, whichever already drives create-workflow routing
end to end — reuse its fixture helpers), add `createWorkflowSkipsWhenSameDefinitionActiveOnSubject`: route two
events with the same `ref` through a create-workflow subscription whose definition is `WAIT_CONTENT`; assert
exactly one `WorkflowRunEntity` exists for the project, and the second event's `RoutingOutcomeItemEntity` has
`status == "skipped"`. Run — expect FAIL (second outcome is `failed`).

- [ ] **Step 6: Implement skip** in `routeToCreateWorkflow`:

```java
        WorkflowRunEntity run;
        try {
            run = QuarkusTransaction.requiringNew().call(() ->
                    workflowExecutionService.triggerWorkflow(projectId, rule.workflowDefinitionId(),
                            extraContext, new EventOrigin(event.id, ledgerId)));
        } catch (WorkflowRunConflictException e) {
            String summary = "Skipped: subject already has an active run of definition "
                    + rule.workflowDefinitionId();
            LOG.infof("%s (event %s, project %d)", summary, event.id, projectId);
            RoutingOutcomeEntity skipped = new RoutingOutcomeEntity();
            skipped.status = "completed";
            skipped.summary = summary;
            skipped.projectId = projectId;
            RoutingOutcomeItemEntity item = newItem(RoutingOutcomeItemEntity.TYPE_WORKFLOW_RUN,
                    "skipped", summary);
            item.projectId = projectId;
            skipped.pendingItems.add(item);
            return skipped;
        }
```

Add `"skipped"` to the outcome item status enum in openapi (and the UI status label map in `api.ts` /
`EventDetailPage.tsx`). Run — expect PASS.

- [ ] **Step 6b: Broadcast dispatch to concurrent runs — test**

Now that two runs can wait on one project, add `dispatchBroadcastsToAllWaitingRunsOnProject` to
`WorkflowEventDispatcherTest` (reuse its fixtures): two definitions, each `start → receive-event(eventType
"issue.commented") → end`, both triggered on the same project; dispatch one matching event correlated to that
project; assert **both** runs advance (status `completed`). This is current behavior, so it should pass
immediately — if it fails, fix the dispatcher so it iterates all matching `WorkflowEventSubscriptionEntity`
rows for the project rather than the first one.

Note: "run creation requires a subject" is enforced structurally (`project_id NOT NULL`, every
`triggerWorkflow` overload takes a `long` subject id, trigger endpoint is subject-scoped); no extra test.

- [ ] **Step 7: Lifecycle removal**

Delete the lifecycle classes, `ProjectEntity.status`, close/reopen endpoints. Add to `ProjectEntity`:

```java
    /**
     * Whether the project has any active (running or waiting) workflow run.
     *
     * @param projectId the project id
     * @return true if at least one run is running or waiting
     */
    public static boolean isActive(long projectId) {
        return WorkflowRunEntity.count("projectId = ?1 and status in ?2",
                projectId, java.util.List.of("running", "waiting")) > 0;
    }
```

Map it to the `Project.active` bean field in `ProjectsResourceImpl` (for list endpoints, compute active
project ids with one query — `select distinct projectId from WorkflowRunEntity where status in (...)` — to
avoid N+1). Fix all compile errors; where code previously moved status (e.g. to InProgress on task start),
just delete that code.

- [ ] **Step 8: Inactive cleanup — failing test then implementation**

`InactiveProjectCleanupTest`: create project A with a run completed 40 days ago, project B with a waiting run,
project C with a run completed 1 day ago; set retention to 30 days; call `cleanup.cleanup()` directly;
assert A's workspace was deleted (use `WorkspaceService.getWorkspacePath` + `Files.exists`, creating the dirs
first with `ensureWorkspace`) and B's and C's remain. Model it on the deleted `ClosedProjectCleanup` logic
(read it from git: `git show HEAD~:app/src/main/java/io/apitomy/axiom/app/ClosedProjectCleanup.java` before
deleting) but select projects where no run is active and `max(completedOn) < now - retentionDays`. Keep the
same `@Scheduled` cadence and config lookup (`RetentionConfigEntity.inactiveProjectRetentionDays`). Whether
cleanup deletes the workspace only or the whole project should match what `ClosedProjectCleanup` did.

- [ ] **Step 9: UI** — as listed in Files; `cd ui && npm test && npm run build`.
- [ ] **Step 9b: SDK** — remove `axiom_close_project` and `axiom_reopen_project` from every SDK file
  (Global Constraints list), including `SdkCallService` (lines ~84, 94, 256 lifecycle use). Remove any
  `status` param from `axiom_update_project`/`axiom_create_project` and any `status` field from
  `axiom_get_project`/`axiom_list_projects` results; add `active` (boolean) to those results. Add
  `SdkResourceTest.closeProjectFunctionIsUnknown`.
- [ ] **Step 10: `./build.sh` — BUILD SUCCESS.**
- [ ] **Step 11: Commit** — `git add -A && git commit -m "refactor: drop project lifecycle; one active run per project and definition"`.

---

### Task 8: Rename Project → Subject (backend + API)

Pure rename; no behavior change.

**Files:** every backend/test/resource file referencing project (~62 main, ~44 test, ~22 resources);
`openapi.json`; `V1__baseline.sql`; `WorkspaceService.java`; `ScriptExecutionService.java:272`.

**Rename map:**

| From | To |
|------|----|
| `ProjectEntity` (table `project`) | `SubjectEntity` (table `subject`) |
| `projectId` / `project_id` (all entities, beans, SSE, LogContext, MDC keys) | `subjectId` / `subject_id` |
| `ProjectsResourceImpl`, `ProjectDeletionService`, `InactiveProjectCleanup` | `SubjectsResourceImpl`, `SubjectDeletionService`, `InactiveSubjectCleanup` |
| `ProjectsResourceTest`, `ProjectDeletionServiceTest`, `InactiveProjectCleanupTest` | `Subjects…`, `SubjectDeletion…`, `InactiveSubject…` |
| `SseEvent.projectUpdated` | `SseEvent.subjectUpdated` |
| `findOrCreateProjectForEvent` | `findOrCreateSubjectForEvent` |
| openapi paths `/projects…` | `/subjects…` (path param `{subjectId}`) |
| openapi schemas `Project`, `NewProject`, `UpdateProject`, `ProjectSearchResults`, `ProjectMetrics`, `DiskUsageProject` | `Subject`, `NewSubject`, `UpdateSubject`, `SubjectSearchResults`, `SubjectMetrics`, `DiskUsageSubject` |
| `projectId` properties in all schemas (Task, ActivityLogEntry, AiUsage, ThreadEntry, AssistantSessionInfo, CreateAssistantSessionRequest, Trace, InboxItem, NewInboxItem, WorkflowInstance, WorkflowRunSummary, EventProcessingOutcome, EventProcessingOutcomeItem) | `subjectId` |
| openapi tag `Projects` (generated interface name) | `Subjects` |
| `RetentionConfig.inactiveProjectRetentionDays` | `inactiveSubjectRetentionDays` |
| Workspace dir `project-<id>` | `subject-<id>` |
| Workflow context key `projectId` | `subjectId` |
| Thread entries' "project thread" wording | "subject thread" |
| Templates/SDK function args `projectId` (`axiom-sdk-functions.json`, `sdk-server.js`, `axiom-assistant-mcp/server.js`, assistant templates) | `subjectId` |
| SDK functions `axiom_list_projects`, `axiom_get_project`, `axiom_create_project`, `axiom_update_project`, `axiom_update_project_body`, `axiom_add_project_label`, `axiom_remove_project_label`, `axiom_get_project_thread` | `axiom_list_subjects`, `axiom_get_subject`, `axiom_create_subject`, `axiom_update_subject`, `axiom_update_subject_body`, `axiom_add_subject_label`, `axiom_remove_subject_label`, `axiom_get_subject_thread` (all SDK files incl. `sdkTools.ts`, `toolNames.ts`, `ActionTypeValidator`, seed data in `SeedDataInitializer` that lists allowed tools) |

- [ ] **Step 1: Failing test** — rename `ProjectsResourceTest` → `SubjectsResourceTest`, change its paths to
  `/api/subjects` and bean types to `Subject*`. It fails to compile / 404s. Also update `SdkResourceTest` to
  call `axiom_list_subjects` / `axiom_get_subject` (fails: unknown function).
- [ ] **Step 2: openapi rename; regenerate.**
- [ ] **Step 3: Rename classes with `git mv`, then fields.** Use IDE/Serena symbol renames where possible;
  otherwise `rg -l` + careful edits. Fix the hardcoded workspace path in `ScriptExecutionService.java:272` to
  call `WorkspaceService.getWorkspacePath(subject)` instead of duplicating it.
- [ ] **Step 4: Rename in `V1__baseline.sql`** (table, columns, sequence `project_SEQ` → `subject_SEQ`, index
  and FK names). Run `MigrationSchemaValidationTest` — PASS.
- [ ] **Step 5: Verify no leftovers**
  `rg -n -i 'project' --glob '!.worktrees' --glob '!.claude' --glob '!ui' --glob '!docs' --glob '!agentdocs' --glob '!pom.xml' --glob '!*.md'`
  Expected: only incidental, non-domain matches (e.g. Maven `${project.version}`, GitHub/Jira API field names
  in event payload mappers like Jira `project` key — leave those).
- [ ] **Step 6: `./build.sh` — BUILD SUCCESS** (UI may not build yet; that's Task 9 — do not run the `ui`
  profile).
- [ ] **Step 7: Commit** — `git add -A && git commit -m "refactor: rename Project to Subject (backend and API)"`.

---

### Task 9: Rename Project → Subject (UI)

**Files:** ~42 UI files. Key ones: `ui/src/pages/ProjectsPage.tsx` → `SubjectsPage.tsx`,
`ProjectDetailPage.tsx` → `SubjectDetailPage.tsx`; widgets `ActiveProjectsWidget` → `ActiveSubjectsWidget`,
`AiCostByProjectWidget` → `AiCostBySubjectWidget`, `ProjectSpotlightWidget` → `SubjectSpotlightWidget`;
`ui/src/config/api.ts` (types, `fetch` URLs `/api/projects` → `/api/subjects`, `projectId` → `subjectId`);
`ui/src/App.tsx` routes `/projects` → `/subjects`; `AppSidebar.tsx` label "Projects" → "Subjects"; all
user-visible strings ("project" → "subject"). Dashboard saved layouts that store widget ids: rename widget ids
too (data is reset, no compatibility needed).

- [ ] **Step 1: `git mv` files, rename symbols, URLs and strings.**
- [ ] **Step 2: Verify**
  `rg -n -i 'project' ui/src` — expected: no domain matches.
- [ ] **Step 3: UI tests** — in `AppSidebar.test.tsx` add `it("shows Subjects and not Projects", ...)`
  asserting `screen.getByText("Subjects")` exists and `queryByText(/projects/i)` is null. Create
  `ui/src/pages/SubjectsPage.test.tsx`: mock `../config/api` so the subjects list call (whatever `SubjectsPage`
  uses, e.g. `fetchSubjects`) resolves to one subject `{ id: 1, name: "Repo issue 42", active: true, ... }`
  (fill required fields from the `Subject` type); render with `renderWithRouter(<SubjectsPage />, "/subjects")`;
  assert `await screen.findByText("Repo issue 42")` and an "Active" label.
- [ ] **Step 4: `cd ui && npm test && npm run build`** — success. Then `./build.sh -Pui` — BUILD SUCCESS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "refactor(ui): rename Project to Subject"`.

---

### Task 10: Workflow run `title`, `labels`, `inputs` + run filters

`subjectId` (required) and `definitionVersion` already exist after Task 8.

**Files:**
- Modify: `core/.../entities/WorkflowRunEntity.java`, `V1__baseline.sql`
- Modify: `app/.../WorkflowExecutionService.java` (`startRun`, `triggerWorkflow` overloads)
- Modify: `app/.../EventStreamOrchestrator.java` (`routeToCreateWorkflow` passes inputs)
- Modify: workflow runs REST impl (`rg -l 'WorkflowRunSearchResults' app/src/main/java`) and its bean mapper
  (`WorkflowRunBeanMapper` — see `WorkflowRunBeanMapperTest`)
- Modify: openapi `WorkflowInstance`, `WorkflowRunSummary`, `TriggerWorkflow`, `GET /workflow/runs` params
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowRunMetadataTest.java` (create),
  `WorkflowRunsResourceTest.java` (extend)

**Interfaces:**
- Produces: `WorkflowRunEntity.title` (String, length 500, NOT NULL), `labels` (TEXT JSON object
  `Map<String,String>`, nullable), `inputs` (TEXT JSON object, nullable).
- Produces: `WorkflowExecutionService.triggerWorkflow(long subjectId, long definitionId, RunRequest request,
  EventOrigin origin)` where

```java
/**
 * Optional parameters for starting a workflow run.
 *
 * @param title        run title; null to use "<definition name> — <subject name>"
 * @param labels       run labels; null for none
 * @param inputs       workflow inputs (validated against the Start node input contract); null for none
 * @param extraContext additional context entries (e.g. the triggering event); null for none
 */
public record RunRequest(String title, Map<String, String> labels, Map<String, Object> inputs,
                         Map<String, Object> extraContext) {

    /** An empty request. */
    public static final RunRequest EMPTY = new RunRequest(null, null, null, null);
}
```

  (create in `app/src/main/java/io/apitomy/axiom/app/RunRequest.java`). Existing overloads delegate to it.
  Inputs must flow through the existing input contract (`docs/developer-guide/workflow-input-contract.md`,
  `WorkflowTriggerInputGuardTest`) — find where inputs are put into context today and route `inputs` there.

- [ ] **Step 1: Failing test**

```java
/**
 * Verifies run metadata (title, labels, inputs) is stored on the run.
 */
@QuarkusTest
class WorkflowRunMetadataTest {

    @Inject
    WorkflowExecutionService workflowExecutionService;

    /**
     * When no title is supplied the default "<definition> — <subject>" title is used.
     */
    @Test
    void defaultTitleCombinesDefinitionAndSubject() {
        long subjectId = TestFixtures.createSubject("meta-default");
        long defId = TestFixtures.createWaitDefinition("Meta Def");

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defId, RunRequest.EMPTY, null));

        assertEquals("Meta Def — meta-default", run.title);
    }

    /**
     * Supplied title, labels and inputs are persisted.
     */
    @Test
    void suppliedMetadataIsPersisted() {
        long subjectId = TestFixtures.createSubject("meta-supplied");
        long defId = TestFixtures.createWaitDefinition("Meta Def 2");
        RunRequest request = new RunRequest("Custom", Map.of("team", "core"), Map.of(), null);

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defId, request, null));

        WorkflowRunEntity loaded = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunEntity.findById(run.id));
        assertEquals("Custom", loaded.title);
        assertTrue(loaded.labels.contains("\"team\":\"core\""));
    }
}
```

  Create `app/src/test/java/io/apitomy/axiom/app/TestFixtures.java` with `createSubject(String name)` and
  `createWaitDefinition(String name)` by moving the `createProject`/`createDefinition` helpers (renamed) out of
  `WorkflowRunConcurrencyTest`, and make that test use `TestFixtures` too.
- [ ] **Step 2: Run — expect FAIL (compile).**
- [ ] **Step 3: Implement** entity fields, `V1__baseline.sql` columns, `RunRequest`, default title
  (`definition.name + " — " + subject.name`), JSON serialization with the injected `ObjectMapper`.
- [ ] **Step 4: Run — expect PASS.**
- [ ] **Step 5: API** — add `title`, `labels` (object of strings), `inputs` (object) to `WorkflowInstance`
  and `WorkflowRunSummary`; add optional `title`, `labels`, `inputs` to `TriggerWorkflow`; add query params
  `status`, `definitionId`, `subjectId`, `label` (format `key:value`, repeatable) to `GET /workflow/runs`.
  Regenerate, implement in the REST impl and mapper. Extend `WorkflowRunsResourceTest` with
  `filtersRunsBySubjectAndLabel` (create two runs on different subjects/labels, assert filtering returns
  exactly one). Label filtering: since labels are a JSON text column, filter in the query with
  `labels like '%"key":"value"%'` (document this simplification in Javadoc).
- [ ] **Step 5b: SDK — add run functions**

Add two functions to every SDK file listed in Global Constraints:

`axiom-sdk-functions.json` entries (match the existing entry format in that file exactly):
- `axiom_get_workflow_run` — "Get a workflow run (title, status, subject, definition, labels, inputs,
  current node). Defaults to the run of the calling task." Params: `runId` (integer, optional).
- `axiom_list_subject_runs` — "List workflow runs for a subject, newest first." Params: `subjectId`
  (integer, required), `status` (string, optional).

`SdkCallService` handlers: resolve `runId` default from the calling task's `workflowRunId` (follow how other
handlers access the calling task context); return JSON with `id`, `title`, `status`, `definitionId`,
`definitionVersion`, `subjectId`, `labels` (object), `inputs` (object), `currentNodeId`, `startedOn`,
`completedOn`. Unknown run → the same "not found" error shape other handlers use.

Tests in `SdkResourceTest`:
- `getWorkflowRunReturnsRunMetadata` — create a run with title/labels via `RunRequest`; call the function
  with `runId`; assert title and labels.
- `listSubjectRunsReturnsRunsNewestFirst` — two runs (different definitions, from `TestFixtures`) on one
  subject; assert two entries, newer first.

Update `sdk-server.js`, `sdkTools.ts`, `toolNames.ts`, `ActionTypeValidator` allow-list and
`McpConfigGeneratorTest`/`ActionTypeValidatorTest` expectations.

- [ ] **Step 6: `./build.sh` — BUILD SUCCESS.**
- [ ] **Step 7: Commit** — `git add -A && git commit -m "feat(workflow): run title, labels and inputs; run filters"`.

---

### Task 11: Schedule triggers (backend)

**Files:**
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/WorkflowScheduleTriggerEntity.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/WorkflowScheduleTriggerScheduler.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/CronSchedules.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/rest/WorkflowScheduleTriggersResourceImpl.java`
- Modify: `V1__baseline.sql`, `openapi.json`, `app/pom.xml` (add explicit `com.cronutils:cron-utils`
  dependency without version if managed by the Quarkus BOM; otherwise pin `9.2.1`)
- Modify: `SubjectEntity` — add static `findOrCreateByName(String name)` (refSource `"schedule"`, type
  `"schedule"`, ref `"schedule:" + name`)
- Modify: workflow definition deletion path — delete its triggers too
- Test: `app/src/test/java/io/apitomy/axiom/app/CronSchedulesTest.java`,
  `WorkflowScheduleTriggerSchedulerTest.java`, `rest/WorkflowScheduleTriggersResourceTest.java` (or in the
  existing `WorkflowDefinitionsResourceTest`)

**Interfaces:**
- Entity (table `workflow_schedule_trigger`):

```java
/**
 * A cron schedule that starts runs of a workflow definition against a fixed subject.
 */
@Entity
@Table(name = "workflow_schedule_trigger")
public class WorkflowScheduleTriggerEntity extends PanacheEntity {

    @Column(name = "definition_id", nullable = false)
    public Long definitionId;

    /** Unix (5-field) cron expression, evaluated in UTC. */
    @Column(nullable = false, length = 100)
    public String cron;

    /** Name of the fixed subject every run attaches to; created on first firing. */
    @Column(name = "subject_name", nullable = false, length = 255)
    public String subjectName;

    /** Static workflow inputs as a JSON object; null for none. */
    @Column(columnDefinition = "TEXT")
    public String inputs;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "next_run_at")
    public Instant nextRunAt;

    @Column(name = "last_run_at")
    public Instant lastRunAt;

    /** Outcome of the last firing: "started", "skipped" or "failed: <reason>". */
    @Column(name = "last_outcome", length = 1000)
    public String lastOutcome;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "updated_on", nullable = false)
    public Instant updatedOn;
}
```

- `CronSchedules`:

```java
package io.apitomy.axiom.app;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Parses and evaluates Unix (5-field) cron expressions in UTC.
 */
public final class CronSchedules {

    private static final CronParser PARSER =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    private CronSchedules() {
    }

    /**
     * Validates a cron expression.
     *
     * @param cron the expression
     * @throws IllegalArgumentException if the expression is invalid
     */
    public static void validate(String cron) {
        PARSER.parse(cron).validate();
    }

    /**
     * Computes the next execution strictly after the given instant.
     *
     * @param cron  the expression
     * @param after the reference instant
     * @return the next execution instant
     * @throws IllegalArgumentException if the expression is invalid or never fires
     */
    public static Instant nextAfter(String cron, Instant after) {
        ExecutionTime executionTime = ExecutionTime.forCron(PARSER.parse(cron));
        Optional<ZonedDateTime> next = executionTime.nextExecution(after.atZone(ZoneOffset.UTC));
        return next.map(ZonedDateTime::toInstant)
                .orElseThrow(() -> new IllegalArgumentException("Cron never fires: " + cron));
    }
}
```

- REST (openapi, tag `WorkflowDefinitions` or the existing tag for `/workflow/definitions`):
  - `GET /workflow/definitions/{workflowDefinitionId}/triggers/schedules` → `ScheduleTrigger[]`
  - `POST …/triggers/schedules` (body `NewScheduleTrigger`) → 201 `ScheduleTrigger`; 400 on invalid cron or
    blank `subjectName`
  - `PUT …/triggers/schedules/{triggerId}` (body `NewScheduleTrigger`) → `ScheduleTrigger`
  - `DELETE …/triggers/schedules/{triggerId}` → 204
  - Schemas: `NewScheduleTrigger {cron*, subjectName*, inputs (object), enabled (bool, default true)}`;
    `ScheduleTrigger` = those + `id`, `definitionId`, `nextRunAt`, `lastRunAt`, `lastOutcome`, `createdOn`,
    `updatedOn`.
  - On create/update/enable: `nextRunAt = CronSchedules.nextAfter(cron, Instant.now())`.

- Scheduler (follows `WorkflowWaitScheduler`):

```java
/**
 * Fires due {@link WorkflowScheduleTriggerEntity} rows: ensures the fixed subject exists and starts a run of the
 * trigger's workflow definition, skipping the firing if that subject already has an active run of the same
 * definition. Each trigger is processed in its own transaction.
 */
@ApplicationScoped
public class WorkflowScheduleTriggerScheduler {

    private static final Logger LOG = Logger.getLogger(WorkflowScheduleTriggerScheduler.class);

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    ObjectMapper objectMapper;

    private volatile boolean shuttingDown = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    @Scheduled(every = "${axiom.workflow.schedule-poll-interval:30s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void checkDueTriggers() {
        if (shuttingDown) {
            return;
        }
        for (long triggerId : findDueTriggerIds(Instant.now())) {
            try {
                fire(triggerId, Instant.now());
            } catch (Exception e) {
                LOG.errorf(e, "Schedule trigger %d failed", triggerId);
            }
        }
    }

    List<Long> findDueTriggerIds(Instant now) {
        return WorkflowScheduleTriggerEntity
                .<WorkflowScheduleTriggerEntity>list("enabled = true and nextRunAt <= ?1", now)
                .stream().map(t -> t.id).toList();
    }

    /**
     * Fires one trigger and advances its next run time. Always advances {@code nextRunAt} so a failing
     * trigger does not fire in a tight loop.
     *
     * @param triggerId the trigger id
     * @param now       the firing time
     */
    @Transactional
    void fire(long triggerId, Instant now) {
        WorkflowScheduleTriggerEntity trigger = WorkflowScheduleTriggerEntity.findById(triggerId);
        if (trigger == null || !trigger.enabled || trigger.nextRunAt == null || trigger.nextRunAt.isAfter(now)) {
            return;
        }
        trigger.lastRunAt = now;
        trigger.nextRunAt = CronSchedules.nextAfter(trigger.cron, now);
        SubjectEntity subject = SubjectEntity.findOrCreateByName(trigger.subjectName);
        try {
            Map<String, Object> inputs = trigger.inputs == null ? null
                    : objectMapper.readValue(trigger.inputs, new TypeReference<Map<String, Object>>() { });
            workflowExecutionService.triggerWorkflow(subject.id, trigger.definitionId,
                    new RunRequest(null, Map.of("trigger", "schedule"), inputs, null), null);
            trigger.lastOutcome = "started";
        } catch (WorkflowRunConflictException e) {
            trigger.lastOutcome = "skipped";
            LOG.infof("Schedule trigger %d skipped: subject %d already has an active run of definition %d",
                    triggerId, subject.id, trigger.definitionId);
        } catch (Exception e) {
            trigger.lastOutcome = "failed: " + e.getMessage();
            LOG.warnf(e, "Schedule trigger %d failed to start a run", triggerId);
        }
    }
}
```

  Note: `triggerWorkflow` is `@Transactional` (REQUIRED) so it joins `fire`'s transaction; a thrown
  `WebApplicationException` from an inner `@Transactional` method marks the transaction rollback-only
  **only** if it is a RuntimeException not excluded — verify by test (step 3 below). If the conflict marks
  the transaction rollback-only, check for an active run **before** calling `triggerWorkflow` instead of
  relying on the exception, using the same query as `startRun`.

- [ ] **Step 1: Failing unit tests for `CronSchedules`** (`CronSchedulesTest`, plain JUnit):

```java
class CronSchedulesTest {

    /** Next execution of a daily 02:00 UTC cron. */
    @Test
    void nextAfterDailyCron() {
        Instant next = CronSchedules.nextAfter("0 2 * * *", Instant.parse("2026-10-05T03:00:00Z"));
        assertEquals(Instant.parse("2026-10-06T02:00:00Z"), next);
    }

    /** Invalid expressions are rejected. */
    @Test
    void invalidCronRejected() {
        assertThrows(IllegalArgumentException.class, () -> CronSchedules.validate("not a cron"));
    }
}
```

  Run, expect FAIL; implement `CronSchedules` + dependency; expect PASS.

- [ ] **Step 2: Failing scheduler tests** (`WorkflowScheduleTriggerSchedulerTest`, `@QuarkusTest`, inject the
  scheduler and call `fire` directly):
  - `firstFiringCreatesSubjectAndStartsRun`: trigger with `subjectName = "Nightly triage"` (unique per test,
    e.g. append a UUID), `nextRunAt` in the past, wait-node definition from `TestFixtures`. After `fire`:
    exactly one `SubjectEntity` with that name, one `WorkflowRunEntity` for it, `lastOutcome == "started"`,
    `nextRunAt` after now.
  - `secondFiringReusesSubjectAndSkipsWhileActive`: fire twice (reset `nextRunAt` to the past between
    firings); assert still one subject, one run, `lastOutcome == "skipped"`.
  - `firingAfterRunEndsStartsNewRunOnSameSubject`: mark the first run completed, fire again; two runs, one
    subject.
  - `disabledTriggerDoesNotFire`.
  Run, expect FAIL (classes missing).

- [ ] **Step 3: Implement entity, `findOrCreateByName`, baseline DDL, scheduler.** Run step-2 tests, expect
  PASS. Run `MigrationSchemaValidationTest`, expect PASS.

- [ ] **Step 4: REST — failing test, then implement.** Tests: create trigger → 201 with non-null `nextRunAt`;
  invalid cron → 400; blank `subjectName` → 400; list returns it; update changes cron and recomputes
  `nextRunAt`; delete → 204; deleting the workflow definition deletes its triggers. Edit openapi, regenerate,
  implement `WorkflowScheduleTriggersResourceImpl` (implements the generated interface; no `@Path`). Expect
  PASS.

- [ ] **Step 5: `./build.sh` — BUILD SUCCESS.**
- [ ] **Step 6: Commit** — `git add -A && git commit -m "feat(workflow): schedule triggers"`.

---

### Task 12: Runs-first UI, subject timeline, Triggers tab

**Files:**
- Modify: `ui/src/App.tsx` (default route `/` → Runs page), `ui/src/components/AppSidebar.tsx` (order: Runs,
  Subjects, Workflows, Action Types, Event Sources, Subscriptions, Inbox, then existing admin entries)
- Modify/create: workflow runs list page (find with `rg -l 'workflow/runs' ui/src/pages`) — add filters
  (status, definition, subject, label) and a Title column
- Modify: run detail page — show title, labels, inputs, subject link, triggering event link, and each task's
  output (tasks are already linked by `workflowRunId`)
- Modify: `ui/src/pages/SubjectDetailPage.tsx` — header (ref, metadata, Active/Inactive), runs timeline
  (newest first, status + definition + title + started/completed), thread, workspace; remove anything left
  from the old project page that no longer has a backend
- Modify: workflow definition detail/editor page — add a **Triggers** tab: table of schedule triggers (cron,
  subject, enabled, next run, last run, last outcome) with add/edit/delete modal (fields: cron, subject name,
  inputs JSON, enabled)
- Modify: `ui/src/config/api.ts` — types and calls for new run fields/filters and schedule triggers
- Modify: Inbox page — remove any non-human-task item types/filters

- [ ] **Step 1: Implement api.ts types/calls; `npm run build` passes.**
- [ ] **Step 2: Runs page + run detail; `npm run build`.**
- [ ] **Step 3: Subject page timeline; `npm run build`.**
- [ ] **Step 4: Triggers tab; `npm run build`.**
- [ ] **Step 5: Basic UI tests** (mock `../config/api` in each; use `renderWithRouter`):
  - `ui/src/App.test.tsx` (or a routes test, whichever is simpler given how `App.tsx` is structured): rendering
    route `/` shows the Runs page heading. If `App` pulls in too much (SSE, layout fetches), instead test that
    the route table exported/used by `App.tsx` maps `/` to the Runs page component.
  - Runs page test: mocked runs list with two runs (titles "Run A", "Run B"); assert both titles render; assert
    the filter controls for status, definition, subject and label are present (by accessible label).
  - Subject detail test: mocked subject + two runs; assert both run titles appear in the timeline.
  - Triggers tab test: mocked trigger list with one trigger (cron `0 2 * * *`, subject "Nightly triage",
    lastOutcome "skipped"); assert those values render; click "Add trigger" (use `@testing-library/user-event`)
    and assert the modal shows fields labeled "Cron" and "Subject name".
  - Sidebar test: first item is "Runs".
  Run `cd ui && npm test` — all pass.
- [ ] **Step 6: `./build.sh -Pui` — BUILD SUCCESS.**
- [ ] **Step 7: Commit** — `git add -A && git commit -m "feat(ui): runs-first navigation, subject timeline, schedule triggers"`.

---

### Task 13: Documentation

**Files:**
- Rewrite: `docs/user-guide/concepts.md` — Subjects, Workflows, Runs, Action Types (workflow-only), Events &
  Subscriptions (`create-workflow`, `workflow-dispatch` broadcast), Schedule Triggers (fixed subject, skip when
  active), Inbox (human tasks), concurrency rule. Remove the four-pillar section.
- Update: `docs/user-guide/workflows.md` — schedule triggers, run title/labels/inputs, concurrency rule,
  no outputs.
- Delete: user-guide pages dedicated to reports, scheduled jobs, the Manager (`rg -l -i 'report|scheduled job|manager' docs/user-guide`)
- Update developer guide: `stack-and-architecture.md`, `event-sourcing-design.md`, `tracing.md`,
  `correlation.md`, `lineage.md`, `data-retention.md`, `database-and-migrations.md` (single baseline; edit V1
  until first release), `extending-axiom.md`; `docs/getting-started/configuring-axiom.md`; `README.md`
- Wrap markdown at 110 columns (tables excepted).
- Document the SDK changes wherever SDK functions are described in docs (`git grep -l -E 'axiom_[a-z_]+' docs`),
  including the new `axiom_get_workflow_run` and `axiom_list_subject_runs`.
- Document UI tests (`cd ui && npm test`) in the developer guide / `CONTRIBUTING.md` where the build is
  described.

- [ ] **Step 1: Edit docs.**
- [ ] **Step 2: Verify** `rg -n -i 'project|report definition|scheduled job|ai manager|invoke-action|userTriggerable' docs README.md`
  — only intentional mentions (e.g. "Report SDK (future)").
- [ ] **Step 3: Commit** — `git add -A && git commit -m "docs: describe workflow-first model"`.

---

## Final Verification

- [ ] `./build.sh -Pui` — BUILD SUCCESS, all Java and UI tests pass.
- [ ] `ls app/src/main/resources/db/migration` — exactly `V1__baseline.sql`; `MigrationSchemaValidationTest`
  passes.
- [ ] `git grep -n -E 'axiom_(create_task|close_project|reopen_project|list_reports|list_report_definitions|add_report_label|remove_report_label|[a-z_]*project[a-z_]*)'`
  — no matches.
- [ ] Spec checklist: every bullet in the spec's sections 1–6 maps to a task above.
