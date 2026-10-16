# Workbench Phase 2a: Composition Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Persist workbench compositions (one workflow plus its action types and tools as server-side drafts
with revision history), expose them through a UI-facing REST API, validate drafts against drafts plus the
database, and publish selected drafts transactionally.

**Architecture:**
- Three Panache entities (`composition`, `composition_item`, `composition_revision`) are added to the V1
  baseline.
- A `CompositionService` owns creating, seeding, saving, revisions and restore. Every content save checks a
  `baseRevision` (an Apitomy Flow content hash) and fires an SSE event after commit.
- A `CompositionValidator` builds a `ValidationContext` overlaying the drafts on the database.
- A `CompositionPublisher` checks dependencies, validity and base conflicts, then calls
  `ImportExportService.importOrUpdatePack`.
- `CompositionsResourceImpl` implements the generated `CompositionsResource`.
- The agent side (turns, change sets, MCP) is Phase 2b.

**Tech Stack:** Java 25, Quarkus 3.40.1, Hibernate Panache, H2 and Flyway, Jackson, Apitomy Flow engine
3.0.1 (`io.apitomy.flow.changeset.ContentRevision`, `ChangeSets`), JUnit 5, RestAssured.

**Spec:** `docs/superpowers/specs/2026-10-08-workflow-workbench-design.md`: "Dependencies on Apitomy Flow",
"§1 Composition model", "§1 Publish", "§3 REST API", "§4 Error handling" and "§6 Phasing" (2a).

## Global Constraints

- REST is contract-first. Edit `common/api/src/main/resources/openapi.json` first, then implement the
  generated `io.apitomy.axiom.api.CompositionsResource`. Never put `@Path` on the implementation class, and
  use the generated beans in `io.apitomy.axiom.api.beans`.
- **Never run `mvn clean`, `./build.sh` or any `mvn install`.** The user may have `quarkus:dev` running from
  the main checkout. Regenerate and test through the reactor:
  `mvn -q -pl app -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`.
- Java style: Javadoc on public types and methods, 4-space indent, explicit types (no ambiguous `var`),
  JUnit 5. Use `git grep`, not `rg`.
- Commit with conventional commit messages and no AI attribution. Do not push.
- Schema: edit `app/src/main/resources/db/migration/V1__baseline.sql` in place. Do not add a V2. Tests use
  Hibernate drop-and-create, and `MigrationSchemaValidationTest` checks that the baseline matches the
  entities.
- Supported composition item types: `workflow`, `action-type`, `tool` (the `ConfigItemType` JSON names).
  Exactly one workflow per composition.
- Revisions: `contentRevision = ContentRevision.of(content)` over the stored JSON exactly as stored, for every
  item type. Layout-only workflow edits (node `position`) keep the same revision.
- Author values: `user`, `ai`. Turn state values: `idle`, `ai-working`. Apply mode values: `live`, `review`.
- Workflow item content is the Flow `Workflow` document. Its pack form is `{name, description?, content}`,
  where `name` is the composition item's name (the workflow definition name), not `content.name`.
- Action type and tool item content is the configuration-pack item JSON. The item name is `content.name`.
- SSE event type `composition-item-changed` with data
  `{"compositionId":N,"itemId":N,"contentRevision":"...","author":"...","turnId":"..."|null}`. It is fired
  only after the transaction commits.
- HTTP mapping: unknown composition, item or revision → 404. Invalid input → 400. Stale `baseRevision`, a
  duplicate name, a second workflow, or a publish base conflict without `force` → 409. Publish blocked by
  problems → 422.

## File Map

| File | Change |
|---|---|
| `pom.xml`, `core/pom.xml`, `app/pom.xml` | Property `apitomy-flow.version` = 3.0.1 |
| `core/src/main/java/io/apitomy/axiom/core/entities/Composition{,Item,Revision}Entity.java` | Create |
| `app/src/main/resources/db/migration/V1__baseline.sql` | Add the three tables, sequences and foreign keys |
| `core/src/main/java/io/apitomy/axiom/core/events/SseEvent.java` | Add `compositionItemChanged(...)` |
| `app/src/main/java/io/apitomy/axiom/app/WorkflowTemplates.java` | Create (moved from `WorkflowDefinitionsResourceImpl`) |
| `app/src/main/java/io/apitomy/axiom/app/ImportExportService.java` | Make `serializeTool` and `serializeActionType` public |
| `app/src/main/java/io/apitomy/axiom/app/validation/ValidationContextFactory.java` | Add `forItems(...)` |
| `app/src/main/java/io/apitomy/axiom/app/composition/*` | Create: `CompositionItems`, exceptions, `CompositionService`, `CompositionValidator`, `CompositionPublisher` |
| `app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java` | Create |
| `common/api/src/main/resources/openapi.json` | Compositions paths and schemas |
| Tests under `app/src/test/java/io/apitomy/axiom/app/composition/` | Create |

---

### Task 1: Upgrade the Apitomy Flow engine to 3.0.1

**Files:**
- Modify: `pom.xml` (the root `<properties>`, next to `apitomy-codegen.version` at about line 58)
- Modify: `core/pom.xml:36-38` and `app/pom.xml:69-71`
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/FlowChangeSetSmokeTest.java`

**Interfaces:**
- Produces: Flow 3.0.1 on the classpath. Later tasks use:
  - `io.apitomy.flow.changeset.ContentRevision.of(JsonNode)` returns a `String` starting with `sha256:`.
  - `ChangeSets.applyChecked(JsonNode workflow, JsonNode changeSet)` returns a `ChangeSetResult`, either
    `Applied(ObjectNode workflow)` or `Rejected(ChangeSetError(String code, Integer opIndex, String reason))`.

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.flow.changeset.ChangeSetResult;
import io.apitomy.flow.changeset.ChangeSets;
import io.apitomy.flow.changeset.ContentRevision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test proving Axiom can use Apitomy Flow 3.x change sets and content revisions server-side.
 */
class FlowChangeSetSmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WORKFLOW = """
            {"id":"w","name":"W","nodes":[
              {"id":"s","type":"start","name":"Start","config":{},"position":{"x":0,"y":0}},
              {"id":"e","type":"end","name":"End","config":{}}],
             "edges":[{"id":"e1","source":"s","target":"e","priority":0,"isDefault":true}]}
            """;

    @Test
    void revisionIgnoresLayout() throws Exception {
        ObjectNode workflow = (ObjectNode) MAPPER.readTree(WORKFLOW);
        String before = ContentRevision.of(workflow);
        ((ObjectNode) workflow.path("nodes").get(0).path("position")).put("x", 500);
        assertTrue(before.startsWith("sha256:"));
        assertEquals(before, ContentRevision.of(workflow));
    }

    @Test
    void appliesChangeSetAgainstCurrentRevision() throws Exception {
        JsonNode workflow = MAPPER.readTree(WORKFLOW);
        JsonNode changeSet = MAPPER.readTree("""
                {"id":"cs1","baseRevision":"%s","author":"agent:axiom-workbench","summary":"rename",
                 "ops":[{"op":"updateNode","id":"s","patch":{"name":"Begin"}}]}
                """.formatted(ContentRevision.of(workflow)));
        ChangeSetResult result = ChangeSets.applyChecked(workflow, changeSet);
        ChangeSetResult.Applied applied = assertInstanceOf(ChangeSetResult.Applied.class, result);
        assertEquals("Begin", applied.workflow().path("nodes").get(0).path("name").asText());
    }

    @Test
    void staleChangeSetIsRejected() throws Exception {
        JsonNode changeSet = MAPPER.readTree("""
                {"id":"cs2","baseRevision":"sha256:old","author":"agent:axiom-workbench","summary":"x",
                 "ops":[{"op":"removeNode","id":"e"}]}
                """);
        ChangeSetResult result = ChangeSets.applyChecked(MAPPER.readTree(WORKFLOW), changeSet);
        assertEquals("stale", assertInstanceOf(ChangeSetResult.Rejected.class, result).error().code());
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=FlowChangeSetSmokeTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: a compilation failure, because the package `io.apitomy.flow.changeset` does not exist in 2.7.0.

- [ ] **Step 3: Upgrade the dependency**

In the root `pom.xml` `<properties>`, add `<apitomy-flow.version>3.0.1</apitomy-flow.version>`. In
`core/pom.xml` and `app/pom.xml`, change the `apitomy-flow-engine` `<version>2.7.0</version>` to
`<version>${apitomy-flow.version}</version>`.

- [ ] **Step 4: Run the test and confirm it passes**

Run the same command. Expected: PASS (3 tests).

- [ ] **Step 5: Run the full build to catch engine regressions**

Run: `mvn -q package`. Expected: exit 0. Count failures across the reports with
`grep -l "<failure\|<error" app/target/surefire-reports/*.xml core/target/surefire-reports/*.xml`.
Expected: no files listed. If anything fails, report BLOCKED with the failures. Do not change unrelated
code.

- [ ] **Step 6: Commit**

```bash
git add pom.xml core/pom.xml app/pom.xml \
  app/src/test/java/io/apitomy/axiom/app/composition/FlowChangeSetSmokeTest.java
git commit -m "build: upgrade Apitomy Flow engine to 3.0.1"
```

---

### Task 2: Composition entities and baseline schema

**Files:**
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/CompositionEntity.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/CompositionItemEntity.java`
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/CompositionRevisionEntity.java`
- Modify: `app/src/main/resources/db/migration/V1__baseline.sql`
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/CompositionEntityTest.java`

**Interfaces:**
- Produces the entity classes with public fields, exactly as below. Later tasks use them by these names.

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the composition entities persist and load with their defaults.
 */
