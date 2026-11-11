# Workflow Run AI Summaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Generate optional AI summaries (headline + markdown body) of finished workflow runs, opt-in per
workflow, with an on-demand button for any terminal run.

**Architecture:** A `workflow_run_summary` table (FK `ON DELETE CASCADE` to `workflow_run`) acts as a queue.
A hook at run termination enqueues rows for opted-in workflows; a `@Scheduled` poller claims rows, leases an
agent, gives it a lean digest plus a stateless read-only MCP server (`axiom-run-summary`), parses
`{headline, body}` JSON, stores it, records AI usage, and fires SSE after commit.

**Tech Stack:** Java 21 / Quarkus / Hibernate Panache / Flyway, OpenAPI contract-first (generated JAX-RS
interfaces + beans), Node MCP SDK (stdio), React 19 + PatternFly 6, vitest, JUnit 5 + RestAssured + Mockito.

**Spec:** `docs/superpowers/specs/2026-11-10-workflow-run-ai-summary-design.md` (issue #672)

## Global Constraints

- Contract-first: every REST change starts in `common/api/src/main/resources/openapi.json`; regenerate with
  `mvn -q -pl common/api install`; impl classes implement generated interfaces, never add `@Path`.
- Summary generation is OFF by default per workflow (`summarize_runs boolean not null default false`).
- Deleting a run deletes its summary at the DB level (`on delete cascade` + `@OnDelete(CASCADE)`); no
  application code deletes summaries.
- New schema name is `WorkflowRunAiSummary`; bean property `aiSummary`. Never reuse `WorkflowRunSummary`.
- Endpoint: `POST /workflow/runs/{runId}/ai-summary` (`generateWorkflowRunAiSummary`), 202/404/409/503.
- MCP server name `axiom-run-summary`; tools `get_run`, `get_task`, `get_task_log`, `get_trace`, GET-only.
- Config: `axiom.run-summary.enabled=true`, `.poll-interval=5s` (`%test` = `off`), `.batch-size=2`,
  `.max-steps=10`, `.timeout-seconds=300`. Max 2 attempts.
- Status values: `pending`, `generating`, `ready`, `failed`; trigger values `auto`, `manual`.
- Java style: Javadoc on public methods, 4-space indent, explicit types (no ambiguous `var`), JUnit 5.
- Single backend test: `mvn -q -pl app -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`.
- UI tests: `npm test -- <file>` in `ui/`.
- Live testing only on an isolated instance:
  `AXIOM_HOME=/tmp/axiom-test/home ./dev.sh --portOffset=500 --dataDir=/tmp/axiom-test/data` (port 9590).
- Commit messages end with `(#672)`; no AI attribution.

## File Map

| File | Responsibility |
|---|---|
| `app/src/main/resources/db/migration/V12__workflow_run_summary.sql` | table, cascade FK, new columns |
| `core/.../entities/WorkflowRunSummaryEntity.java` | summary row entity (create) |
| `core/.../entities/WorkflowDefinitionEntity.java` | `summarizeRuns` column (modify) |
| `core/.../entities/SystemConfigEntity.java` | `summaryEngine`, `summaryModel` (modify) |
| `core/.../events/RunSummaryChanged.java` | CDI record event (create) |
| `app/.../summary/RunSummaryResponseParser.java` | lenient JSON parsing (create) |
| `app/.../summary/RunSummaryPromptBuilder.java` | digest + instructions (create) |
| `app/.../summary/RunSummaryService.java` | enqueue, request, generate (create) |
| `app/.../summary/RunSummaryPoller.java` | schedule, claim, startup recovery (create) |
| `app/.../summary/RunSummarySseNotifier.java` | AFTER_SUCCESS SSE (create) |
| `app/.../summary/RunSummaryMcp.java` | install template, build server definitions (create) |
| `app/src/main/resources/templates/axiom-run-summary-mcp/{package.json,server.js}` | MCP server (create) |
| `app/.../WorkflowExecutionService.java` | `onRunTerminal` hook (modify) |
| `app/.../WorkflowRunBeanMapper.java` | `aiSummary` mapping (modify) |
| `app/.../rest/WorkflowDefinitionsResourceImpl.java` | endpoint + `summarizeRuns` (modify) |
| `app/.../rest/SystemResourceImpl.java` | summary settings (modify) |
| `app/.../McpConfigGenerator.java`, `agents/opencode/.../OpenCodeMcpManager.java` | OpenCode wiring (modify) |
| `ui/src/config/api.ts` | types + `generateWorkflowRunAiSummary` (modify) |
| `ui/src/components/RunAiSummaryCard.tsx` | detail card (create) |
| `ui/src/pages/{WorkflowRunsPage,WorkflowRunDetailPage,EngineSettingsPage,WorkflowDefinitionDetailPage}.tsx`, `ui/src/pages/workbench/RunsList.tsx` | UI wiring (modify) |

`app/...` = `app/src/main/java/io/apitomy/axiom/app`; `core/...` = `core/src/main/java/io/apitomy/axiom/core`.

---

### Task 1: Schema, entities, and DB cascade

**Files:**
- Create: `app/src/main/resources/db/migration/V12__workflow_run_summary.sql`
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/WorkflowRunSummaryEntity.java`
- Modify: `core/.../entities/WorkflowDefinitionEntity.java`, `core/.../entities/SystemConfigEntity.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/summary/RunSummaryCascadeTest.java`

**Interfaces:**
- Produces: `WorkflowRunSummaryEntity` (fields `runId`, `run`, `status`, `headline`, `body`, `error`, `engine`,
  `model`, `triggerType`, `attempts`, `createdOn`, `completedOn`; constants `PENDING`, `GENERATING`, `READY`,
  `FAILED`, `AUTO`, `MANUAL`); `WorkflowDefinitionEntity.summarizeRuns` (boolean);
  `SystemConfigEntity.summaryEngine`, `summaryModel` (String, nullable).

- [ ] **Step 1: Write the failing cascade test**

```java
package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunSummaryEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies that deleting runs in bulk removes their summaries through the DB cascade. */
@QuarkusTest
class RunSummaryCascadeTest {

    @Test
    void bulkDeleteOfRunRemovesSummary() {
        Long runId = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.title = "Cascade";
            run.subjectId = -1L;
            run.definitionId = -1L;
            run.definitionVersion = 1;
            run.instanceState = "{}";
            run.status = "completed";
            run.startedOn = Instant.now();
            run.completedOn = Instant.now();
            run.persist();
            WorkflowRunSummaryEntity summary = WorkflowRunSummaryEntity.newPending(run, WorkflowRunSummaryEntity.AUTO);
            summary.persist();
            return run.id;
        });
        QuarkusTransaction.requiringNew().run(() -> WorkflowRunEntity.delete("id in ?1", java.util.List.of(runId)));
        long remaining = QuarkusTransaction.requiringNew()
                .call(() -> WorkflowRunSummaryEntity.count("runId", runId));
        assertEquals(0L, remaining);
    }
}
```

- [ ] **Step 2: Run it; expect compile failure** (`WorkflowRunSummaryEntity` missing).

Run: `mvn -q -pl app -am test -Dtest=RunSummaryCascadeTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Write the migration**

```sql
-- Axiom 3.x incremental migration 12.
--
-- AI-generated workflow run summaries (#672): per-workflow opt-in, global engine/model, and a summary row
-- per run that is deleted with the run by the database.
alter table workflow_definition add column summarize_runs boolean default false not null;

alter table system_config add column summary_engine varchar(255);
alter table system_config add column summary_model varchar(255);

create table workflow_run_summary (
    run_id bigint not null,
    status varchar(20) not null,
    headline varchar(200),
    body TEXT,
    error TEXT,
    engine varchar(255),
    model varchar(255),
    trigger_type varchar(10) not null,
    attempts integer not null,
    created_on timestamp(6) with time zone not null,
    completed_on timestamp(6) with time zone,
    primary key (run_id)
);

alter table if exists workflow_run_summary
    add constraint fk_workflow_run_summary_run
    foreign key (run_id)
    references workflow_run on delete cascade;

create index idx_workflow_run_summary_status on workflow_run_summary (status);
```

- [ ] **Step 4: Create the entity**

```java
package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * The AI-generated summary of one workflow run, also used as the generation queue. The row shares the run's
 * id and is deleted by the database when the run is deleted.
 */
@Entity
@Table(name = "workflow_run_summary",
        indexes = @Index(name = "idx_workflow_run_summary_status", columnList = "status"))
public class WorkflowRunSummaryEntity extends PanacheEntityBase {

    public static final String PENDING = "pending";
    public static final String GENERATING = "generating";
    public static final String READY = "ready";
    public static final String FAILED = "failed";
    public static final String AUTO = "auto";
    public static final String MANUAL = "manual";

    @Id
    @Column(name = "run_id")
    public Long runId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", foreignKey = @jakarta.persistence.ForeignKey(name = "fk_workflow_run_summary_run"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    public WorkflowRunEntity run;

    @Column(length = 20, nullable = false)
    public String status;

    @Column(length = 200)
    public String headline;

    @Column(columnDefinition = "TEXT")
    public String body;

    @Column(columnDefinition = "TEXT")
    public String error;

    public String engine;

    public String model;

    @Column(name = "trigger_type", length = 10, nullable = false)
    public String triggerType;

    @Column(nullable = false)
    public int attempts;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "completed_on")
    public Instant completedOn;

    /**
     * Creates an unsaved pending summary for a run.
     *
     * @param run the run
     * @param triggerType {@link #AUTO} or {@link #MANUAL}
     * @return the new entity
     */
    public static WorkflowRunSummaryEntity newPending(WorkflowRunEntity run, String triggerType) {
        WorkflowRunSummaryEntity entity = new WorkflowRunSummaryEntity();
        entity.run = run;
        entity.status = PENDING;
        entity.triggerType = triggerType;
        entity.attempts = 0;
        entity.createdOn = Instant.now();
        return entity;
    }
}
```

Add to `WorkflowDefinitionEntity`:

```java
    /** Whether finished (completed or failed) runs of this workflow get an AI summary; off by default. */
    @Column(name = "summarize_runs", nullable = false, columnDefinition = "boolean default false")
    public boolean summarizeRuns = false;
```

Add to `SystemConfigEntity`:

```java
    /** Engine used for run summaries; null means the system default engine. */
    @Column(name = "summary_engine")
    public String summaryEngine;

    /** Model used for run summaries; null means the engine's configured default model. */
    @Column(name = "summary_model")
    public String summaryModel;
```

- [ ] **Step 5: Run the cascade test and the schema validation test; both PASS**

Run: `mvn -q -pl app -am test -Dtest='RunSummaryCascadeTest,MigrationSchemaValidationTest' -Dsurefire.failIfNoSpecifiedTests=false`

If `MigrationSchemaValidationTest` reports a type mismatch, change the SQL (not the entity) to match what
Hibernate expects for H2, keeping `on delete cascade`.

