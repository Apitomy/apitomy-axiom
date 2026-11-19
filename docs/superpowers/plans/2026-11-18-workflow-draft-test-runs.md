# Workflow Draft Test Runs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Run a workbench composition's unpublished workflow on the real engine, triggered by a stream event or a
fabricated event, on an ephemeral test subject, with assistant tools to launch and monitor the run.

**Architecture:** A test run is a normal `WorkflowRunEntity` with `isTest=true`. It carries a snapshot of the
draft workflow (`definitionSnapshot`) and of the draft action types (`actionOverlaySnapshot`). Two new
components, `RunWorkflowLoader` and `ActionTypeResolver`, prefer these snapshots over the published catalog. The
production run start is split into a shared core, so test runs reuse it unchanged. Production queries, event
dispatch and post-run hooks exclude test runs. A reaper deletes expired test subjects.

**Tech Stack:** Java 25, Quarkus, Hibernate Panache, Flyway, JUnit 5, RestAssured, Node (workbench MCP server),
React 19 + PatternFly 6 + Vitest.

**Spec:** `docs/superpowers/specs/2026-11-18-workflow-draft-test-runs-design.md`

## Global Constraints

- REST changes are contract-first: edit `common/api/src/main/resources/openapi.json`, then run
  `mvn install -pl common/api -q`, then implement the generated interface. Never add `@Path` to impl classes.
- Steps run for real (no mocking); the UI and the tool descriptions carry the side-effect warning.
- Test subject refs use the prefix `test:` (`SubjectEntity.TEST_REF_PREFIX`).
- Config: `axiom.workflow-test-runs.retention` (default `24h`) and `axiom.workflow-test-runs.wait-timeout`
  (default `1h`), read with `@ConfigProperty`.
- At most 2 concurrent test runs per composition; exceeding the limit returns 429.
- Long-poll `wait` is clamped to 0–30 seconds.
- 4-space indentation, Javadoc on public methods, no `var` where the type is ambiguous.
- Live testing only via `AXIOM_HOME=/tmp/axiom-test/home ./dev.sh --skip-ui --portOffset=500
  --dataDir=/tmp/axiom-test/data`. Never use ports 9191 or 9090.
- Single backend test: `mvn -pl app -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`.

## File Map

| File | Responsibility |
|---|---|
| `core/.../entities/WorkflowRunEntity.java` | new columns; `definitionId`/`definitionVersion` become nullable |
| `core/.../entities/SubjectEntity.java` | `isTest`, `expiresAt`, `TEST_REF_PREFIX` |
| `app/src/main/resources/db/migration/V14__workflow_test_runs.sql` | schema migration |
| `app/.../app/RunWorkflowLoader.java` (new) | loads a run's `Workflow` from the snapshot or the published version |
| `app/.../app/ActionTypeResolver.java` (new) | resolves an action type by name from the run overlay or the catalog |
| `app/.../app/WorkflowExecutionService.java` | split `startRun`; `startTestRun`; skip summaries for test runs |
| `app/.../app/WorkflowEventDispatcher.java` | skip test-run subscriptions; `offerTestEvent` |
| `app/.../app/TestRunLaunch.java` (new) | record holding the inputs of a test-run start |
| `app/.../composition/WorkflowTestRunService.java` (new) | composition → snapshot → subject → run; concurrency; views |
| `app/.../composition/WorkflowTestRunReaper.java` (new) | wait timeout and retention cleanup |
| `app/.../rest/CompositionsResourceImpl.java` | new endpoints |
| `templates/axiom-workbench-mcp/server.js`, `assistant-templates/axiom-workbench.json` | assistant tools |
| `ui/src/config/compositions.ts`, `ui/src/pages/workbench/*` | UI |

---

### Task 1: Schema for test runs and test subjects

**Files:**
- Modify: `core/src/main/java/io/apitomy/axiom/core/entities/WorkflowRunEntity.java`
- Modify: `core/src/main/java/io/apitomy/axiom/core/entities/SubjectEntity.java`
- Create: `app/src/main/resources/db/migration/V14__workflow_test_runs.sql`
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowTestRunSchemaTest.java`

**Interfaces:**
- Produces:
  - `WorkflowRunEntity`: `boolean isTest`, `Long compositionId`, `String compositionRevision`,
    `String definitionSnapshot`, `String actionOverlaySnapshot`, `Long definitionId` (nullable),
    `Integer definitionVersion` (nullable).
  - `SubjectEntity`: `boolean isTest`, `Instant expiresAt`, `static final String TEST_REF_PREFIX = "test:"`.

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class WorkflowTestRunSchemaTest {

    @Test
    void persistsTestRunWithoutPublishedDefinition() {
        long runId = QuarkusTransaction.requiringNew().call(() -> {
            SubjectEntity subject = new SubjectEntity();
            subject.name = "t";
            subject.type = "other";
            subject.ref = SubjectEntity.TEST_REF_PREFIX + "schema";
            subject.isTest = true;
            subject.expiresAt = Instant.now().plusSeconds(60);
            subject.createdOn = Instant.now();
            subject.updatedOn = Instant.now();
            subject.persist();
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.subjectId = subject.id;
            run.isTest = true;
            run.compositionId = 7L;
            run.compositionRevision = "rev-1";
            run.definitionSnapshot = "{}";
            run.actionOverlaySnapshot = "{}";
            run.instanceState = "{}";
            run.status = "running";
            run.startedOn = Instant.now();
            run.persist();
            return run.id;
        });
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
            assertTrue(run.isTest);
            assertNull(run.definitionId);
            assertNull(run.definitionVersion);
            assertEquals("rev-1", run.compositionRevision);
        });
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl app -am test -Dtest=WorkflowTestRunSchemaTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`isTest`, `TEST_REF_PREFIX` and the other new fields are undefined).

- [ ] **Step 3: Update the entities**

In `WorkflowRunEntity`, change the two definition columns and add the new fields:

```java
    @Column(name = "definition_id")
    public Long definitionId;

    @Column(name = "definition_version")
    public Integer definitionVersion;

    /** True for a workbench test run of an unpublished draft. */
    @Column(name = "is_test", nullable = false)
    public boolean isTest;

    /** The composition whose draft a test run executes; null for production runs. */
    @Column(name = "composition_id")
    public Long compositionId;

    /** The draft workflow item's content revision at launch. */
    @Column(name = "composition_revision")
    public String compositionRevision;

    /** The draft workflow JSON executed by a test run; when set it replaces the published version. */
    @Column(name = "definition_snapshot", columnDefinition = "TEXT")
    public String definitionSnapshot;

    /** JSON object of draft action type name to action type JSON, preferred over the catalog. */
    @Column(name = "action_overlay_snapshot", columnDefinition = "TEXT")
    public String actionOverlaySnapshot;
```

In `SubjectEntity`, add:

```java
    /** Ref prefix reserved for ephemeral workbench test subjects. */
    public static final String TEST_REF_PREFIX = "test:";

    /** True for an ephemeral subject created by a workbench test run. */
    @Column(name = "is_test", nullable = false)
    public boolean isTest;

    /** When an ephemeral test subject (and its runs) may be deleted. */
    @Column(name = "expires_at")
    public Instant expiresAt;
```

- [ ] **Step 4: Write the migration**

Create `V14__workflow_test_runs.sql`:

```sql
-- Axiom 3.x incremental migration 14.
--
-- Workbench test runs of unpublished workflow drafts: runs carry a draft snapshot and may have no
-- published definition; ephemeral test subjects expire.
alter table workflow_run alter column definition_id drop not null;
alter table workflow_run alter column definition_version drop not null;
alter table workflow_run add column is_test boolean default false not null;
alter table workflow_run add column composition_id bigint;
alter table workflow_run add column composition_revision varchar(255);
alter table workflow_run add column definition_snapshot TEXT;
alter table workflow_run add column action_overlay_snapshot TEXT;
create index idx_workflow_run_is_test on workflow_run (is_test);

