# Workflow Receive Event Node Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** A workflow branch entering a `receive-event` node parks until a matching Axiom event
arrives, then resumes automatically with event data merged into workflow context.

**Architecture:** Mirrors the Wait node pattern: a `workflow_event_subscription` row is persisted
when a branch parks; a new `WorkflowEventDispatcher`, invoked from `PipelineOrchestrator` right
after events pass source filters, prefilters subscriptions by event type, applies hybrid
project/broadcast scoping, delegates matching to the Flow engine's `matchesEvent`, and resumes
matched runs via a new `WorkflowExecutionService.onEventReceived` that reuses `advanceWorkflow`.

**Tech Stack:** Java 21 / Quarkus, Hibernate ORM Panache, Flyway (H2 + Postgres), JUnit 5 +
`@QuarkusTest`, `io.apitomy:apitomy-flow-engine` 2.0.2.

**Spec:** `docs/superpowers/specs/2026-09-16-workflow-receive-event-design.md`

## Global Constraints

- 4-space indentation, explicit types, Javadoc on public methods (user preference).
- Tests use JUnit 5 with `@QuarkusTest`; scheduled/polling jobs are disabled in the test profile.
- Flyway migrations must be idempotent-friendly and work on both H2 (tests) and Postgres.
- Next free migration number is `V59` (verify with `ls app/src/main/resources/db/migration`
  before creating; bump if taken).
- Panache entity ID sequences use `START WITH 1 INCREMENT BY 50` (matches Hibernate default
  allocation).
- To compile the app module after core changes, core must be installed first:
  `mvn -q -pl core install -DskipTests`.
- Line references below are to branch `feat/workflow-receive-event-support` at the time of plan
  writing; verify with grep before editing.

---

### Task 1: WorkflowEventSubscriptionEntity + migration

**Files:**
- Create: `app/src/main/resources/db/migration/V59__create_workflow_event_subscription.sql`
- Create: `core/src/main/java/io/apitomy/axiom/core/entities/WorkflowEventSubscriptionEntity.java`

**Interfaces:**
- Consumes: nothing (foundation task).
- Produces: `WorkflowEventSubscriptionEntity extends PanacheEntity` with public fields
  `Long runId`, `String nodeId`, `String eventType`, `Long projectId`, `Instant createdOn`.
  Later tasks query it via `find("eventType", ...)`, `find("runId", ...)`,
  `delete("runId", ...)`.

- [ ] **Step 1: Write the migration**

Check the next free number first: `ls app/src/main/resources/db/migration | sort -t V -k2 -n | tail -3`.
Assuming `V59` is free, create `V59__create_workflow_event_subscription.sql`:

```sql
-- Tracks parked receive-event branches of running workflow instances. A row
-- exists while a branch waits for a matching event; the dispatcher deletes the
-- row in the same transaction as resuming the branch.
CREATE TABLE workflow_event_subscription (
    id BIGINT PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES workflow_run(id),
    node_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    project_id BIGINT NOT NULL,
    created_on TIMESTAMP NOT NULL
);

CREATE SEQUENCE IF NOT EXISTS workflow_event_subscription_SEQ START WITH 1 INCREMENT BY 50;

CREATE INDEX idx_wf_event_sub_event_type ON workflow_event_subscription(event_type);
CREATE INDEX idx_wf_event_sub_run ON workflow_event_subscription(run_id);
```

- [ ] **Step 2: Write the entity**

Create `core/src/main/java/io/apitomy/axiom/core/entities/WorkflowEventSubscriptionEntity.java`
(model it on `WorkflowWaitEntity.java` in the same package):

```java
package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A parked receive-event branch of a running {@link WorkflowRunEntity}. Exists
 * from the moment a workflow instance enters a {@code RECEIVE_EVENT} node until
 * a matching event arrives and the dispatcher resumes the branch, at which
 * point the row is deleted. {@code eventType} and {@code projectId} are
 * denormalized (from node config and the owning run respectively) so the
 * dispatcher can prefilter candidates cheaply.
 */
@Entity
@Table(name = "workflow_event_subscription")
public class WorkflowEventSubscriptionEntity extends PanacheEntity {

    @Column(name = "run_id", nullable = false)
    public Long runId;

    @Column(name = "node_id", nullable = false)
    public String nodeId;

    @Column(name = "event_type", nullable = false)
    public String eventType;

    @Column(name = "project_id", nullable = false)
    public Long projectId;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;
}
```

- [ ] **Step 3: Build core + app to verify migration and entity load**

Run: `mvn -q -pl core install -DskipTests && mvn -q -pl app test -Dtest=WorkflowExecutionServiceTest -DfailIfNoTests=false`