- [ ] **Step 6: Extend the cascade test to the other two bulk paths** — add tests that delete via
  `WorkflowRunEntity.delete("subjectId", -77L)` and `WorkflowRunEntity.delete("definitionId", -78L)` (runs created
  with those ids) and assert zero summaries remain. Run; PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/resources/db/migration/V12__workflow_run_summary.sql core/src/main/java/io/apitomy/axiom/core/entities app/src/test/java/io/apitomy/axiom/app/summary
git commit -m "Add workflow run summary table with DB cascade and opt-in column (#672)"
```

---

### Task 2: OpenAPI contract and bean mapping

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`
- Modify: `app/.../WorkflowRunBeanMapper.java`, `app/.../rest/WorkflowDefinitionsResourceImpl.java`,
  `app/.../rest/SystemResourceImpl.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/summary/RunSummaryApiMappingTest.java`

**Interfaces:**
- Consumes: Task 1 entities.
- Produces: generated beans `WorkflowRunAiSummary`; `WorkflowInstance.getAiSummary()`,
  `WorkflowRunSummary.getAiSummary()`; `WorkflowDefinition/NewWorkflowDefinition/UpdateWorkflowDefinition
  .getSummarizeRuns()` (Boolean); `SystemConfig/UpdateSystemConfigRequest .getSummaryEngine()/.getSummaryModel()`;
  generated interface method `Response generateWorkflowRunAiSummary(long runId)` on `WorkflowResource`
  (implemented in Task 6 — add a temporary impl that throws 501 so the build compiles);
  `WorkflowRunBeanMapper.toAiSummaryBean(WorkflowRunSummaryEntity, boolean full)`.

- [ ] **Step 1: Edit `openapi.json`** (use apicurio-data-models MCP tools if available, else a careful JSON edit):

Add schema:
```json
"WorkflowRunAiSummary": {
  "type": "object",
  "description": "The AI-generated summary of a workflow run.",
  "properties": {
    "status": {"type": "string", "enum": ["pending", "generating", "ready", "failed"]},
    "headline": {"type": "string"},
    "body": {"type": "string", "description": "Markdown"},
    "error": {"type": "string"},
    "engine": {"type": "string"},
    "model": {"type": "string"},
    "trigger": {"type": "string", "enum": ["auto", "manual"]},
    "completedOn": {"type": "string", "format": "date-time"}
  },
  "required": ["status"]
}
```
Add `"aiSummary": {"$ref": "#/components/schemas/WorkflowRunAiSummary"}` to `WorkflowInstance` and
`WorkflowRunSummary` properties. Add `"summarizeRuns": {"type": "boolean"}` to `WorkflowDefinition`,
`NewWorkflowDefinition`, `UpdateWorkflowDefinition`. Add `"summaryEngine": {"type": "string"}` and
`"summaryModel": {"type": "string"}` to `SystemConfig` and `UpdateSystemConfigRequest` (keep `required:
["defaultEngine"]`). Add path:
```json
"/workflow/runs/{runId}/ai-summary": {
  "post": {
    "tags": ["WorkflowRuns"],
    "summary": "Generate or regenerate the AI summary of a finished run",
    "operationId": "generateWorkflowRunAiSummary",
    "responses": {
      "202": {"description": "Summary generation queued", "content": {"application/json": {}}},
      "404": {"$ref": "#/components/responses/NotFound"},
      "409": {"description": "The run is not finished, or its summary is being generated"},
      "503": {"description": "Run summaries are disabled"}
    }
  },
  "parameters": [{"name": "runId", "in": "path", "description": "Workflow run ID", "required": true,
    "schema": {"format": "int64", "type": "integer"}}]
}
```

- [ ] **Step 2: Regenerate** — `mvn -q -pl common/api install`. Confirm
  `grep -n "generateWorkflowRunAiSummary" common/api/target/generated-sources/jaxrs/io/apitomy/axiom/api/WorkflowResource.java`
  shows a `Response` return type. Add the temporary 501 impl in `WorkflowDefinitionsResourceImpl`.

- [ ] **Step 3: Write the failing mapping test**

```java
package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.app.WorkflowRunBeanMapper;
import io.apitomy.axiom.api.beans.WorkflowRunAiSummary;
import io.apitomy.axiom.core.entities.WorkflowRunSummaryEntity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Checks the REST shape of the run summary feature. */
@QuarkusTest
class RunSummaryApiMappingTest {

    @Inject
    WorkflowRunBeanMapper mapper;

    @Test
    void listMappingCarriesOnlyStatusAndHeadline() {
        WorkflowRunSummaryEntity entity = new WorkflowRunSummaryEntity();
        entity.status = WorkflowRunSummaryEntity.READY;
        entity.headline = "Did things";
        entity.body = "- a";
        entity.engine = "claude-code";
        entity.triggerType = WorkflowRunSummaryEntity.AUTO;
        entity.completedOn = Instant.now();
        WorkflowRunAiSummary light = mapper.toAiSummaryBean(entity, false);
        assertEquals("Did things", light.getHeadline());
        assertNull(light.getBody());
        WorkflowRunAiSummary full = mapper.toAiSummaryBean(entity, true);
        assertEquals("- a", full.getBody());
        assertEquals("claude-code", full.getEngine());
    }

    @Test
    void newDefinitionIsNotOptedIn() {
        given().contentType(ContentType.JSON).body("{\"name\":\"sum-default-" + System.nanoTime() + "\"}")
                .when().post("/api/v1/workflow/definitions")
                .then().statusCode(200).body("summarizeRuns", equalTo(false));
    }

    @Test
    void definitionOptInRoundTrips() {
        int id = given().contentType(ContentType.JSON).body("{\"name\":\"sum-on-" + System.nanoTime() + "\"}")
                .when().post("/api/v1/workflow/definitions").then().statusCode(200).extract().path("id");
        given().contentType(ContentType.JSON).body("{\"summarizeRuns\":true}")
                .when().put("/api/v1/workflow/definitions/" + id)
                .then().statusCode(200).body("summarizeRuns", equalTo(true));
        given().contentType(ContentType.JSON).body("{\"description\":\"x\"}")
                .when().put("/api/v1/workflow/definitions/" + id)
                .then().statusCode(200).body("summarizeRuns", equalTo(true));
    }

    @Test
    void systemConfigStoresAndClearsSummarySettings() {
        String engine = given().when().get("/api/v1/system/config").then().extract().path("defaultEngine");
        given().contentType(ContentType.JSON)
                .body("{\"defaultEngine\":\"" + engine + "\",\"summaryEngine\":\"" + engine
                        + "\",\"summaryModel\":\"m1\"}")
                .when().put("/api/v1/system/config")
                .then().statusCode(200).body("summaryModel", equalTo("m1"));
        given().contentType(ContentType.JSON)
                .body("{\"defaultEngine\":\"" + engine + "\",\"summaryEngine\":\"\",\"summaryModel\":\"\"}")
                .when().put("/api/v1/system/config")
                .then().statusCode(200).body("summaryEngine", org.hamcrest.Matchers.nullValue());
    }
}
```

- [ ] **Step 4: Run; expect FAIL** (no `toAiSummaryBean`, fields not mapped).

- [ ] **Step 5: Implement mapping**

In `WorkflowRunBeanMapper`:
```java
    /**
     * Maps a summary entity to its REST bean.
     *
     * @param entity the summary row
     * @param full true for the run detail (all fields), false for list items (status and headline only)
     * @return the bean
     */
    public io.apitomy.axiom.api.beans.WorkflowRunAiSummary toAiSummaryBean(WorkflowRunSummaryEntity entity,
                                                                           boolean full) {
        io.apitomy.axiom.api.beans.WorkflowRunAiSummary bean = new io.apitomy.axiom.api.beans.WorkflowRunAiSummary();
        bean.setStatus(io.apitomy.axiom.api.beans.WorkflowRunAiSummary.Status.fromValue(entity.status));
        bean.setHeadline(entity.headline);
        if (full) {
            bean.setBody(entity.body);
            bean.setError(entity.error);
            bean.setEngine(entity.engine);
            bean.setModel(entity.model);
            bean.setTrigger(io.apitomy.axiom.api.beans.WorkflowRunAiSummary.Trigger.fromValue(entity.triggerType));
            if (entity.completedOn != null) {
                bean.setCompletedOn(Date.from(entity.completedOn));
            }
        }
        return bean;
    }
```
(If the generator emits plain `String` instead of enums, call `setStatus(entity.status)` /
`setTrigger(entity.triggerType)` — check the generated class.)

In `toBean(WorkflowRunEntity)` add:
```java
        WorkflowRunSummaryEntity aiSummary = WorkflowRunSummaryEntity.findById(entity.id);
        if (aiSummary != null) {
            bean.setAiSummary(toAiSummaryBean(aiSummary, true));
        }
```
In `toSummaries(List<WorkflowRunEntity> runs)` batch-load once (no N+1) and pass the map into `toSummary`:
```java
        List<Long> runIds = runs.stream().map(r -> r.id).toList();
        Map<Long, WorkflowRunSummaryEntity> aiSummaries = runIds.isEmpty() ? Map.of()
                : WorkflowRunSummaryEntity.<WorkflowRunSummaryEntity>list("runId in ?1", runIds).stream()
                        .collect(Collectors.toMap(s -> s.runId, s -> s));
```
and in `toSummary`: `WorkflowRunSummaryEntity s = aiSummaries.get(run.id); if (s != null) { summary.setAiSummary(toAiSummaryBean(s, false)); }`.

In `WorkflowDefinitionsResourceImpl`: `toBean` adds `bean.setSummarizeRuns(entity.summarizeRuns);`;
`createWorkflowDefinition` adds `entity.summarizeRuns = Boolean.TRUE.equals(data.getSummarizeRuns());`;
`updateWorkflowDefinition` adds `if (data.getSummarizeRuns() != null) { entity.summarizeRuns = data.getSummarizeRuns(); }`.

In `SystemResourceImpl.getSystemConfig()` set `summaryEngine`/`summaryModel` from the entity. In
`updateSystemConfig`, before `entity.persist()`:
```java
        if (data.getSummaryEngine() != null) {
            String summaryEngine = data.getSummaryEngine().trim();
            if (!summaryEngine.isEmpty() && agentRegistry.getAllAgents().stream()
                    .noneMatch(agent -> summaryEngine.equals(agent.getType()))) {
                throw new WebApplicationException("Unknown engine type: " + summaryEngine, 400);
            }
            entity.summaryEngine = summaryEngine.isEmpty() ? null : summaryEngine;
        }
        if (data.getSummaryModel() != null) {
            String summaryModel = data.getSummaryModel().trim();
            entity.summaryModel = summaryModel.isEmpty() ? null : summaryModel;
        }
```

- [ ] **Step 6: Run the test + existing run/definition/system tests; PASS**