alter table subject add column is_test boolean default false not null;
alter table subject add column expires_at timestamp(6) with time zone;
create index idx_subject_is_test on subject (is_test);
```

Check the real table names (`workflow_run`, `subject`) against `@Table` on the entities before saving.

- [ ] **Step 5: Fix the compile errors caused by the nullable columns**

Run: `mvn -pl app -am compile -q`

For every reported error:
- Where a `long` or `int` was unboxed from `definitionId` or `definitionVersion` (the call sites listed in the
  research: `WorkflowExecutionService` :306/:389/:458/:632/:1064, `WorkflowEventDispatcher` :164,
  `WorkflowRunBeanMapper` :82/:91, `TraceResourceImpl` :339, `RunSummaryService` :93/:327, `SdkCallService` :257,
  plus the bean mappers), leave the loads to Task 2 (they become `runWorkflowLoader.load(run)`).
- For the other sites, guard the access, e.g.
  `run.definitionId != null ? WorkflowDefinitionEntity.findById(run.definitionId) : null`.
- Bean mapping: map `null` through unchanged (generated beans use boxed types).

- [ ] **Step 6: Run the tests**

Run: `mvn -pl app -am test -Dtest='WorkflowTestRunSchemaTest,WorkflowRunTitleTest,WorkflowExecutionServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add core app/src/main app/src/test/java/io/apitomy/axiom/app/WorkflowTestRunSchemaTest.java
git commit -m "Add schema for workflow draft test runs and test subjects"
```

---

### Task 2: `RunWorkflowLoader` (snapshot-aware definition loading)

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/RunWorkflowLoader.java`
- Modify: `WorkflowExecutionService.java` (replace `loadWorkflowContent` at :1128 and its five callers)
- Modify: `WorkflowEventDispatcher.java` (replace the private `loadWorkflowContent` :312 and the call at :164)
- Modify: `WorkflowRunBeanMapper.java:91-96`
- Test: `app/src/test/java/io/apitomy/axiom/app/RunWorkflowLoaderTest.java`

**Interfaces:**
- Produces:
  - `@ApplicationScoped RunWorkflowLoader`
  - `Workflow load(WorkflowRunEntity run)`, which throws `WebApplicationException(500)` when nothing is found
  - `String loadContent(WorkflowRunEntity run)`, which returns the raw JSON or null

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class RunWorkflowLoaderTest {

    @Inject
    RunWorkflowLoader loader;

    private static final String SNAPSHOT = """
        {"id": "snap", "name": "Snapshot WF",
         "nodes": [
           {"id": "s1", "type": "start", "name": "Start", "config": {}, "position": {"x": 0, "y": 0}},
           {"id": "e1", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 100}}],
         "edges": [{"id": "x1", "source": "s1", "target": "e1", "priority": 0, "isDefault": true}]}
        """;

    @Test
    void prefersSnapshotOverPublishedVersion() {
        long defId = TestFixtures.createWaitDefinition("loader-pub");
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.definitionId = defId;
        run.definitionVersion = 1;
        run.definitionSnapshot = SNAPSHOT;
        assertEquals("Snapshot WF", loader.load(run).name());
    }

    @Test
    void fallsBackToPublishedVersion() {
        long defId = TestFixtures.createWaitDefinition("loader-pub-2");
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.definitionId = defId;
        run.definitionVersion = 1;
        assertEquals("Wait WF", loader.load(run).name());
    }
}
```

Adjust `.name()` to the Flow `Workflow` accessor (`getName()` if it is a class). Check what
`deserializeWorkflow` returns in `WorkflowExecutionService`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl app -am test -Dtest=RunWorkflowLoaderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`RunWorkflowLoader` is missing).

- [ ] **Step 3: Implement**

```java
package io.apitomy.axiom.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

/**
 * Loads the workflow a run executes: the draft snapshot of a test run, otherwise the published version the
 * run was started from.
 */
@ApplicationScoped
public class RunWorkflowLoader {

    @Inject
    ObjectMapper objectMapper;

    /**
     * Returns the raw workflow JSON a run executes.
     *
     * @param run the run
     * @return the workflow JSON, or null if neither a snapshot nor the published version exists
     */
    public String loadContent(WorkflowRunEntity run) {
        if (run.definitionSnapshot != null) {
            return run.definitionSnapshot;
        }
        if (run.definitionId == null || run.definitionVersion == null) {
            return null;
        }
        WorkflowDefinitionVersionEntity version = WorkflowDefinitionVersionEntity
                .find("definitionId = ?1 and version = ?2", run.definitionId, run.definitionVersion)
                .firstResult();
        return version != null ? version.content : null;
    }

    /**
     * Returns the parsed workflow a run executes.
     *
     * @param run the run
     * @return the workflow
     * @throws WebApplicationException (500) if the workflow cannot be found or parsed
     */
    public Workflow load(WorkflowRunEntity run) {
        String content = loadContent(run);
        if (content == null) {
            throw new WebApplicationException("Workflow version not found", 500);
        }
        try {
            return objectMapper.readValue(content, Workflow.class);
        } catch (JsonProcessingException e) {
            throw new WebApplicationException("Invalid workflow content: " + e.getMessage(), 500);
        }
    }
}
```

Use the same `Workflow` import and deserialization as `WorkflowExecutionService.deserializeWorkflow`. If it
does more than `readValue` (for example, a Flow parser), delegate to that same code.

- [ ] **Step 4: Replace the callers**

- In `WorkflowExecutionService`:
  - Inject `RunWorkflowLoader runWorkflowLoader`.
  - Replace each `loadWorkflowContent(entity.definitionId, entity.definitionVersion)` (:306, :389, :458, :632,
    :1064) with `runWorkflowLoader.load(entity)`.
  - Delete the private `loadWorkflowContent`.
- In `WorkflowEventDispatcher`:
  - Inject the loader.
  - At :164, call `runWorkflowLoader.loadContent(run)`, deserialize it the same way as the old private method,
    and keep the warn-and-return-null behavior.
  - Delete the private method.
- In `WorkflowRunBeanMapper`, set `workflowContent` from `runWorkflowLoader.loadContent(run)`.

- [ ] **Step 5: Run the tests**

Run: `mvn -pl app -am test -Dtest='RunWorkflowLoaderTest,WorkflowExecutionServiceTest,WorkflowRunTitleTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "Load a run's workflow from its draft snapshot when present"
```

---

### Task 3: `ActionTypeResolver` (draft overlay for action types)

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/ActionTypeResolver.java`
- Modify: `TaskExecutionService.java:212, :396, :707`, `NodeExecutionService.java:87`,
  `ScriptExecutionService.java:190`, `WorkflowExecutionService.java:327`
- Modify: `DraftTestRunner.java`: make `toEntity(JsonNode)` (:305) `static` and package-visible, so the
  resolver can reuse it.
- Test: `app/src/test/java/io/apitomy/axiom/app/ActionTypeResolverTest.java`