Expected: BUILD SUCCESS (the `@QuarkusTest` boot runs Flyway, proving the migration applies on
H2; the existing tests still pass).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/resources/db/migration/V59__create_workflow_event_subscription.sql \
        core/src/main/java/io/apitomy/axiom/core/entities/WorkflowEventSubscriptionEntity.java
git commit -m "feat(workflow): add workflow_event_subscription table and entity"
```

---

### Task 2: Park on receive-event nodes + onEventReceived resume path

**Files:**
- Modify: `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java`
  (~line 53 `SUPPORTED_NODE_TYPES`, ~line 249 after `onWaitElapsed`, ~line 302 `cancelWorkflow`,
  ~line 408 `createTaskForNode`, ~line 513 after `createWaitForNode`, ~line 552
  `validateNodeTypes` message)
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowExecutionServiceTest.java`

**Interfaces:**
- Consumes: `WorkflowEventSubscriptionEntity` (Task 1); engine API
  `workflowEngine.getReceiveEventInfo(Workflow, WorkflowInstance, String nodeId)` returning
  `io.apitomy.flow.model.ReceiveEventInfo` record with accessors `nodeId()`, `nodeName()`,
  `eventType()`, `matchExpressions()`; existing private helper
  `advanceWorkflow(WorkflowRunEntity, Workflow, WorkflowInstance, String nodeId, NodeResult)`.
- Produces: `@Transactional public void onEventReceived(long runId, String nodeId,
  Map<String, Object> eventMap)` — called by the dispatcher in Task 4. Parking behavior:
  a branch entering a receive-event node persists one `WorkflowEventSubscriptionEntity` and no
  `TaskEntity`.

- [ ] **Step 1: Write the failing tests**

Add to `WorkflowExecutionServiceTest.java`. The test class already has a
`createProjectAndDefinition(String projectName, String content)` helper and imports for
`QuarkusTransaction`, `WorkflowRunEntity`, `TaskEntity`, assertions. Add the import
`io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity` and `java.util.Map`, plus:

```java
    private static final String RECEIVE_EVENT_CONTENT = """
        {
            "id": "receive-event-wf",
            "name": "Receive Event WF",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start",
                 "config": {}, "position": {"x": 100, "y": 100}},
                {"id": "r1", "type": "receive-event", "name": "Await PR Merge",
                 "config": {"eventType": "pr-merged"},
                 "position": {"x": 100, "y": 200}},
                {"id": "e1", "type": "end", "name": "End",
                 "config": {}, "position": {"x": 100, "y": 300}}
            ],
            "edges": [
                {"id": "edge1", "source": "s1", "target": "r1",
                 "priority": 0, "isDefault": true},
                {"id": "edge2", "source": "r1", "target": "e1",
                 "priority": 0, "isDefault": true}
            ]
        }
        """;

    /**
     * Triggering a workflow that parks on a receive-event node should create a
     * {@link WorkflowEventSubscriptionEntity} (not a {@link TaskEntity})
     * addressed to the node, with the denormalized eventType and projectId.
     */
    @Test
    void triggeringReceiveEventWorkflowParksWithoutCreatingATask() {
        long[] ids = createProjectAndDefinition(
                "Receive Event Park Project", RECEIVE_EVENT_CONTENT);

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(ids[0], ids[1]));
        assertEquals("waiting", run.status,
                "Run should be waiting at the receive-event node");

        QuarkusTransaction.requiringNew().run(() -> {
            TaskEntity task = TaskEntity.find("workflowRunId", run.id).firstResult();
            assertNull(task, "Receive-event nodes must not create a task");

            WorkflowEventSubscriptionEntity sub = WorkflowEventSubscriptionEntity
                    .find("runId", run.id).firstResult();
            assertNotNull(sub, "A subscription should be created for the receive-event node");
            assertEquals("r1", sub.nodeId);
            assertEquals("pr-merged", sub.eventType);
            assertEquals(ids[0], sub.projectId);
        });
    }

    /**
     * {@link WorkflowExecutionService#onEventReceived(long, String, Map)}
     * should advance a WAITING run parked on a receive-event node through to
     * completion, mirroring what {@link WorkflowEventDispatcher} does when a
     * matching event arrives.
     */
    @Test
    void onEventReceivedAdvancesRunToCompleted() {
        long[] ids = createProjectAndDefinition(
                "Receive Event Resume Project", RECEIVE_EVENT_CONTENT);

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(ids[0], ids[1]));
        assertEquals("waiting", run.status);

        Map<String, Object> eventMap = Map.of(
                "type", "pr-merged",
                "source", "github",
                "issueRef", "test/repo#1",
                "payload", Map.of("number", 1));
        QuarkusTransaction.requiringNew().run(() ->
                workflowExecutionService.onEventReceived(run.id, "r1", eventMap));

        WorkflowRunEntity completedRun = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunEntity.findById(run.id));
        assertNotNull(completedRun);
        assertEquals("completed", completedRun.status,
                "Run should advance to completed once the event result is applied");
    }

    /**
     * Cancelling a run parked on a receive-event node should delete its
     * subscription rows.
     */
    @Test
    void cancellingRunDeletesEventSubscriptions() {
        long[] ids = createProjectAndDefinition(
                "Receive Event Cancel Project", RECEIVE_EVENT_CONTENT);

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(ids[0], ids[1]));

        QuarkusTransaction.requiringNew().run(() ->
                workflowExecutionService.cancelWorkflow(ids[0]));

        QuarkusTransaction.requiringNew().run(() -> {
            long count = WorkflowEventSubscriptionEntity.count("runId", run.id);
            assertEquals(0, count,
                    "Cancelling the run should delete its event subscriptions");
        });
    }
```