Run: `mvn -q -pl app -am test -Dtest='RunSummaryApiMappingTest,WorkflowRunsResourceTest,*SystemResource*' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 7: Commit** — `git commit -am "Add AI summary fields to the REST contract (#672)"` (also `git add` the new test).

---

### Task 3: Response parser

**Files:**
- Create: `app/.../summary/RunSummaryResponseParser.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/summary/RunSummaryResponseParserTest.java`

**Interfaces:**
- Produces: `record ParsedSummary(String headline, String body)`;
  `static ParsedSummary RunSummaryResponseParser.parse(String agentOutput)` throws
  `IllegalArgumentException` with a readable message when no usable JSON/headline is found.

- [ ] **Step 1: Failing test**

```java
package io.apitomy.axiom.app.summary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunSummaryResponseParserTest {

    @Test
    void parsesPlainJson() {
        RunSummaryResponseParser.ParsedSummary s =
                RunSummaryResponseParser.parse("{\"headline\":\"H\",\"body\":\"- a\"}");
        assertEquals("H", s.headline());
        assertEquals("- a", s.body());
    }

    @Test
    void parsesFencedJsonWithChatter() {
        String out = "Here you go:\n```json\n{\"headline\": \"Fenced\", \"body\": \"- {x}\"}\n```\nDone.";
        assertEquals("Fenced", RunSummaryResponseParser.parse(out).headline());
    }

    @Test
    void bodyIsOptional() {
        assertNull(RunSummaryResponseParser.parse("{\"headline\":\"Only\"}").body());
    }

    @Test
    void truncatesLongHeadlineTo200() {
        String h = "x".repeat(300);
        assertEquals(200, RunSummaryResponseParser.parse("{\"headline\":\"" + h + "\"}").headline().length());
    }

    @Test
    void rejectsMissingHeadlineOrNoJson() {
        assertThrows(IllegalArgumentException.class, () -> RunSummaryResponseParser.parse("{\"body\":\"b\"}"));
        assertThrows(IllegalArgumentException.class, () -> RunSummaryResponseParser.parse("no json here"));
        assertThrows(IllegalArgumentException.class, () -> RunSummaryResponseParser.parse(null));
    }
}
```

- [ ] **Step 2: Run; FAIL (class missing).**

- [ ] **Step 3: Implement**

```java
package io.apitomy.axiom.app.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Extracts the {@code {headline, body}} JSON object from an agent's free-form output. */
public final class RunSummaryResponseParser {

    /** Maximum stored headline length (matches the column). */
    public static final int MAX_HEADLINE = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A parsed summary. @param headline the one-line headline @param body the markdown body, or null */
    public record ParsedSummary(String headline, String body) { }

    private RunSummaryResponseParser() { }

    /**
     * Parses the first JSON object containing a headline from the agent output.
     *
     * @param agentOutput the raw output
     * @return the parsed summary
     * @throws IllegalArgumentException if no JSON object with a non-blank headline is found
     */
    public static ParsedSummary parse(String agentOutput) {
        if (agentOutput == null || agentOutput.isBlank()) {
            throw new IllegalArgumentException("The agent returned no output");
        }
        for (int start = agentOutput.indexOf('{'); start >= 0; start = agentOutput.indexOf('{', start + 1)) {
            int end = matchingBrace(agentOutput, start);
            if (end < 0) {
                continue;
            }
            JsonNode node = tryRead(agentOutput.substring(start, end + 1));
            if (node != null && node.isObject()) {
                String headline = node.path("headline").asText("").trim();
                if (headline.isEmpty()) {
                    throw new IllegalArgumentException("The agent's JSON has no headline");
                }
                if (headline.length() > MAX_HEADLINE) {
                    headline = headline.substring(0, MAX_HEADLINE);
                }
                String body = node.hasNonNull("body") ? node.get("body").asText().trim() : null;
                return new ParsedSummary(headline, body == null || body.isEmpty() ? null : body);
            }
        }
        throw new IllegalArgumentException("The agent did not return a JSON summary");
    }

    private static JsonNode tryRead(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** Finds the brace closing the one at {@code start}, ignoring braces inside JSON strings. */
    private static int matchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
```

- [ ] **Step 4: Run; PASS.**  `mvn -q -pl app -am test -Dtest=RunSummaryResponseParserTest -Dsurefire.failIfNoSpecifiedTests=false`
- [ ] **Step 5: Commit** — `git add app/src/*/java/io/apitomy/axiom/app/summary && git commit -m "Add run summary response parser (#672)"`

---

### Task 4: Prompt builder

**Files:**
- Create: `app/.../summary/RunSummaryPromptBuilder.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/summary/RunSummaryPromptBuilderTest.java`

**Interfaces:**
- Produces: `record RunDigest(long runId, String workflowName, String title, String status, Long durationMs,
  String failureReason, Double costUsd, List<StepDigest> steps)`; `record StepDigest(long taskId, String nodeId,
  String status, String output)`; `static String RunSummaryPromptBuilder.build(RunDigest digest)`;
  constants `DIGEST_BUDGET = 3000`, `OUTPUT_PREVIEW = 150`. The builder is pure (no DB).

- [ ] **Step 1: Failing test**

```java
package io.apitomy.axiom.app.summary;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunSummaryPromptBuilderTest {

    private static RunSummaryPromptBuilder.RunDigest digest(List<RunSummaryPromptBuilder.StepDigest> steps) {
        return new RunSummaryPromptBuilder.RunDigest(42L, "Triage", "Daily triage", "failed", 65_000L,
                "Rate limited\nstack...", 0.1234, steps);
    }

    @Test
    void includesRunFactsToolsHintAndJsonInstruction() {
        String p = RunSummaryPromptBuilder.build(digest(List.of(
                new RunSummaryPromptBuilder.StepDigest(7L, "fetch", "Completed", "14 issues"))));
        assertTrue(p.contains("Run ID: 42"));
        assertTrue(p.contains("Workflow: Triage"));
        assertTrue(p.contains("Failure: Rate limited"));
        assertFalse(p.contains("stack..."));
        assertTrue(p.contains("task 7 [fetch] Completed: 14 issues"));
        assertTrue(p.contains("$0.1234"));
        assertTrue(p.contains("\"headline\""));
        assertTrue(p.contains("get_task_log"));
    }

    @Test
    void previewsAreOneLineAndShort() {
        String out = "line1\nline2 " + "y".repeat(500);
        String p = RunSummaryPromptBuilder.build(digest(List.of(
                new RunSummaryPromptBuilder.StepDigest(1L, "n", "Completed", out))));
        assertFalse(p.contains("y".repeat(200)));
        assertTrue(p.contains("line1 line2"));
    }

    @Test
    void digestStaysWithinBudgetForHugeRuns() {
        List<RunSummaryPromptBuilder.StepDigest> steps = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            steps.add(new RunSummaryPromptBuilder.StepDigest(i, "node" + i, "Completed", "out " + i));
        }
        String p = RunSummaryPromptBuilder.build(digest(steps));
        String digestPart = p.substring(p.indexOf("## Run"), p.indexOf("## Instructions"));
        assertTrue(digestPart.length() <= RunSummaryPromptBuilder.DIGEST_BUDGET + 100);
        assertTrue(digestPart.contains("more steps omitted"));
    }
}
```

- [ ] **Step 2: Run; FAIL.**

- [ ] **Step 3: Implement**

```java
package io.apitomy.axiom.app.summary;

import java.util.List;
import java.util.Locale;

/** Builds the agent prompt for a run summary: a budgeted digest plus output instructions. */
public final class RunSummaryPromptBuilder {

    /** Character budget for the digest section. */
    public static final int DIGEST_BUDGET = 3000;
    /** Maximum characters of each step's output preview. */
    public static final int OUTPUT_PREVIEW = 150;

    /** One step of the run. */
    public record StepDigest(long taskId, String nodeId, String status, String output) { }

    /** The facts about a run that go into the prompt. */
    public record RunDigest(long runId, String workflowName, String title, String status, Long durationMs,
                            String failureReason, Double costUsd, List<StepDigest> steps) { }

    private RunSummaryPromptBuilder() { }

    /**
     * Builds the prompt.
     *
     * @param digest the run facts
     * @return the prompt text
     */
    public static String build(RunDigest digest) {
        StringBuilder d = new StringBuilder("## Run\n");
        d.append("Run ID: ").append(digest.runId()).append('\n');
        d.append("Workflow: ").append(digest.workflowName()).append('\n');
        if (digest.title() != null && !digest.title().isBlank()) {
            d.append("Title: ").append(oneLine(digest.title(), 200)).append('\n');
        }
        d.append("Status: ").append(digest.status()).append('\n');
        if (digest.durationMs() != null) {
            d.append("Duration: ").append(digest.durationMs() / 1000).append("s\n");
        }
        String failure = firstLine(digest.failureReason());
        if (failure != null) {
            d.append("Failure: ").append(oneLine(failure, 300)).append('\n');
        }
        if (digest.costUsd() != null) {
            d.append("AI cost: $").append(String.format(Locale.ROOT, "%.4f", digest.costUsd())).append('\n');
        }
        d.append("Steps (in order):\n");
        List<StepDigest> steps = digest.steps();
        for (int i = 0; i < steps.size(); i++) {
            StepDigest s = steps.get(i);
            String line = "- task " + s.taskId() + " [" + s.nodeId() + "] " + s.status()
                    + (s.output() == null || s.output().isBlank() ? "" : ": " + oneLine(s.output(), OUTPUT_PREVIEW))
                    + "\n";
            if (d.length() + line.length() > DIGEST_BUDGET - 40) {
                d.append("- … ").append(steps.size() - i).append(" more steps omitted (use get_run)\n");
                break;
            }
            d.append(line);
        }
        return "You are summarizing one finished workflow run of the Axiom automation platform for a person "
                + "browsing a list of runs.\n\n"
                + d
                + "\n## Instructions\n"
                + "- The digest above may be enough. If it is not (especially to explain a failure), use the "
                + "read-only tools get_run, get_task, get_task_log, and get_trace with runId " + digest.runId()
                + ". Only examine this run.\n"
                + "- Never reproduce secrets, tokens, or credentials.\n"
                + "- Respond ONLY with a JSON object: {\"headline\": \"...\", \"body\": \"...\"}\n"
                + "- headline: at most 120 characters, one line, what the run accomplished (or why it failed).\n"
                + "- body: 3-8 markdown bullet points covering what the run did, key outputs, and the cause of "
                + "any failure.\n";
    }

    private static String firstLine(String text) {
        if (text == null) {
            return null;
        }
        return text.lines().map(String::trim).filter(l -> !l.isEmpty()).findFirst().orElse(null);
    }

    private static String oneLine(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
```

- [ ] **Step 4: Run; PASS.**
- [ ] **Step 5: Commit** — `git commit -m "Add run summary prompt builder (#672)"` (add files).

---

### Task 5: Enqueue hook, after-commit SSE, and configuration

**Files:**
- Create: `core/src/main/java/io/apitomy/axiom/core/events/RunSummaryChanged.java`
- Create: `app/.../summary/RunSummarySseNotifier.java`, `app/.../summary/RunSummaryService.java` (enqueue part)
- Modify: `app/.../WorkflowExecutionService.java`, `app/src/main/resources/application.properties`
- Test: `app/src/test/java/io/apitomy/axiom/app/summary/RunSummaryEnqueueTest.java`

**Interfaces:**
- Produces: `record RunSummaryChanged(long subjectId, long runId, String runStatus)`;
  `RunSummaryService` (`@ApplicationScoped`) with `boolean isEnabled()`,
  `void enqueueIfEnabled(WorkflowRunEntity run)` (must be called inside a transaction),
  `void markChanged(WorkflowRunEntity run)` (fires `RunSummaryChanged`).
- Consumes: Task 1 entities.

- [ ] **Step 1: Failing test** (calls the service directly with persisted fixtures)

```java
package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunSummaryEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class RunSummaryEnqueueTest {

    @Inject
    RunSummaryService service;

    private long definition(boolean summarize) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowDefinitionEntity d = new WorkflowDefinitionEntity();
            d.name = "enqueue-" + System.nanoTime();
            d.content = "{}";
            d.summarizeRuns = summarize;
            d.createdOn = Instant.now();
            d.updatedOn = Instant.now();
            d.persist();
            return d.id;
        });
    }

    private long enqueueFor(long definitionId, String status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.title = "t";
            run.subjectId = -1L;
            run.definitionId = definitionId;
            run.definitionVersion = 1;
            run.instanceState = "{}";
            run.status = status;
            run.startedOn = Instant.now();
            run.persist();
            service.enqueueIfEnabled(run);
            service.enqueueIfEnabled(run);
            return run.id;
        });
    }

    private long rows(long runId) {
        return QuarkusTransaction.requiringNew().call(() -> WorkflowRunSummaryEntity.count("runId", runId));
    }

    @Test
    void optedInCompletedAndFailedRunsEnqueueOnce() {
        long def = definition(true);
        assertEquals(1, rows(enqueueFor(def, "completed")));
        assertEquals(1, rows(enqueueFor(def, "failed")));
    }

    @Test
    void cancelledAndActiveRunsAreNotEnqueued() {
        long def = definition(true);
        assertEquals(0, rows(enqueueFor(def, "cancelled")));
        assertEquals(0, rows(enqueueFor(def, "running")));
    }

    @Test
    void workflowsAreNotOptedInByDefault() {
        long def = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowDefinitionEntity d = new WorkflowDefinitionEntity();
            d.name = "default-" + System.nanoTime();
            d.content = "{}";
            d.createdOn = Instant.now();
            d.updatedOn = Instant.now();
            d.persist();
            return d.id;
        });
        assertEquals(0, rows(enqueueFor(def, "completed")));
    }
}
```

- [ ] **Step 2: Run; FAIL.**

- [ ] **Step 3: Implement event, notifier, and enqueue**

```java
package io.apitomy.axiom.core.events;