@QuarkusTest
class CompositionEntityTest {

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    @Test
    void persistsCompositionItemAndRevision() {
        long itemId = QuarkusTransaction.requiringNew().call(() -> {
            CompositionEntity composition = new CompositionEntity();
            composition.name = "c1";
            composition.createdOn = Instant.now();
            composition.updatedOn = composition.createdOn;
            composition.persist();

            CompositionItemEntity item = new CompositionItemEntity();
            item.compositionId = composition.id;
            item.itemType = "tool";
            item.name = "t1";
            item.content = "{\"name\":\"t1\"}";
            item.contentRevision = "sha256:x";
            item.lastAuthor = "user";
            item.createdOn = Instant.now();
            item.updatedOn = item.createdOn;
            item.persist();

            CompositionRevisionEntity revision = new CompositionRevisionEntity();
            revision.itemId = item.id;
            revision.content = item.content;
            revision.contentRevision = item.contentRevision;
            revision.author = "user";
            revision.createdOn = Instant.now();
            revision.persist();
            return item.id;
        });

        QuarkusTransaction.requiringNew().run(() -> {
            CompositionItemEntity item = CompositionItemEntity.findById(itemId);
            CompositionEntity composition = CompositionEntity.findById(item.compositionId);
            assertEquals(CompositionEntity.TURN_IDLE, composition.turnState);
            assertEquals(CompositionEntity.APPLY_LIVE, composition.applyMode);
            assertEquals(1, CompositionRevisionEntity.count("itemId", itemId));
        });
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=CompositionEntityTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: a compilation failure, because the entities do not exist.

- [ ] **Step 3: Create the entities**

`CompositionEntity.java`:

```java
package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A workbench composition: one workflow plus the action types and tools it uses, edited as drafts by the user
 * and the AI until published.
 */
@Entity
@Table(name = "composition")
public class CompositionEntity extends PanacheEntity {

    /** No AI turn is running. */
    public static final String TURN_IDLE = "idle";
    /** An AI turn is running. */
    public static final String TURN_AI_WORKING = "ai-working";
    /** AI workflow change sets are applied immediately. */
    public static final String APPLY_LIVE = "live";
    /** AI workflow change sets are staged for review. */
    public static final String APPLY_REVIEW = "review";

    @Column(nullable = false)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String goal;

    public String engine;

    public String model;

    @Column(name = "assistant_session_id")
    public String assistantSessionId;

    @Column(name = "turn_state", nullable = false)
    public String turnState = TURN_IDLE;

    @Column(name = "current_turn_id")
    public String currentTurnId;

    @Column(name = "apply_mode", nullable = false)
    public String applyMode = APPLY_LIVE;

    @Column(name = "pending_change_set", columnDefinition = "TEXT")
    public String pendingChangeSet;

    @Column(name = "pending_turn_id")
    public String pendingTurnId;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "updated_on", nullable = false)
    public Instant updatedOn;
}
```

`CompositionItemEntity.java`:

```java
package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A draft item in a composition: a workflow (Flow document), an action type or a tool (pack item JSON).
 */
@Entity
@Table(name = "composition_item",
        uniqueConstraints = @UniqueConstraint(columnNames = {"composition_id", "item_type", "name"}))
public class CompositionItemEntity extends PanacheEntity {

    @Column(name = "composition_id", nullable = false)
    public Long compositionId;

    /** The {@code ConfigItemType} JSON name: workflow, action-type or tool. */
    @Column(name = "item_type", nullable = false)
    public String itemType;

    @Column(nullable = false)
    public String name;

    /** Database id of the published item this draft edits, or null for a new item. */
    @Column(name = "base_item_id")
    public Long baseItemId;

    /** Hash of the published item's JSON when it was loaded or last published. */
    @Column(name = "base_content_revision")
    public String baseContentRevision;

    @Column(columnDefinition = "TEXT", nullable = false)
    public String content;

    @Column(name = "content_revision", nullable = false)
    public String contentRevision;

    @Column(name = "last_author", nullable = false)
    public String lastAuthor;

    @Column(name = "last_turn_id")
    public String lastTurnId;

    @Column(name = "published_revision")
    public String publishedRevision;

    @Column(name = "published_on")
    public Instant publishedOn;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "updated_on", nullable = false)
    public Instant updatedOn;
}
```

`CompositionRevisionEntity.java`:

```java
package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One saved version of a composition item's content, with who made it.
 */
@Entity
@Table(name = "composition_revision")
public class CompositionRevisionEntity extends PanacheEntity {

    @Column(name = "item_id", nullable = false)
    public Long itemId;

    @Column(columnDefinition = "TEXT", nullable = false)
    public String content;

    @Column(name = "content_revision", nullable = false)
    public String contentRevision;

    /** {@code user} or {@code ai}. */
    @Column(nullable = false)
    public String author;

    @Column(name = "turn_id")
    public String turnId;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;
}
```

- [ ] **Step 4: Add the tables to the V1 baseline**

In `V1__baseline.sql`, add the sequences next to the other `create sequence` statements:

```sql
    create sequence composition_SEQ start with 1 increment by 50;
    create sequence composition_item_SEQ start with 1 increment by 50;
    create sequence composition_revision_SEQ start with 1 increment by 50;
```

Add the tables next to the other `create table` statements:

```sql
    create table composition (
        created_on timestamp(6) not null,
        id bigint not null,
        updated_on timestamp(6) not null,
        apply_mode varchar(255) not null,
        assistant_session_id varchar(255),
        current_turn_id varchar(255),
        engine varchar(255),
        goal TEXT,
        model varchar(255),
        name varchar(255) not null,
        pending_change_set TEXT,
        pending_turn_id varchar(255),
        turn_state varchar(255) not null,
        primary key (id)
    );

    create table composition_item (
        base_item_id bigint,
        composition_id bigint not null,
        created_on timestamp(6) not null,
        id bigint not null,
        published_on timestamp(6),
        updated_on timestamp(6) not null,
        base_content_revision varchar(255),
        content TEXT not null,
        content_revision varchar(255) not null,
        item_type varchar(255) not null,
        last_author varchar(255) not null,
        last_turn_id varchar(255),
        name varchar(255) not null,
        published_revision varchar(255),
        primary key (id),
        unique (composition_id, item_type, name)
    );

    create table composition_revision (
        created_on timestamp(6) not null,
        id bigint not null,
        item_id bigint not null,
        author varchar(255) not null,
        content TEXT not null,
        content_revision varchar(255) not null,
        turn_id varchar(255),
        primary key (id)
    );
```

Add the foreign keys next to `alter table workflow_definition_version add foreign key ...`:

```sql
    alter table composition_item add foreign key (composition_id) references composition (id) on delete cascade;
    alter table composition_revision add foreign key (item_id) references composition_item (id) on delete cascade;
```

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `mvn -q -pl app -am test -Dtest='CompositionEntityTest,MigrationSchemaValidationTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS. If `MigrationSchemaValidationTest` reports a column mismatch, fix the SQL to match the
entity, never the reverse.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/io/apitomy/axiom/core/entities/Composition*.java \
  app/src/main/resources/db/migration/V1__baseline.sql \
  app/src/test/java/io/apitomy/axiom/app/composition/CompositionEntityTest.java
git commit -m "feat(workbench): add composition entities and schema"
```

---
### Task 3: Item helpers (`CompositionItems`, `WorkflowTemplates`, public serializers)

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/WorkflowTemplates.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/WorkflowDefinitionsResourceImpl.java`
  (`createEmptyWorkflowContent` at about line 444)
- Modify: `app/src/main/java/io/apitomy/axiom/app/ImportExportService.java` (`serializeTool` at about line
  725 and `serializeActionType` at about line 757: make them `public` and add Javadoc)
- Create: `app/src/main/java/io/apitomy/axiom/app/composition/CompositionItems.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/CompositionItemsTest.java`

**Interfaces:**
- Produces:
  - `WorkflowTemplates.emptyWorkflow(String name, ObjectMapper mapper): ObjectNode`
  - `ImportExportService.serializeTool(ToolDefinitionEntity): ObjectNode` (now public)
  - `ImportExportService.serializeActionType(ActionTypeEntity): ObjectNode` (now public)
  - `CompositionItems` static methods, exactly as listed in the code below: `SUPPORTED`,
    `supportedType(String)`, `revisionOf(JsonNode)`, `nameOf(JsonNode)`, `blank(...)`, `toPackItem(...)`,
    `packArray(...)`, `referencedActionTypes(JsonNode)`, `referencedTools(JsonNode)`.

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the composition item helpers.
 */
class CompositionItemsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void supportedTypesAreWorkflowActionTypeAndTool() {
        assertEquals(ConfigItemType.WORKFLOW, CompositionItems.supportedType("workflow"));
        assertEquals(ConfigItemType.ACTION_TYPE, CompositionItems.supportedType("action-type"));
        assertEquals(ConfigItemType.TOOL, CompositionItems.supportedType("tool"));
        assertThrows(IllegalArgumentException.class, () -> CompositionItems.supportedType("toolset"));
        assertThrows(IllegalArgumentException.class, () -> CompositionItems.supportedType("nope"));
    }

    @Test
    void blankWorkflowHasStartAndEnd() {
        ObjectNode workflow = CompositionItems.blank(ConfigItemType.WORKFLOW, "wf", MAPPER);
        assertEquals("wf", workflow.path("name").asText());
        assertEquals(2, workflow.path("nodes").size());
        assertEquals("start", workflow.path("nodes").get(0).path("type").asText());
        assertTrue(CompositionItems.revisionOf(workflow).startsWith("sha256:"));
    }

    @Test
    void blankActionTypeAndToolCarryTheirName() {
        assertEquals("at", CompositionItems.nameOf(CompositionItems.blank(ConfigItemType.ACTION_TYPE, "at", MAPPER)));
        assertEquals("node", CompositionItems.blank(ConfigItemType.ACTION_TYPE, "at", MAPPER)
                .path("executionMode").asText());
        assertEquals("t", CompositionItems.nameOf(CompositionItems.blank(ConfigItemType.TOOL, "t", MAPPER)));
    }

    @Test
    void nameOfIgnoresBlank() throws Exception {
        assertNull(CompositionItems.nameOf(MAPPER.readTree("{\"name\":\"  \"}")));
        assertNull(CompositionItems.nameOf(MAPPER.readTree("{}")));
        assertEquals("x", CompositionItems.nameOf(MAPPER.readTree("{\"name\":\" x \"}")));
    }

    @Test
    void workflowPackItemWrapsTheDocument() throws Exception {
        JsonNode doc = MAPPER.readTree("{\"id\":\"w\",\"name\":\"Doc name\",\"description\":\"d\",\"nodes\":[],\"edges\":[]}");
        ObjectNode item = CompositionItems.toPackItem(ConfigItemType.WORKFLOW, "wf-def", doc, MAPPER);
        assertEquals("wf-def", item.path("name").asText());
        assertEquals("d", item.path("description").asText());
        assertEquals(doc, item.path("content"));
        assertEquals("workflowDefinitions", CompositionItems.packArray(ConfigItemType.WORKFLOW));
    }

    @Test
    void actionTypePackItemIsTheContent() throws Exception {
        JsonNode content = MAPPER.readTree("{\"name\":\"at\",\"executionMode\":\"node\"}");
        assertEquals(content, CompositionItems.toPackItem(ConfigItemType.ACTION_TYPE, "at", content, MAPPER));
        assertEquals("actionTypes", CompositionItems.packArray(ConfigItemType.ACTION_TYPE));
        assertEquals("tools", CompositionItems.packArray(ConfigItemType.TOOL));
    }

    @Test
    void findsReferencedActionTypesExcludingSdk() throws Exception {
        JsonNode workflow = MAPPER.readTree("""
                {"nodes":[{"id":"a","type":"action","config":{"actionType":"triage"}},
                          {"id":"b","type":"action","config":{"actionType":"sdk:add-label"}},
                          {"id":"c","type":"action","config":{"actionType":"triage"}},
                          {"id":"s","type":"start","config":{}}]}
                """);
        assertEquals(Set.of("triage"), CompositionItems.referencedActionTypes(workflow));
    }

    @Test
    void findsReferencedToolsInStringOrArrayAllowedTools() throws Exception {
        JsonNode asString = MAPPER.readTree(
                "{\"allowedTools\":\"Read, mcp__axiom-tools__gh-labels mcp__axiom-tools__gh-comment\"}");
        assertEquals(Set.of("gh-labels", "gh-comment"), CompositionItems.referencedTools(asString));
        JsonNode asArray = MAPPER.readTree("{\"allowedTools\":[\"mcp__axiom-tools__x\",\"Bash\"]}");
        assertEquals(Set.of("x"), CompositionItems.referencedTools(asArray));
        assertFalse(CompositionItems.referencedTools(MAPPER.readTree("{}")).iterator().hasNext());
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=CompositionItemsTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: a compilation failure, because `CompositionItems` does not exist.

- [ ] **Step 3: Create `WorkflowTemplates`**

Move the body of `WorkflowDefinitionsResourceImpl.createEmptyWorkflowContent` into this class. The nodes,
edge and inputs are the same as before:

```java
package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * Templates for new workflow documents.
 */
public final class WorkflowTemplates {

    private WorkflowTemplates() {
    }

    /**
     * Builds a new workflow with a Start node (declaring the standard subjectId, ref and event inputs), an End
     * node and one default edge between them.
     *
     * @param name the workflow name
     * @param mapper the JSON mapper
     * @return the Flow workflow document
     */
    public static ObjectNode emptyWorkflow(String name, ObjectMapper mapper) {
        ObjectNode workflow = mapper.createObjectNode();
        workflow.put("id", UUID.randomUUID().toString());
        workflow.put("name", name);
        ArrayNode nodes = workflow.putArray("nodes");

        ObjectNode start = nodes.addObject();
        start.put("id", "start-1");
        start.put("type", "start");
        start.put("name", "Start");
        ArrayNode inputs = start.putObject("config").putArray("inputs");
        input(inputs, "subjectId", "number", true, "The Axiom subject ID");
        input(inputs, "ref", "string", true, "Full URL identifying the subject");
        input(inputs, "event", "object", false,
                "The triggering event (present when created by subscription routing)");

        ObjectNode end = nodes.addObject();
        end.put("id", "end-1");
        end.put("type", "end");
        end.put("name", "End");
        end.putObject("config");

        ObjectNode edge = workflow.putArray("edges").addObject();
        edge.put("id", "edge-1");
        edge.put("source", "start-1");
        edge.put("target", "end-1");
        edge.put("priority", 0);
        edge.put("isDefault", true);
        return workflow;
    }

    private static void input(ArrayNode inputs, String name, String type, boolean required, String description) {
        ObjectNode input = inputs.addObject();
        input.put("name", name);
        input.put("type", type);
        input.put("required", required);
        input.put("description", description);
    }
}
```

In `WorkflowDefinitionsResourceImpl`, replace the body of `createEmptyWorkflowContent(String name)` with
`return WorkflowTemplates.emptyWorkflow(name, objectMapper).toString();` and remove any imports that are no
longer used. Running `WorkflowDefinitionsResourceTest` later confirms that new definitions still work.

- [ ] **Step 4: Make the serializers public**

In `ImportExportService`, change `private ObjectNode serializeTool(ToolDefinitionEntity e)` and
`private ObjectNode serializeActionType(ActionTypeEntity e)` to `public`, and add Javadoc to each:
`Serializes a tool (an action type) to its configuration-pack item JSON. @param e the entity @return the item
JSON`.

- [ ] **Step 5: Create `CompositionItems`**

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.WorkflowTemplates;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.flow.changeset.ContentRevision;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pure helpers for composition item content: supported types, revisions, blank items, pack conversion and
 * references between items.
 */
public final class CompositionItems {

    /** Item types a composition can hold. */
    public static final Set<ConfigItemType> SUPPORTED = Collections.unmodifiableSet(
            EnumSet.of(ConfigItemType.WORKFLOW, ConfigItemType.ACTION_TYPE, ConfigItemType.TOOL));

    private static final String TOOL_PREFIX = "mcp__axiom-tools__";
    private static final String SDK_PREFIX = "sdk:";
    private static final String BLANK_CODE = "export async function run(inputs, ctx) {\n    return {};\n}\n";

    private CompositionItems() {
    }

    /**
     * Resolves a supported item type from its JSON name.
     *
     * @param jsonName for example {@code action-type}
     * @return the type
     * @throws IllegalArgumentException if the name is unknown or the type is not supported in compositions
     */
    public static ConfigItemType supportedType(String jsonName) {
        ConfigItemType type = ConfigItemType.fromName(jsonName);
        if (!SUPPORTED.contains(type)) {
            throw new IllegalArgumentException("Item type not supported in compositions: " + jsonName);
        }
        return type;
    }

    /**
     * Computes the content revision of an item's JSON, exactly as stored (Flow's content hash).
     *
     * @param content the item content (a JSON object)
     * @return the revision, for example {@code sha256:...}
     */
    public static String revisionOf(JsonNode content) {
        return ContentRevision.of(content);
    }

    /**
     * Returns the trimmed {@code name} field, or null when it is missing or blank.
     *
     * @param content the item content
     * @return the name or null
     */
    public static String nameOf(JsonNode content) {
        String name = content.path("name").asText("").trim();
        return name.isEmpty() ? null : name;
    }

    /**
     * Builds the content of a new, empty item.
     *
     * @param type the item type (must be supported)
     * @param name the item name
     * @param mapper the JSON mapper
     * @return the item content
     */
    public static ObjectNode blank(ConfigItemType type, String name, ObjectMapper mapper) {
        if (type == ConfigItemType.WORKFLOW) {
            return WorkflowTemplates.emptyWorkflow(name, mapper);
        }
        ObjectNode item = mapper.createObjectNode();
        item.put("name", name);
        item.put("description", "");
        if (type == ConfigItemType.ACTION_TYPE) {
            item.put("executionMode", "node");
            item.putArray("inputs");
            item.putArray("outputs");
            item.put("codeTemplate", BLANK_CODE);
        } else {
            item.putArray("parameters");
            item.put("scriptTemplate", "#!/usr/bin/env bash\nset -euo pipefail\n");
        }
        return item;
    }

    /**
     * Converts draft content to the item JSON used in configuration packs and by the validators.
     *
     * @param type the item type
     * @param name the item name (for workflows, the workflow definition name)
     * @param content the draft content
     * @param mapper the JSON mapper
     * @return the pack item
     */
    public static ObjectNode toPackItem(ConfigItemType type, String name, JsonNode content, ObjectMapper mapper) {
        if (type != ConfigItemType.WORKFLOW) {
            return (ObjectNode) content.deepCopy();
        }
        ObjectNode item = mapper.createObjectNode();
        item.put("name", name);
        if (content.hasNonNull("description")) {
            item.put("description", content.path("description").asText());
        }
        item.set("content", content.deepCopy());
        return item;
    }

    /**
     * Returns the configuration-pack array that holds items of a type.
     *
     * @param type the item type (must be supported)
     * @return {@code workflowDefinitions}, {@code actionTypes} or {@code tools}
     */
    public static String packArray(ConfigItemType type) {
        return switch (type) {
            case WORKFLOW -> "workflowDefinitions";
            case ACTION_TYPE -> "actionTypes";
            case TOOL -> "tools";
            default -> throw new IllegalArgumentException("Item type not supported in compositions: " + type);
        };
    }

    /**
     * Lists the action types referenced by a workflow's action nodes, excluding {@code sdk:} functions.
     *
     * @param workflow the Flow workflow document
     * @return the names, in first-use order
     */
    public static Set<String> referencedActionTypes(JsonNode workflow) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode node : workflow.path("nodes")) {
            String actionType = node.path("config").path("actionType").asText("").trim();
            if ("action".equals(node.path("type").asText()) && !actionType.isEmpty()
                    && !actionType.startsWith(SDK_PREFIX)) {
                names.add(actionType);
            }
        }
        return names;
    }

    /**
     * Lists the Axiom script tools an action type allows ({@code mcp__axiom-tools__<name>} entries). The
     * {@code allowedTools} field may be a string separated by commas or whitespace, or an array.
     *
     * @param actionType the action type item JSON
     * @return the tool names, in order
     */
    public static Set<String> referencedTools(JsonNode actionType) {
        Set<String> names = new LinkedHashSet<>();
        JsonNode allowed = actionType.path("allowedTools");
        Iterable<String> entries = allowed.isArray()
                ? () -> java.util.stream.StreamSupport.stream(allowed.spliterator(), false)
                        .map(JsonNode::asText).iterator()
                : java.util.Arrays.asList(allowed.asText("").split("[,\\s]+"));
        for (String entry : entries) {
            String trimmed = entry.trim();
            if (trimmed.startsWith(TOOL_PREFIX) && trimmed.length() > TOOL_PREFIX.length()) {
                names.add(trimmed.substring(TOOL_PREFIX.length()));
            }
        }
        return names;
    }
}
```

- [ ] **Step 6: Run the tests and confirm they pass**

Run: `mvn -q -pl app -am test -Dtest='CompositionItemsTest,WorkflowDefinitionsResourceTest' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/WorkflowTemplates.java \
  app/src/main/java/io/apitomy/axiom/app/rest/WorkflowDefinitionsResourceImpl.java \
  app/src/main/java/io/apitomy/axiom/app/ImportExportService.java \
  app/src/main/java/io/apitomy/axiom/app/composition/CompositionItems.java \
  app/src/test/java/io/apitomy/axiom/app/composition/CompositionItemsTest.java
git commit -m "feat(workbench): add composition item helpers"
```

---

### Task 4: `CompositionService` (drafts, seeding, revisions, SSE)

**Files:**
- Modify: `core/src/main/java/io/apitomy/axiom/core/events/SseEvent.java` (add a factory)
- Create in `app/src/main/java/io/apitomy/axiom/app/composition/`: `CompositionNotFoundException.java`,
  `CompositionConflictException.java`, `StaleRevisionException.java`, `CompositionService.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/CompositionServiceTest.java`

**Interfaces:**
- Consumes: the entities (Task 2), `CompositionItems` and the public serializers (Task 3).
- Produces:
  - `SseEvent.compositionItemChanged(long compositionId, long itemId, String contentRevision, String author, String turnId)`
  - `CompositionNotFoundException(String)`, `CompositionConflictException(String)`,
    `StaleRevisionException(String currentRevision)` with `currentRevision()`. All are `RuntimeException`.
  - `CompositionService` with these public constants and methods:
    - `AUTHOR_USER = "user"`, `AUTHOR_AI = "ai"`
    - `CompositionEntity create(String name, String goal, String engine, String model, Long seedWorkflowId)`
    - `List<CompositionEntity> list()`, `CompositionEntity get(long id)`
    - `CompositionEntity update(long id, String name, String applyMode)`, `void delete(long id)`
    - `List<CompositionItemEntity> items(long compositionId)`, `CompositionItemEntity item(long compositionId, long itemId)`
    - `CompositionItemEntity addBlank(long compositionId, ConfigItemType type, String name)`
    - `CompositionItemEntity addExisting(long compositionId, ConfigItemType type, long existingId)`
    - `CompositionItemEntity save(long compositionId, long itemId, JsonNode content, String baseRevision, String author, String turnId)`
    - `List<CompositionRevisionEntity> revisions(long compositionId, long itemId)` (newest first)
    - `CompositionItemEntity restore(long compositionId, long itemId, long revisionId)`
    - `void removeItem(long compositionId, long itemId)`
    - `JsonNode contentOf(CompositionItemEntity item)`
    - `String currentBaseRevision(ConfigItemType type, long baseItemId)`: the hash of the published item's
      JSON now, or null if it no longer exists
    - `static boolean isChanged(CompositionItemEntity item)`

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for creating compositions, seeding, saving with revision checks, revisions and restore.
 */
@QuarkusTest
class CompositionServiceTest {

    @Inject
    CompositionService service;

    @Inject
    ObjectMapper mapper;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
            WorkflowDefinitionEntity.delete("name like ?1", "cst-%");
            ActionTypeEntity.list("name like ?1", "cst-%").forEach(e -> e.delete());
            ToolDefinitionEntity.list("name like ?1", "cst-%").forEach(e -> e.delete());
        });
    }

    @Test
    void createsBlankCompositionAndItems() {
        CompositionEntity composition = service.create("c", "goal", null, null, null);
        assertEquals(CompositionEntity.TURN_IDLE, composition.turnState);
        CompositionItemEntity workflow = service.addBlank(composition.id, ConfigItemType.WORKFLOW, "cst-wf");
        CompositionItemEntity tool = service.addBlank(composition.id, ConfigItemType.TOOL, "cst-tool");
        assertEquals(2, service.items(composition.id).size());
        assertEquals(CompositionItems.revisionOf(service.contentOf(workflow)), workflow.contentRevision);
        assertEquals(1, service.revisions(composition.id, tool.id).size());
        assertTrue(CompositionService.isChanged(tool));
    }

    @Test
    void rejectsSecondWorkflowAndDuplicateNames() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        service.addBlank(composition.id, ConfigItemType.WORKFLOW, "cst-wf");
        service.addBlank(composition.id, ConfigItemType.TOOL, "cst-tool");
        assertThrows(CompositionConflictException.class,
                () -> service.addBlank(composition.id, ConfigItemType.WORKFLOW, "cst-wf2"));
        assertThrows(CompositionConflictException.class,
                () -> service.addBlank(composition.id, ConfigItemType.TOOL, "cst-tool"));
        assertThrows(IllegalArgumentException.class,
                () -> service.addBlank(composition.id, ConfigItemType.TOOLSET, "cst-ts"));
    }

    @Test
    void saveChecksBaseRevisionAndRecordsAuthor() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity tool = service.addBlank(composition.id, ConfigItemType.TOOL, "cst-tool");
        ObjectNode content = (ObjectNode) service.contentOf(tool);
        content.put("description", "changed");

        assertThrows(StaleRevisionException.class,
                () -> service.save(composition.id, tool.id, content, "sha256:old", CompositionService.AUTHOR_USER, null));

        CompositionItemEntity saved = service.save(composition.id, tool.id, content, tool.contentRevision,
                CompositionService.AUTHOR_AI, "turn-1");
        assertNotEquals(tool.contentRevision, saved.contentRevision);
        assertEquals("ai", saved.lastAuthor);
        assertEquals("turn-1", saved.lastTurnId);
        List<CompositionRevisionEntity> revisions = service.revisions(composition.id, tool.id);
        assertEquals(2, revisions.size());
        assertEquals("turn-1", revisions.get(0).turnId);
    }

    @Test
    void saveRenamesActionTypesAndToolsFromContent() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity tool = service.addBlank(composition.id, ConfigItemType.TOOL, "cst-a");
        ObjectNode content = (ObjectNode) service.contentOf(tool);
        content.put("name", "cst-b");
        assertEquals("cst-b", service.save(composition.id, tool.id, content, tool.contentRevision,
                CompositionService.AUTHOR_USER, null).name);
        content.remove("name");
        assertThrows(IllegalArgumentException.class, () -> service.save(composition.id, tool.id, content,
                null, CompositionService.AUTHOR_USER, null));
    }

    @Test
    void layoutOnlyWorkflowSaveKeepsRevisionAndAddsNoRevisionRow() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity workflow = service.addBlank(composition.id, ConfigItemType.WORKFLOW, "cst-wf");
        ObjectNode content = (ObjectNode) service.contentOf(workflow);
        ((ObjectNode) content.path("nodes").get(0)).putObject("position").put("x", 10).put("y", 20);
        CompositionItemEntity saved = service.save(composition.id, workflow.id, content, workflow.contentRevision,
                CompositionService.AUTHOR_USER, null);
        assertEquals(workflow.contentRevision, saved.contentRevision);
        assertEquals(10, service.contentOf(saved).path("nodes").get(0).path("position").path("x").asInt());
        assertEquals(1, service.revisions(composition.id, workflow.id).size());
    }

    @Test
    void restoreCreatesNewUserRevision() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity tool = service.addBlank(composition.id, ConfigItemType.TOOL, "cst-tool");
        String original = tool.contentRevision;
        ObjectNode content = (ObjectNode) service.contentOf(tool);
        content.put("description", "v2");
        service.save(composition.id, tool.id, content, original, CompositionService.AUTHOR_AI, "t1");
        long firstRevisionId = service.revisions(composition.id, tool.id).get(1).id;

        CompositionItemEntity restored = service.restore(composition.id, tool.id, firstRevisionId);
        assertEquals(original, restored.contentRevision);
        assertEquals("user", restored.lastAuthor);
        assertEquals(3, service.revisions(composition.id, tool.id).size());
    }

    @Test
    void seedsFromExistingWorkflowWithActionTypesAndTools() throws Exception {
        QuarkusTransaction.requiringNew().run(() -> {
            ToolDefinitionEntity tool = new ToolDefinitionEntity();
            tool.name = "cst-seed-tool";
            tool.scriptTemplate = "echo hi";
            tool.persist();
            ActionTypeEntity actionType = new ActionTypeEntity();
            actionType.name = "cst-seed-at";
            actionType.executionMode = "agent";
            actionType.promptTemplate = "Do it";
            actionType.allowedTools = "mcp__axiom-tools__cst-seed-tool";
            actionType.persist();
            WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
            workflow.name = "cst-seed-wf";
            workflow.content = """
                    {"id":"w","name":"cst-seed-wf","nodes":[
                      {"id":"a","type":"action","name":"A","config":{"actionType":"cst-seed-at"}}],"edges":[]}
                    """;
            workflow.createdOn = Instant.now();
            workflow.updatedOn = workflow.createdOn;
            workflow.persist();
        });
        long workflowId = QuarkusTransaction.requiringNew().call(
                () -> WorkflowDefinitionEntity.<WorkflowDefinitionEntity>find("name", "cst-seed-wf").firstResult().id);

        CompositionEntity composition = service.create("seeded", null, null, null, workflowId);
        List<CompositionItemEntity> items = service.items(composition.id);
        assertEquals(List.of("workflow:cst-seed-wf", "action-type:cst-seed-at", "tool:cst-seed-tool"),
                items.stream().map(i -> i.itemType + ":" + i.name).toList());
        CompositionItemEntity actionType = items.get(1);
        assertEquals(actionType.baseContentRevision,
                service.currentBaseRevision(ConfigItemType.ACTION_TYPE, actionType.baseItemId));
        assertFalse(CompositionService.isChanged(actionType));
        JsonNode workflowContent = service.contentOf(items.get(0));
        assertEquals("cst-seed-at", workflowContent.path("nodes").get(0).path("config").path("actionType").asText());
    }

    @Test
    void unknownCompositionThrowsNotFound() {
        assertThrows(CompositionNotFoundException.class, () -> service.get(999_999L));
        assertThrows(CompositionNotFoundException.class, () -> service.items(999_999L));
    }

    @Test
    void deleteRemovesItemsAndRevisions() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        service.addBlank(composition.id, ConfigItemType.TOOL, "cst-tool");
        service.delete(composition.id);
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> CompositionItemEntity.count()
                + CompositionRevisionEntity.count()));
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=CompositionServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: a compilation failure, because `CompositionService` does not exist.