Note: if `WorkflowEventDispatcher` (referenced in a Javadoc comment) does not exist yet, use
plain text instead of a `{@link}` to keep Javadoc valid — write it as
`WorkflowEventDispatcher` without the link tag. Task 4 creates the class.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl app test -Dtest=WorkflowExecutionServiceTest -DfailIfNoTests=false`

Expected: FAIL — `triggeringReceiveEventWorkflowParksWithoutCreatingATask` gets an HTTP 400
(`Workflow contains unsupported node types: RECEIVE_EVENT`), `onEventReceivedAdvancesRunToCompleted`
fails compilation or the 400, `cancellingRunDeletesEventSubscriptions` gets the 400.

- [ ] **Step 3: Implement in WorkflowExecutionService**

All edits in `app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java`:

3a. Add import (with the other `io.apitomy.flow.model` imports):

```java
import io.apitomy.flow.model.ReceiveEventInfo;
```

and (with the other entity imports):

```java
import io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity;
```

3b. Extend the supported set (~line 53):

```java
    private static final Set<NodeType> SUPPORTED_NODE_TYPES =
            Set.of(NodeType.START, NodeType.END, NodeType.ACTION, NodeType.HUMAN_TASK,
                    NodeType.WAIT, NodeType.RECEIVE_EVENT);
```

3c. Update the `validateNodeTypes` error message (~line 560):

```java
                            + ". Supported: start, end, action, human-task, wait, receive-event.",
```

3d. In `createTaskForNode` (~line 408), after the `WaitInfo` block and before the `ActionInfo`
lookup, add:

```java
        ReceiveEventInfo receiveEventInfo =
                workflowEngine.getReceiveEventInfo(workflow, instance, nodeId);
        if (receiveEventInfo != null) {
            createEventSubscriptionForNode(entity, receiveEventInfo);
            return;
        }
```

3e. After `createWaitForNode` (~line 540), add the new helper:

```java
    /**
     * Parks a branch that has entered a receive-event node: persists a
     * {@link WorkflowEventSubscriptionEntity} so the event dispatcher can
     * resume the branch when a matching event arrives. No task is created —
     * the node resolves automatically, without user action.
     *
     * @param entity           the owning workflow run
     * @param receiveEventInfo the engine's receive-event introspection for the parked node
     */
    private void createEventSubscriptionForNode(WorkflowRunEntity entity,
            ReceiveEventInfo receiveEventInfo) {
        WorkflowEventSubscriptionEntity sub = new WorkflowEventSubscriptionEntity();
        sub.runId = entity.id;
        sub.nodeId = receiveEventInfo.nodeId();
        sub.eventType = receiveEventInfo.eventType();
        sub.projectId = entity.projectId;
        sub.createdOn = Instant.now();
        sub.persist();

        TraceContext traceCtx = traceContextFor(entity);
        if (traceCtx != null) {
            try {
                String nodeName = receiveEventInfo.nodeName();
                String label = (nodeName != null && !nodeName.isBlank()
                        ? nodeName : "Receive event")
                        + " (awaiting: " + sub.eventType + ")";
                traceService.addNode(traceCtx, "task", "in-progress", label,
                        "workflow-event-subscription", sub.id);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to add workflow event-subscription trace node");
            }
        }

        LOG.infof("Parked workflow instance %d at receive-event node %s (awaiting: %s)",
                entity.id, sub.nodeId, sub.eventType);
    }