/**
 * Fired inside a transaction when a run's AI summary changes; observed after commit to notify the UI.
 *
 * @param subjectId the run's subject
 * @param runId the run
 * @param runStatus the run's status
 */
public record RunSummaryChanged(long subjectId, long runId, String runStatus) { }
```

```java
package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.core.events.RunSummaryChanged;
import io.apitomy.axiom.core.events.SseEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;

/** Sends a workflow-updated SSE event after a run summary change commits. */
@ApplicationScoped
public class RunSummarySseNotifier {

    @Inject
    Event<SseEvent> sseEvents;

    void onChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) RunSummaryChanged changed) {
        sseEvents.fire(SseEvent.workflowUpdated(changed.subjectId(), changed.runId(), changed.runStatus()));
    }
}
```

`RunSummaryService` (initial part; Task 6 and Task 8 add methods):
```java
package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunSummaryEntity;
import io.apitomy.axiom.core.events.RunSummaryChanged;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Set;

/** Queues, requests, and generates AI summaries of workflow runs. */
@ApplicationScoped
public class RunSummaryService {

    /** Run statuses that are summarized automatically. */
    static final Set<String> AUTO_STATUSES = Set.of("completed", "failed");

    @ConfigProperty(name = "axiom.run-summary.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    Event<RunSummaryChanged> changes;

    /** @return whether run summaries are enabled globally */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Queues an automatic summary when the run finished completed/failed and its workflow opted in.
     * Must run inside the transaction that finished the run.
     *
     * @param run the finished run
     */
    public void enqueueIfEnabled(WorkflowRunEntity run) {
        if (!enabled || !AUTO_STATUSES.contains(run.status)) {
            return;
        }
        WorkflowDefinitionEntity definition = WorkflowDefinitionEntity.findById(run.definitionId);
        if (definition == null || !definition.summarizeRuns) {
            return;
        }
        if (WorkflowRunSummaryEntity.findById(run.id) != null) {
            return;
        }
        WorkflowRunSummaryEntity.newPending(run, WorkflowRunSummaryEntity.AUTO).persist();
        markChanged(run);
    }

    /**
     * Notifies the UI (after commit) that the run's summary changed.
     *
     * @param run the run
     */
    public void markChanged(WorkflowRunEntity run) {
        changes.fire(new RunSummaryChanged(run.subjectId, run.id, run.status));
    }
}
```

In `WorkflowExecutionService`: add `@Inject RunSummaryService runSummaryService;` and
```java
    /** Shared hook for every transition of a run into a terminal state. */
    private void onRunTerminal(WorkflowRunEntity entity) {
        try {
            runSummaryService.enqueueIfEnabled(entity);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to queue the AI summary of run %d", entity.id);
        }
    }
```
Call `onRunTerminal(entity);` right after `completeRunTrace(...)` in: the sync-start COMPLETED/FAILED branch
(~line 249), both COMPLETED and FAILED branches of `applyAdvancedInstance`, and `cancelWorkflowRun` (after
`completeRunTrace(entity, "cancelled")`; it is a no-op for cancelled but keeps one hook for all terminal paths).
`entity.id` is set at all of these points (sync start: `entity.persist()` precedes the branch — verify; if
not, move the call after persist).

`application.properties` — add after the agent section:
```properties
# --- Run summaries (AI-generated, opt-in per workflow) ---
# Global kill switch for automatic and on-demand run summaries
axiom.run-summary.enabled=true
# How often pending summaries are picked up
axiom.run-summary.poll-interval=5s
%test.axiom.run-summary.poll-interval=off
# Maximum summaries generated concurrently
axiom.run-summary.batch-size=2
# Agent limits for one summary
axiom.run-summary.max-steps=10
axiom.run-summary.timeout-seconds=300
```

- [ ] **Step 4: Run new test + `WorkflowRunsResourceTest` + `HistoryCleanupTest`; PASS.**
- [ ] **Step 5: Add an end-to-end hook test** to `RunSummaryEnqueueTest`: create a definition with
  `summarizeRuns=true` via REST and publish start→end content (copy `createAndPublishDefinition` from
  `WorkflowRunsResourceTest`, which completes synchronously), start a run via `TestRunsResource`, and assert one
  `pending` row with `triggerType == "auto"`. Run; PASS.
- [ ] **Step 6: Commit** — `git commit -m "Queue AI summaries when opted-in runs finish (#672)"`

---

### Task 6: On-demand endpoint

**Files:**
- Modify: `app/.../summary/RunSummaryService.java`, `app/.../rest/WorkflowDefinitionsResourceImpl.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/summary/RunSummaryEndpointTest.java`

**Interfaces:**
- Produces: `RunSummaryService.request(long runId)` (`@Transactional`) throwing `WebApplicationException`
  404/409/503; returns void.

- [ ] **Step 1: Failing test**

```java
package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunSummaryEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class RunSummaryEndpointTest {

    private long run(String status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.title = "t";
            run.subjectId = -1L;
            run.definitionId = -1L;
            run.definitionVersion = 1;
            run.instanceState = "{}";
            run.status = status;
            run.startedOn = Instant.now();
            run.persist();
            return run.id;
        });
    }

    private String path(long id) {
        return "/api/v1/workflow/runs/" + id + "/ai-summary";
    }

    @Test
    void queuesManualSummaryForCancelledRun() {
        long id = run("cancelled");
        given().when().post(path(id)).then().statusCode(202);
        WorkflowRunSummaryEntity s = QuarkusTransaction.requiringNew()
                .call(() -> WorkflowRunSummaryEntity.findById(id));
        assertEquals(WorkflowRunSummaryEntity.PENDING, s.status);
        assertEquals(WorkflowRunSummaryEntity.MANUAL, s.triggerType);
    }

    @Test
    void regenerateResetsFailedSummary() {
        long id = run("completed");
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunSummaryEntity s = WorkflowRunSummaryEntity.newPending(
                    WorkflowRunEntity.findById(id), WorkflowRunSummaryEntity.AUTO);
            s.status = WorkflowRunSummaryEntity.FAILED;
            s.attempts = 2;
            s.error = "boom";
            s.persist();
        });
        given().when().post(path(id)).then().statusCode(202);
        WorkflowRunSummaryEntity s = QuarkusTransaction.requiringNew()
                .call(() -> WorkflowRunSummaryEntity.findById(id));
        assertEquals(0, s.attempts);
        assertEquals(null, s.error);
    }

    @Test
    void rejectsActiveRunsMissingRunsAndGeneratingSummaries() {
        given().when().post(path(run("running"))).then().statusCode(409);
        given().when().post(path(Long.MAX_VALUE)).then().statusCode(404);
        long id = run("failed");
        given().when().post(path(id)).then().statusCode(202);
        QuarkusTransaction.requiringNew().run(() -> WorkflowRunSummaryEntity.update(
                "status = ?1 where runId = ?2", WorkflowRunSummaryEntity.GENERATING, id));
        given().when().post(path(id)).then().statusCode(409);
    }
}
```

- [ ] **Step 2: Run; FAIL (501).**

- [ ] **Step 3: Implement** — in `RunSummaryService`:
```java
    /**
     * Queues a manual (re)generation of a finished run's summary.
     *
     * @param runId the run
     * @throws WebApplicationException 404 unknown run, 409 active run or generation in progress, 503 disabled
     */
    @Transactional
    public void request(long runId) {
        if (!enabled) {
            throw new WebApplicationException("Run summaries are disabled", 503);
        }
        WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
        if (run == null) {
            throw new WebApplicationException("Workflow run not found: " + runId, Response.Status.NOT_FOUND);
        }
        if (!HistoryCleanup.FINISHED_WORKFLOW_RUN_STATUSES.contains(run.status)) {
            throw conflict("The run has not finished");
        }
        WorkflowRunSummaryEntity summary = WorkflowRunSummaryEntity.findById(runId,
                jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (summary == null) {
            summary = WorkflowRunSummaryEntity.newPending(run, WorkflowRunSummaryEntity.MANUAL);
            summary.persist();
        } else if (WorkflowRunSummaryEntity.GENERATING.equals(summary.status)) {
            throw conflict("A summary is already being generated");
        } else {
            summary.status = WorkflowRunSummaryEntity.PENDING;
            summary.triggerType = WorkflowRunSummaryEntity.MANUAL;
            summary.attempts = 0;
            summary.error = null;
            summary.createdOn = java.time.Instant.now();
            summary.completedOn = null;
        }
        markChanged(run);
    }

    private static WebApplicationException conflict(String message) {
        return new WebApplicationException(Response.status(409).entity(java.util.Map.of("message", message)).build());
    }
```
(imports: `jakarta.transaction.Transactional`, `jakarta.ws.rs.WebApplicationException`,
`jakarta.ws.rs.core.Response`, `io.apitomy.axiom.app.HistoryCleanup` — make `FINISHED_WORKFLOW_RUN_STATUSES`
`public` if it is package-private and the package differs.)

Replace the 501 stub in `WorkflowDefinitionsResourceImpl` (inject `RunSummaryService runSummaryService`):
```java
    /** {@inheritDoc} */
    @Override
    public Response generateWorkflowRunAiSummary(long runId) {
        runSummaryService.request(runId);
        return Response.accepted().build();
    }
```

- [ ] **Step 4: Run; PASS.**
- [ ] **Step 5: Commit** — `git commit -m "Add endpoint to request a run's AI summary (#672)"`

---

### Task 7: Read-only MCP server template

**Files:**
- Create: `app/src/main/resources/templates/axiom-run-summary-mcp/package.json`
- Create: `app/src/main/resources/templates/axiom-run-summary-mcp/server.js`
- Create: `app/src/test/node/run-summary-mcp.test.js`
- Create: `app/src/test/java/io/apitomy/axiom/app/summary/RunSummaryMcpServerScriptTest.java`

**Interfaces:**
- Produces: stdio MCP server, env `AXIOM_API_URL`; tools `get_run(runId)`, `get_task(runId, taskId)`,
  `get_task_log(runId, taskId, offset?, limit?)`, `get_trace(runId)`. Exports
  `{ TOOLS, setFetch, pageText }` for tests. Claude names: `mcp__axiom-run-summary__<tool>`.

- [ ] **Step 1: Failing node test**

```js
const test = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const server = require(path.resolve(__dirname, "../../main/resources/templates/axiom-run-summary-mcp/server.js"));

const calls = [];
const run = { id: 5, subjectId: 9, status: "failed", traceId: "tr-1",
    history: [{ taskId: 11, nodeId: "fetch", taskStatus: "Failed" }] };
server.setFetch(async (url, opts) => {
    calls.push({ url, method: opts.method });
    const body = url.endsWith("/workflow/runs/5") ? JSON.stringify(run)
        : url.endsWith("/subjects/9/tasks/11") ? JSON.stringify({ id: 11, input: "in", output: "out" })
        : url.endsWith("/subjects/9/tasks/11/log") ? "abcdefghij"
        : url.endsWith("/traces/tr-1") ? JSON.stringify({ nodes: [] }) : null;
    return { ok: body !== null, status: body === null ? 404 : 200, text: async () => body ?? "not found" };
});
const tool = (name) => server.TOOLS.find(t => t.name === name);

test("exposes exactly the four read-only tools", () => {
    assert.deepStrictEqual(server.TOOLS.map(t => t.name).sort(), ["get_run", "get_task", "get_task_log", "get_trace"]);
});

test("get_task returns the task of the run", async () => {
    const out = await tool("get_task").handler({ runId: 5, taskId: 11 });
    assert.match(out, /out/);
});

test("get_task refuses a task that is not part of the run", async () => {
    const out = await tool("get_task").handler({ runId: 5, taskId: 99 });
    assert.match(out, /not part of run 5/);
});

test("get_task_log pages the log", async () => {
    const out = await tool("get_task_log").handler({ runId: 5, taskId: 11, offset: 2, limit: 3 });
    assert.match(out, /cde/);
    assert.match(out, /chars 2-5 of 10/);
});

test("only GET requests are made", async () => {
    await tool("get_run").handler({ runId: 5 });
    await tool("get_trace").handler({ runId: 5 });
    assert.ok(calls.length > 0);
    assert.ok(calls.every(c => c.method === "GET"));
});

test("pageText clamps bounds", () => {
    assert.deepStrictEqual(server.pageText("abc", 5, 10), { text: "", start: 3, end: 3, total: 3 });
});
```

JUnit runner (mirrors `WorkbenchMcpServerScriptTest`; copy its node-missing skip logic):
```java
package io.apitomy.axiom.app.summary;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runs the run-summary MCP server's node tests. */
class RunSummaryMcpServerScriptTest {

    @Test
    void nodeTestsPass() throws Exception {
        Process probe;
        try {
            probe = new ProcessBuilder("node", "--version").start();
        } catch (java.io.IOException e) {
            Assumptions.abort("node is not installed");
            return;
        }
        Assumptions.assumeTrue(probe.waitFor() == 0, "node is not available");
        Path script = Path.of("src/test/node/run-summary-mcp.test.js").toAbsolutePath();
        Process p = new ProcessBuilder("node", "--test", script.toString()).inheritIO().start();
        assertEquals(0, p.waitFor());
    }
}
```

- [ ] **Step 2: Run `node --test app/src/test/node/run-summary-mcp.test.js`; FAIL (module missing).**

- [ ] **Step 3: Implement**

`package.json`:
```json
{
  "name": "axiom-run-summary-mcp",
  "version": "3.0.6-SNAPSHOT",
  "private": true,
  "type": "commonjs",
  "main": "server.js",
  "dependencies": {
    "@modelcontextprotocol/sdk": "^1.32.2"
  }
}
```

`server.js`:
```js
// Read-only MCP server used by Axiom to let an agent investigate a finished workflow run while summarizing it.
// Stateless: every tool takes a runId. Only GET requests are made.
"use strict";

const API_URL = (process.env.AXIOM_API_URL || "http://localhost:9090/api/v1").replace(/\/$/, "");
const MAX_TEXT = 20000;
let fetchImpl = (...args) => fetch(...args);

/** Replaces fetch (tests only). */
function setFetch(fn) { fetchImpl = fn; }

async function get(path, accept = "application/json") {
    const resp = await fetchImpl(API_URL + path, { method: "GET", headers: { Accept: accept } });
    const text = await resp.text();
    if (!resp.ok) {
        throw new Error(`GET ${path} failed (${resp.status}): ${text.slice(0, 300)}`);
    }
    return text;
}

function clip(text) {
    return text.length <= MAX_TEXT ? text : text.slice(0, MAX_TEXT) + `\n…[truncated ${text.length - MAX_TEXT} chars]`;
}

/** Returns a window of text with clamped bounds. */
function pageText(text, offset, limit) {
    const total = text.length;
    const start = Math.min(Math.max(0, Number(offset) || 0), total);
    const end = Math.min(total, start + Math.max(1, Math.min(Number(limit) || 8000, MAX_TEXT)));
    return { text: text.slice(start, end), start, end, total };
}

async function loadRun(runId) {
    return JSON.parse(await get(`/workflow/runs/${encodeURIComponent(runId)}`));
}

function runTask(run, taskId) {
    return (run.history || []).find(h => h.taskId !== undefined && h.taskId !== null
        && Number(h.taskId) === Number(taskId));
}

const TOOLS = [
    {
        name: "get_run",
        description: "Get a workflow run: status, inputs, timings, labels, failure reason, and its steps "
            + "(task id, node, status). Read-only.",
        parameters: [{ name: "runId", type: "number", description: "The run id", required: true }],
        handler: async (args) => clip(JSON.stringify(await loadRun(args.runId), null, 2)),
    },
    {
        name: "get_task",
        description: "Get the full input and output of one task (step) of a run. Read-only.",
        parameters: [
            { name: "runId", type: "number", description: "The run id", required: true },
            { name: "taskId", type: "number", description: "The task id from get_run", required: true },
        ],
        handler: async (args) => {
            const run = await loadRun(args.runId);
            if (!runTask(run, args.taskId)) {
                return `Task ${args.taskId} is not part of run ${args.runId}.`;
            }
            const task = JSON.parse(await get(`/subjects/${encodeURIComponent(run.subjectId)}/tasks/${encodeURIComponent(args.taskId)}`));
            return clip(JSON.stringify({ id: task.id, status: task.status, input: task.input, output: task.output }, null, 2));
        },
    },
    {
        name: "get_task_log",
        description: "Read part of a task's execution log. Use offset/limit (characters) to page. Read-only.",
        parameters: [
            { name: "runId", type: "number", description: "The run id", required: true },
            { name: "taskId", type: "number", description: "The task id from get_run", required: true },
            { name: "offset", type: "number", description: "Start character (default 0)", required: false },
            { name: "limit", type: "number", description: "Characters to read (default 8000)", required: false },
        ],
        handler: async (args) => {
            const run = await loadRun(args.runId);
            if (!runTask(run, args.taskId)) {
                return `Task ${args.taskId} is not part of run ${args.runId}.`;
            }
            let log;
            try {
                log = await get(`/subjects/${encodeURIComponent(run.subjectId)}/tasks/${encodeURIComponent(args.taskId)}/log`, "text/plain");
            } catch (e) {
                return `No execution log is available for task ${args.taskId}.`;
            }
            const page = pageText(log, args.offset, args.limit);
            return `[chars ${page.start}-${page.end} of ${page.total}]\n${page.text}`;
        },
    },
    {
        name: "get_trace",
        description: "Get the execution trace of a run (node timings and statuses, failed nodes first). Read-only.",
        parameters: [{ name: "runId", type: "number", description: "The run id", required: true }],
        handler: async (args) => {
            const run = await loadRun(args.runId);
            if (!run.traceId) {
                return `Run ${args.runId} has no trace.`;
            }
            const trace = JSON.parse(await get(`/traces/${encodeURIComponent(run.traceId)}`));
            const nodes = (trace.nodes || []).slice().sort((a, b) =>
                (/fail|error/i.test(String(b.status)) ? 1 : 0) - (/fail|error/i.test(String(a.status)) ? 1 : 0));
            return clip(JSON.stringify({ ...trace, nodes }, null, 2));
        },
    },
];

async function main() {
    const { Server } = require("@modelcontextprotocol/sdk/server/index.js");
    const { StdioServerTransport } = require("@modelcontextprotocol/sdk/server/stdio.js");
    const { ListToolsRequestSchema, CallToolRequestSchema } = require("@modelcontextprotocol/sdk/types.js");
    const server = new Server({ name: "axiom-run-summary", version: "1.0.0" }, { capabilities: { tools: {} } });
    server.setRequestHandler(ListToolsRequestSchema, async () => ({
        tools: TOOLS.map(t => ({
            name: t.name,
            description: t.description,
            inputSchema: {
                type: "object",
                properties: Object.fromEntries(t.parameters.map(p => [p.name, { type: p.type, description: p.description }])),
                required: t.parameters.filter(p => p.required).map(p => p.name),
            },
        })),
    }));
    server.setRequestHandler(CallToolRequestSchema, async (request) => {
        const tool = TOOLS.find(t => t.name === request.params.name);
        if (!tool) {
            return { content: [{ type: "text", text: "Unknown tool: " + request.params.name }], isError: true };
        }
        try {
            const text = await tool.handler(request.params.arguments || {});
            return { content: [{ type: "text", text: text || "OK" }] };
        } catch (error) {
            return { content: [{ type: "text", text: error.message }], isError: true };
        }
    });
    await server.connect(new StdioServerTransport());
}

module.exports = { TOOLS, setFetch, pageText };

if (require.main === module) {
    main().catch((e) => { console.error(e); process.exit(1); });
}
```

Before relying on field names, check the generated `WorkflowInstance` bean for the history entry's task id/
status/node property names (`grep -n "class WorkflowInstanceHistory\|taskId\|taskStatus" common/api/target/generated-sources/jaxrs/io/apitomy/axiom/api/beans/*.java`)
and the trace bean's node list property; adjust `runTask` and `get_trace` if they differ.

- [ ] **Step 4: Run node test + JUnit wrapper; PASS.**
- [ ] **Step 5: Commit** — `git commit -m "Add read-only run summary MCP server (#672)"`

---

### Task 8: Generation, poller, recovery, and MCP wiring

**Files:**
- Create: `app/.../summary/RunSummaryMcp.java`, `app/.../summary/RunSummaryPoller.java`
- Modify: `app/.../summary/RunSummaryService.java`, `app/.../McpConfigGenerator.java`,
  `agents/opencode/src/main/java/io/apitomy/axiom/agents/opencode/OpenCodeMcpManager.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/summary/RunSummaryGenerationTest.java`

**Interfaces:**
- Consumes: Tasks 1, 3, 4, 5, 7.
- Produces:
  - `RunSummaryMcp`: `static final String SERVER_NAME = "axiom-run-summary"`,
    `static final List<String> ALLOWED_TOOLS` (4 `mcp__axiom-run-summary__*` names),
    `McpServerDefinition serverDefinition()`, `Path writeConfigFile(long runId)`, `void cleanup(long runId)`.
  - `RunSummaryService`: `List<Long> claimPending(int max)`, `void generate(long runId)`,
    `int recoverInterrupted()`.
  - `RunSummaryPoller.poll()` (scheduled), `onStartup` recovery.

- [ ] **Step 1: Failing generation test** (mocks `AgentPool` like `LogCorrelationTest`)

```java
package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentRequest;
import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.app.AgentLease;
import io.apitomy.axiom.app.AgentPool;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunSummaryEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class RunSummaryGenerationTest {

    @Inject
    RunSummaryService service;

    @InjectMock
    AgentPool agentPool;

    Agent agent;

    @BeforeEach
    void setUp() {
        agent = Mockito.mock(Agent.class);
        when(agent.getType()).thenReturn("claude-code");
        when(agentPool.tryLease(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new AgentLease(null, "fake-agent", agent)));
    }

    private long pendingRun() {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.title = "Gen";
            run.subjectId = -1L;
            run.definitionId = -1L;
            run.definitionVersion = 1;
            run.instanceState = "{}";
            run.status = "completed";
            run.startedOn = Instant.now();
            run.completedOn = Instant.now();
            run.persist();
            WorkflowRunSummaryEntity.newPending(run, WorkflowRunSummaryEntity.AUTO).persist();
            return run.id;
        });
    }

    private WorkflowRunSummaryEntity row(long id) {
        return QuarkusTransaction.requiringNew().call(() -> WorkflowRunSummaryEntity.findById(id));
    }

    private void claimAndGenerate(long id) {
        assertTrue(service.claimPending(50).contains(id));
        service.generate(id);
    }

    @Test
    void successStoresSummaryAndRecordsUsage() {
        when(agent.execute(any())).thenReturn(CompletableFuture.completedFuture(new AgentResult(true,
                "{\"headline\":\"Done it\",\"body\":\"- a\"}", null, null, null, 0.01, 10L, 5L,
                "claude-code", "haiku")));
        long id = pendingRun();
        claimAndGenerate(id);
        WorkflowRunSummaryEntity s = row(id);
        assertEquals(WorkflowRunSummaryEntity.READY, s.status);
        assertEquals("Done it", s.headline);
        assertEquals("haiku", s.model);
        long usage = QuarkusTransaction.requiringNew().call(() ->
                AiUsageEntity.count("workflowRunId = ?1 and invocationType = 'run-summary'", id));
        assertEquals(1L, usage);
        ArgumentCaptor<AgentRequest> req = ArgumentCaptor.forClass(AgentRequest.class);
        Mockito.verify(agent).execute(req.capture());
        assertEquals(RunSummaryMcp.ALLOWED_TOOLS, req.getValue().getAllowedTools());
        assertEquals(10, req.getValue().getMaxSteps());
    }

    @Test
    void badOutputRetriesThenFails() {
        when(agent.execute(any())).thenReturn(CompletableFuture.completedFuture(AgentResult.success("nope")));
        long id = pendingRun();
        claimAndGenerate(id);
        assertEquals(WorkflowRunSummaryEntity.PENDING, row(id).status);
        claimAndGenerate(id);
        WorkflowRunSummaryEntity s = row(id);
        assertEquals(WorkflowRunSummaryEntity.FAILED, s.status);
        assertTrue(s.error.contains("JSON"));
    }

    @Test
    void noLeaseLeavesRowPending() {
        when(agentPool.tryLease(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        long id = pendingRun();
        claimAndGenerate(id);
        WorkflowRunSummaryEntity s = row(id);
        assertEquals(WorkflowRunSummaryEntity.PENDING, s.status);
        assertEquals(0, s.attempts);
    }

    @Test
    void recoveryResetsGeneratingRows() {
        long id = pendingRun();
        assertTrue(service.claimPending(50).contains(id));
        assertEquals(WorkflowRunSummaryEntity.GENERATING, row(id).status);
        assertTrue(service.recoverInterrupted() >= 1);
        assertEquals(WorkflowRunSummaryEntity.PENDING, row(id).status);
    }
}
```
(Check `AgentRequest` getter names — `getAllowedTools()`, `getMaxSteps()` — and the `AgentLease` package;
adjust imports to match.)

- [ ] **Step 2: Run; FAIL.**

- [ ] **Step 3: Implement `RunSummaryMcp`** — reuse the install mechanism. Extract
  `AssistantSessionManager.ensureMcpServerInstalled(String dirName, String resourceDir)` into a new
  `@ApplicationScoped app/.../McpServerInstaller.java` with `public Path ensureInstalled(String dirName,
  String resourceDir, String... files)` (move the body and the `installedMcpServers` map; keep
  `AssistantSessionManager` calling it — run `AssistantSessionManager*` tests after the move).

```java
package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.app.McpServerInstaller;
// plus McpServerDefinition, ClaudeMcpConfigWriter, AxiomHome — use the same imports as McpConfigGenerator
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Installs and describes the read-only {@code axiom-run-summary} MCP server. */
@ApplicationScoped
public class RunSummaryMcp {

    /** MCP server name. */
    public static final String SERVER_NAME = "axiom-run-summary";

    /** The only tools a summary agent may use. */
    public static final List<String> ALLOWED_TOOLS = List.of(
            "mcp__axiom-run-summary__get_run",
            "mcp__axiom-run-summary__get_task",
            "mcp__axiom-run-summary__get_task_log",
            "mcp__axiom-run-summary__get_trace");

    @Inject
    McpServerInstaller installer;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "9090")
    int httpPort;

    /**
     * @return the stdio server definition (installs the template on first use)
     * @throws IOException if installation fails
     */
    public McpServerDefinition serverDefinition() throws IOException {
        Path dir = installer.ensureInstalled("run-summary-mcp-server", "templates/axiom-run-summary-mcp/",
                "package.json", "server.js");
        return McpServerDefinition.stdio("node", List.of(dir.resolve("server.js").toAbsolutePath().toString()),
                Map.of("AXIOM_API_URL", "http://localhost:" + httpPort + "/api/v1"));
    }

    /**
     * Writes a Claude/Copilot MCP config containing only this server.
     *
     * @param runId the run (names the directory)
     * @return the config file
     * @throws IOException on failure
     */
    public Path writeConfigFile(long runId) throws IOException {
        Path dir = AxiomHome.path().resolve("run-summaries").resolve(Long.toString(runId));
        Files.createDirectories(dir);
        Path file = dir.resolve("mcp-config.json");
        Files.writeString(file, ClaudeMcpConfigWriter.toJson(objectMapper, Map.of(SERVER_NAME, serverDefinition())));
        McpConfigGenerator.restrictToOwner(file); // use whatever owner-only helper McpConfigGenerator uses
        return file;
    }

    /** Deletes the per-run config directory. @param runId the run */
    public void cleanup(long runId) {
        Path dir = AxiomHome.path().resolve("run-summaries").resolve(Long.toString(runId));
        try (java.util.stream.Stream<Path> paths = Files.exists(dir) ? Files.walk(dir) : java.util.stream.Stream.empty()) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // best effort
        }
    }
}
```
Match exact helper names (`ClaudeMcpConfigWriter.toJson`, `restrictToOwner`, `environmentIsolation.isolateAll`)
to what `McpConfigGenerator.generateMcpConfig` uses; wrap the map with `environmentIsolation.isolateAll(...)`
as it does.

- [ ] **Step 4: OpenCode wiring** — in `McpConfigGenerator.getMcpServersForOpenCode`, add (inject `RunSummaryMcp`):
```java
        if (hasRestrictions && allowedTools.stream().anyMatch(a -> a.startsWith("mcp__" + RunSummaryMcp.SERVER_NAME + "__"))) {
            try {
                servers.put(RunSummaryMcp.SERVER_NAME, runSummaryMcp.serverDefinition());
            } catch (Exception e) {
                LOG.errorf(e, "Failed to prepare the run summary MCP server");
            }
        }