**Interfaces:**
- Consumes: `WorkflowRunEntity.actionOverlaySnapshot` (Task 1), `DraftTestRunner.toEntity(JsonNode)`.
- Produces:
  - `ActionTypeEntity resolve(String name, Long workflowRunId)`, which returns the overlay entity (transient,
    not persisted) or the catalog entity, or null.
  - `static String overlayJson(Map<String, JsonNode> drafts, ObjectMapper mapper)`.

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class ActionTypeResolverTest {

    @Inject
    ActionTypeResolver resolver;

    @Inject
    ObjectMapper mapper;

    @Test
    void overlayWinsForTestRunAndCatalogOtherwise() throws Exception {
        String overlay = ActionTypeResolver.overlayJson(Map.of("draft-echo", mapper.readTree(
                "{\"name\":\"draft-echo\",\"kind\":\"script\",\"script\":\"echo hi\"}")), mapper);
        long runId = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.subjectId = TestFixtures.createSubject("resolver");
            run.isTest = true;
            run.actionOverlaySnapshot = overlay;
            run.instanceState = "{}";
            run.status = "running";
            run.startedOn = Instant.now();
            run.persist();
            return run.id;
        });
        QuarkusTransaction.requiringNew().run(() -> {
            ActionTypeEntity draft = resolver.resolve("draft-echo", runId);
            assertEquals("draft-echo", draft.name);
            assertNull(resolver.resolve("draft-echo", null));
        });
    }
}
```

Copy the draft JSON shape from an existing action-type fixture used by `DraftTestRunnerTest` (or a similar test)
so that `toEntity` accepts it.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl app -am test -Dtest=ActionTypeResolverTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package io.apitomy.axiom.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * Resolves action types by name for a run: a test run's draft overlay first, then the published catalog.
 */
@ApplicationScoped
public class ActionTypeResolver {

    @Inject
    ObjectMapper objectMapper;

    /**
     * Resolves an action type.
     *
     * @param name          the action type name
     * @param workflowRunId the run the action executes in; null for actions outside a run
     * @return the draft (transient) or catalog action type, or null if unknown
     */
    public ActionTypeEntity resolve(String name, Long workflowRunId) {
        if (workflowRunId != null) {
            WorkflowRunEntity run = WorkflowRunEntity.findById(workflowRunId);
            if (run != null && run.actionOverlaySnapshot != null) {
                JsonNode draft = readOverlay(run.actionOverlaySnapshot).get(name);
                if (draft != null) {
                    return DraftTestRunner.toEntity(draft);
                }
            }
        }
        return ActionTypeEntity.find("name", name).firstResult();
    }

    /**
     * Serializes draft action types to the overlay JSON stored on a test run.
     *
     * @param drafts draft action type JSON keyed by name
     * @param mapper the object mapper
     * @return the overlay JSON
     */
    public static String overlayJson(Map<String, JsonNode> drafts, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(drafts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readOverlay(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid action overlay snapshot", e);
        }
    }
}
```

If `DraftTestRunner` is in a different package than `io.apitomy.axiom.app`, make `toEntity` `public static`.

- [ ] **Step 4: Replace the catalog lookups**

At each listed `ActionTypeEntity.find("name", X).firstResult()` site, call `actionTypeResolver.resolve(X,
task.workflowRunId)` (or `entity.id` in `WorkflowExecutionService`), using whichever run id is in scope. If a
site has no run id, pass `null`.

- [ ] **Step 5: Run the tests**

Run: `mvn -pl app -am test -Dtest='ActionTypeResolverTest,WorkflowExecutionServiceTest,DraftTestRunner*' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "Resolve action types from a test run's draft overlay before the catalog"
```

---

### Task 4: `startTestRun` in `WorkflowExecutionService`

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/TestRunLaunch.java`
- Modify: `WorkflowExecutionService.java:148-268` (split `startRun`), `onRunTerminal` :729
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowTestRunStartTest.java`

**Interfaces:**
- Produces:

```java
/**
 * Inputs for starting a workbench test run of a draft workflow.
 *
 * @param subjectId           the ephemeral test subject
 * @param workflowJson        the draft workflow JSON
 * @param actionOverlayJson   the draft action type overlay JSON (may be null)
 * @param compositionId       the composition being tested
 * @param compositionRevision the draft workflow item's content revision
 * @param baseDefinitionId    the published definition the draft edits, or null for a new workflow
 * @param event               the trigger event; persisted (picked) or transient (fabricated); may be null
 */
public record TestRunLaunch(long subjectId, String workflowJson, String actionOverlayJson, long compositionId,
                            String compositionRevision, Long baseDefinitionId, StreamEventEntity event) {
}
```

  - `@Transactional public WorkflowRunEntity startTestRun(TestRunLaunch launch)`

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class WorkflowTestRunStartTest {

    @Inject
    WorkflowExecutionService service;

    private static final String START_END = """
        {"id": "t", "name": "Draft WF",
         "nodes": [
           {"id": "s1", "type": "start", "name": "Start", "config": {}, "position": {"x": 0, "y": 0}},
           {"id": "e1", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 100}}],
         "edges": [{"id": "x1", "source": "s1", "target": "e1", "priority": 0, "isDefault": true}]}
        """;

    @Test
    void startsDraftWithoutPublishedDefinition() {
        long subjectId = TestFixtures.createSubject("draft-start");
        long runId = QuarkusTransaction.requiringNew().call(() -> service.startTestRun(
                new TestRunLaunch(subjectId, START_END, null, 42L, "rev-a", null, null)).id);
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
            assertTrue(run.isTest);
            assertNull(run.definitionId);
            assertEquals(42L, run.compositionId);
            assertEquals("completed", run.status);
        });
    }

    @Test
    void waitingDraftResumesFromSnapshot() {
        long subjectId = TestFixtures.createSubject("draft-wait");
        String waitJson = TestFixtures.waitContent();
        long runId = QuarkusTransaction.requiringNew().call(() -> service.startTestRun(
                new TestRunLaunch(subjectId, waitJson, null, 42L, "rev-b", null, null)).id);
        service.onWaitElapsed(runId, "w1");
        QuarkusTransaction.requiringNew().run(() ->
                assertEquals("completed", WorkflowRunEntity.<WorkflowRunEntity>findById(runId).status));
    }
}
```

Add `public static String waitContent() { return WAIT_CONTENT; }` to `TestFixtures`. Check the exact signature
of `onWaitElapsed` used at `WorkflowExecutionServiceTest.java:255`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl app -am test -Dtest=WorkflowTestRunStartTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Split `startRun` into resolve + core**

Refactor `startRun` (`:148-268`) as follows:

1. Keep the subject lookup, conflict check and definition/version loading in `startRun`.
2. Move everything from `validateNodeTypes(workflow)` (:186) through `return entity` (:267) into:

```java
    /** Run attributes that differ between production and test starts. */
    private record RunAttributes(String displayName, Long definitionId, Integer definitionVersion,
                                 boolean isTest, Long compositionId, String compositionRevision,
                                 String definitionSnapshot, String actionOverlaySnapshot) {
    }

    private WorkflowRunEntity startResolvedRun(SubjectEntity subject, Workflow workflow, RunAttributes attrs,
                                               RunRequest request, EventOrigin origin,
                                               Supplier<JsonNode> eventValue, LogContext logCtx) {
        // former body from validateNodeTypes(workflow) to return entity, with these replacements:
        //   definition.name             -> attrs.displayName()
        //   entity.definitionId = ...   -> attrs.definitionId()
        //   entity.definitionVersion = ...-> attrs.definitionVersion()
        //   subjectId                   -> subject.id
        // and after setting definitionVersion:
        //   entity.isTest = attrs.isTest();
        //   entity.compositionId = attrs.compositionId();
        //   entity.compositionRevision = attrs.compositionRevision();
        //   entity.definitionSnapshot = attrs.definitionSnapshot();
        //   entity.actionOverlaySnapshot = attrs.actionOverlaySnapshot();
        // entity.persist() must run BEFORE createTasksForActiveBranches so that ActionTypeResolver can read
        // the overlay (it already does today: persist :232, tasks :249).
    }
```

3. `startRun` now ends with:

```java
        Supplier<JsonNode> eventValue = () -> {
            if (origin == null || origin.eventId() == null) {
                return null;
            }
            StreamEventEntity event = StreamEventEntity.findById(origin.eventId());
            return event != null ? StandardInputs.eventValue(event, objectMapper) : null;
        };
        return startResolvedRun(subject, workflow,
                new RunAttributes(definition.name, definitionId, definition.currentVersion,
                        false, null, null, null, null),
                request, origin, eventValue, logCtx);