```

3f. After `onWaitElapsed` (~line 263), add the resume path:

```java
    /**
     * Called when an Axiom event has matched a workflow's parked receive-event
     * node, advancing the workflow. The event map is merged into workflow
     * context under the {@code event} key so downstream nodes can reference
     * fields such as {@code event.payload.number}. The caller
     * (WorkflowEventDispatcher) is responsible for deleting the corresponding
     * {@link WorkflowEventSubscriptionEntity} row in the same transaction.
     *
     * @param runId    the id of the run to advance
     * @param nodeId   the parked receive-event node's id
     * @param eventMap the matched event, as built by WorkflowEventMapper
     */
    @Transactional
    public void onEventReceived(long runId, String nodeId, Map<String, Object> eventMap) {
        WorkflowRunEntity entity = WorkflowRunEntity.findById(runId);
        if (entity == null) {
            LOG.warnf("Workflow run %d not found for received event at node %s",
                    runId, nodeId);
            return;
        }

        Workflow workflow = loadWorkflowContent(
                entity.definitionId, entity.definitionVersion);
        WorkflowInstance instance = deserializeInstance(entity.instanceState);

        NodeResult result = new NodeResult(
                NodeResultStatus.COMPLETED, Map.of("event", eventMap));
        advanceWorkflow(entity, workflow, instance, nodeId, result);
    }
```

3g. In `cancelWorkflow` (~line 324), next to the existing wait cleanup:

```java
        WorkflowWaitEntity.delete("runId", entity.id);
        WorkflowEventSubscriptionEntity.delete("runId", entity.id);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl app test -Dtest=WorkflowExecutionServiceTest -DfailIfNoTests=false`

Expected: PASS (all tests in the class, including the pre-existing linear/wait tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/WorkflowExecutionService.java \
        app/src/test/java/io/apitomy/axiom/app/WorkflowExecutionServiceTest.java
git commit -m "feat(workflow): park and resume receive-event nodes in WorkflowExecutionService"
```

---

### Task 3: WorkflowEventMapper

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/WorkflowEventMapper.java`
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowEventMapperTest.java`

**Interfaces:**
- Consumes: `EventEntity` (core) — fields `eventType`, `source`, `issueRef`, `repository`,
  `receivedAt` (Instant), `payload` (String, raw JSON or null).
- Produces: `public static Map<String, Object> toEventMap(EventEntity event,
  ObjectMapper objectMapper)` — the curated event map defined in the spec. Keys always present:
  `type`, `source`, `payload` (a `Map<String,Object>`, empty when payload is null or
  unparseable). Keys present only when non-null on the entity: `issueRef`, `repository`,
  `receivedAt` (ISO-8601 String).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/io/apitomy/axiom/app/WorkflowEventMapperTest.java`. Plain JUnit — no
Quarkus needed:

```java
package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.EventEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the curated event-map shape produced by {@link WorkflowEventMapper}
 * for engine matching and workflow-context merging.
 */
class WorkflowEventMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsAllFieldsIncludingParsedPayload() {
        EventEntity event = new EventEntity();
        event.eventType = "pr-merged";
        event.source = "github";
        event.issueRef = "acme/widget#42";
        event.repository = "acme/widget";
        event.receivedAt = Instant.parse("2026-09-16T12:00:00Z");
        event.payload = "{\"number\": 42, \"action\": \"closed\"}";

        Map<String, Object> map = WorkflowEventMapper.toEventMap(event, objectMapper);

        assertEquals("pr-merged", map.get("type"));
        assertEquals("github", map.get("source"));
        assertEquals("acme/widget#42", map.get("issueRef"));
        assertEquals("acme/widget", map.get("repository"));
        assertEquals("2026-09-16T12:00:00Z", map.get("receivedAt"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) map.get("payload");
        assertEquals(42, payload.get("number"));
        assertEquals("closed", payload.get("action"));
    }

    @Test
    void nullPayloadYieldsEmptyPayloadMap() {
        EventEntity event = new EventEntity();
        event.eventType = "issue-created";
        event.source = "github";
        event.payload = null;

        Map<String, Object> map = WorkflowEventMapper.toEventMap(event, objectMapper);

        assertEquals(Map.of(), map.get("payload"));
    }

    @Test
    void unparseablePayloadYieldsEmptyPayloadMap() {
        EventEntity event = new EventEntity();
        event.eventType = "issue-created";
        event.source = "github";
        event.payload = "not json at all {{{";

        Map<String, Object> map = WorkflowEventMapper.toEventMap(event, objectMapper);

        assertEquals(Map.of(), map.get("payload"));
    }