```
In `OpenCodeMcpManager`: `BUILT_IN_SERVERS = Set.of("axiom-tools", "axiom-sdk", "axiom-run-summary");`.
Add a case to `OpenCodeMcpManagerSyncTest` asserting `axiom-run-summary` is not disconnected when absent from
the active set (copy the existing built-in test pattern). Run that test.

- [ ] **Step 5: Implement generation in `RunSummaryService`** (inject `AgentPool agentPool`, `AgentRegistry
  agentRegistry`, `RunSummaryMcp runSummaryMcp`; config `max-steps` default 10, `timeout-seconds` default 300):

```java
    /** Maximum attempts before a summary is marked failed. */
    static final int MAX_ATTEMPTS = 2;

    /**
     * Atomically claims pending summaries.
     *
     * @param max maximum rows to claim
     * @return claimed run ids
     */
    public List<Long> claimPending(int max) {
        List<Long> candidates = QuarkusTransaction.requiringNew().call(() -> WorkflowRunSummaryEntity
                .find("status = ?1 order by createdOn", WorkflowRunSummaryEntity.PENDING)
                .page(0, max).project(Long.class).list());
        // project(Long.class) needs a select clause in some Panache versions; if so use
        // find("select s.runId from WorkflowRunSummaryEntity s where s.status = ?1 order by s.createdOn")
        List<Long> claimed = new ArrayList<>();
        for (Long runId : candidates) {
            int updated = QuarkusTransaction.requiringNew().call(() -> WorkflowRunSummaryEntity.update(
                    "status = ?1 where runId = ?2 and status = ?3",
                    WorkflowRunSummaryEntity.GENERATING, runId, WorkflowRunSummaryEntity.PENDING));
            if (updated == 1) {
                claimed.add(runId);
            }
        }
        return claimed;
    }

    /**
     * Resets summaries left generating by a previous process.
     *
     * @return rows reset
     */
    public int recoverInterrupted() {
        return QuarkusTransaction.requiringNew().call(() -> WorkflowRunSummaryEntity.update(
                "status = ?1 where status = ?2", WorkflowRunSummaryEntity.PENDING, WorkflowRunSummaryEntity.GENERATING));
    }

    /**
     * Generates the summary of a claimed run (blocking; call off the request thread).
     *
     * @param runId a run whose summary row is {@code generating}
     */
    public void generate(long runId) {
        Prepared prepared = QuarkusTransaction.requiringNew().call(() -> prepare(runId));
        if (prepared == null) {
            return;
        }
        AgentLease lease = agentPool.tryLease(null, prepared.engine(), null, "run-summary", runId).orElse(null);
        if (lease == null) {
            QuarkusTransaction.requiringNew().run(() -> WorkflowRunSummaryEntity.update(
                    "status = ?1 where runId = ?2", WorkflowRunSummaryEntity.PENDING, runId));
            return;
        }
        long startedAt = System.currentTimeMillis();
        try {
            AgentRequest.Builder builder = AgentRequest.builder()
                    .prompt(prepared.prompt())
                    .model(prepared.model())
                    .allowedTools(RunSummaryMcp.ALLOWED_TOOLS)
                    .maxSteps(maxSteps)
                    .timeoutSeconds(timeoutSeconds)
                    .environment(Map.of())
                    .actionType("run-summary");
            if (!"opencode".equals(lease.agent().getType())) {
                builder.mcpConfigFile(runSummaryMcp.writeConfigFile(runId));
            } else {
                agentRegistry.getMcpManager("opencode").configureMcpServers(runId, Map.of(), RunSummaryMcp.ALLOWED_TOOLS);
            }
            AgentResult result = lease.agent().execute(builder.build())
                    .get(timeoutSeconds + 30L, java.util.concurrent.TimeUnit.SECONDS);
            if (!result.success()) {
                throw new IllegalStateException(result.errorMessage() != null ? result.errorMessage() : "The agent failed");
            }
            RunSummaryResponseParser.ParsedSummary parsed = RunSummaryResponseParser.parse(result.output());
            long durationMs = System.currentTimeMillis() - startedAt;
            QuarkusTransaction.requiringNew().run(() -> storeReady(runId, parsed, result, prepared, lease, durationMs));
        } catch (Exception e) {
            String message = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null
                    ? e.getCause().getMessage() : e.getMessage();
            QuarkusTransaction.requiringNew().run(() -> storeFailure(runId, message));
        } finally {
            agentPool.release(lease);
            runSummaryMcp.cleanup(runId);
        }
    }

    /** Everything needed to call the agent, read in one transaction. */
    record Prepared(String prompt, String engine, String model) { }

    private Prepared prepare(long runId) {
        WorkflowRunSummaryEntity summary = WorkflowRunSummaryEntity.findById(runId);
        WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
        if (summary == null || run == null || !WorkflowRunSummaryEntity.GENERATING.equals(summary.status)) {
            return null;
        }
        WorkflowDefinitionEntity definition = WorkflowDefinitionEntity.findById(run.definitionId);
        List<RunSummaryPromptBuilder.StepDigest> steps = TaskEntity.<TaskEntity>list("workflowRunId = ?1 order by id", runId)
                .stream()
                .map(t -> new RunSummaryPromptBuilder.StepDigest(t.id, t.nodeId, t.status, t.output))
                .toList();
        Double cost = AiUsageEntity.<AiUsageEntity>list("workflowRunId", runId).stream()
                .map(u -> u.costUsd).filter(java.util.Objects::nonNull).reduce(Double::sum).orElse(null);
        Long durationMs = run.completedOn != null ? run.completedOn.toEpochMilli() - run.startedOn.toEpochMilli() : null;
        String prompt = RunSummaryPromptBuilder.build(new RunSummaryPromptBuilder.RunDigest(run.id,
                definition != null ? definition.name : "Unknown", run.title, run.status, durationMs,
                run.failureReason, cost, steps));
        SystemConfigEntity config = SystemConfigEntity.<SystemConfigEntity>findAll().firstResult();
        String engine = config != null && config.summaryEngine != null ? config.summaryEngine
                : config != null && config.defaultEngine != null ? config.defaultEngine
                : agentRegistry.getDefaultAgentType();
        String model = config != null ? config.summaryModel : null;
        return new Prepared(prompt, engine, model);
    }

    private void storeReady(long runId, RunSummaryResponseParser.ParsedSummary parsed, AgentResult result,
                            Prepared prepared, AgentLease lease, long durationMs) {
        WorkflowRunSummaryEntity summary = WorkflowRunSummaryEntity.findById(runId);
        WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
        if (summary == null || run == null) {
            return; // run deleted meanwhile
        }
        summary.status = WorkflowRunSummaryEntity.READY;
        summary.headline = parsed.headline();
        summary.body = parsed.body();
        summary.error = null;
        summary.engine = result.engine() != null ? result.engine() : lease.agent().getType();
        summary.model = result.model() != null ? result.model() : prepared.model();
        summary.completedOn = Instant.now();
        AiUsageEntity usage = new AiUsageEntity();
        usage.invocationType = "run-summary";
        usage.subjectId = run.subjectId;
        usage.workflowRunId = run.id;
        usage.workflowDefinitionId = run.definitionId;
        usage.traceId = run.traceId;
        usage.agentId = lease.agentEntityId();
        usage.engine = summary.engine;
        usage.model = summary.model;
        usage.costUsd = result.costUsd();
        usage.inputTokens = result.inputTokens();
        usage.outputTokens = result.outputTokens();
        usage.durationMs = durationMs;
        usage.createdOn = Instant.now();
        usage.persist();
        markChanged(run);
    }

    private void storeFailure(long runId, String message) {
        WorkflowRunSummaryEntity summary = WorkflowRunSummaryEntity.findById(runId);
        WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
        if (summary == null || run == null) {
            return;
        }
        summary.attempts++;
        summary.error = message;
        if (summary.attempts < MAX_ATTEMPTS) {
            summary.status = WorkflowRunSummaryEntity.PENDING;
        } else {
            summary.status = WorkflowRunSummaryEntity.FAILED;
            summary.completedOn = Instant.now();
        }
        markChanged(run);
    }
