# Trace ID as Correlation ID Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Every activity row, AI usage row and agent-created task carries the trace ID of the unit of work that
produced it, so a user can find all records for a trace.

**Architecture:** Add `trace_id` to `activity_log` and `ai_usage`, populate it from the trace already in scope at
each write, expose it via the API (field + filter), clear it in `TraceCleanup`. Agents propagate their trace via
`X-Axiom-Trace-Id` / `X-Axiom-Parent-Node-Id` headers; a request filter validates them into a request-scoped
`CallerTraceContext`, and `POST /projects/{id}/tasks` joins the caller's trace.

**Tech Stack:** Java 25, Quarkus, Hibernate Panache, Flyway (H2), JAX-RS generated from OpenAPI, JUnit 5 +
RestAssured, React/TypeScript UI, Node MCP server template.

**Spec:** `docs/superpowers/specs/2026-10-02-correlation-id-design.md`

## Global Constraints

- API-first: change `common/api/src/main/resources/openapi.json` first, regenerate with
  `mvn install -pl common/api -am -DskipTests`, implement generated interfaces; no `@Path` on impl classes.
- Java style: 4-space indentation, Javadoc on public methods, explicit types (no `var`), JUnit 5.
- Next Flyway migration is `V66`. No foreign keys on new `trace_id` columns.
- Malformed `filterTraceId` returns 400 (same as `filterEventId`).
- Markdown wrapped at 110 characters.
- No AI attribution in commits.
- Full verification command: `./build.sh -Dquarkus.http.test-port=0` (app module test count was 679 on main).
- Paths: `APP = app/src/main/java/io/apitomy/axiom/app`, `CORE = core/src/main/java/io/apitomy/axiom/core`,
  `TEST = app/src/test/java/io/apitomy/axiom/app`.

---

### Task 1: Schema, entities and API for `trace_id` on activity and AI usage

**Files:**
- Create: `app/src/main/resources/db/migration/V66__add_trace_id_to_activity_and_ai_usage.sql`
- Modify: `CORE/entities/ActivityLogEntity.java`, `CORE/entities/AiUsageEntity.java`
- Modify: `common/api/src/main/resources/openapi.json` (`ActivityLogEntry`, `AiUsage`, `GET /activity`,
  `GET /usage/ai`)
- Modify: `APP/rest/ActivityResourceImpl.java`, `APP/rest/UsageResourceImpl.java`,
  `APP/rest/TraceResourceImpl.java` (shared UUID parser)
- Test: `TEST/ActivityResourceTest.java`, `TEST/UsageResourceTest.java` (create if absent; follow
  `ActivityResourceTest` seeding pattern)

**Interfaces:**
- Produces: `ActivityLogEntity.traceId` (`UUID`), `AiUsageEntity.traceId` (`UUID`);
  `static UUID TraceResourceImpl.parseUuidParam(String name, String value)` (400 on malformed);
  query param `filterTraceId` on both endpoints; bean property `traceId` on `ActivityLogEntry`/`AiUsage`.

- [ ] **Step 1: Write failing tests.** In `ActivityResourceTest`, seed two extra entries with
  `TRACE_A = UUID.fromString("d4d4d4d4-0000-4000-8000-000000000400")` (one with summary
  `FILTER-TEST traced`) and add:

```java
@Test
void testFilterByTraceId() {
    given().queryParam("filterTraceId", TRACE_A.toString())
        .when().get(ACTIVITY_PATH)
        .then().statusCode(200)
            .body("items.size()", greaterThanOrEqualTo(1))
            .body("items.traceId", everyItem(equalTo(TRACE_A.toString())));
}

@Test
void testFilterByMalformedTraceIdReturns400() {
    given().queryParam("filterTraceId", "not-a-uuid")
        .when().get(ACTIVITY_PATH)
        .then().statusCode(400);
}
```

  Add the same two tests for `GET /api/v1/usage/ai` in `UsageResourceTest`, seeding `AiUsageEntity` rows
  (`invocationType="task"`, `costUsd`, `createdOn=Instant.now()`, `traceId=TRACE_A`).