```

- [ ] **Step 4: Add `startTestRun`**

```java
    /**
     * Starts a workbench test run of an unpublished draft workflow on an ephemeral test subject. The run
     * executes the draft snapshot and prefers the draft action types; steps have real side effects.
     *
     * @param launch the test run inputs
     * @return the created run
     */
    @Transactional
    public WorkflowRunEntity startTestRun(TestRunLaunch launch) {
        try (LogContext logCtx = LogContext.create().subjectId(launch.subjectId())) {
            SubjectEntity subject = SubjectEntity.findById(launch.subjectId());
            if (subject == null) {
                throw new WebApplicationException("Subject not found", 404);
            }
            Workflow workflow = deserializeWorkflow(launch.workflowJson());
            StreamEventEntity event = launch.event();
            boolean persistedEvent = event != null && StreamEventEntity.findById(event.id) != null;
            EventOrigin origin = persistedEvent ? new EventOrigin(event.id, null) : null;
            Supplier<JsonNode> eventValue = () -> event != null
                    ? StandardInputs.eventValue(event, objectMapper) : null;
            return startResolvedRun(subject, workflow,
                    new RunAttributes("[test] " + workflow.name(), launch.baseDefinitionId(), null, true,
                            launch.compositionId(), launch.compositionRevision(), launch.workflowJson(),
                            launch.actionOverlayJson()),
                    new RunRequest("Test run", List.of("test")), origin, eventValue, logCtx);
        }
    }
```

Use the same `Workflow` name accessor as in Task 2. A fabricated event is never persisted: it reaches the
run only through `eventValue`.

- [ ] **Step 5: Skip post-run hooks for test runs**

In `onRunTerminal` (:729), add `if (entity.isTest) { return; }` before `runSummaryService.enqueueIfEnabled`.

- [ ] **Step 6: Run the tests**

Run: `mvn -pl app -am test -Dtest='WorkflowTestRunStartTest,WorkflowExecutionServiceTest,WorkflowRunTitleTest,EventManualRunTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src
git commit -m "Start test runs of draft workflows through the shared run start path"
```

---

### Task 5: Isolate test runs from production paths

**Files:**
- Modify: `WorkflowEventDispatcher.java` (`planStreamDispatch` :133, `replayLookback` :231); add `offerTestEvent`
- Modify: `WorkflowRunBeanMapper.java:310-317` (search HQL), `WorkflowDashboardService.java` (:105, :126, :208,
  :265), `rest/SubjectsResourceImpl.java` (:97, :114), `SubjectEntity.findActiveSubjectIds` (:82),
  `rest/UsageResourceImpl.java:289-301`, `rest/WorkflowDefinitionsResourceImpl.java:195`,
  `EventStreamOrchestrator.java:945/:989` (subject lookup by ref)
- Modify: `common/api/src/main/resources/openapi.json` (add the `includeTest` query param to `GET /workflow/runs`)
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowTestRunIsolationTest.java`

**Interfaces:**
- Produces: `public ResumedRun offerTestEvent(long runId, Map<String, Object> eventMap)` on
  `WorkflowEventDispatcher`. It returns null when no subscription of the run matches.

- [ ] **Step 1: Write the failing tests**

```java
package io.apitomy.axiom.app;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class WorkflowTestRunIsolationTest {

    @Inject
    WorkflowExecutionService service;

    @Inject
    WorkflowEventDispatcher dispatcher;

    static final String RECEIVE = """
        {"id": "r", "name": "Receive WF",
         "nodes": [
           {"id": "s1", "type": "start", "name": "Start", "config": {}, "position": {"x": 0, "y": 0}},
           {"id": "r1", "type": "receive-event", "name": "Wait PR", "config": {"eventType": "pr.merged"},
            "position": {"x": 0, "y": 100}},
           {"id": "e1", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 200}}],
         "edges": [
           {"id": "x1", "source": "s1", "target": "r1", "priority": 0, "isDefault": true},
           {"id": "x2", "source": "r1", "target": "e1", "priority": 0, "isDefault": true}]}
        """;

    private long startTest(String name, String json) {
        long subjectId = TestFixtures.createSubject(name);
        return QuarkusTransaction.requiringNew().call(() -> service.startTestRun(
                new TestRunLaunch(subjectId, json, null, 99L, "r", null, null)).id);
    }

    @Test
    void streamEventsDoNotResumeTestRuns() {
        long runId = startTest("iso-stream", RECEIVE);
        Map<String, Object> event = new HashMap<>(Map.of("type", "pr.merged"));
        assertTrue(dispatcher.dispatchStreamEvent("pr.merged", event, null).stream()
                .noneMatch(r -> r.runId() == runId));
    }

    @Test
    void offerTestEventResumesTestRun() {
        long runId = startTest("iso-offer", RECEIVE);
        assertNotNull(dispatcher.offerTestEvent(runId, new HashMap<>(Map.of("type", "pr.merged"))));
        QuarkusTransaction.requiringNew().run(() -> assertEquals("completed",
                io.apitomy.axiom.core.entities.WorkflowRunEntity
                        .<io.apitomy.axiom.core.entities.WorkflowRunEntity>findById(runId).status));
    }

    @Test
    void runListingExcludesTestRunsByDefault() {
        long runId = startTest("iso-list", TestFixtures.waitContent());
        given().get("/api/v1/workflow/runs").then().statusCode(200)
                .body("items.id", not(hasItem((int) runId)));
        given().queryParam("includeTest", true).get("/api/v1/workflow/runs").then().statusCode(200)
                .body("items.id", hasItem((int) runId));
    }
}
```

Before running, adapt these to the real code:
- The receive-event node config: copy the type and config from an existing receive-event test fixture.
- The `dispatchStreamEvent` return type and `ResumedRun` accessor names.
- The JSON path of the runs list (`items` or the root).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -pl app -am test -Dtest=WorkflowTestRunIsolationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`offerTestEvent`), then assertion failures.

- [ ] **Step 3: Filter test-run subscriptions in the dispatcher**

Replace the query in `planStreamDispatch`:

```java
        List<WorkflowEventSubscriptionEntity> candidates = WorkflowEventSubscriptionEntity.list(
                "eventType = ?1 and runId in (select r.id from WorkflowRunEntity r where r.isTest = false)",
                eventType);
```

In `replayLookback`, return early inside the first transaction when the subscription's run is a test run:

```java
            WorkflowRunEntity run = WorkflowRunEntity.findById(sub.runId);
            if (run == null || run.isTest) {
                return List.<Object[]>of();
            }
```

Add:

```java
    /**
     * Offers a fabricated event to the receive-event subscriptions of one workbench test run.
     *
     * @param runId    the test run
     * @param eventMap the fabricated event map
     * @return the resumed run, or null if no subscription of the run matched
     */
    public ResumedRun offerTestEvent(long runId, Map<String, Object> eventMap) {
        List<Long> ids = QuarkusTransaction.requiringNew().call(() -> WorkflowEventSubscriptionEntity
                .<WorkflowEventSubscriptionEntity>list("runId", runId).stream().map(s -> s.id).toList());
        for (Long id : ids) {
            MatchedSubscription[] matched = new MatchedSubscription[1];
            ResumedRun resumed = QuarkusTransaction.requiringNew().call(
                    () -> offerToSubscription(id, eventMap, null, matched));
            if (resumed != null || matched[0] != null) {
                return resumed;
            }
        }
        return null;
    }
```

- [ ] **Step 4: Filter production listings**

- Add `and r.isTest = false` (or `isTest = false` for Panache strings) to every run and subject query listed under
  **Files**. Subject queries filter on `s.isTest = false`.