- [ ] **Step 3: Add the SSE factory**

In `SseEvent.java`, add this next to `workflowUpdated`, using the existing private `escapeJson`:

```java
    /**
     * A composition draft item changed (saved by the user or the AI, or restored).
     *
     * @param compositionId the composition id
     * @param itemId the item id
     * @param contentRevision the item's new content revision
     * @param author {@code user} or {@code ai}
     * @param turnId the AI turn id, or null
     * @return the event
     */
    public static SseEvent compositionItemChanged(long compositionId, long itemId, String contentRevision,
                                                  String author, String turnId) {
        return new SseEvent("composition-item-changed",
                "{\"compositionId\":" + compositionId
                        + ",\"itemId\":" + itemId
                        + ",\"contentRevision\":\"" + escapeJson(contentRevision) + "\""
                        + ",\"author\":\"" + escapeJson(author) + "\""
                        + ",\"turnId\":" + (turnId == null ? "null" : "\"" + escapeJson(turnId) + "\"")
                        + "}");
    }
```

- [ ] **Step 4: Create the exceptions**

```java
package io.apitomy.axiom.app.composition;

/** A composition, item or revision does not exist (HTTP 404). */
public class CompositionNotFoundException extends RuntimeException {
    /**
     * @param message what was not found
     */
    public CompositionNotFoundException(String message) {
        super(message);
    }
}
```