    @Test
    void nullOptionalFieldsAreOmitted() {
        EventEntity event = new EventEntity();
        event.eventType = "internal-note";
        event.source = "internal";
        // issueRef, repository, receivedAt left null

        Map<String, Object> map = WorkflowEventMapper.toEventMap(event, objectMapper);

        assertEquals("internal-note", map.get("type"));
        assertFalse(map.containsKey("issueRef"));
        assertFalse(map.containsKey("repository"));
        assertFalse(map.containsKey("receivedAt"));
        assertTrue(map.containsKey("payload"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl app test -Dtest=WorkflowEventMapperTest -DfailIfNoTests=false`

Expected: COMPILATION FAILURE — `WorkflowEventMapper` does not exist.

- [ ] **Step 3: Implement WorkflowEventMapper**

Create `app/src/main/java/io/apitomy/axiom/app/WorkflowEventMapper.java`:

```java
package io.apitomy.axiom.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.EventEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds the curated event map used for receive-event node matching and for
 * merging into workflow context. The map's {@code type} key is what the Flow
 * engine's {@code matchesEvent} compares against a node's configured
 * {@code eventType}; the whole map is available to {@code match} EL
 * expressions and, on match, becomes the node's {@code event} output.
 */
public final class WorkflowEventMapper {

    private WorkflowEventMapper() {
    }

    /**
     * Maps an event entity to the curated event map.
     *
     * @param event        the event to map
     * @param objectMapper used to parse the raw JSON payload
     * @return a map with keys {@code type}, {@code source}, {@code payload}
     *         (always present; payload is an empty map when null/unparseable)
     *         and {@code issueRef}, {@code repository}, {@code receivedAt}
     *         (present only when non-null on the entity)
     */
    public static Map<String, Object> toEventMap(EventEntity event,
            ObjectMapper objectMapper) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", event.eventType);
        map.put("source", event.source);
        if (event.issueRef != null) {
            map.put("issueRef", event.issueRef);
        }
        if (event.repository != null) {
            map.put("repository", event.repository);
        }
        if (event.receivedAt != null) {
            map.put("receivedAt", event.receivedAt.toString());
        }
        map.put("payload", parsePayload(event.payload, objectMapper));
        return map;
    }

    private static Map<String, Object> parsePayload(String payload,
            ObjectMapper objectMapper) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(payload, Map.class);
            return parsed;
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl app test -Dtest=WorkflowEventMapperTest -DfailIfNoTests=false`

Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/WorkflowEventMapper.java \
        app/src/test/java/io/apitomy/axiom/app/WorkflowEventMapperTest.java
git commit -m "feat(workflow): add WorkflowEventMapper for receive-event matching"
```

---

### Task 4: WorkflowEventDispatcher + pipeline hook

**Files:**
- Create: `app/src/main/java/io/apitomy/axiom/app/WorkflowEventDispatcher.java`
- Modify: `app/src/main/java/io/apitomy/axiom/app/PipelineOrchestrator.java` (~line 192, right
  after the `filterStatus = "allowed"` transaction block, before trace creation)
- Test: `app/src/test/java/io/apitomy/axiom/app/WorkflowEventDispatcherTest.java`

**Interfaces:**
- Consumes: `WorkflowEventSubscriptionEntity` (Task 1);
  `WorkflowExecutionService.onEventReceived(long, String, Map<String,Object>)` (Task 2);
  `WorkflowEventMapper.toEventMap(EventEntity, ObjectMapper)` (Task 3); engine API
  `workflowEngine.matchesEvent(Workflow, WorkflowInstance, String nodeId, Map<String,Object>)`.
  The dispatcher needs its own `WorkflowEngine` — construct one in `@PostConstruct` exactly as
  `WorkflowExecutionService.init()` does (a stub `NodeExecutorProvider` returning PENDING,
  empty listeners, null error handler), since `matchesEvent` never executes nodes.
- Produces: `@Transactional public void dispatchEvent(long eventId)` — called by
  `PipelineOrchestrator`. Package-visible for tests.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/io/apitomy/axiom/app/WorkflowEventDispatcherTest.java`:

```java
package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exercises {@link WorkflowEventDispatcher#dispatchEvent(long)} directly
 * (bypassing the pipeline scheduler, disabled in the test profile), verifying
 * event-type prefiltering, hybrid project/broadcast scoping, match-expression
 * filtering, and end-to-end run resumption.
 */
@QuarkusTest
class WorkflowEventDispatcherTest {

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    WorkflowEventDispatcher dispatcher;

    private static final String RECEIVE_EVENT_CONTENT = """
        {
            "id": "receive-event-wf",
            "name": "Receive Event WF",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start",
                 "config": {}, "position": {"x": 100, "y": 100}},
                {"id": "r1", "type": "receive-event", "name": "Await PR Merge",
                 "config": {"eventType": "pr-merged"},
                 "position": {"x": 100, "y": 200}},
                {"id": "e1", "type": "end", "name": "End",
                 "config": {}, "position": {"x": 100, "y": 300}}
            ],
            "edges": [
                {"id": "edge1", "source": "s1", "target": "r1",
                 "priority": 0, "isDefault": true},
                {"id": "edge2", "source": "r1", "target": "e1",
                 "priority": 0, "isDefault": true}
            ]
        }
        """;

    private static final String MATCH_EXPRESSION_CONTENT = """
        {
            "id": "receive-event-match-wf",
            "name": "Receive Event Match WF",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start",
                 "config": {}, "position": {"x": 100, "y": 100}},
                {"id": "r1", "type": "receive-event", "name": "Await Big PR",
                 "config": {"eventType": "pr-merged",
                            "match": ["event.payload.number > 100"]},
                 "position": {"x": 100, "y": 200}},
                {"id": "e1", "type": "end", "name": "End",
                 "config": {}, "position": {"x": 100, "y": 300}}
            ],
            "edges": [
                {"id": "edge1", "source": "s1", "target": "r1",
                 "priority": 0, "isDefault": true},
                {"id": "edge2", "source": "r1", "target": "e1",
                 "priority": 0, "isDefault": true}
            ]
        }
        """;

    @Test
    void nonMatchingEventTypeResumesNothing() {
        long[] ids = setup("Dispatcher Type Filter Project", RECEIVE_EVENT_CONTENT);
        WorkflowRunEntity run = trigger(ids);

        long eventId = createEvent("issue-created", "github",
                projectRef(ids[0]), null);
        QuarkusTransaction.requiringNew().run(() -> dispatcher.dispatchEvent(eventId));

        assertRunStatus(run.id, "waiting");
        assertSubscriptionCount(run.id, 1);
    }

    @Test
    void projectScopedEventDoesNotResumeOtherProjectsRun() {
        long[] idsA = setup("Dispatcher Scope Project A", RECEIVE_EVENT_CONTENT);
        long[] idsB = setup("Dispatcher Scope Project B", RECEIVE_EVENT_CONTENT);
        WorkflowRunEntity runA = trigger(idsA);
        WorkflowRunEntity runB = trigger(idsB);

        // Event correlated to project A (issueRef == project A's ref).
        long eventId = createEvent("pr-merged", "github", projectRef(idsA[0]), null);
        QuarkusTransaction.requiringNew().run(() -> dispatcher.dispatchEvent(eventId));

        assertRunStatus(runA.id, "completed");
        assertRunStatus(runB.id, "waiting");
        assertSubscriptionCount(runA.id, 0);
        assertSubscriptionCount(runB.id, 1);
    }

    @Test
    void broadcastEventResumesAnyMatchingRun() {
        long[] ids = setup("Dispatcher Broadcast Project", RECEIVE_EVENT_CONTENT);
        WorkflowRunEntity run = trigger(ids);

        // No issueRef and no projectId: broadcast.
        long eventId = createEvent("pr-merged", "github", null, null);
        QuarkusTransaction.requiringNew().run(() -> dispatcher.dispatchEvent(eventId));

        assertRunStatus(run.id, "completed");
        assertSubscriptionCount(run.id, 0);
    }

    @Test
    void falseMatchExpressionLeavesRunParked() {
        long[] ids = setup("Dispatcher Match Expr Project", MATCH_EXPRESSION_CONTENT);
        WorkflowRunEntity run = trigger(ids);

        long smallPr = createEvent("pr-merged", "github", projectRef(ids[0]),
                "{\"number\": 5}");
        QuarkusTransaction.requiringNew().run(() -> dispatcher.dispatchEvent(smallPr));
        assertRunStatus(run.id, "waiting");
        assertSubscriptionCount(run.id, 1);

        long bigPr = createEvent("pr-merged", "github", projectRef(ids[0]),
                "{\"number\": 500}");
        QuarkusTransaction.requiringNew().run(() -> dispatcher.dispatchEvent(bigPr));
        assertRunStatus(run.id, "completed");
        assertSubscriptionCount(run.id, 0);
    }

    // -- Helpers --

    private long[] setup(String projectName, String content) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ProjectEntity project = new ProjectEntity();
            project.name = projectName;
            project.type = "other";
            project.status = "new";
            project.ref = "test/" + projectName.toLowerCase().replace(" ", "-");
            project.createdOn = Instant.now();
            project.updatedOn = Instant.now();
            project.persist();

            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = projectName + " WF";
            def.content = content;
            def.currentVersion = 1;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();

            WorkflowDefinitionVersionEntity version = new WorkflowDefinitionVersionEntity();
            version.definitionId = def.id;
            version.version = 1;
            version.content = content;
            version.createdOn = Instant.now();
            version.persist();

            return new long[] { project.id, def.id };
        });
    }

    private WorkflowRunEntity trigger(long[] ids) {
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(ids[0], ids[1]));
        assertEquals("waiting", run.status);
        return run;
    }

    private String projectRef(long projectId) {
        return QuarkusTransaction.requiringNew().call(() ->
                ((ProjectEntity) ProjectEntity.findById(projectId)).ref);
    }

    private long createEvent(String eventType, String source, String issueRef,
            String payload) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EventEntity event = new EventEntity();
            event.eventType = eventType;
            event.source = source;
            event.issueRef = issueRef;
            event.payload = payload;
            event.receivedAt = Instant.now();
            event.persist();
            return event.id;
        });
    }

    private void assertRunStatus(long runId, String expected) {
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
            assertNotNull(run);
            assertEquals(expected, run.status);
        });
    }

    private void assertSubscriptionCount(long runId, long expected) {
        QuarkusTransaction.requiringNew().run(() -> assertEquals(expected,
                WorkflowEventSubscriptionEntity.count("runId", runId)));
    }
}
```

Note: verify `EventEntity`'s exact field names/nullability against
`core/src/main/java/io/apitomy/axiom/core/entities/EventEntity.java` before running — if any
NOT NULL columns beyond those set here exist (e.g. `filterStatus`), set them in `createEvent`.
Also verify the EL syntax for match expressions: the engine evaluates each expression with the
instance context and the event map as EL variables. Check how flow-ui documents `match`
expressions (e.g. whether the event map is root — `payload.number > 100` — or namespaced —
`event.payload.number > 100`). Write one quick throwaway assertion first if unsure, then use the
working syntax in `MATCH_EXPRESSION_CONTENT`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -q -pl app test -Dtest=WorkflowEventDispatcherTest -DfailIfNoTests=false`