- In `EventStreamOrchestrator`, `SubjectEntity.find("ref", …)` needs no change: test refs start with `test:`, which
  real event refs never do.
- For `GET /workflow/runs`, add the boolean query parameter `includeTest` (default false) to `openapi.json`. Run
  `mvn install -pl common/api -q`, thread the value into `WorkflowRunBeanMapper.search`, and drop the filter
  when it is true.
- Do **not** filter the cleanup jobs or `SubjectDeletionService`. They must still see test data.

- [ ] **Step 5: Run the tests**

Run: `mvn -pl app -am test -Dtest='WorkflowTestRunIsolationTest,WorkflowEventDispatcher*,WorkflowDashboard*,Subjects*' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add common/api app/src
git commit -m "Keep workbench test runs out of event dispatch and production listings"
```

---

### Task 6: `WorkflowTestRunService` (composition → test run)

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/composition/WorkflowTestRunService.java`
- Modify: `EventStreamOrchestrator.java`: make `newSubjectFor(StreamEventEntity)` public.
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/WorkflowTestRunServiceTest.java`

**Interfaces:**
- Consumes: `WorkflowExecutionService.startTestRun`, `TestRunLaunch`, `ActionTypeResolver.overlayJson`,
  `WorkflowEventDispatcher.offerTestEvent`, `CompositionValidator`, `TooManyTestRunsException`.
- Produces:

```java
/** A fabricated trigger event. */
public record FabricatedEvent(String type, String ref, JsonNode data) { }

public WorkflowRunEntity start(long compositionId, UUID eventId, FabricatedEvent fabricated,
                               Map<String, Object> subjectOverrides);
public List<WorkflowRunEntity> list(long compositionId);
public WorkflowRunEntity await(long compositionId, long runId, int waitSeconds); // 404 if not this composition
public boolean sendEvent(long compositionId, long runId, FabricatedEvent event); // false if nothing matched
public void cancel(long compositionId, long runId);
```

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.TestFixtures;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class WorkflowTestRunServiceTest {

    @Inject
    WorkflowTestRunService service;

    @Inject
    ObjectMapper mapper;

    static long composition(String workflowJson) {
        return QuarkusTransaction.requiringNew().call(() -> {
            CompositionEntity c = new CompositionEntity();
            c.name = "c-" + System.nanoTime();
            c.createdOn = Instant.now();
            c.updatedOn = Instant.now();
            c.persist();
            CompositionItemEntity item = new CompositionItemEntity();
            item.compositionId = c.id;
            item.itemType = "workflow";
            item.name = "wf";
            item.content = workflowJson;
            item.contentRevision = "rev-1";
            item.lastAuthor = "user";
            item.createdOn = Instant.now();
            item.updatedOn = Instant.now();
            item.persist();
            return c.id;
        });
    }

    @Test
    void fabricatedEventStartsTestRunOnEphemeralSubject() throws Exception {
        long compId = composition(TestFixtures.waitContent());
        WorkflowRunEntity run = service.start(compId, null, new WorkflowTestRunService.FabricatedEvent(
                "issue.created", "https://github.com/o/r/issues/1", mapper.readTree("{\"title\":\"x\"}")),
                Map.of("name", "Override"));
        QuarkusTransaction.requiringNew().run(() -> {
            SubjectEntity subject = SubjectEntity.findById(run.subjectId);
            assertTrue(subject.isTest);
            assertTrue(subject.ref.startsWith(SubjectEntity.TEST_REF_PREFIX));
            assertEquals("Override", subject.name);
            assertTrue(subject.expiresAt.isAfter(Instant.now()));
        });
        assertEquals("rev-1", run.compositionRevision);
    }

    @Test
    void enforcesConcurrencyLimit() throws Exception {
        long compId = composition(TestFixtures.waitContent());
        WorkflowTestRunService.FabricatedEvent ev = new WorkflowTestRunService.FabricatedEvent(
                "issue.created", "https://github.com/o/r/issues/2", mapper.readTree("{}"));
        service.start(compId, null, ev, Map.of());
        service.start(compId, null, ev, Map.of());
        assertThrows(TooManyTestRunsException.class, () -> service.start(compId, null, ev, Map.of()));
    }

    @Test
    void awaitReturnsWithinWaitWhenRunIsUnchanged() throws Exception {
        long compId = composition(TestFixtures.waitContent());
        WorkflowRunEntity run = service.start(compId, null, new WorkflowTestRunService.FabricatedEvent(
                "issue.created", "https://github.com/o/r/issues/3", mapper.readTree("{}")), Map.of());
        long t0 = System.nanoTime();
        assertEquals("waiting", service.await(compId, run.id, 1).status);
        assertTrue(System.nanoTime() - t0 >= 900_000_000L);
    }
}
```

Use the `TooManyTestRunsException` constructor and package already present in `composition/`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl app -am test -Dtest=WorkflowTestRunServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Implement the service**

The `start` method does the following:

1. Load the composition (404 if missing). Run `CompositionValidator` on it and, if it reports errors, throw
   `WebApplicationException` with status 400 and body `{"message": ..., "problems": [...]}`. Use the same
   response shape that `CompositionsResourceImpl` already returns for validation failures.
2. Find the composition's `workflow` item (400 "Composition has no workflow" if there is none). Collect the
   `action-type` items into `Map<String, JsonNode>` keyed by `name`.
3. Count the active test runs:
   `WorkflowRunEntity.count("compositionId = ?1 and isTest = true and status in ?2", id, List.of("running",
   "waiting"))`. If the count is `>= 2`, throw `TooManyTestRunsException`.
4. Build the event:
   - If `eventId` is set, use `StreamEventEntity.findById(eventId)`, or return 404 if it doesn't exist.
   - Otherwise, use a transient `StreamEventEntity`:
     - `id = UUID.randomUUID()`
     - `type` and `ref` from the request
     - `source = "test"`, `eventSourceId = "workbench-test"`, `sourceEventId = id.toString()`
     - `timestamp = Instant.now()`
     - `actor = "{\"login\":\"workbench\"}"`
     - `payload = data.toString()`
   - If neither is given, return 400.
5. Create the subject in `QuarkusTransaction.requiringNew()`:
   - Start from `orchestrator.newSubjectFor(event)`.
   - Set `ref = TEST_REF_PREFIX + UUID.randomUUID()`, `isTest = true` and
     `expiresAt = Instant.now().plus(retention)`.
   - Apply the `name`, `type`, `body` and `repository` keys from `subjectOverrides`.
   - Persist it, then call `workspaceService.ensureWorkspace(subject)`.
6. Call `workflowExecutionService.startTestRun(new TestRunLaunch(subject.id, item.content,
   overlayJsonOrNull, compositionId, item.contentRevision, item.baseItemId, event))`.

Read the retention with `@ConfigProperty(name = "axiom.workflow-test-runs.retention", defaultValue = "24h")
Duration retention`.

`await` polls every 250 ms in a fresh transaction. It returns once the status differs from the status at entry,
once `completedOn != null`, or when `min(max(wait, 0), 30)` seconds have passed.

`sendEvent`:
- Builds the event map from the fabricated event:
  `Map.of("type", type, "ref", ref, "payload", mapper.convertValue(data, Map.class))`. Match the key names
  produced by `WorkflowEventMapper.toEventMap`.
- Calls `dispatcher.offerTestEvent(runId, map)` and returns `true` if a subscription matched.

`cancel` delegates to `workflowExecutionService.cancelWorkflowRun(runId)`. All five methods first check that the
run's `compositionId` and `isTest` match, and return 404 otherwise.

- [ ] **Step 4: Run the tests**

Run: `mvn -pl app -am test -Dtest=WorkflowTestRunServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "Add service to start and monitor test runs of composition drafts"
```

---

### Task 7: REST API for workflow test runs

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/composition/WorkflowTestRunViews.java` (entity → bean mapping)
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/WorkflowTestRunsResourceTest.java`

**Interfaces:**
- Produces these schemas, all with tag `Compositions`:
  - `WorkflowTestRunRequest` `{ eventId?: uuid, event?: FabricatedEvent, subjectOverrides?: object }`
  - `FabricatedEvent` `{ type: string (required), ref: string (required), data: object }`
  - `WorkflowTestRun`:
    - `{ id: int64, compositionId: int64, compositionRevision, status, subjectId, startedOn, completedOn,
      currentNodeId, failureReason, traceId: uuid, steps: WorkflowTestRunStep[], waitingFor?: string }`
    - `WorkflowTestRunStep` `{ taskId, nodeId, name, status, output (string, truncated), error, logTail }`
- Operations:

| operationId | Method + path | Response |
|---|---|---|
| `startWorkflowTestRun` | POST `/compositions/{compositionId}/workflow-test-runs` | 201 `WorkflowTestRun`; 400/404/429 |
| `listWorkflowTestRuns` | GET same | 200 `WorkflowTestRun[]` |
| `getWorkflowTestRun` | GET `/compositions/{compositionId}/workflow-test-runs/{runId}?wait=int` | 200; 404 |
| `sendWorkflowTestRunEvent` | POST `.../{runId}/events` body `FabricatedEvent` | 204; 404; 409 if nothing matched |
| `cancelWorkflowTestRun` | POST `.../{runId}/cancel` | 204; 404 |

- [ ] **Step 1: Add the schemas and operations to `openapi.json`**

Follow the structure of `/compositions/{compositionId}/test-runs`. Then run `mvn install -pl common/api -q`.
Expected: `CompositionsResource` gains the five methods.

- [ ] **Step 2: Write the failing REST test**

```java
package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.app.TestFixtures;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class WorkflowTestRunsResourceTest {

    @Test
    void startGetCancel() {
        long compId = WorkflowTestRunServiceTest.composition(TestFixtures.waitContent());
        String base = "/api/v1/compositions/" + compId + "/workflow-test-runs";
        int runId = given().contentType("application/json")
                .body("{\"event\":{\"type\":\"issue.created\",\"ref\":\"https://github.com/o/r/issues/9\","
                        + "\"data\":{}}}")
                .post(base).then().statusCode(201).body("status", equalTo("waiting"))
                .extract().path("id");
        given().get(base + "/" + runId + "?wait=0").then().statusCode(200)
                .body("traceId", notNullValue());
        given().post(base + "/" + runId + "/cancel").then().statusCode(204);
        given().get(base + "/" + runId).then().statusCode(200).body("status", equalTo("cancelled"));
    }

    @Test
    void missingTriggerIs400() {
        long compId = WorkflowTestRunServiceTest.composition(TestFixtures.waitContent());
        given().contentType("application/json").body("{}")
                .post("/api/v1/compositions/" + compId + "/workflow-test-runs").then().statusCode(400);
    }
}
```

If `cancelWorkflowRun` produces a terminal status other than `"cancelled"`, assert that status instead.

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -pl app -am test -Dtest=WorkflowTestRunsResourceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL (the impl doesn't compile until the methods exist).

- [ ] **Step 4: Implement the endpoints**

- In `CompositionsResourceImpl`, implement the five generated methods by delegating to
  `WorkflowTestRunService` and mapping results with `WorkflowTestRunViews.toBean(run)`.
- Mark `getWorkflowTestRun` `@Blocking` if the class is reactive; otherwise it is already on a worker thread.
- `WorkflowTestRunViews.toBean`:
  - Loads `TaskEntity` rows `where workflowRunId = run.id` ordered by id.
  - Fills `steps` with the output and log tail truncated to 4000 characters, redacted with `TestRunRedactor`.
  - For a waiting run, sets `waitingFor` from the first `WorkflowEventSubscriptionEntity` of the run (`"event
    <type> at node <nodeId>"`) or from the current node.