- [ ] **Step 2: Run and confirm failure.**
  `mvn test -pl app -Dtest='ActivityResourceTest,UsageResourceTest' -Dquarkus.http.test-port=0`
  Expected: compile failure (`traceId` field missing).

- [ ] **Step 3: Migration.**

```sql
-- Correlation: link activity and AI usage rows to the trace (unit of work) that produced them.
-- No foreign key: TraceCleanup clears these references explicitly when traces age out.
ALTER TABLE activity_log ADD COLUMN trace_id UUID;
CREATE INDEX IF NOT EXISTS idx_activity_log_trace ON activity_log(trace_id);
ALTER TABLE ai_usage ADD COLUMN trace_id UUID;
CREATE INDEX IF NOT EXISTS idx_ai_usage_trace ON ai_usage(trace_id);
```

- [ ] **Step 4: Entities.** Add to both entities, matching existing `eventId` declarations:

```java
@Column(name = "trace_id")
public UUID traceId;
```

- [ ] **Step 5: OpenAPI.** Add to `ActivityLogEntry` and `AiUsage` properties:
  `"traceId": {"type": "string", "format": "uuid", "description": "Trace (unit of work) that produced this
  record"}`. Add a query parameter to `GET /activity` and `GET /usage/ai`, copying the `filterEventId` block:
  `"name": "filterTraceId"`, `"description": "Filter by exact trace ID"`, `string`/`uuid`. Regenerate:
  `mvn install -pl common/api -am -DskipTests`.

- [ ] **Step 6: REST impls.** Rename `TraceResourceImpl.parseEventId` to a general helper and keep the old
  name delegating:

```java
/**
 * Parses a UUID query parameter, returning 400 if it is malformed.
 *
 * @param name  the parameter name used in the error message
 * @param value the raw parameter value
 * @return the parsed UUID
 */
static UUID parseUuidParam(String name, String value) {
    try {
        return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
        throw new WebApplicationException("Invalid " + name + ": " + value, 400);
    }
}
```

  Add the new generated `filterTraceId` parameter to the `ActivityResourceImpl` and `UsageResourceImpl`
  method signatures, and add to each HQL builder:

```java
if (filterTraceId != null && !filterTraceId.isBlank()) {
    hql.append(" and traceId = :traceId");
    params.put("traceId", TraceResourceImpl.parseUuidParam("filterTraceId", filterTraceId));
}
```

  Map `entry.setTraceId(entity.traceId)` / `usage.setTraceId(entity.traceId)` in both bean mappers. Fix any
  other callers of the regenerated interfaces (compile will show them).

- [ ] **Step 7: Run tests.** Same command as Step 2. Expected: PASS.

- [ ] **Step 8: Commit.** `feat(tracing): add trace_id to activity log and AI usage (#427)`

---

### Task 2: Populate `trace_id` at every activity and AI usage write

**Files:**
- Modify: `APP/EventStreamOrchestrator.java` (`logActivity` ~:877, callers ~:507, :529, :536, :693, :791;
  `handleIgnore`, `handleEscalation`)
- Modify: `APP/TaskExecutionService.java` (`logActivity` ~:576, callers :372, :406, :474, :530; AI usage
  ~:605)
- Modify: `APP/ScriptExecutionService.java` (`logActivity` ~:495, callers :427, :450)
- Modify: `APP/WorkflowExecutionService.java` (`logActivity` ~:876, callers :213, :355, :360, :412)
- Modify: `APP/ReportExecutionService.java` (`logActivity` ~:526, callers :302, :364, :398; AI usage ~:337)
- Modify: `APP/ScheduledJobExecutionService.java` (`logActivity` ~:553, callers :314, :370, :399, :418, :442;
  AI usage ~:344)
- Test: `TEST/TraceCorrelationTest.java` (new `@QuarkusTest`, reuse setup helpers/mocks from
  `TEST/TraceLifecycleTest.java`)

**Interfaces:**
- Consumes: `ActivityLogEntity.traceId`, `AiUsageEntity.traceId` (Task 1).
- Produces: each private `logActivity` helper takes a trailing `UUID traceId` parameter (nullable).