Expected: COMPILATION FAILURE — `WorkflowEventDispatcher` does not exist.

- [ ] **Step 3: Implement WorkflowEventDispatcher**

Create `app/src/main/java/io/apitomy/axiom/app/WorkflowEventDispatcher.java`:

```java
package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.flow.engine.WorkflowEngine;
import io.apitomy.flow.model.Workflow;
import io.apitomy.flow.model.WorkflowInstance;
import io.apitomy.flow.spi.NodeExecutionContext;
import io.apitomy.flow.spi.NodeExecutor;
import io.apitomy.flow.spi.NodeExecutorProvider;
import io.apitomy.flow.spi.NodeResult;
import io.apitomy.flow.spi.NodeResultStatus;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Matches allowed pipeline events against parked receive-event workflow
 * branches and resumes the ones that match. Scoping is hybrid: an event that
 * correlates to a project (by {@code issueRef} → {@code project.ref}, else
 * {@code event.projectId}) is offered only to that project's subscriptions;
 * an uncorrelated event is broadcast to all subscriptions of its event type.
 * Matching itself (event type, WAITING status, {@code match} EL expressions)
 * is delegated to the Flow engine's {@code matchesEvent}, so stale
 * subscription rows are harmless.
 */
@ApplicationScoped
public class WorkflowEventDispatcher {

    private static final Logger LOG = Logger.getLogger(WorkflowEventDispatcher.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    private WorkflowEngine workflowEngine;

    @PostConstruct
    void init() {
        // matchesEvent never executes nodes; the stub provider mirrors
        // WorkflowExecutionService.init().
        NodeExecutorProvider provider = actionType -> new NodeExecutor() {
            @Override
            public String actionType() {
                return actionType;
            }

            @Override
            public NodeResult execute(NodeExecutionContext context) {
                return new NodeResult(NodeResultStatus.PENDING, Map.of());
            }
        };
        this.workflowEngine = new WorkflowEngine(provider, List.of(), null);
    }

    /**
     * Offers an event to all candidate receive-event subscriptions, resuming
     * every run whose parked node matches. Failures are contained per
     * subscription; this method itself only throws if the event cannot be
     * loaded.
     *
     * @param eventId the id of the (filter-allowed) event to dispatch
     */
    @Transactional
    public void dispatchEvent(long eventId) {
        EventEntity event = EventEntity.findById(eventId);
        if (event == null) {
            LOG.warnf("Event %d not found for workflow dispatch", eventId);
            return;
        }

        List<WorkflowEventSubscriptionEntity> candidates =
                WorkflowEventSubscriptionEntity.list("eventType", event.eventType);
        if (candidates.isEmpty()) {
            return;
        }

        ProjectEntity project = findProjectForEvent(event);
        if (project != null) {
            Long projectId = project.id;
            candidates = candidates.stream()
                    .filter(sub -> projectId.equals(sub.projectId))
                    .toList();
        }
        if (candidates.isEmpty()) {
            return;
        }

        Map<String, Object> eventMap = WorkflowEventMapper.toEventMap(event, objectMapper);

        for (WorkflowEventSubscriptionEntity sub : candidates) {
            try {
                offerToSubscription(sub, eventMap);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to offer event %d to workflow run %d (node %s)",
                        eventId, sub.runId, sub.nodeId);
            }
        }
    }

    /**
     * Checks a single subscription against the event map and, on match,
     * deletes the subscription and resumes the run. Subscriptions whose run
     * is missing or terminal are cleaned up opportunistically.
     */
    private void offerToSubscription(WorkflowEventSubscriptionEntity sub,
            Map<String, Object> eventMap) {
        WorkflowRunEntity run = WorkflowRunEntity.findById(sub.runId);
        if (run == null || run.completedOn != null) {
            sub.delete();
            return;
        }

        Workflow workflow = loadWorkflowContent(run.definitionId, run.definitionVersion);
        WorkflowInstance instance = deserializeInstance(run.instanceState);
        if (workflow == null || instance == null) {
            return;
        }

        if (!workflowEngine.matchesEvent(workflow, instance, sub.nodeId, eventMap)) {
            return;
        }

        long runId = sub.runId;
        String nodeId = sub.nodeId;
        sub.delete();
        workflowExecutionService.onEventReceived(runId, nodeId, eventMap);
        LOG.infof("Event resumed workflow run %d at receive-event node %s", runId, nodeId);
    }

    /** Same correlation rule as PipelineOrchestrator.findProjectForEvent. */
    private ProjectEntity findProjectForEvent(EventEntity event) {
        if (event.issueRef != null) {
            return ProjectEntity.find("ref", event.issueRef).firstResult();
        }
        if (event.projectId != null) {
            return ProjectEntity.findById(event.projectId);
        }
        return null;
    }

    private Workflow loadWorkflowContent(long definitionId, int definitionVersion) {
        WorkflowDefinitionVersionEntity version = WorkflowDefinitionVersionEntity
                .find("definitionId = ?1 and version = ?2", definitionId, definitionVersion)
                .firstResult();
        if (version == null) {
            LOG.warnf("Workflow version %d/%d not found for event dispatch",
                    definitionId, definitionVersion);
            return null;
        }
        try {
            return objectMapper.readValue(version.content, Workflow.class);
        } catch (Exception e) {
            LOG.warnf(e, "Invalid workflow JSON for definition %d v%d",
                    definitionId, definitionVersion);
            return null;
        }
    }

    private WorkflowInstance deserializeInstance(String json) {
        try {
            return objectMapper.readValue(json, WorkflowInstance.class);
        } catch (Exception e) {
            LOG.warnf(e, "Invalid workflow instance state during event dispatch");
            return null;
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -q -pl app test -Dtest=WorkflowEventDispatcherTest -DfailIfNoTests=false`