- The existing `@ServerExceptionMapper` already maps `TooManyTestRunsException` to 429.

- [ ] **Step 5: Run the tests**

Run: `mvn -pl app -am test -Dtest='WorkflowTestRunsResourceTest,CompositionTestRunsResourceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add common/api app/src
git commit -m "Expose workflow test run endpoints on compositions"
```

---

### Task 8: `WorkflowTestRunReaper` (wait timeout and retention cleanup)

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/composition/WorkflowTestRunReaper.java`
- Modify: `app/src/main/resources/application.properties` (defaults; `%test` interval off)
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/WorkflowTestRunReaperTest.java`

**Interfaces:**
- Produces:
  - `void reap()`, a public method for tests.
  - Scheduled with `@Scheduled(every = "${axiom.workflow-test-runs.reap-interval:15m}",
    concurrentExecution = SKIP)`.

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.TestFixtures;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class WorkflowTestRunReaperTest {

    @Inject
    WorkflowTestRunService service;

    @Inject
    WorkflowTestRunReaper reaper;

    @Inject
    ObjectMapper mapper;

    @Test
    void cancelsStaleWaitsAndDeletesExpiredSubjects() throws Exception {
        long compId = WorkflowTestRunServiceTest.composition(TestFixtures.waitContent());
        WorkflowRunEntity run = service.start(compId, null, new WorkflowTestRunService.FabricatedEvent(
                "issue.created", "https://github.com/o/r/issues/5", mapper.readTree("{}")), Map.of());
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunEntity r = WorkflowRunEntity.findById(run.id);
            r.startedOn = Instant.now().minusSeconds(7200);
        });
        reaper.reap();
        QuarkusTransaction.requiringNew().run(() -> assertNotEquals("waiting",
                WorkflowRunEntity.<WorkflowRunEntity>findById(run.id).status));

        QuarkusTransaction.requiringNew().run(() -> {
            SubjectEntity s = SubjectEntity.findById(run.subjectId);
            s.expiresAt = Instant.now().minusSeconds(1);
        });
        reaper.reap();
        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(SubjectEntity.findById(run.subjectId));
            assertNull(WorkflowRunEntity.findById(run.id));
        });
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl app -am test -Dtest=WorkflowTestRunReaperTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.app.SubjectDeletionService;
import io.apitomy.axiom.app.WorkflowExecutionService;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Cancels workbench test runs that have waited longer than the wait timeout, and deletes expired test
 * subjects together with their runs, tasks, traces and workspaces.
 */
@ApplicationScoped
public class WorkflowTestRunReaper {

    private static final Logger LOG = Logger.getLogger(WorkflowTestRunReaper.class);

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    SubjectDeletionService subjectDeletionService;

    @ConfigProperty(name = "axiom.workflow-test-runs.wait-timeout", defaultValue = "1h")
    Duration waitTimeout;

    @Scheduled(every = "${axiom.workflow-test-runs.reap-interval:15m}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledReap() {
        reap();
    }