```java
package io.apitomy.axiom.app.composition;

/** The request conflicts with the composition's current state (HTTP 409). */
public class CompositionConflictException extends RuntimeException {
    /**
     * @param message the conflict
     */
    public CompositionConflictException(String message) {
        super(message);
    }
}
```

```java
package io.apitomy.axiom.app.composition;

/** A save was based on a content revision that is no longer current (HTTP 409). */
public class StaleRevisionException extends RuntimeException {
    private final String currentRevision;

    /**
     * @param currentRevision the item's current content revision
     */
    public StaleRevisionException(String currentRevision) {
        super("The item changed since it was loaded; current revision is " + currentRevision);
        this.currentRevision = currentRevision;
    }

    /**
     * @return the item's current content revision
     */
    public String currentRevision() {
        return currentRevision;
    }
}
```

- [ ] **Step 5: Create `CompositionService`**

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.ImportExportService;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.events.SseEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Creates and edits workbench compositions. Every content change goes through {@link #save}, which checks the
 * caller's base revision, records a revision when the content (not just the layout) changed, and announces the
 * change over SSE after the transaction commits.
 */
@ApplicationScoped
public class CompositionService {

    /** Author of edits made in the UI. */
    public static final String AUTHOR_USER = "user";
    /** Author of edits made by the workbench agent. */
    public static final String AUTHOR_AI = "ai";

    @Inject
    ObjectMapper mapper;

    @Inject
    ImportExportService importExportService;

    @Inject
    Event<SseEvent> sseEvents;

    @Inject
    TransactionSynchronizationRegistry transactions;

    /**
     * Creates a composition, optionally seeded with an existing workflow, the action types its action nodes use
     * and the Axiom tools those action types allow.
     *
     * @param name the composition name (required)
     * @param goal the user's goal, or null
     * @param engine the engine override, or null
     * @param model the model override, or null
     * @param seedWorkflowId an existing workflow definition id, or null
     * @return the composition
     * @throws IllegalArgumentException if the name is blank or the seed workflow does not exist
     */
    @Transactional
    public CompositionEntity create(String name, String goal, String engine, String model, Long seedWorkflowId) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("'name' is required");
        }
        CompositionEntity composition = new CompositionEntity();
        composition.name = name.trim();
        composition.goal = goal;
        composition.engine = engine;
        composition.model = model;
        composition.createdOn = Instant.now();
        composition.updatedOn = composition.createdOn;
        composition.persist();
        if (seedWorkflowId != null) {
            seed(composition.id, seedWorkflowId);
        }
        return composition;
    }

    private void seed(long compositionId, long workflowId) {
        if (WorkflowDefinitionEntity.findById(workflowId) == null) {
            throw new IllegalArgumentException("Workflow definition not found: " + workflowId);
        }
        CompositionItemEntity workflow = addExisting(compositionId, ConfigItemType.WORKFLOW, workflowId);
        for (String actionTypeName : CompositionItems.referencedActionTypes(contentOf(workflow))) {
            ActionTypeEntity actionType = ActionTypeEntity.find("name", actionTypeName).firstResult();
            if (actionType == null || exists(compositionId, ConfigItemType.ACTION_TYPE, actionTypeName)) {
                continue;
            }
            CompositionItemEntity actionTypeItem = addExisting(compositionId, ConfigItemType.ACTION_TYPE, actionType.id);
            for (String toolName : CompositionItems.referencedTools(contentOf(actionTypeItem))) {
                ToolDefinitionEntity tool = ToolDefinitionEntity.find("name", toolName).firstResult();
                if (tool != null && !exists(compositionId, ConfigItemType.TOOL, toolName)) {
                    addExisting(compositionId, ConfigItemType.TOOL, tool.id);
                }
            }
        }
    }

    /**
     * @return all compositions, newest first
     */
    public List<CompositionEntity> list() {
        return CompositionEntity.list("order by updatedOn desc");
    }

    /**
     * @param id the composition id
     * @return the composition
     * @throws CompositionNotFoundException if it does not exist
     */
    public CompositionEntity get(long id) {
        CompositionEntity composition = CompositionEntity.findById(id);
        if (composition == null) {
            throw new CompositionNotFoundException("Composition not found: " + id);
        }
        return composition;
    }

    /**
     * Renames a composition or changes its apply mode. Null arguments are left unchanged.
     *
     * @param id the composition id
     * @param name the new name, or null
     * @param applyMode {@code live} or {@code review}, or null
     * @return the composition
     */
    @Transactional
    public CompositionEntity update(long id, String name, String applyMode) {
        CompositionEntity composition = get(id);
        if (name != null) {
            if (name.isBlank()) {
                throw new IllegalArgumentException("'name' must not be blank");
            }
            composition.name = name.trim();
        }
        if (applyMode != null) {
            if (!CompositionEntity.APPLY_LIVE.equals(applyMode) && !CompositionEntity.APPLY_REVIEW.equals(applyMode)) {
                throw new IllegalArgumentException("'applyMode' must be live or review");
            }
            composition.applyMode = applyMode;
        }
        composition.updatedOn = Instant.now();
        return composition;
    }

    /**
     * Deletes a composition and all its drafts and revisions. Published items are not affected.
     *
     * @param id the composition id
     */
    @Transactional
    public void delete(long id) {
        CompositionEntity composition = get(id);
        for (CompositionItemEntity item : items(id)) {
            CompositionRevisionEntity.delete("itemId", item.id);
            item.delete();
        }
        composition.delete();
    }

    /**
     * @param compositionId the composition id
     * @return the items: the workflow first, then action types, then tools, each by name
     */
    public List<CompositionItemEntity> items(long compositionId) {
        get(compositionId);
        List<CompositionItemEntity> items = CompositionItemEntity.list("compositionId", compositionId);
        return items.stream()
                .sorted(java.util.Comparator.<CompositionItemEntity>comparingInt(i -> typeOrder(i.itemType))
                        .thenComparing(i -> i.name))
                .toList();
    }

    private static int typeOrder(String itemType) {
        return switch (itemType) {
            case "workflow" -> 0;
            case "action-type" -> 1;
            default -> 2;
        };
    }

    /**
     * @param compositionId the composition id
     * @param itemId the item id
     * @return the item
     * @throws CompositionNotFoundException if the composition or item does not exist
     */
    public CompositionItemEntity item(long compositionId, long itemId) {
        get(compositionId);
        CompositionItemEntity item = CompositionItemEntity.findById(itemId);
        if (item == null || item.compositionId != compositionId) {
            throw new CompositionNotFoundException("Composition item not found: " + itemId);
        }
        return item;
    }

    /**
     * Adds a new, empty draft.
     *
     * @param compositionId the composition id
     * @param type workflow, action type or tool
     * @param name the item name
     * @return the item
     * @throws CompositionConflictException for a second workflow or a duplicate name
     */
    @Transactional
    public CompositionItemEntity addBlank(long compositionId, ConfigItemType type, String name) {
        requireSupported(type);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("'name' is required");
        }
        String trimmed = name.trim();
        checkCanAdd(compositionId, type, trimmed);
        return insert(compositionId, type, trimmed, CompositionItems.blank(type, trimmed, mapper), null, null);
    }

    /**
     * Adds a draft copy of a published item.
     *
     * @param compositionId the composition id
     * @param type workflow, action type or tool
     * @param existingId the database id of the published item
     * @return the item
     * @throws IllegalArgumentException if the published item does not exist
     */
    @Transactional
    public CompositionItemEntity addExisting(long compositionId, ConfigItemType type, long existingId) {
        requireSupported(type);
        String name = publishedName(type, existingId);
        if (name == null) {
            throw new IllegalArgumentException("No " + type.jsonName() + " with id " + existingId);
        }
        checkCanAdd(compositionId, type, name);
        JsonNode content = publishedContent(type, existingId);
        return insert(compositionId, type, name, content, existingId, CompositionItems.revisionOf(content));
    }

    /**
     * Saves new content for an item. For action types and tools, {@code content.name} renames the item.
     * Layout-only workflow edits update the stored content without a new revision.
     *
     * @param compositionId the composition id
     * @param itemId the item id
     * @param content the complete new content (a JSON object)
     * @param baseRevision the revision the caller edited, or null to skip the check
     * @param author {@link #AUTHOR_USER} or {@link #AUTHOR_AI}
     * @param turnId the AI turn id, or null
     * @return the saved item
     * @throws StaleRevisionException if {@code baseRevision} is not the current revision
     */
    @Transactional
    public CompositionItemEntity save(long compositionId, long itemId, JsonNode content, String baseRevision,
                                      String author, String turnId) {
        CompositionItemEntity item = item(compositionId, itemId);
        if (baseRevision != null && !baseRevision.equals(item.contentRevision)) {
            throw new StaleRevisionException(item.contentRevision);
        }
        if (content == null || !content.isObject()) {
            throw new IllegalArgumentException("'content' must be a JSON object");
        }
        ConfigItemType type = ConfigItemType.fromName(item.itemType);
        if (type != ConfigItemType.WORKFLOW) {
            String newName = CompositionItems.nameOf(content);
            if (newName == null) {
                throw new IllegalArgumentException("'content.name' is required");
            }
            if (!newName.equals(item.name)) {
                checkUniqueName(compositionId, type, newName);
                item.name = newName;
            }
        }
        String revision = CompositionItems.revisionOf(content);
        item.content = content.toString();
        item.updatedOn = Instant.now();
        if (!revision.equals(item.contentRevision)) {
            item.contentRevision = revision;
            item.lastAuthor = author;
            item.lastTurnId = turnId;
            addRevision(item, author, turnId);
            fireAfterCommit(SseEvent.compositionItemChanged(compositionId, item.id, revision, author, turnId));
        }
        return item;
    }

    /**
     * @param compositionId the composition id
     * @param itemId the item id
     * @return the item's revisions, newest first
     */
    public List<CompositionRevisionEntity> revisions(long compositionId, long itemId) {
        item(compositionId, itemId);
        return CompositionRevisionEntity.list("itemId = ?1 order by createdOn desc, id desc", itemId);
    }

    /**
     * Restores an earlier revision as a new user revision.
     *
     * @param compositionId the composition id
     * @param itemId the item id
     * @param revisionId the revision to restore
     * @return the item
     */
    @Transactional
    public CompositionItemEntity restore(long compositionId, long itemId, long revisionId) {
        item(compositionId, itemId);
        CompositionRevisionEntity revision = CompositionRevisionEntity.findById(revisionId);
        if (revision == null || !Objects.equals(revision.itemId, itemId)) {
            throw new CompositionNotFoundException("Revision not found: " + revisionId);
        }
        return save(compositionId, itemId, parse(revision.content), null, AUTHOR_USER, null);
    }

    /**
     * Removes a draft from the composition. The published item, if any, is not deleted.
     *
     * @param compositionId the composition id
     * @param itemId the item id
     */
    @Transactional
    public void removeItem(long compositionId, long itemId) {
        CompositionItemEntity item = item(compositionId, itemId);
        CompositionRevisionEntity.delete("itemId", item.id);
        item.delete();
    }

    /**
     * @param item a composition item
     * @return its content as JSON
     */
    public JsonNode contentOf(CompositionItemEntity item) {
        return parse(item.content);
    }

    /**
     * Computes the hash of a published item's JSON as it is now.
     *
     * @param type the item type
     * @param baseItemId the published item's database id
     * @return the revision, or null if the item no longer exists
     */
    public String currentBaseRevision(ConfigItemType type, long baseItemId) {
        if (publishedName(type, baseItemId) == null) {
            return null;
        }
        return CompositionItems.revisionOf(publishedContent(type, baseItemId));
    }

    /**
     * Whether an item has changes that are not published: compared with its last publish, or else with the
     * published item it was loaded from. New items are always changed.
     *
     * @param item the item
     * @return true if publishing it would change something
     */
    public static boolean isChanged(CompositionItemEntity item) {
        if (item.publishedRevision != null) {
            return !item.publishedRevision.equals(item.contentRevision);
        }
        return item.baseContentRevision == null || !item.baseContentRevision.equals(item.contentRevision);
    }

    private CompositionItemEntity insert(long compositionId, ConfigItemType type, String name, JsonNode content,
                                         Long baseItemId, String baseRevision) {
        CompositionItemEntity item = new CompositionItemEntity();
        item.compositionId = compositionId;
        item.itemType = type.jsonName();
        item.name = name;
        item.baseItemId = baseItemId;
        item.baseContentRevision = baseRevision;
        item.content = content.toString();
        item.contentRevision = CompositionItems.revisionOf(content);
        item.lastAuthor = AUTHOR_USER;
        item.createdOn = Instant.now();
        item.updatedOn = item.createdOn;
        item.persist();
        addRevision(item, AUTHOR_USER, null);
        fireAfterCommit(SseEvent.compositionItemChanged(compositionId, item.id, item.contentRevision, AUTHOR_USER, null));
        return item;
    }

    private void addRevision(CompositionItemEntity item, String author, String turnId) {
        CompositionRevisionEntity revision = new CompositionRevisionEntity();
        revision.itemId = item.id;
        revision.content = item.content;
        revision.contentRevision = item.contentRevision;
        revision.author = author;
        revision.turnId = turnId;
        revision.createdOn = Instant.now();
        revision.persist();
    }

    private void checkCanAdd(long compositionId, ConfigItemType type, String name) {
        get(compositionId);
        if (type == ConfigItemType.WORKFLOW
                && CompositionItemEntity.count("compositionId = ?1 and itemType = ?2", compositionId, type.jsonName()) > 0) {
            throw new CompositionConflictException("A composition holds exactly one workflow");
        }
        checkUniqueName(compositionId, type, name);
    }

    private void checkUniqueName(long compositionId, ConfigItemType type, String name) {
        if (exists(compositionId, type, name)) {
            throw new CompositionConflictException("The composition already has a " + type.jsonName() + " named " + name);
        }
    }

    private static boolean exists(long compositionId, ConfigItemType type, String name) {
        return CompositionItemEntity.count("compositionId = ?1 and itemType = ?2 and name = ?3",
                compositionId, type.jsonName(), name) > 0;
    }

    private static void requireSupported(ConfigItemType type) {
        if (!CompositionItems.SUPPORTED.contains(type)) {
            throw new IllegalArgumentException("Item type not supported in compositions: " + type.jsonName());
        }
    }

    private static String publishedName(ConfigItemType type, long id) {
        return switch (type) {
            case WORKFLOW -> {
                WorkflowDefinitionEntity e = WorkflowDefinitionEntity.findById(id);
                yield e == null ? null : e.name;
            }
            case ACTION_TYPE -> {
                ActionTypeEntity e = ActionTypeEntity.findById(id);
                yield e == null ? null : e.name;
            }
            case TOOL -> {
                ToolDefinitionEntity e = ToolDefinitionEntity.findById(id);
                yield e == null ? null : e.name;
            }
            default -> null;
        };
    }

    private JsonNode publishedContent(ConfigItemType type, long id) {
        return switch (type) {
            case WORKFLOW -> {
                WorkflowDefinitionEntity e = WorkflowDefinitionEntity.findById(id);
                yield e.content == null || e.content.isBlank()
                        ? CompositionItems.blank(ConfigItemType.WORKFLOW, e.name, mapper) : parse(e.content);
            }
            case ACTION_TYPE -> importExportService.serializeActionType(ActionTypeEntity.findById(id));
            case TOOL -> importExportService.serializeTool(ToolDefinitionEntity.findById(id));
            default -> throw new IllegalArgumentException("Unsupported type " + type);
        };
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored composition content is not valid JSON", e);
        }
    }

    private void fireAfterCommit(SseEvent event) {
        transactions.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
            }

            @Override
            public void afterCompletion(int status) {
                if (status == Status.STATUS_COMMITTED) {
                    sseEvents.fire(event);
                }
            }
        });
    }
}
```

Note: `list()`, `get()`, `items()`, `item()`, `revisions()`, `contentOf()` and `currentBaseRevision()` are
read-only and run in the caller's transaction, or with none (Panache reads work outside a transaction).

- [ ] **Step 6: Run the test and confirm it passes**

Run: `mvn -q -pl app -am test -Dtest=CompositionServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS (9 tests). If an `ActionTypeEntity` or `ToolDefinitionEntity` needs more non-null fields to
persist, set them in the test's seed block and note it in your report.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/io/apitomy/axiom/core/events/SseEvent.java \
  app/src/main/java/io/apitomy/axiom/app/composition \
  app/src/test/java/io/apitomy/axiom/app/composition/CompositionServiceTest.java