Expected: PASS (4 tests). If `falseMatchExpressionLeavesRunParked` fails on the EL syntax, see
the note in Step 1 and adjust the expression in `MATCH_EXPRESSION_CONTENT` (not the dispatcher).

- [ ] **Step 5: Hook into PipelineOrchestrator**

In `app/src/main/java/io/apitomy/axiom/app/PipelineOrchestrator.java`:

5a. Inject the dispatcher (next to the other `@Inject` fields):

```java
    @Inject
    WorkflowEventDispatcher workflowEventDispatcher;
```

5b. In `processEvent`, immediately after the transaction block that sets
`managed.filterStatus = "allowed"` (~line 192) and before trace creation, add:

```java
        // Offer the event to any parked receive-event workflow branches.
        // Orthogonal to Manager evaluation: failures are logged, never block
        // the pipeline, and a matched event still flows through the Manager.
        try {
            workflowEventDispatcher.dispatchEvent(eventId);
        } catch (Exception e) {
            LOG.errorf(e, "Workflow event dispatch failed for event %d", eventId);
        }
```

- [ ] **Step 6: Run the full app test suite**

Run: `mvn -pl app test > /tmp/opencode/receive-event-tests.log 2>&1; echo EXIT=$?; grep -E "Tests run:.*(Failures: [1-9]|Errors: [1-9])|BUILD" /tmp/opencode/receive-event-tests.log`