    /**
     * Runs one reap pass.
     */
    public void reap() {
        Instant cutoff = Instant.now().minus(waitTimeout);
        List<Long> stale = QuarkusTransaction.requiringNew().call(() -> WorkflowRunEntity
                .<WorkflowRunEntity>list("isTest = true and status = 'waiting' and startedOn < ?1", cutoff)
                .stream().map(r -> r.id).toList());
        for (Long runId : stale) {
            try {
                workflowExecutionService.cancelWorkflowRun(runId);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to cancel stale test run %d", runId);
            }
        }
        List<Long> expired = QuarkusTransaction.requiringNew().call(() -> SubjectEntity
                .<SubjectEntity>list("isTest = true and expiresAt < ?1", Instant.now())
                .stream().map(s -> s.id).toList());
        for (Long subjectId : expired) {
            try {
                subjectDeletionService.delete(subjectId);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to delete expired test subject %d", subjectId);
            }
        }
    }
}
```

Use the actual public deletion method of `SubjectDeletionService` (around :46). If it doesn't delete the
subject's runs, delete them first with `WorkflowRunEntity.delete("subjectId", subjectId)`. Use the actual
signature of `cancelWorkflowRun`.

- [ ] **Step 4: Add the configuration**

In `application.properties`:

```properties
axiom.workflow-test-runs.retention=24h
axiom.workflow-test-runs.wait-timeout=1h
axiom.workflow-test-runs.reap-interval=15m
%test.axiom.workflow-test-runs.reap-interval=off
```

- [ ] **Step 5: Run the tests**

Run: `mvn -pl app -am test -Dtest=WorkflowTestRunReaperTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "Reap stale and expired workbench test runs"
```

---

### Task 9: Workbench assistant tools

**Files:**
- Modify: `app/src/main/resources/templates/axiom-workbench-mcp/server.js`
- Modify: `app/src/main/resources/templates/assistant-templates/axiom-workbench.json`
- Modify: `CompositionsResourceImpl` / `openapi.json` only if the agent routes need the `/agent/` prefix (see
  Step 1)
- Test: `app/src/test/node/workbench-mcp.test.js`

**Interfaces:**
- Consumes the REST endpoints from Task 7.
- Produces these tools: `start_workflow_test_run`, `get_workflow_test_run`, `list_workflow_test_runs`,
  `send_test_event`, `cancel_workflow_test_run`.

- [ ] **Step 1: Decide on the agent route prefix**

The existing agent tools call `${agentBase()}` = `/compositions/{id}/agent/...`. Check how
`CompositionsResourceImpl` authenticates `/agent/` routes with `AXIOM_SESSION_TOKEN`. If the agent token is
only accepted under `/agent/`, add `/compositions/{compositionId}/agent/workflow-test-runs[...]` mirrors to
`openapi.json`, delegating to the same service methods, with a matching test in
`WorkflowTestRunsResourceTest`. Otherwise call the plain paths through `compBase()`.

- [ ] **Step 2: Write the failing node tests**

Add cases to `workbench-mcp.test.js` that follow the existing pattern for `get_test_run` (stubbed `axiomApi`):

```js
test("start_workflow_test_run posts a fabricated event and warns about side effects", async () => {
    const calls = stubApi({ POST: JSON.stringify({ id: 5, status: "running", steps: [] }) });
    const out = await callTool("start_workflow_test_run",
        { event: { type: "issue.created", ref: "https://github.com/o/r/issues/1", data: {} } });
    expect(calls[0].method).toBe("POST");
    expect(calls[0].path).toMatch(/workflow-test-runs$/);
    expect(out).toMatch(/real side effects/i);
    expect(out).toMatch(/run 5/i);
});

test("get_workflow_test_run passes the clamped wait", async () => {
    const calls = stubApi({ GET: JSON.stringify({ id: 5, status: "completed", steps: [] }) });
    await callTool("get_workflow_test_run", { runId: "5", wait: 99 });
    expect(calls[0].path).toMatch(/workflow-test-runs\/5\?wait=30$/);
});
```

Replace `stubApi` and `callTool` with the helpers this test file already uses.

- [ ] **Step 3: Run the tests to verify they fail**

Run (in `app/`): `node --test src/test/node/workbench-mcp.test.js` (or the command used by
`WorkbenchMcpServerScriptTest`)
Expected: FAIL (unknown tool).

- [ ] **Step 4: Implement the tools**

Add to `TOOLS` in `server.js`:

```js
{ name: "start_workflow_test_run",
  description: "Start a test run of the DRAFT workflow on a fresh ephemeral test subject. Trigger with an "
      + "existing stream event (eventId) or a fabricated event {type, ref, data}. WARNING: steps execute "
      + "for real (scripts, agents, external APIs). Tell the user what will happen before starting.",
  parameters: [
      { name: "eventId", type: "string", description: "Existing stream event id", required: false },
      { name: "event", type: "object", description: "Fabricated event {type, ref, data}", required: false },
      { name: "subjectOverrides", type: "object", description: "Subject fields to override", required: false }],
  handler: async (args) => {
      const run = JSON.parse(await axiomApi("POST", `${testRunBase()}`, {
          eventId: args.eventId, event: args.event, subjectOverrides: args.subjectOverrides }));
      return `Started test run ${run.id} (real side effects). Poll with get_workflow_test_run.\n`
          + formatWorkflowTestRun(run); } },
{ name: "get_workflow_test_run",
  description: "Get a draft workflow test run: status, steps, outputs, errors, log tails and what it waits "
      + "for. 'wait' (0-30s) blocks until the status changes. Read-only.",
  parameters: [
      { name: "runId", type: "string", description: "Test run id", required: true },
      { name: "wait", type: "number", description: "Seconds to wait for a change (default 20)", required: false }],
  handler: async (args) => {
      const wait = Math.max(0, Math.min(30, args.wait ?? 20));
      const run = await axiomApi("GET",
          `${testRunBase()}/${encodeURIComponent(args.runId)}?wait=${wait}`, undefined, { allow404: true });
      return run === null ? `Test run '${args.runId}' not found.` : formatWorkflowTestRun(JSON.parse(run)); } },
{ name: "list_workflow_test_runs", description: "List recent draft workflow test runs. Read-only.",
  parameters: [],
  handler: async () => JSON.parse(await axiomApi("GET", testRunBase()))
      .map(r => `#${r.id} ${r.status} rev ${r.compositionRevision} started ${r.startedOn}`).join("\n")
      || "No test runs." },
{ name: "send_test_event",
  description: "Send a fabricated event {type, ref, data} to a waiting test run to resume it.",
  parameters: [
      { name: "runId", type: "string", description: "Test run id", required: true },
      { name: "event", type: "object", description: "Fabricated event {type, ref, data}", required: true }],
  handler: async (args) => {
      await axiomApi("POST", `${testRunBase()}/${encodeURIComponent(args.runId)}/events`, args.event);
      return `Event delivered to test run ${args.runId}.`; } },
{ name: "cancel_workflow_test_run", description: "Cancel a draft workflow test run.",
  parameters: [{ name: "runId", type: "string", description: "Test run id", required: true }],
  handler: async (args) => {
      await axiomApi("POST", `${testRunBase()}/${encodeURIComponent(args.runId)}/cancel`);
      return `Test run ${args.runId} cancelled.`; } },
```

Add these helpers next to `agentBase()`:

```js
function testRunBase() {
    return `${agentBase()}/workflow-test-runs`; // or the plain composition path, per Step 1
}