git commit -m "feat(workbench): add composition service with revisions and seeding"
```

---
### Task 5: Draft validation (`forItems` and `CompositionValidator`)

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/validation/ValidationContextFactory.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/composition/CompositionValidator.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/CompositionValidatorTest.java`

**Interfaces:**
- Consumes: `CompositionService` (Task 4), `CompositionItems` (Task 3), and
  `ConfigItemValidationService.validate(ConfigItemType, JsonNode, ValidationContext)`.
- Produces:
  - `ValidationContextFactory.forItems(Map<ConfigItemType, List<JsonNode>> items): ValidationContext`.
    The items are pack-item JSON, overlaid on `databaseOnly()`.
  - `CompositionValidator.context(long compositionId): ValidationContext`
  - `CompositionValidator.validateAll(long compositionId): Map<Long, List<ConfigProblem>>`, keyed by item
    id in item order, with an entry for every item.
  - `CompositionValidator.validateContent(long compositionId, ConfigItemType type, String name, JsonNode content): List<ConfigProblem>`
  - `CompositionValidator.packItem(CompositionItemEntity item): ObjectNode`

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.app.validation.ConfigProblem;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drafts are validated against the database plus the other drafts in the same composition.
 */
@QuarkusTest
class CompositionValidatorTest {

    @Inject
    CompositionService service;

    @Inject
    CompositionValidator validator;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    private static boolean hasErrors(List<ConfigProblem> problems) {
        return problems.stream().anyMatch(ConfigProblem::isError);
    }

    /** Makes the workflow call the given action type between Start and End. */
    private ObjectNode workflowUsing(CompositionItemEntity workflow, String actionType) {
        ObjectNode content = (ObjectNode) service.contentOf(workflow);
        ArrayNode nodes = (ArrayNode) content.path("nodes");
        ObjectNode action = nodes.addObject();
        action.put("id", "act-1");
        action.put("type", "action");
        action.put("name", "Act");
        action.putObject("config").put("actionType", actionType).putObject("inputs");
        ArrayNode edges = content.putArray("edges");
        edges.addObject().put("id", "e1").put("source", "start-1").put("target", "act-1")
                .put("priority", 0).put("isDefault", true);
        edges.addObject().put("id", "e2").put("source", "act-1").put("target", "end-1")
                .put("priority", 0).put("isDefault", true);
        return content;
    }

    @Test
    void workflowMayUseActionTypeThatIsOnlyADraft() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity workflow = service.addBlank(composition.id, ConfigItemType.WORKFLOW, "cvt-wf");
        service.addBlank(composition.id, ConfigItemType.ACTION_TYPE, "cvt-draft-at");

        List<ConfigProblem> withDraft = validator.validateContent(composition.id, ConfigItemType.WORKFLOW,
                "cvt-wf", workflowUsing(workflow, "cvt-draft-at"));
        assertFalse(hasErrors(withDraft), withDraft.toString());

        List<ConfigProblem> unknown = validator.validateContent(composition.id, ConfigItemType.WORKFLOW,
                "cvt-wf", workflowUsing(workflow, "cvt-no-such-at"));
        assertTrue(hasErrors(unknown), unknown.toString());
    }

    @Test
    void validateAllReturnsAnEntryForEveryItem() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity workflow = service.addBlank(composition.id, ConfigItemType.WORKFLOW, "cvt-wf");
        CompositionItemEntity tool = service.addBlank(composition.id, ConfigItemType.TOOL, "cvt-tool");
        Map<Long, List<ConfigProblem>> problems = validator.validateAll(composition.id);
        assertEquals(List.of(workflow.id, tool.id), List.copyOf(problems.keySet()));
    }
}
```

`ConfigProblem` needs an `isError()` method. If it does not already have one (check with
`git grep -n "isError" app/src/main/java/io/apitomy/axiom/app/validation/ConfigProblem.java`), add:

```java
    /**
     * @return true for error-severity problems
     */
    public boolean isError() {
        return "error".equals(severity);
    }
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=CompositionValidatorTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: a compilation failure, because `CompositionValidator` does not exist.

- [ ] **Step 3: Add `forItems` to `ValidationContextFactory`**

Add this public method next to `forPack`. It reuses the existing private `union`, `overlay` and
`pendingInputs` helpers:

```java
    /**
     * A context in which the given draft items count as existing, in addition to the database. Used by the
     * workbench, whose drafts live in the database rather than in a pack or a directory.
     *
     * @param items draft items in configuration-pack form, by type
     * @return the context
     */
    public ValidationContext forItems(Map<ConfigItemType, List<JsonNode>> items) {
        ValidationContext db = databaseOnly();
        List<JsonNode> actionTypes = items.getOrDefault(ConfigItemType.ACTION_TYPE, List.of());
        return new ValidationContext(
                union(db.toolNames(), itemNames(items, ConfigItemType.TOOL)),
                db.toolsetNames(),
                union(db.actionTypeNames(), itemNames(items, ConfigItemType.ACTION_TYPE)),
                union(db.workflowDefinitionNames(), itemNames(items, ConfigItemType.WORKFLOW)),
                db.workflowDefinitionIds(),
                db.eventSourceIds(),
                db.secretNames(),
                overlay(db.actionTypeInputs(), pendingInputs(actionTypes)));
    }

    private static Set<String> itemNames(Map<ConfigItemType, List<JsonNode>> items, ConfigItemType type) {
        Set<String> names = new HashSet<>();
        for (JsonNode item : items.getOrDefault(type, List.of())) {
            String name = item.path("name").asText("");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }
```

Add any missing imports (`java.util.List`, `java.util.Map`, `java.util.Set`, `java.util.HashSet`).

- [ ] **Step 4: Create `CompositionValidator`**

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.app.validation.ConfigItemValidationService;
import io.apitomy.axiom.app.validation.ConfigProblem;
import io.apitomy.axiom.app.validation.ValidationContext;
import io.apitomy.axiom.app.validation.ValidationContextFactory;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates composition drafts with the same rules as publishing, treating the composition's other drafts as
 * existing.
 */
@ApplicationScoped
public class CompositionValidator {

    @Inject
    CompositionService service;

    @Inject
    ConfigItemValidationService validationService;

    @Inject
    ValidationContextFactory contextFactory;

    @Inject
    ObjectMapper mapper;

    /**
     * @param compositionId the composition id
     * @return a validation context of the database plus this composition's drafts
     */
    public ValidationContext context(long compositionId) {
        Map<ConfigItemType, List<JsonNode>> byType = new EnumMap<>(ConfigItemType.class);
        for (CompositionItemEntity item : service.items(compositionId)) {
            byType.computeIfAbsent(ConfigItemType.fromName(item.itemType), t -> new ArrayList<>()).add(packItem(item));
        }
        return contextFactory.forItems(byType);
    }

    /**
     * Validates every draft in the composition.
     *
     * @param compositionId the composition id
     * @return problems by item id, in item order; empty lists for valid items
     */
    public Map<Long, List<ConfigProblem>> validateAll(long compositionId) {
        ValidationContext context = context(compositionId);
        Map<Long, List<ConfigProblem>> result = new LinkedHashMap<>();
        for (CompositionItemEntity item : service.items(compositionId)) {
            result.put(item.id, validationService.validate(ConfigItemType.fromName(item.itemType), packItem(item), context));
        }
        return result;
    }

    /**
     * Validates content that may not be saved yet, for example the editor's current workflow.
     *
     * @param compositionId the composition id
     * @param type the item type
     * @param name the item name (for workflows, the item's name, not {@code content.name})
     * @param content the draft content
     * @return the problems
     */
    public List<ConfigProblem> validateContent(long compositionId, ConfigItemType type, String name, JsonNode content) {
        return validationService.validate(type, CompositionItems.toPackItem(type, name, content, mapper),
                context(compositionId));
    }

    /**
     * @param item a composition item
     * @return its configuration-pack form
     */
    public ObjectNode packItem(CompositionItemEntity item) {
        return CompositionItems.toPackItem(ConfigItemType.fromName(item.itemType), item.name,
                service.contentOf(item), mapper);
    }
}
```

- [ ] **Step 5: Run the test and confirm it passes**

Run: `mvn -q -pl app -am test -Dtest=CompositionValidatorTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS. If the "draft action type" case reports a different, legitimate error (for example a
required action-type input that is not mapped), look at the problems and adjust only the test's workflow
fixture so that the case is genuinely valid. Do not loosen the validator.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/validation \
  app/src/main/java/io/apitomy/axiom/app/composition/CompositionValidator.java \
  app/src/test/java/io/apitomy/axiom/app/composition/CompositionValidatorTest.java
git commit -m "feat(workbench): validate composition drafts against drafts and database"
```

---

### Task 6: Publish (`CompositionPublisher`)

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/composition/PublishBlockedException.java`
- Create: `app/src/main/java/io/apitomy/axiom/app/composition/CompositionPublisher.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/CompositionPublisherTest.java`

**Interfaces:**
- Consumes: `CompositionService`, `CompositionValidator`, `CompositionItems` and
  `ImportExportService.importOrUpdatePack(JsonNode)`, which returns `UpsertResult`.
- Produces:
  - `PublishBlockedException(Map<String, List<ConfigProblem>> problems)` with `problems()`. Keys are
    `"<type>/<name>"`, for example `"workflow/my-wf"`.
  - `record CompositionPublisher.Outcome(int created, int updated, List<CompositionItemEntity> items)`
  - `CompositionPublisher.publish(long compositionId, List<Long> itemIds, boolean force): Outcome`.
    It throws `IllegalArgumentException` (no items selected), `PublishBlockedException` (missing
    dependency or validation errors), or `CompositionConflictException` (the base changed outside the
    workbench and `force` is false).

- [ ] **Step 1: Write the failing test**

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publishing selected drafts: dependencies, validation, base conflicts and bookkeeping.
 */
@QuarkusTest
class CompositionPublisherTest {

    @Inject
    CompositionService service;