- [ ] **Step 1: Write failing tests** in `TraceCorrelationTest`, modelled on the existing
  `TraceLifecycleTest` scenarios (same agent/engine mocking):
  1. A task completed through `TaskExecutionService` → every `ActivityLogEntity` with that `taskId`, and its
     `AiUsageEntity`, has `traceId == task.traceId`.
  2. A report generated through `ReportExecutionService` → activity rows written during generation and the
     `AiUsageEntity` with `invocationType = 'report'` created after the start time have
     `traceId == report.traceId`.
  3. A scheduled job run (agent mode) → same assertion against `run.traceId`.
  4. A workflow run triggered via `WorkflowExecutionService.triggerWorkflow` → the `workflow-started`
     activity row has `traceId == run.traceId`.
  5. `EventStreamOrchestrator` manager route with a mocked `ManagerService` returning one `ignore` decision →
     the `event-ignored` activity row has `traceId` equal to the routing outcome's `traceId`.

  Query helpers run in `QuarkusTransaction.requiringNew()`, as in `TraceLifecycleTest`.

- [ ] **Step 2: Run and confirm failure.**
  `mvn test -pl app -Dtest=TraceCorrelationTest -Dquarkus.http.test-port=0` — Expected: assertions fail
  (`traceId` null).

- [ ] **Step 3: Implement.** In each class, add the parameter and set it:

```java
private void logActivity(..., UUID traceId) {
    ActivityLogEntity entry = new ActivityLogEntity();
    // existing assignments unchanged
    entry.traceId = traceId;
    entry.persist();
}
```

  Pass at each call site: `task.traceId` (task/script services), `run.traceId` / `entity.traceId`
  (workflow), `report.traceId` or `traceCtx != null ? traceCtx.traceId() : null` (reports, jobs), and the
  routing `traceCtx.traceId()` (orchestrator). Change `handleIgnore(...)` and `handleEscalation(...)` to take
  `TraceContext traceCtx` and pass it from the decision loop. For AI usage writes set
  `usage.traceId = <same trace>`. Where a job/report activity row is written before a trace exists, pass
  `null`.

- [ ] **Step 4: Run tests.** Step 2 command, then
  `mvn test -pl app -Dtest='TraceLifecycleTest,EventStreamOrchestratorTest,ScriptExecutionServiceTest,WorkflowExecutionServiceTest' -Dquarkus.http.test-port=0`.
  Expected: PASS.

- [ ] **Step 5: Commit.** `feat(tracing): record trace ID on activity and AI usage writes (#427)`

---

### Task 3: Trace cleanup clears the new references

**Files:**
- Modify: `APP/TraceCleanup.java` (`doCleanup`)
- Test: `TEST/TraceCleanupTest.java` (new `@QuarkusTest`)

**Interfaces:**
- Consumes: Task 1 fields. `TraceCleanup.doCleanup()` is package-private and callable from a test in the same
  package.

- [ ] **Step 1: Failing test.** In a transaction: set `RetentionConfigEntity.traceRetentionDays = 1`; persist a
  `TraceEntity` with `startedOn = Instant.now().minus(5, DAYS)`, an `ActivityLogEntity` and an
  `AiUsageEntity` referencing it. Call `traceCleanup.doCleanup()` inside `QuarkusTransaction.requiringNew()`.
  Assert the trace is gone and both rows still exist with `traceId == null`. Restore the retention value in
  `@AfterEach`.

- [ ] **Step 2: Run, confirm failure.** `mvn test -pl app -Dtest=TraceCleanupTest -Dquarkus.http.test-port=0`

- [ ] **Step 3: Implement.** After the existing `ReportEntity.update(...)` line:

```java
ActivityLogEntity.update("traceId = null where traceId in ?1", traceIds);
AiUsageEntity.update("traceId = null where traceId in ?1", traceIds);
```

- [ ] **Step 4: Run, confirm pass.** Same command.

- [ ] **Step 5: Commit.** `feat(tracing): clear activity and AI usage trace references on cleanup (#427)`

---

### Task 4: Caller trace propagation and agent tasks joining the caller's trace