```
Note: `storeFailure` keeps `error` on a retry so the UI could show it; `request()` clears it.

- [ ] **Step 6: Poller**

```java
package io.apitomy.axiom.app.summary;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.virtual.threads.VirtualThreads;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Picks up pending run summaries and generates them in the background. */
@ApplicationScoped
public class RunSummaryPoller {

    private static final Logger LOG = Logger.getLogger(RunSummaryPoller.class);

    @Inject
    RunSummaryService service;

    @ConfigProperty(name = "axiom.run-summary.batch-size", defaultValue = "2")
    int batchSize;

    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean shuttingDown;

    void onStartup(@Observes StartupEvent event) {
        try {
            int reset = service.recoverInterrupted();
            if (reset > 0) {
                LOG.infof("Re-queued %d interrupted run summaries", reset);
            }
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to recover interrupted run summaries");
        }
    }

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
        executor.shutdownNow();
    }

    @Scheduled(every = "${axiom.run-summary.poll-interval:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        if (shuttingDown || !service.isEnabled()) {
            return;
        }
        int free = batchSize - inFlight.get();
        if (free <= 0) {
            return;
        }
        List<Long> claimed = service.claimPending(free);
        for (Long runId : claimed) {
            inFlight.incrementAndGet();
            executor.submit(() -> {
                try {
                    service.generate(runId);
                } catch (RuntimeException e) {
                    LOG.errorf(e, "Run summary generation failed for run %d", runId);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }
}
```
(Drop the unused `VirtualThreads` import if not needed.)

- [ ] **Step 7: Run generation test, `OpenCodeMcpManagerSyncTest`, `AssistantSessionManager*` tests; PASS.**

Run: `mvn -q -pl app,agents/opencode -am test -Dtest='RunSummaryGenerationTest,OpenCodeMcpManagerSyncTest,AssistantSessionManager*' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 8: Commit** — `git commit -m "Generate run summaries in the background with read-only tools (#672)"`

---

### Task 9: UI

**Files:**
- Modify: `ui/src/config/api.ts`
- Create: `ui/src/components/RunAiSummaryCard.tsx`, `ui/src/components/RunAiSummaryCard.test.tsx`
- Modify: `ui/src/pages/WorkflowRunsPage.tsx`, `ui/src/pages/workbench/RunsList.tsx`,
  `ui/src/pages/WorkflowRunDetailPage.tsx`, `ui/src/pages/EngineSettingsPage.tsx`,
  `ui/src/pages/WorkflowDefinitionDetailPage.tsx`, and their existing `*.test.tsx`

**Interfaces:**
- Consumes: REST from Tasks 2 and 6.
- Produces: TS `WorkflowRunAiSummary`; `generateWorkflowRunAiSummary(runId: number): Promise<void>`;
  `<RunAiSummaryCard runId status summary onRequested />`; `RunAiSummaryHeadline` for lists.

- [ ] **Step 1: `api.ts` types and call**

```ts
/** The AI-generated summary of a workflow run. */
export interface WorkflowRunAiSummary {
    status: "pending" | "generating" | "ready" | "failed";
    headline?: string;
    body?: string;
    error?: string;
    engine?: string;
    model?: string;
    trigger?: "auto" | "manual";
    completedOn?: string;
}

/**
 * Requests generation (or regeneration) of a finished run's AI summary.
 * @param runId the run
 */
export async function generateWorkflowRunAiSummary(runId: number): Promise<void> {
    const response: Response = await fetch(`${API_BASE}/workflow/runs/${runId}/ai-summary`, { method: "POST" });
    if (!response.ok) {
        throw new Error(await extractErrorMessage(response, "Failed to request the run summary"));
    }
}
```
Use the same base-URL constant and error helper as `rerunWorkflowRun` (copy its shape exactly). Add
`aiSummary?: WorkflowRunAiSummary` to `WorkflowRunSummary` and `WorkflowInstanceInfo`; `summarizeRuns?: boolean`
to the workflow definition type and its update request type; `summaryEngine?: string; summaryModel?: string` to
`SystemConfig` and `UpdateSystemConfigRequest`.

- [ ] **Step 2: Failing card test**

```tsx
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { RunAiSummaryCard } from "./RunAiSummaryCard";

vi.mock("../config/api", () => ({ generateWorkflowRunAiSummary: vi.fn().mockResolvedValue(undefined) }));
import { generateWorkflowRunAiSummary } from "../config/api";

describe("RunAiSummaryCard", () => {
    it("is hidden for active runs", () => {
        const { container } = render(<RunAiSummaryCard runId={1} runStatus="running" />);
        expect(container).toBeEmptyDOMElement();
    });

    it("offers to summarize a finished run without a summary", async () => {
        render(<RunAiSummaryCard runId={3} runStatus="cancelled" />);
        fireEvent.click(screen.getByRole("button", { name: "Summarize this run" }));
        await waitFor(() => expect(generateWorkflowRunAiSummary).toHaveBeenCalledWith(3));
    });

    it("shows headline, body and engine when ready", () => {
        render(<RunAiSummaryCard runId={1} runStatus="completed"
            summary={{ status: "ready", headline: "Triaged 14 issues", body: "- two escalated",
                engine: "claude-code", model: "haiku" }} />);
        expect(screen.getByText("Triaged 14 issues")).toBeInTheDocument();
        expect(screen.getByText("two escalated")).toBeInTheDocument();
        expect(screen.getByText(/claude-code\/haiku/)).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Regenerate" })).toBeInTheDocument();
    });

    it("shows progress while generating", () => {
        render(<RunAiSummaryCard runId={1} runStatus="failed" summary={{ status: "generating" }} />);
        expect(screen.getByText("Generating summary…")).toBeInTheDocument();
    });

    it("shows the error and Retry when failed", () => {
        render(<RunAiSummaryCard runId={1} runStatus="failed" summary={{ status: "failed", error: "timeout" }} />);
        expect(screen.getByText(/timeout/)).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
    });
});
```

- [ ] **Step 3: Run `npm test -- src/components/RunAiSummaryCard.test.tsx`; FAIL.**

- [ ] **Step 4: Implement the card**

```tsx
import React, { useState } from "react";
import { Alert, Button, Card, CardBody, CardFooter, CardTitle, Content, Flex, FlexItem, Skeleton, Spinner }
    from "@patternfly/react-core";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { generateWorkflowRunAiSummary, WorkflowRunAiSummary } from "../config/api";
import "../axiom-markdown.css";

const FINISHED: string[] = ["completed", "failed", "cancelled"];

/** Props of {@link RunAiSummaryCard}. */
export interface RunAiSummaryCardProps {
    runId: number;
    runStatus: string;
    summary?: WorkflowRunAiSummary;
    /** Called after a (re)generation request is accepted, e.g. to reload the run. */
    onRequested?: () => void;
}

/** The AI summary of a finished run, with generate/regenerate actions. Hidden for active runs. */
export function RunAiSummaryCard({ runId, runStatus, summary, onRequested }: RunAiSummaryCardProps): React.ReactElement | null {
    const [requesting, setRequesting] = useState<boolean>(false);
    const [error, setError] = useState<string | null>(null);
    if (!FINISHED.includes(runStatus)) {
        return null;
    }
    const request = async (): Promise<void> => {
        setRequesting(true);
        setError(null);
        try {
            await generateWorkflowRunAiSummary(runId);
            onRequested?.();
        } catch (e) {
            setError(e instanceof Error ? e.message : String(e));
        } finally {
            setRequesting(false);
        }
    };
    const busy: boolean = summary?.status === "pending" || summary?.status === "generating";
    return (
        <Card isCompact className="axiom-run-ai-summary">
            <CardTitle>AI summary</CardTitle>
            <CardBody>
                {error && <Alert isInline variant="danger" title={error} />}
                {!summary && (
                    <Button variant="secondary" onClick={request} isLoading={requesting}>Summarize this run</Button>
                )}
                {busy && (
                    <Flex direction={{ default: "column" }}>
                        <FlexItem><Spinner size="sm" /> Generating summary…</FlexItem>
                        <Skeleton width="60%" /><Skeleton width="80%" />
                    </Flex>
                )}
                {summary?.status === "failed" && (
                    <Alert isInline variant="warning" title={`The summary could not be generated: ${summary.error ?? "unknown error"}`}
                        actionLinks={<Button variant="link" isInline onClick={request} isLoading={requesting}>Retry</Button>} />
                )}
                {summary?.status === "ready" && (
                    <>
                        <Content component="p"><strong>{summary.headline}</strong></Content>
                        {summary.body && (
                            <div className="axiom-markdown"><Content><Markdown remarkPlugins={[remarkGfm]}>{summary.body}</Markdown></Content></div>
                        )}
                    </>
                )}
            </CardBody>
            {summary?.status === "ready" && (
                <CardFooter>
                    <Flex justifyContent={{ default: "justifyContentSpaceBetween" }}>
                        <Content component="small">
                            Generated by {summary.engine}{summary.model ? `/${summary.model}` : ""}
                            {summary.completedOn ? ` · ${new Date(summary.completedOn).toLocaleString()}` : ""}
                        </Content>
                        <Button variant="link" isInline onClick={request} isLoading={requesting}>Regenerate</Button>
                    </Flex>
                </CardFooter>
            )}
        </Card>
    );
}

/** One-line headline (or progress) for run lists; renders nothing otherwise. */
export function RunAiSummaryHeadline({ summary }: { summary?: WorkflowRunAiSummary }): React.ReactElement | null {
    if (summary?.status === "ready" && summary.headline) {
        return <Content component="small" className="axiom-run-ai-headline">{summary.headline}</Content>;
    }
    if (summary?.status === "pending" || summary?.status === "generating") {
        return <Content component="small" className="axiom-run-ai-headline"><Spinner size="sm" /> Summarizing…</Content>;
    }
    return null;
}
```

- [ ] **Step 5: Run the card test; PASS.**

- [ ] **Step 6: Wire into pages, test first for each.**
  - `WorkflowRunsPage.test.tsx`: add a run with `aiSummary: { status: "ready", headline: "Triaged 14 issues" }`
    to the mocked `fetchWorkflowRuns` result; assert the text is shown. Then in the "Run" cell, after the title
    line, render `<RunAiSummaryHeadline summary={run.aiSummary} />`. Same in `workbench/RunsList.tsx` (add a
    `RunsList.test.tsx` case if a test file exists; otherwise create one rendering a single run).
  - `WorkflowRunDetailPage.test.tsx`: mock `getWorkflowRun` with `status: "completed"` and a ready `aiSummary`;
    assert the headline renders; add `generateWorkflowRunAiSummary: vi.fn()` to the `vi.mock`. Then render
    `<RunAiSummaryCard runId={numericRunId} runStatus={run.status} summary={run.aiSummary} onRequested={load} />`
    between the header `Flex` and `<Tabs>`. The existing SSE `workflow-updated` handler already reloads.
  - `EngineSettingsPage`: add a test that changing the summary engine select calls `updateSystemConfig` with
    `{ defaultEngine: <current>, summaryEngine: "opencode" }`. Implement a "Run summaries" `Card` with
    `FormSelect id="summary-engine"` (first option `value=""` label "System default", then each
    `config.engines[].type`) and `FormSelect id="summary-model"` (first option `""` "Engine default", then the
    models of the selected engine — or of the default engine when "System default" — from
    `config.engines[].models`). Each change calls
    `updateSystemConfig({ defaultEngine: config.defaultEngine, summaryEngine, summaryModel })`; changing the engine
    resets the model to `""`. Helper text: "Summaries are generated only for workflows that opt in. Empty values
    use the system default engine and that engine's default model."
  - `WorkflowDefinitionDetailPage`: test that the Edit modal shows an unchecked "Generate AI summaries for
    completed and failed runs" checkbox for a definition without `summarizeRuns`, and that saving after checking
    it calls `updateWorkflowDefinition(id, expect.objectContaining({ summarizeRuns: true }))`. Implement with a
    PatternFly `Checkbox id="summarize-runs"` bound to modal state initialized from
    `definition.summarizeRuns ?? false`.

- [ ] **Step 7: Run `npm test` and `npm run lint` (or the repo's lint script) in `ui/`; all PASS.**
- [ ] **Step 8: Commit** — `git commit -m "Show AI run summaries and their settings in the UI (#672)"`

---

### Task 10: Full verification and live check

**Files:** none new (fix whatever fails).

- [ ] **Step 1: Full backend build** — `./build.sh` (or `mvn -q install`); all tests PASS, including
  `MigrationSchemaValidationTest` and `RunSummaryMcpServerScriptTest`.
- [ ] **Step 2: Live check on an isolated instance (never port 9191/9090):**

```bash
rm -rf /tmp/axiom-test && mkdir -p /tmp/axiom-test/home
AXIOM_HOME=/tmp/axiom-test/home ./dev.sh --portOffset=500 --dataDir=/tmp/axiom-test/data
```
  With the UI on the offset port (or `--skip-ui` + API only on 9590):
  1. Create a workflow; confirm the opt-in checkbox is unchecked. Run it → no summary row
     (`GET /api/v1/workflow/runs/{id}` has no `aiSummary`).
  2. Enable `summarizeRuns`, set a cheap summary model in Settings, run again → the list shows
     "Summarizing…", then a headline; the detail card shows body + "Generated by …".
  3. Make a step fail → summary explains the failure (check the agent used `get_task_log` in its log if
     visible).
  4. Cancel a running run → no automatic summary; "Summarize this run" works.
  5. Delete the run's subject → `select count(*) from workflow_run_summary` is 0 for those runs.
  6. Check AI usage reporting lists `run-summary` costs.
- [ ] **Step 3: Stop the test instance.**
- [ ] **Step 4: Update docs** — add a short "AI run summaries" section to the user guide page covering workflow
  runs (find it with `grep -ril "workflow run" docs/`), wrapped at 110 characters, and the new
  `axiom.run-summary.*` properties to the configuration reference if one exists. Commit:
  `git commit -m "Document AI run summaries (#672)"`.