    @Inject
    CompositionPublisher publisher;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
            WorkflowDefinitionEntity.list("name like ?1", "cpt-%").forEach(e -> e.delete());
            ActionTypeEntity.list("name like ?1", "cpt-%").forEach(e -> e.delete());
            ToolDefinitionEntity.list("name like ?1", "cpt-%").forEach(e -> e.delete());
        });
    }

    /** A node action type with one output, valid on its own. */
    private CompositionItemEntity validActionType(long compositionId, String name) {
        CompositionItemEntity item = service.addBlank(compositionId, ConfigItemType.ACTION_TYPE, name);
        ObjectNode content = (ObjectNode) service.contentOf(item);
        content.put("description", "Test action type");
        ArrayNode outputs = content.putArray("outputs");
        outputs.addObject().put("name", "result").put("type", "string").put("required", true);
        content.put("codeTemplate", "export async function run(inputs, ctx) {\n    return { result: 'ok' };\n}\n");
        return service.save(compositionId, item.id, content, item.contentRevision, CompositionService.AUTHOR_USER, null);
    }

    private CompositionItemEntity workflowUsing(long compositionId, String name, String actionType) {
        CompositionItemEntity item = service.addBlank(compositionId, ConfigItemType.WORKFLOW, name);
        ObjectNode content = (ObjectNode) service.contentOf(item);
        ObjectNode action = ((ArrayNode) content.path("nodes")).addObject();
        action.put("id", "act-1").put("type", "action").put("name", "Act");
        action.putObject("config").put("actionType", actionType).putObject("inputs");
        ArrayNode edges = content.putArray("edges");
        edges.addObject().put("id", "e1").put("source", "start-1").put("target", "act-1")
                .put("priority", 0).put("isDefault", true);
        edges.addObject().put("id", "e2").put("source", "act-1").put("target", "end-1")
                .put("priority", 0).put("isDefault", true);
        return service.save(compositionId, item.id, content, item.contentRevision, CompositionService.AUTHOR_USER, null);
    }

    @Test
    void publishesWorkflowWithItsDraftActionType() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity actionType = validActionType(composition.id, "cpt-at");
        CompositionItemEntity workflow = workflowUsing(composition.id, "cpt-wf", "cpt-at");

        CompositionPublisher.Outcome outcome = publisher.publish(composition.id,
                List.of(workflow.id, actionType.id), false);
        assertEquals(2, outcome.created());
        assertEquals(0, outcome.updated());

        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowDefinitionEntity definition = WorkflowDefinitionEntity.find("name", "cpt-wf").firstResult();
            assertNotNull(definition);
            assertNotNull(definition.currentVersion);
            CompositionItemEntity published = CompositionItemEntity.findById(actionType.id);
            assertEquals(published.contentRevision, published.publishedRevision);
            assertNotNull(published.baseItemId);
            assertFalse(CompositionService.isChanged(published));
        });
    }

    @Test
    void blocksWorkflowWhoseDraftActionTypeIsNotSelected() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        validActionType(composition.id, "cpt-at");
        CompositionItemEntity workflow = workflowUsing(composition.id, "cpt-wf", "cpt-at");

        PublishBlockedException blocked = assertThrows(PublishBlockedException.class,
                () -> publisher.publish(composition.id, List.of(workflow.id), false));
        assertTrue(blocked.problems().containsKey("workflow/cpt-wf"), blocked.problems().toString());
        assertEquals("missing-dependency", blocked.problems().get("workflow/cpt-wf").get(0).code());
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() -> WorkflowDefinitionEntity.count("name", "cpt-wf")));
    }

    @Test
    void blocksInvalidItems() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity workflow = workflowUsing(composition.id, "cpt-wf", "cpt-nowhere");
        assertThrows(PublishBlockedException.class,
                () -> publisher.publish(composition.id, List.of(workflow.id), false));
    }

    @Test
    void requiresAtLeastOneItem() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> publisher.publish(composition.id, List.of(), false));
    }

    @Test
    void baseChangedOutsideWorkbenchNeedsForce() {
        CompositionEntity composition = service.create("c", null, null, null, null);
        CompositionItemEntity actionType = validActionType(composition.id, "cpt-at");
        publisher.publish(composition.id, List.of(actionType.id), false);

        QuarkusTransaction.requiringNew().run(() -> {
            ActionTypeEntity entity = ActionTypeEntity.find("name", "cpt-at").firstResult();
            entity.description = "edited elsewhere";
        });
        CompositionItemEntity current = QuarkusTransaction.requiringNew().call(
                () -> CompositionItemEntity.<CompositionItemEntity>findById(actionType.id));
        ObjectNode content = (ObjectNode) service.contentOf(current);
        content.put("description", "edited in workbench");
        service.save(composition.id, actionType.id, content, current.contentRevision, CompositionService.AUTHOR_USER, null);

        assertThrows(CompositionConflictException.class,
                () -> publisher.publish(composition.id, List.of(actionType.id), false));
        CompositionPublisher.Outcome forced = publisher.publish(composition.id, List.of(actionType.id), true);
        assertEquals(1, forced.updated());
        assertEquals("edited in workbench", QuarkusTransaction.requiringNew().call(
                () -> ActionTypeEntity.<ActionTypeEntity>find("name", "cpt-at").firstResult().description));
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=CompositionPublisherTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: a compilation failure, because `CompositionPublisher` does not exist.

- [ ] **Step 3: Create `PublishBlockedException`**

```java
package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.app.validation.ConfigProblem;

import java.util.List;
import java.util.Map;

/** Publishing was refused because selected items have errors or missing dependencies (HTTP 422). */
public class PublishBlockedException extends RuntimeException {
    private final Map<String, List<ConfigProblem>> problems;

    /**
     * @param problems problems keyed by {@code "<type>/<name>"}
     */
    public PublishBlockedException(Map<String, List<ConfigProblem>> problems) {
        super("Cannot publish: " + problems.keySet());
        this.problems = problems;
    }

    /**
     * @return problems keyed by {@code "<type>/<name>"}
     */
    public Map<String, List<ConfigProblem>> problems() {
        return problems;
    }
}
```

- [ ] **Step 4: Create `CompositionPublisher`**

```java
package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.ImportExportService;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.app.validation.ConfigProblem;
import io.apitomy.axiom.app.validation.ConfigValidationException;
import io.apitomy.axiom.app.validation.ValidationContext;
import io.apitomy.axiom.app.validation.ConfigItemValidationService;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Publishes selected composition drafts as one configuration pack, in one transaction. The composition stays
 * open afterwards.
 */
@ApplicationScoped
public class CompositionPublisher {

    /**
     * Result of a publish.
     *
     * @param created items created in the database
     * @param updated items updated in the database
     * @param items the published composition items, after bookkeeping
     */
    public record Outcome(int created, int updated, List<CompositionItemEntity> items) {
    }

    @Inject
    CompositionService service;

    @Inject
    CompositionValidator validator;

    @Inject
    ConfigItemValidationService validationService;

    @Inject
    ImportExportService importExportService;

    @Inject
    ObjectMapper mapper;

    /**
     * Publishes the selected drafts.
     *
     * @param compositionId the composition id
     * @param itemIds the items to publish
     * @param force publish even if a published item changed outside the workbench since it was loaded
     * @return the outcome
     */
    @Transactional
    public Outcome publish(long compositionId, List<Long> itemIds, boolean force) {
        CompositionEntity composition = service.get(compositionId);
        if (itemIds == null || itemIds.isEmpty()) {
            throw new IllegalArgumentException("Select at least one item to publish");
        }
        List<CompositionItemEntity> selected = itemIds.stream().distinct()
                .map(id -> service.item(compositionId, id)).toList();

        Map<String, List<ConfigProblem>> blocked = new LinkedHashMap<>();
        checkDependencies(compositionId, selected, blocked);
        ValidationContext context = validator.context(compositionId);
        for (CompositionItemEntity item : selected) {
            List<ConfigProblem> errors = validationService.validate(ConfigItemType.fromName(item.itemType),
                    validator.packItem(item), context).stream().filter(ConfigProblem::isError).toList();
            if (!errors.isEmpty()) {
                blocked.computeIfAbsent(key(item), k -> new ArrayList<>()).addAll(errors);
            }
        }
        if (!blocked.isEmpty()) {
            throw new PublishBlockedException(blocked);
        }

        if (!force) {
            List<String> conflicts = selected.stream()
                    .filter(item -> item.baseItemId != null)
                    .filter(item -> !Objects.equals(item.baseContentRevision,
                            service.currentBaseRevision(ConfigItemType.fromName(item.itemType), item.baseItemId)))
                    .map(CompositionPublisher::key).toList();
            if (!conflicts.isEmpty()) {
                throw new CompositionConflictException(
                        "Changed outside the workbench since loaded: " + String.join(", ", conflicts));
            }
        }

        ObjectNode pack = mapper.createObjectNode();
        pack.putObject("metadata").put("name", "Workbench - " + composition.name);
        for (CompositionItemEntity item : selected) {
            ConfigItemType type = ConfigItemType.fromName(item.itemType);
            pack.withArray(CompositionItems.packArray(type)).add(validator.packItem(item));
        }
        ImportExportService.UpsertResult result;
        try {
            result = importExportService.importOrUpdatePack(pack);
        } catch (ConfigValidationException e) {
            throw new PublishBlockedException(e.problemsByItem());
        }

        Instant now = Instant.now();
        for (CompositionItemEntity item : selected) {
            ConfigItemType type = ConfigItemType.fromName(item.itemType);
            item.baseItemId = publishedId(type, item.name);
            item.baseContentRevision = service.currentBaseRevision(type, item.baseItemId);
            item.publishedRevision = item.contentRevision;
            item.publishedOn = now;
        }
        int created = result.workflowDefinitionsCreated() + result.actionTypesCreated() + result.toolsCreated();
        int updated = result.workflowDefinitionsUpdated() + result.actionTypesUpdated() + result.toolsUpdated();
        return new Outcome(created, updated, selected);
    }

    private void checkDependencies(long compositionId, List<CompositionItemEntity> selected,
                                   Map<String, List<ConfigProblem>> blocked) {
        Set<String> selectedKeys = selected.stream().map(CompositionPublisher::key).collect(Collectors.toSet());
        Set<String> draftKeys = service.items(compositionId).stream().map(CompositionPublisher::key)
                .collect(Collectors.toSet());
        for (CompositionItemEntity item : selected) {
            JsonNode content = service.contentOf(item);
            if (ConfigItemType.WORKFLOW.jsonName().equals(item.itemType)) {
                for (String name : CompositionItems.referencedActionTypes(content)) {
                    requireDependency(item, ConfigItemType.ACTION_TYPE, name,
                            ActionTypeEntity.count("name", name) > 0, selectedKeys, draftKeys, blocked);
                }
            } else if (ConfigItemType.ACTION_TYPE.jsonName().equals(item.itemType)) {
                for (String name : CompositionItems.referencedTools(content)) {
                    requireDependency(item, ConfigItemType.TOOL, name,
                            ToolDefinitionEntity.count("name", name) > 0, selectedKeys, draftKeys, blocked);
                }
            }
        }
    }

    private static void requireDependency(CompositionItemEntity item, ConfigItemType type, String name,
                                          boolean inDatabase, Set<String> selectedKeys, Set<String> draftKeys,
                                          Map<String, List<ConfigProblem>> blocked) {
        String dependency = type.jsonName() + "/" + name;
        if (selectedKeys.contains(dependency) || inDatabase) {
            return;
        }
        String message = draftKeys.contains(dependency)
                ? "Uses the draft " + type.jsonName() + " '" + name + "', which must be published with it"
                : "Uses the " + type.jsonName() + " '" + name + "', which does not exist";
        blocked.computeIfAbsent(key(item), k -> new ArrayList<>())
                .add(ConfigProblem.error("", "missing-dependency", message));
    }

    private static Long publishedId(ConfigItemType type, String name) {
        return switch (type) {
            case WORKFLOW -> WorkflowDefinitionEntity.<WorkflowDefinitionEntity>find("name", name).firstResult().id;
            case ACTION_TYPE -> ActionTypeEntity.<ActionTypeEntity>find("name", name).firstResult().id;
            case TOOL -> ToolDefinitionEntity.<ToolDefinitionEntity>find("name", name).firstResult().id;
            default -> throw new IllegalArgumentException("Unsupported type " + type);
        };
    }

    private static String key(CompositionItemEntity item) {
        return item.itemType + "/" + item.name;
    }
}
```

A dependency that exists in the database is satisfied even when an unselected draft with the same name
exists; the draft's unpublished edits simply are not part of this publish. Check the `ConfigProblem.error`
factory's parameter order with
`git grep -n "static ConfigProblem error" app/src/main/java/io/apitomy/axiom/app/validation/ConfigProblem.java`.
The call above assumes `error(path, code, message)`; adapt the argument order to the real signature.

- [ ] **Step 5: Run the test and confirm it passes**

Run: `mvn -q -pl app -am test -Dtest=CompositionPublisherTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS (5 tests). If `validActionType` is not valid under the real `ActionTypeValidator` (look at
the `PublishBlockedException` problems), fix the fixture to be a genuinely valid node action type. Do not
weaken the publisher.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/composition \
  app/src/test/java/io/apitomy/axiom/app/composition/CompositionPublisherTest.java
git commit -m "feat(workbench): publish selected composition drafts"
```

---
### Task 7: REST — compositions and items

**Files:**
- Modify: `common/api/src/main/resources/openapi.json` (add the paths and schemas below)
- Create: `app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/CompositionsResourceTest.java`

**Interfaces:**
- Consumes: `CompositionService` and the exceptions from Task 4.
- Produces:
  - The generated interface `io.apitomy.axiom.api.CompositionsResource`, under `@Path("/api/v1/compositions")`.
  - The beans `Composition`, `NewComposition`, `CompositionUpdate`, `CompositionItem`,
    `NewCompositionItem`, `CompositionItemUpdate` and `CompositionContent` (an any-properties map).
  - `CompositionsResourceImpl`, with private mappers `toBean(CompositionEntity)` and
    `toBean(CompositionItemEntity, boolean withContent)`. Task 8 extends this class.

- [ ] **Step 1: Add the contract to `openapi.json`**

Add these entries to `"paths"`. Every operation uses `"tags": ["Compositions"]`. Write each path-level
`parameters` entry as shown in the first path:

```json
"/compositions": {
  "get": {"tags": ["Compositions"], "summary": "List workbench compositions", "operationId": "listCompositions",
    "responses": {"200": {"description": "Compositions, newest first", "content": {"application/json": {"schema": {"type": "array", "items": {"$ref": "#/components/schemas/Composition"}}}}}}},
  "post": {"tags": ["Compositions"], "summary": "Create a composition, optionally seeded from an existing workflow", "operationId": "createComposition",
    "requestBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/NewComposition"}}}},
    "responses": {"200": {"description": "The composition", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Composition"}}}}, "400": {"description": "Invalid request"}}}
},
"/compositions/{compositionId}": {
  "get": {"tags": ["Compositions"], "summary": "Get a composition", "operationId": "getComposition",
    "responses": {"200": {"description": "The composition", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Composition"}}}}, "404": {"$ref": "#/components/responses/NotFound"}}},
  "patch": {"tags": ["Compositions"], "summary": "Rename a composition or change its apply mode", "operationId": "updateComposition",
    "requestBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CompositionUpdate"}}}},
    "responses": {"200": {"description": "The composition", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Composition"}}}}, "400": {"description": "Invalid request"}, "404": {"$ref": "#/components/responses/NotFound"}}},
  "delete": {"tags": ["Compositions"], "summary": "Delete a composition and its unpublished drafts", "operationId": "deleteComposition",
    "responses": {"204": {"description": "Deleted"}, "404": {"$ref": "#/components/responses/NotFound"}}},
  "parameters": [{"name": "compositionId", "in": "path", "required": true, "schema": {"format": "int64", "type": "integer"}}]
},
"/compositions/{compositionId}/items": {
  "get": {"tags": ["Compositions"], "summary": "List a composition's draft items (without content)", "operationId": "listCompositionItems",
    "responses": {"200": {"description": "Items: the workflow, then action types, then tools", "content": {"application/json": {"schema": {"type": "array", "items": {"$ref": "#/components/schemas/CompositionItem"}}}}}, "404": {"$ref": "#/components/responses/NotFound"}}},
  "post": {"tags": ["Compositions"], "summary": "Add a blank draft, or a draft copy of an existing item", "operationId": "addCompositionItem",
    "requestBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/NewCompositionItem"}}}},
    "responses": {"200": {"description": "The item, with content", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CompositionItem"}}}}, "400": {"description": "Invalid request"}, "404": {"$ref": "#/components/responses/NotFound"}, "409": {"description": "A second workflow or a duplicate name"}}},
  "parameters": [ "compositionId as above" ]
},
"/compositions/{compositionId}/items/{itemId}": {
  "get": {"tags": ["Compositions"], "summary": "Get a draft item with its content", "operationId": "getCompositionItem",
    "responses": {"200": {"description": "The item, with content", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CompositionItem"}}}}, "404": {"$ref": "#/components/responses/NotFound"}}},
  "put": {"tags": ["Compositions"], "summary": "Save a draft item's content (a user edit)", "description": "Fails with 409 if baseRevision is not the item's current contentRevision. The 409 body has message and currentRevision.", "operationId": "updateCompositionItem",
    "requestBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CompositionItemUpdate"}}}},
    "responses": {"200": {"description": "The saved item, with content", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CompositionItem"}}}}, "400": {"description": "Invalid content"}, "404": {"$ref": "#/components/responses/NotFound"}, "409": {"description": "Stale baseRevision or duplicate name"}}},
  "delete": {"tags": ["Compositions"], "summary": "Remove a draft from the composition (published items are kept)", "operationId": "deleteCompositionItem",
    "responses": {"204": {"description": "Removed"}, "404": {"$ref": "#/components/responses/NotFound"}}},
  "parameters": [ "compositionId as above", {"name": "itemId", "in": "path", "required": true, "schema": {"format": "int64", "type": "integer"}} ]
}
```

(`"compositionId as above"` stands for the full parameter object from the first path. Write it out in
full.)

Add these to `components.schemas`:

```json
"CompositionContent": {"description": "A draft item's JSON: the Flow Workflow document for workflows, otherwise the configuration item JSON.", "type": "object", "additionalProperties": true},
"Composition": {"required": ["id", "name", "turnState", "applyMode", "createdAt", "updatedAt"], "type": "object", "properties": {
  "id": {"format": "int64", "type": "integer"}, "name": {"type": "string"}, "goal": {"type": "string"},
  "engine": {"type": "string"}, "model": {"type": "string"}, "assistantSessionId": {"type": "string"},
  "turnState": {"enum": ["idle", "ai-working"], "type": "string"},
  "applyMode": {"enum": ["live", "review"], "type": "string"},
  "createdAt": {"format": "date-time", "type": "string"}, "updatedAt": {"format": "date-time", "type": "string"}}},
"NewComposition": {"required": ["name"], "type": "object", "properties": {
  "name": {"type": "string"}, "goal": {"type": "string"}, "engine": {"type": "string"}, "model": {"type": "string"},
  "seedWorkflowId": {"description": "Existing workflow definition to load, with its action types and tools", "format": "int64", "type": "integer"}}},
"CompositionUpdate": {"type": "object", "properties": {"name": {"type": "string"}, "applyMode": {"enum": ["live", "review"], "type": "string"}}},
"CompositionItem": {"required": ["id", "type", "name", "contentRevision", "lastAuthor", "changed"], "type": "object", "properties": {
  "id": {"format": "int64", "type": "integer"},
  "type": {"enum": ["workflow", "action-type", "tool"], "type": "string"},
  "name": {"type": "string"}, "contentRevision": {"type": "string"},
  "baseItemId": {"description": "Database id of the published item this draft edits", "format": "int64", "type": "integer"},
  "lastAuthor": {"enum": ["user", "ai"], "type": "string"}, "lastTurnId": {"type": "string"},
  "publishedRevision": {"type": "string"}, "publishedAt": {"format": "date-time", "type": "string"},
  "changed": {"description": "True when publishing would change something", "type": "boolean"},
  "content": {"$ref": "#/components/schemas/CompositionContent"}}},
"NewCompositionItem": {"required": ["type"], "type": "object", "properties": {
  "type": {"enum": ["workflow", "action-type", "tool"], "type": "string"},
  "name": {"description": "Name of a new blank item (ignored when existingItemId is set)", "type": "string"},
  "existingItemId": {"description": "Database id of an existing item to copy as a draft", "format": "int64", "type": "integer"}}},
"CompositionItemUpdate": {"required": ["content", "baseRevision"], "type": "object", "properties": {
  "content": {"$ref": "#/components/schemas/CompositionContent"},
  "baseRevision": {"description": "The contentRevision this edit was based on", "type": "string"}}}
```

Then regenerate and look at the generated interface (method names, parameter types and enum classes):

```bash
mvn -q -pl app -am compile
sed -n '1,200p' common/api/target/generated-sources/jaxrs/io/apitomy/axiom/api/CompositionsResource.java
```

- [ ] **Step 2: Write the failing REST test**

```java
package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * REST tests for compositions and their draft items.
 */
@QuarkusTest
class CompositionsResourceTest {

    static final String BASE = "/api/v1/compositions";

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    static long createComposition() {
        return given().contentType(ContentType.JSON).body("{\"name\":\"rest-c\",\"goal\":\"triage\"}")
                .post(BASE).then().statusCode(200)
                .body("turnState", equalTo("idle")).body("applyMode", equalTo("live"))
                .extract().jsonPath().getLong("id");
    }

    static JsonPath addItem(long compositionId, String type, String name) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("type", type, "name", name))
                .post(BASE + "/{id}/items", compositionId).then().statusCode(200)
                .body("content", notNullValue()).extract().jsonPath();
    }

    @Test
    void createsListsUpdatesAndDeletes() {
        long id = createComposition();
        given().get(BASE).then().statusCode(200).body("$", hasSize(1));
        given().contentType(ContentType.JSON).body("{\"applyMode\":\"review\"}")
                .patch(BASE + "/{id}", id).then().statusCode(200).body("applyMode", equalTo("review"));
        given().delete(BASE + "/{id}", id).then().statusCode(204);
        given().get(BASE + "/{id}", id).then().statusCode(404);
    }

    @Test
    void addsItemsAndRejectsSecondWorkflow() {
        long id = createComposition();
        addItem(id, "workflow", "rest-wf");
        addItem(id, "tool", "rest-tool");
        given().get(BASE + "/{id}/items", id).then().statusCode(200)
                .body("$", hasSize(2)).body("[0].type", equalTo("workflow"))
                .body("[0].content", nullValue()).body("[1].changed", equalTo(true));
        given().contentType(ContentType.JSON).body("{\"type\":\"workflow\",\"name\":\"other\"}")
                .post(BASE + "/{id}/items", id).then().statusCode(409);
        given().contentType(ContentType.JSON).body("{\"type\":\"toolset\",\"name\":\"x\"}")
                .post(BASE + "/{id}/items", id).then().statusCode(400);
    }

    @Test
    void savesWithBaseRevisionAndReportsStale() {
        long id = createComposition();
        JsonPath tool = addItem(id, "tool", "rest-tool");
        long itemId = tool.getLong("id");
        String revision = tool.getString("contentRevision");
        Map<String, Object> content = tool.getMap("content");
        content.put("description", "edited");

        String newRevision = given().contentType(ContentType.JSON)
                .body(Map.of("content", content, "baseRevision", revision))
                .put(BASE + "/{id}/items/{itemId}", id, itemId).then().statusCode(200)
                .body("lastAuthor", equalTo("user")).body("content.description", equalTo("edited"))
                .extract().path("contentRevision");

        given().contentType(ContentType.JSON).body(Map.of("content", content, "baseRevision", revision))
                .put(BASE + "/{id}/items/{itemId}", id, itemId).then().statusCode(409)
                .body("currentRevision", equalTo(newRevision));
        given().get(BASE + "/{id}/items/{itemId}", id, 999_999L).then().statusCode(404);
    }

    @Test
    void deletesItem() {
        long id = createComposition();
        long itemId = addItem(id, "tool", "rest-tool").getLong("id");
        given().delete(BASE + "/{id}/items/{itemId}", id, itemId).then().statusCode(204);
        given().get(BASE + "/{id}/items", id).then().statusCode(200).body("$", hasSize(0));
    }
}
```

- [ ] **Step 3: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=CompositionsResourceTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: FAIL. The endpoints return 404 because no implementation exists, or compilation fails if
something references the impl.

- [ ] **Step 4: Implement `CompositionsResourceImpl`**

Implement every method of the generated `CompositionsResource`. Match its exact method names, parameter
types and return types; read them from the generated file. The body for each operation:

| Operation | Body |
|---|---|
| `listCompositions` | `service.list().stream().map(this::toBean).toList()` |
| `createComposition(data)` | `toBean(service.create(data.getName(), data.getGoal(), data.getEngine(), data.getModel(), data.getSeedWorkflowId()))` |
| `getComposition(id)` | `toBean(service.get(id))` |
| `updateComposition(id, data)` | `toBean(service.update(id, data.getName(), data.getApplyMode() == null ? null : data.getApplyMode().value()))` |
| `deleteComposition(id)` | `service.delete(id)` |
| `listCompositionItems(id)` | `service.items(id).stream().map(i -> toBean(i, false)).toList()` |
| `addCompositionItem(id, data)` | type = `CompositionItems.supportedType(data.getType().value())`. If `existingItemId` is set, call `service.addExisting(id, type, existingItemId)`; otherwise call `service.addBlank(id, type, data.getName())`. Return `toBean(item, true)`. |
| `getCompositionItem(id, itemId)` | `toBean(service.item(id, itemId), true)` |
| `updateCompositionItem(id, itemId, data)` | `content = data.getContent() == null ? null : mapper.valueToTree(data.getContent().getAdditionalProperties())`. Then `toBean(service.save(id, itemId, content, data.getBaseRevision(), CompositionService.AUTHOR_USER, null), true)` |
| `deleteCompositionItem(id, itemId)` | `service.removeItem(id, itemId)` |

Class skeleton:

```java
package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.CompositionsResource;
import io.apitomy.axiom.api.beans.Composition;
import io.apitomy.axiom.api.beans.CompositionContent;
import io.apitomy.axiom.api.beans.CompositionItem;
import io.apitomy.axiom.app.composition.CompositionConflictException;
import io.apitomy.axiom.app.composition.CompositionItems;
import io.apitomy.axiom.app.composition.CompositionNotFoundException;
import io.apitomy.axiom.app.composition.CompositionService;
import io.apitomy.axiom.app.composition.StaleRevisionException;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST implementation of workbench compositions: drafts, revisions, validation and publish.
 */
@ApplicationScoped
@RunOnVirtualThread
public class CompositionsResourceImpl implements CompositionsResource {

    @Inject
    CompositionService service;

    @Inject
    ObjectMapper mapper;

    // ... the operations from the table above, each with /** {@inheritDoc} */ ...

    private Composition toBean(CompositionEntity entity) {
        Composition bean = new Composition();
        bean.setId(entity.id);
        bean.setName(entity.name);
        bean.setGoal(entity.goal);
        bean.setEngine(entity.engine);
        bean.setModel(entity.model);
        bean.setAssistantSessionId(entity.assistantSessionId);
        bean.setTurnState(Composition.TurnState.fromValue(entity.turnState));
        bean.setApplyMode(Composition.ApplyMode.fromValue(entity.applyMode));
        bean.setCreatedAt(Date.from(entity.createdOn));
        bean.setUpdatedAt(Date.from(entity.updatedOn));
        return bean;
    }

    @SuppressWarnings("unchecked")
    private CompositionItem toBean(CompositionItemEntity entity, boolean withContent) {
        CompositionItem bean = new CompositionItem();
        bean.setId(entity.id);
        bean.setType(CompositionItem.Type.fromValue(entity.itemType));
        bean.setName(entity.name);
        bean.setContentRevision(entity.contentRevision);
        bean.setBaseItemId(entity.baseItemId);
        bean.setLastAuthor(CompositionItem.LastAuthor.fromValue(entity.lastAuthor));
        bean.setLastTurnId(entity.lastTurnId);
        bean.setPublishedRevision(entity.publishedRevision);
        bean.setPublishedAt(entity.publishedOn == null ? null : Date.from(entity.publishedOn));
        bean.setChanged(CompositionService.isChanged(entity));
        if (withContent) {
            CompositionContent content = new CompositionContent();
            mapper.convertValue(service.contentOf(entity), Map.class)
                    .forEach((k, v) -> content.setAdditionalProperty((String) k, v));
            bean.setContent(content);
        }
        return bean;
    }

    private static Response error(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body).build();
    }

    /**
     * @param e the exception
     * @return 404
     */
    @ServerExceptionMapper
    public Response mapNotFound(CompositionNotFoundException e) {
        return error(404, e.getMessage());
    }

    /**
     * @param e the exception
     * @return 409
     */
    @ServerExceptionMapper
    public Response mapConflict(CompositionConflictException e) {
        return error(409, e.getMessage());
    }

    /**
     * @param e the exception
     * @return 409 with the current revision
     */
    @ServerExceptionMapper
    public Response mapStale(StaleRevisionException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", e.getMessage());
        body.put("currentRevision", e.currentRevision());
        return Response.status(409).type(MediaType.APPLICATION_JSON).entity(body).build();
    }

    /**
     * @param e the exception
     * @return 400
     */
    @ServerExceptionMapper
    public Response mapBadRequest(IllegalArgumentException e) {
        return error(400, e.getMessage());
    }
}
```

The generated enum class names (`Composition.TurnState`, `CompositionItem.Type`, and so on) and the date
type (`Date` versus another type) must match what the generator produced. Adjust them to the generated
code. If the generated path parameters are `BigInteger` or `Long` rather than `long`, convert them at the
top of each method. Check that `@ServerExceptionMapper` resolves; it is used the same way in
`LineageResourceImpl`. Note that mapping `IllegalArgumentException` in this class only affects this
resource.

`mapper.convertValue` keeps JSON `null` values as map entries, so the content that comes back to the
client hashes the same as what was stored. Do not filter out nulls.

- [ ] **Step 5: Run the test and confirm it passes**

Run: `mvn -q -pl app -am test -Dtest=CompositionsResourceTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add common/api/src/main/resources/openapi.json \
  app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java \
  app/src/test/java/io/apitomy/axiom/app/composition/CompositionsResourceTest.java
git commit -m "feat(workbench): add compositions REST API"
```

---

### Task 8: REST — revisions, validation and publish

**Files:**
- Modify: `common/api/src/main/resources/openapi.json`
- Modify: `app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/composition/CompositionsResourceFlowTest.java`

**Interfaces:**
- Consumes: `CompositionService.revisions/restore`,
  `CompositionValidator.validateAll/validateContent` and `CompositionPublisher.publish`.
- Produces the REST operations `listCompositionItemRevisions`, `restoreCompositionItemRevision`,
  `validateComposition`, `validateCompositionItem` and `publishComposition`. The publish 422 body is
  `{message, problems: {"<type>/<name>": [ConfigProblem]}}`.

- [ ] **Step 1: Add the contract**

Add these paths. Write out the `compositionId` and `itemId` path parameters in full, as in Task 7, and add a
`revisionId` parameter of the same shape:

```json
"/compositions/{compositionId}/items/{itemId}/revisions": {
  "get": {"tags": ["Compositions"], "summary": "List an item's revisions, newest first", "operationId": "listCompositionItemRevisions",
    "responses": {"200": {"description": "Revisions", "content": {"application/json": {"schema": {"type": "array", "items": {"$ref": "#/components/schemas/CompositionRevision"}}}}}, "404": {"$ref": "#/components/responses/NotFound"}}},
  "parameters": ["compositionId", "itemId"]
},
"/compositions/{compositionId}/items/{itemId}/revisions/{revisionId}/restore": {
  "post": {"tags": ["Compositions"], "summary": "Restore a revision as a new user revision", "operationId": "restoreCompositionItemRevision",
    "responses": {"200": {"description": "The item, with content", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CompositionItem"}}}}, "404": {"$ref": "#/components/responses/NotFound"}, "409": {"description": "The restored name collides with another item"}}},
  "parameters": ["compositionId", "itemId", "revisionId"]
},
"/compositions/{compositionId}/items/{itemId}/validate": {
  "post": {"tags": ["Compositions"], "summary": "Validate unsaved content for an item against the drafts and the database", "operationId": "validateCompositionItem",
    "requestBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CompositionItemValidateRequest"}}}},
    "responses": {"200": {"description": "Validation result", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/AssistantValidateResult"}}}}, "404": {"$ref": "#/components/responses/NotFound"}}},
  "parameters": ["compositionId", "itemId"]
},
"/compositions/{compositionId}/validate": {
  "post": {"tags": ["Compositions"], "summary": "Validate every draft in a composition", "operationId": "validateComposition",
    "responses": {"200": {"description": "Problems per item", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CompositionValidationResult"}}}}, "404": {"$ref": "#/components/responses/NotFound"}}},
  "parameters": ["compositionId"]
},
"/compositions/{compositionId}/publish": {
  "post": {"tags": ["Compositions"], "summary": "Publish selected drafts in one transaction", "description": "422 body: {message, problems: {\"<type>/<name>\": [ConfigProblem]}}. 409 when a published item changed outside the workbench and force is false.", "operationId": "publishComposition",
    "requestBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CompositionPublishRequest"}}}},
    "responses": {"200": {"description": "Publish result", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CompositionPublishResult"}}}}, "400": {"description": "No items selected"}, "404": {"$ref": "#/components/responses/NotFound"}, "409": {"description": "Changed outside the workbench"}, "422": {"description": "Errors or missing dependencies"}}},
  "parameters": ["compositionId"]
}
```

Add these schemas:

```json
"CompositionRevision": {"required": ["id", "contentRevision", "author", "createdAt"], "type": "object", "properties": {
  "id": {"format": "int64", "type": "integer"}, "contentRevision": {"type": "string"},
  "author": {"enum": ["user", "ai"], "type": "string"}, "turnId": {"type": "string"},
  "createdAt": {"format": "date-time", "type": "string"},
  "content": {"$ref": "#/components/schemas/CompositionContent"}}},
"CompositionItemValidateRequest": {"required": ["content"], "type": "object", "properties": {
  "content": {"$ref": "#/components/schemas/CompositionContent"}}},
"CompositionItemProblems": {"required": ["itemId", "type", "name", "valid", "problems"], "type": "object", "properties": {
  "itemId": {"format": "int64", "type": "integer"},
  "type": {"enum": ["workflow", "action-type", "tool"], "type": "string"},
  "name": {"type": "string"}, "valid": {"type": "boolean"},
  "problems": {"type": "array", "items": {"$ref": "#/components/schemas/ConfigProblem"}}}},
"CompositionValidationResult": {"required": ["items"], "type": "object", "properties": {
  "items": {"type": "array", "items": {"$ref": "#/components/schemas/CompositionItemProblems"}}}},
"CompositionPublishRequest": {"required": ["itemIds"], "type": "object", "properties": {
  "itemIds": {"type": "array", "items": {"format": "int64", "type": "integer"}},
  "force": {"description": "Publish even if a published item changed outside the workbench", "type": "boolean"}}},
"CompositionPublishResult": {"required": ["created", "updated", "items"], "type": "object", "properties": {
  "created": {"type": "integer"}, "updated": {"type": "integer"},
  "items": {"type": "array", "items": {"$ref": "#/components/schemas/CompositionItem"}}}}
```

Regenerate with `mvn -q -pl app -am compile` and read the new method signatures in `CompositionsResource`.

- [ ] **Step 2: Write the failing test**

```java
package io.apitomy.axiom.app.composition;

import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;

/**
 * REST tests for revisions, validation and publish.
 */
@QuarkusTest
class CompositionsResourceFlowTest {

    static final String BASE = CompositionsResourceTest.BASE;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
            WorkflowDefinitionEntity.list("name like ?1", "crf-%").forEach(e -> e.delete());
            ActionTypeEntity.list("name like ?1", "crf-%").forEach(e -> e.delete());
        });
    }

    @Test
    void listsAndRestoresRevisions() {
        long id = CompositionsResourceTest.createComposition();
        JsonPath tool = CompositionsResourceTest.addItem(id, "tool", "crf-tool");
        long itemId = tool.getLong("id");
        Map<String, Object> content = tool.getMap("content");
        content.put("description", "v2");
        given().contentType(ContentType.JSON)
                .body(Map.of("content", content, "baseRevision", tool.getString("contentRevision")))
                .put(BASE + "/{id}/items/{itemId}", id, itemId).then().statusCode(200);

        JsonPath revisions = given().get(BASE + "/{id}/items/{itemId}/revisions", id, itemId)
                .then().statusCode(200).body("$", hasSize(2)).extract().jsonPath();
        long first = revisions.getLong("[1].id");
        given().post(BASE + "/{id}/items/{itemId}/revisions/{rev}/restore", id, itemId, first)
                .then().statusCode(200).body("contentRevision", equalTo(tool.getString("contentRevision")));
    }

    @Test
    void validatesUnsavedContentAndWholeComposition() {
        long id = CompositionsResourceTest.createComposition();
        JsonPath workflow = CompositionsResourceTest.addItem(id, "workflow", "crf-wf");
        Map<String, Object> content = workflow.getMap("content");
        given().contentType(ContentType.JSON).body(Map.of("content", content))
                .post(BASE + "/{id}/items/{itemId}/validate", id, workflow.getLong("id"))
                .then().statusCode(200).body("valid", equalTo(true));
        given().post(BASE + "/{id}/validate", id).then().statusCode(200)
                .body("items", hasSize(1)).body("items[0].name", equalTo("crf-wf"));
    }

    @Test
    void publishesAndReportsBlockedItems() {
        long id = CompositionsResourceTest.createComposition();
        JsonPath workflow = CompositionsResourceTest.addItem(id, "workflow", "crf-wf");
        given().contentType(ContentType.JSON).body(Map.of("itemIds", List.of(workflow.getLong("id"))))
                .post(BASE + "/{id}/publish", id).then().statusCode(200)
                .body("created", equalTo(1)).body("items[0].changed", equalTo(false));

        JsonPath badTool = CompositionsResourceTest.addItem(id, "tool", "crf-bad tool name!");
        given().contentType(ContentType.JSON).body(Map.of("itemIds", List.of(badTool.getLong("id"))))
                .post(BASE + "/{id}/publish", id).then().statusCode(422)
                .body("problems", hasKey("tool/crf-bad tool name!"));
        given().contentType(ContentType.JSON).body(Map.of("itemIds", List.of()))
                .post(BASE + "/{id}/publish", id).then().statusCode(400);
    }
}
```

The bad-tool case assumes that tool names with spaces are invalid. If they are not, choose a fixture that
`ConfigItemValidationService` really rejects for tools (for example, an empty `scriptTemplate`), and write
it through `PUT` before publishing.

- [ ] **Step 3: Run the test and confirm it fails**

Run: `mvn -q -pl app -am test -Dtest=CompositionsResourceFlowTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected: compilation fails, because `CompositionsResourceImpl` does not implement the new methods.

- [ ] **Step 4: Implement the operations**

In `CompositionsResourceImpl`, inject `CompositionValidator validator` and `CompositionPublisher publisher`,
then implement:

| Operation | Body |
|---|---|
| `listCompositionItemRevisions(id, itemId)` | Map each `service.revisions(id, itemId)` to `CompositionRevision` (id, contentRevision, author enum, turnId, createdAt). Omit `content` from the list. |
| `restoreCompositionItemRevision(id, itemId, revisionId)` | `toBean(service.restore(id, itemId, revisionId), true)` |
| `validateCompositionItem(id, itemId, data)` | `item = service.item(id, itemId)`. Then `problems = validator.validateContent(id, ConfigItemType.fromName(item.itemType), item.name, mapper.valueToTree(data.getContent().getAdditionalProperties()))`. Return an `AssistantValidateResult` with `valid = no error-severity problem` and the problems mapped to `ConfigProblem` beans (severity enum, path, code, message), as `AssistantResourceImpl.validateAssistantItem` does. |
| `validateComposition(id)` | For each entry of `validator.validateAll(id)`, build a `CompositionItemProblems` (itemId, type, name, valid, problems). Return `CompositionValidationResult`. |
| `publishComposition(id, data)` | `outcome = publisher.publish(id, data.getItemIds(), Boolean.TRUE.equals(data.getForce()))`. Return `CompositionPublishResult(created, updated, items mapped with toBean(i, false))`. |

Put the problem-bean mapping in one private helper, `toProblemBeans(List<io.apitomy.axiom.app.validation.ConfigProblem>)`,
and use it for all three operations that return problems. Add a mapper for `PublishBlockedException`:

```java
    /**
     * @param e the exception
     * @return 422 with problems keyed by "&lt;type&gt;/&lt;name&gt;"
     */
    @ServerExceptionMapper
    public Response mapPublishBlocked(PublishBlockedException e) {
        Map<String, Object> problems = new LinkedHashMap<>();
        e.problems().forEach((key, list) -> problems.put(key, toProblemBeans(list)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", e.getMessage());
        body.put("problems", problems);
        return Response.status(422).type(MediaType.APPLICATION_JSON).entity(body).build();
    }
```

Convert `itemIds` to `List<Long>` if the generator produced `Long`/`BigInteger` elements.

- [ ] **Step 5: Run the REST tests and confirm they pass**

Run: `mvn -q -pl app -am test -Dtest='CompositionsResource*Test' -Dsurefire.failIfNoSpecifiedTests=false`

Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add common/api/src/main/resources/openapi.json \
  app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java \
  app/src/test/java/io/apitomy/axiom/app/composition/CompositionsResourceFlowTest.java
git commit -m "feat(workbench): add composition revisions, validation and publish endpoints"
```

---

### Task 9: Full verification

**Files:** none. This task only verifies.

- [ ] **Step 1: Full build (no clean)**

Run: `mvn -q package`. Expected: exit 0. Then run
`grep -l "<failure\|<error" */target/surefire-reports/*.xml */*/target/surefire-reports/*.xml` and expect
no output. Report the total test count from the Surefire XML files.

- [ ] **Step 2: Contract check**

Run: `git grep -n "@Path" app/src/main/java/io/apitomy/axiom/app/rest/CompositionsResourceImpl.java`.
Expected: no output.

- [ ] **Step 3: Report**

List the commands run and their results. State that nothing was checked in a browser, because there is no
UI in this phase.