Expected: `EXIT=0`, `BUILD SUCCESS`, no failing test lines. (Known flake:
`OpenCodeAssistantProtocolHarnessTest` can fail under load with a
`ConcurrentModificationException` unrelated to this work — rerun if it's the only failure.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/apitomy/axiom/app/WorkflowEventDispatcher.java \
        app/src/main/java/io/apitomy/axiom/app/PipelineOrchestrator.java \
        app/src/test/java/io/apitomy/axiom/app/WorkflowEventDispatcherTest.java
git commit -m "feat(workflow): dispatch pipeline events to parked receive-event nodes"
```

---

## Self-Review Notes

- Spec coverage: data model (Task 1), execution-service park/resume/cancel (Task 2), event map
  (Task 3), dispatcher + hybrid scoping + pipeline hook + error containment (Task 4). Testing
  section of the spec maps 1:1 onto the tests in Tasks 2-4. Out-of-scope items need no tasks.
- Interfaces: `onEventReceived(long, String, Map<String,Object>)` (Task 2) matches the
  dispatcher's call (Task 4); `toEventMap(EventEntity, ObjectMapper)` (Task 3) matches its use
  in Task 4; entity field names consistent across Tasks 1, 2, and 4.
- Open verification points flagged inline: exact `V59` number, `EventEntity` required columns in
  the test helper, and the engine's EL root for `match` expressions.