function formatWorkflowTestRun(run) {
    const lines = [`Run ${run.id}: ${run.status}`
        + (run.failureReason ? ` — ${run.failureReason}` : "")
        + (run.waitingFor ? ` (waiting for ${run.waitingFor})` : "")];
    for (const s of run.steps ?? []) {
        lines.push(`- ${s.name ?? s.nodeId}: ${s.status}`);
        if (s.error) lines.push(`  error: ${s.error}`);
        if (s.output) lines.push(`  output: ${s.output}`);
        if (s.logTail) lines.push(`  log: ${s.logTail}`);
    }
    if (run.traceId) lines.push(`Trace: ${run.traceId} (use get_trace for detail)`);
    return lines.join("\n");
}
```

- [ ] **Step 5: Update the session template**

In `axiom-workbench.json`:
- Add `"mcp__workbench__start_workflow_test_run"`, `"mcp__workbench__get_workflow_test_run"`,
  `"mcp__workbench__list_workflow_test_runs"`, `"mcp__workbench__send_test_event"` and
  `"mcp__workbench__cancel_workflow_test_run"` to the allowed tools.
- Append this paragraph to `systemPrompt`:

```
Testing the draft workflow: validate_composition first. Choose a trigger with list_stream_events or fabricate
one. Before start_workflow_test_run, tell the user that steps run for real. Poll get_workflow_test_run (wait=20)
until completed, failed or cancelled; if it waits for an event, send_test_event. Diagnose failures from step
errors, logs and get_trace, then propose fixes with propose_workflow_changes and re-run.
```

- [ ] **Step 6: Run the tests**

Run: `node --test src/test/node/workbench-mcp.test.js` and `mvn -pl app -am test -Dtest=WorkbenchMcpServerScriptTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src
git commit -m "Add workbench assistant tools to run and monitor draft workflow test runs"
```

---

### Task 10: Workbench UI

**Files:**
- Modify: `ui/src/config/compositions.ts` (API client)
- Create: `ui/src/pages/workbench/WorkflowTestRunDialog.tsx`, `ui/src/pages/workbench/WorkflowTestRunsPanel.tsx`,
  `ui/src/pages/workbench/useWorkflowTestRuns.ts`
- Modify: `ui/src/pages/workbench/WorkbenchPage.tsx` (header button :478-489; new bottom tab :620-645)
- Test: `ui/src/pages/workbench/WorkflowTestRunDialog.test.tsx`, `useWorkflowTestRuns.test.ts`

**Interfaces:**
- Produces in `compositions.ts`:

```ts
export interface FabricatedEvent { type: string; ref: string; data: Record<string, unknown>; }
export interface WorkflowTestRunStep {
    taskId?: number; nodeId: string; name?: string; status: string;
    output?: string; error?: string; logTail?: string;
}
export interface WorkflowTestRun {
    id: number; compositionId: number; compositionRevision: string; status: string; subjectId: number;
    startedOn: string; completedOn?: string; currentNodeId?: string; failureReason?: string;
    traceId?: string; steps: WorkflowTestRunStep[]; waitingFor?: string;
}
export function startWorkflowTestRun(compositionId: number, body: {
    eventId?: string; event?: FabricatedEvent; subjectOverrides?: Record<string, unknown>;
}): Promise<WorkflowTestRun>;
export function listWorkflowTestRuns(compositionId: number): Promise<WorkflowTestRun[]>;
export function sendWorkflowTestRunEvent(compositionId: number, runId: number, e: FabricatedEvent): Promise<void>;
export function cancelWorkflowTestRun(compositionId: number, runId: number): Promise<void>;
```

- [ ] **Step 1: Write the failing tests**

```tsx
import { render, screen, fireEvent } from "@testing-library/react";
import { vi, test, expect } from "vitest";
import { WorkflowTestRunDialog } from "./WorkflowTestRunDialog";
import * as api from "../../config/compositions";

test("fabricated event is submitted and the side-effect warning is shown", async () => {
    const start = vi.spyOn(api, "startWorkflowTestRun").mockResolvedValue({ id: 1 } as api.WorkflowTestRun);
    const onStarted = vi.fn();
    render(<WorkflowTestRunDialog compositionId={3} isOpen onClose={() => {}} onStarted={onStarted} />);
    expect(screen.getByText(/steps run for real/i)).toBeTruthy();
    fireEvent.click(screen.getByLabelText("Fabricate"));
    fireEvent.change(screen.getByLabelText("Event type"), { target: { value: "issue.created" } });
    fireEvent.change(screen.getByLabelText("Ref"), { target: { value: "https://x/1" } });
    fireEvent.click(screen.getByRole("button", { name: "Start test run" }));
    await vi.waitFor(() => expect(onStarted).toHaveBeenCalledWith(1));
    expect(start).toHaveBeenCalledWith(3, expect.objectContaining({
        event: { type: "issue.created", ref: "https://x/1", data: {} } }));
});
```

```ts
import { renderHook, waitFor } from "@testing-library/react";
import { vi, test, expect } from "vitest";
import { useWorkflowTestRuns } from "./useWorkflowTestRuns";
import * as api from "../../config/compositions";

test("loads test runs for the composition", async () => {
    vi.spyOn(api, "listWorkflowTestRuns").mockResolvedValue([{ id: 7, status: "running" } as api.WorkflowTestRun]);
    const { result } = renderHook(() => useWorkflowTestRuns(3));
    await waitFor(() => expect(result.current.runs[0]?.id).toBe(7));
});
```

Mirror the structure of the existing tests in `ui/src/pages/workbench/*.test.tsx`, including any provider
wrappers.

- [ ] **Step 2: Run the tests to verify they fail**

Run (in `ui/`): `npx vitest run src/pages/workbench/WorkflowTestRunDialog.test.tsx src/pages/workbench/useWorkflowTestRuns.test.ts`
Expected: FAIL (modules are missing).

- [ ] **Step 3: Implement the API client and hook**

- Add the functions to `compositions.ts` using that file's existing fetch helper.
- `useWorkflowTestRuns(compositionId)`:
  - Returns `{ runs, reload }`.
  - Loads on mount.
  - Reloads on `sseClient.subscribe(e => e.type === "workflow-updated" && reload())` (match the event-name check
    in `useWorkflowRuns.ts:67`) and on `sseClient.onReconnect`.

- [ ] **Step 4: Implement the dialog and the panel**

- `WorkflowTestRunDialog` (PatternFly `Modal`):
  - A radio pair labeled **From event stream** / **Fabricate**.
  - For a stream event, an event picker populated from the stream-event API call that `api.ts` already has.
  - For a fabricated event, `TextInput` fields labeled "Event type" and "Ref", plus a JSON `TextArea` for "Data".
  - An optional JSON `TextArea` for "Subject overrides".
  - An `Alert variant="warning"` with the title "Steps run for real, including scripts, agents and external API
    calls."
  - Primary button **Start test run**. On error, show the response message in an inline alert.
- `WorkflowTestRunsPanel`:
  - Shows a table of runs (id, status label, revision, started).
  - Clicking a row opens `RunDetailDialog` with the run id. Reuse its existing props; if it needs a definition,
    pass the snapshot via the run detail API.
  - A waiting run shows a **Send event** button that opens the dialog in fabricate-only mode and calls
    `sendWorkflowTestRunEvent`.
  - A running or waiting run shows **Cancel**.
- In `WorkbenchPage.tsx`:
  - Add a **Test run** button next to the existing header buttons. It opens the dialog, and on start it
    switches to the new **Test runs** bottom tab.

- [ ] **Step 5: Run the tests and the build**

Run (in `ui/`): `npx vitest run src/pages/workbench` and `npm run build`
Expected: PASS, and the build succeeds.

- [ ] **Step 6: Commit**

```bash
git add ui/src
git commit -m "Add workbench UI to start and follow draft workflow test runs"
```

---

### Task 11: End-to-end verification on an isolated instance

**Files:** none (verification only)

- [ ] **Step 1: Run the full build**

Run: `mvn clean install -q` and (in `ui/`) `npm test`
Expected: success.

- [ ] **Step 2: Start the isolated instance**

Run: `AXIOM_HOME=/tmp/axiom-test/home ./dev.sh --portOffset=500 --dataDir=/tmp/axiom-test/data`
Expected: the backend is up on port 9590.

- [ ] **Step 3: Check the UI manually**

- Create a composition with a workflow containing a receive-event step.
- Start a test run from a fabricated event and check that the run appears in the **Test runs** tab, waits, and
  completes after **Send event**.
- Check that the run does not appear on the main Runs page and that its subject does not appear on the Subjects
  page.

- [ ] **Step 4: Check the assistant**

Ask the workbench assistant to test the workflow. Confirm that it warns about side effects, calls
`start_workflow_test_run`, polls `get_workflow_test_run` until the run finishes, and reports the result.

- [ ] **Step 5: Stop the instance**

Stop the instance and delete `/tmp/axiom-test`.