**Files:**
- Create: `APP/rest/CallerTraceContext.java` (`@RequestScoped` bean)
- Create: `APP/rest/CallerTraceFilter.java` (`@Provider` `ContainerRequestFilter`)
- Modify: `APP/rest/ProjectsResourceImpl.java` (`createTask` ~:278)
- Modify: `app/src/main/resources/templates/axiom-mcp-server/sdk-server.js` (`axiomApi` ~:19)
- Modify: `common/api/src/main/resources/openapi.json` (document headers as optional parameters on
  `POST /projects/{id}/tasks`)
- Test: `TEST/CallerTraceTest.java` (new `@QuarkusTest`)

**Interfaces:**
- Produces:
  - `CallerTraceContext`: `Optional<TraceContext> get()`; `void set(TraceContext ctx)`.
  - Header constants `CallerTraceFilter.TRACE_HEADER = "X-Axiom-Trace-Id"`,
    `CallerTraceFilter.PARENT_NODE_HEADER = "X-Axiom-Parent-Node-Id"`.
- Consumes: `new TraceContext(UUID traceId, Long rootNodeId)`; `TraceService.addNode(ctx, ...)`;
  `TraceEntity.status`; `TraceNodeEntity.traceId`; `TraceEntity.rootNodeId` (check the entity for the exact
  root-node field name and use it when no parent header is sent).

- [ ] **Step 1: Failing tests** in `CallerTraceTest` (create a project via existing test helpers or
  `POST /api/v1/projects`, then create an in-progress trace with `traceService.createTrace(...)`):
  1. `POST /api/v1/projects/{id}/tasks` with both headers → response task `traceId` equals the caller trace;
     a `task` node exists with `parentNodeId` equal to the header node; no new `user-action` trace was
     created for that project.
  2. The caller trace stays `in-progress` while the task is Pending (`TaskTraceFinalizer` relies on the open
     node).
  3. Headers with a malformed UUID, an unknown trace, a trace with status `completed`, or a parent node from a
     different trace → task gets a new `user-action` trace (assert trace type via `TraceEntity.findById`).
  4. Only `X-Axiom-Trace-Id` (no parent) → node is attached under the trace's root node.

- [ ] **Step 2: Run, confirm failure.** `mvn test -pl app -Dtest=CallerTraceTest -Dquarkus.http.test-port=0`

- [ ] **Step 3: `CallerTraceContext`.**

```java
/**
 * Request-scoped holder for the trace of the agent (or other caller) making the current API request.
 */
@RequestScoped
public class CallerTraceContext {

    private TraceContext traceContext;

    /**
     * Returns the validated caller trace context, if the request carried one.
     *
     * @return the caller trace context, or empty
     */
    public Optional<TraceContext> get() {
        return Optional.ofNullable(traceContext);
    }

    /**
     * Sets the validated caller trace context.
     *
     * @param traceContext the trace context to use for this request
     */
    public void set(TraceContext traceContext) {
        this.traceContext = traceContext;
    }
}
```

- [ ] **Step 4: `CallerTraceFilter`.** `@Provider`, implements `ContainerRequestFilter`, injects
  `CallerTraceContext`. In `filter(...)`: return if `TRACE_HEADER` is absent; parse the UUID (catch
  `IllegalArgumentException` → `LOG.debugf` and return); in `QuarkusTransaction.requiringNew().call(...)` load
  `TraceEntity`; require non-null and `"in-progress".equals(trace.status)`. Resolve the parent node: if
  `PARENT_NODE_HEADER` is present, parse as `Long` and require
  `TraceNodeEntity` with that id and `traceId` equal to the trace; otherwise use the trace's root node id.
  On success `callerTraceContext.set(new TraceContext(traceId, parentNodeId))`; on any failure log at debug
  and leave it unset. Never fail the request.

- [ ] **Step 5: `createTask` joins the caller trace.** Inject `CallerTraceContext`. Replace the
  `traceCtx` creation:

```java
Optional<TraceContext> callerTrace = callerTraceContext.get();
TraceContext traceCtx = callerTrace.orElse(null);
if (traceCtx == null) {
    try {
        traceCtx = traceService.createTrace("user-action", ...unchanged...);
    } catch (Exception e) {
        LOG.warnf(e, "Failed to create trace for user action on project %d", projectId);
    }
}
```

  Set `entity.createdBy = callerTrace.isPresent() ? "agent" : "user";` only if `"agent"` is already an
  accepted value of `createdBy` (check `TaskEntity` / OpenAPI enum); otherwise leave `"user"`. The rest of the
  method (setting `entity.traceId`, adding the `task` node) is unchanged and now attaches under the caller's
  parent node.

- [ ] **Step 6: `sdk-server.js`.**

```js
const headers = { "Content-Type": contentType, "Accept": "application/json" };
if (process.env.AXIOM_TRACE_ID) headers["X-Axiom-Trace-Id"] = process.env.AXIOM_TRACE_ID;
if (process.env.AXIOM_PARENT_NODE_ID) headers["X-Axiom-Parent-Node-Id"] = process.env.AXIOM_PARENT_NODE_ID;
const opts = { method, headers };
```

- [ ] **Step 7: OpenAPI.** Add optional header parameters to `POST /projects/{id}/tasks`:
  `{"name": "X-Axiom-Trace-Id", "in": "header", "required": false, "description": "Trace of the calling agent;
  the new task joins it", "schema": {"type": "string", "format": "uuid"}}` and `X-Axiom-Parent-Node-Id`
  (`integer`, `int64`). Regenerate; if the generated `createTask` signature gains header parameters, accept
  and ignore them in the impl (the filter is the source of truth).

  Note: the other agent write endpoints listed in the spec write no activity rows today (verified: no
  `ActivityLogEntity` writes under `APP/rest/`), so they need no change. They still receive the headers and
  the filter populates `CallerTraceContext`, ready for future use. Document the headers only on
  `POST /projects/{id}/tasks`, where they change behavior.

- [ ] **Step 8: Run tests.** Step 2 command plus `-Dtest=ProjectsResourceTest`. Expected: PASS.

- [ ] **Step 9: Commit.** `feat(tracing): agent API calls join the caller's trace (#427)`

---

### Task 5: UI — show and filter by trace ID

**Files:**
- Modify: `ui/src/**/api.ts` (or wherever `ActivityLogEntry` / `AiUsage` TS types live — `grep -rn
  "interface ActivityLogEntry\|AiUsage" ui/src`)
- Modify: `ui/src/**/ActivityLogPage.tsx`, the AI usage page under `/metrics/ai-usage`

- [ ] **Step 1:** Add `traceId?: string` to both TS types and a `filterTraceId` param to their fetch
  functions, mirroring `filterEventId`.
- [ ] **Step 2:** In both tables add a "Trace" column rendering a router `Link` to `/logs/traces/${traceId}`
  showing the first 8 characters with the full UUID in `title` (same pattern #414 used for event IDs). Add a
  "Trace ID" text filter input wired to `filterTraceId`, and read an initial value from the `traceId` URL query
  parameter so other pages can deep-link.
- [ ] **Step 3:** Run `npx tsc -b` in `ui/`. Expected: no errors.
- [ ] **Step 4: Commit.** `feat(ui): show and filter activity and AI usage by trace (#427)`

---

### Task 6: Documentation and full verification

**Files:**
- Create: `docs/developer-guide/correlation.md`
- Modify: `docs/developer-guide/tracing.md`, `mkdocs.yml` (add page to nav next to `tracing.md`)

- [ ] **Step 1:** Write `correlation.md`: the model (trace ID = unit of work), which tables carry `trace_id`,
  how to find all records for a trace (`GET /traces/{id}`, `GET /activity?filterTraceId=`,
  `GET /usage/ai?filterTraceId=`, tasks/runs/reports by `traceId`), the agent headers and their validation
  rules, the accepted risk (agent tasks keep the trace open), and out-of-scope items from the spec.
- [ ] **Step 2:** Add a short "Correlation" section in `tracing.md` linking to it.
- [ ] **Step 3: Full build.** `./build.sh -Dquarkus.http.test-port=0`. Expected: BUILD SUCCESS, 0 failures,
  app test count greater than 679.
- [ ] **Step 4: Commit.** `docs(tracing): document trace ID correlation (#427)`
