package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunResumeEntity;
import io.apitomy.axiom.core.entities.WorkflowWaitEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the links between stream events, their routing outcomes and the workflow runs they
 * start (#420) or resume (#421).
 */
@QuarkusTest
class EventWorkflowLinksTest {

    @InjectMock
    AgentPool agentPool;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    EventStreamOrchestrator orchestrator;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkflowWaitScheduler waitScheduler;

    @Inject
    ProjectDeletionService projectDeletionService;

    @BeforeEach
    void setUp() {
        Mockito.when(agentPool.tryLease(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            RoutingOutcomeItemEntity.deleteAll();
            RoutingOutcomeEntity.deleteAll();
            EventProcessingLedgerEntity.deleteAll();
            StreamEventEntity.deleteAll();
            EventSubscriptionEntity.deleteAll();
        });
    }

    @Test
    void createWorkflowLinksRunTraceAndOutcomeToEvent() throws Exception {
        String awaitedType = "links-await-" + UUID.randomUUID();
        long definitionId = createDefinition("Links Create WF", awaitedType);
        UUID eventId = createEventAndSubscription("issue.created",
                "[{\"type\":\"create-workflow\",\"workflowDefinitionId\":" + definitionId + "}]");

        orchestrator.processNewEvents();

        RoutingOutcomeEntity outcome = singleOutcome("create-workflow");
        assertEquals("completed", outcome.status);
        RoutingOutcomeItemEntity item = items(outcome.id).get(0);
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunEntity.<WorkflowRunEntity>findById(item.workflowRunId));
        assertNotNull(run);
        Long ledgerId = ledgerId(eventId);

        // Run → event
        assertEquals(eventId, run.triggerEventId);
        assertEquals(ledgerId, run.triggerLedgerId);
        // Trace → event
        assertNotNull(run.traceId);
        TraceEntity trace = QuarkusTransaction.requiringNew().call(() ->
                TraceEntity.<TraceEntity>findById(run.traceId));
        assertEquals(eventId, trace.eventId);
        // Outcome → trace
        assertEquals(run.traceId, outcome.traceId);
        // The stream event ID is in the run's context
        JsonNode context = objectMapper.readTree(run.instanceState).path("context");
        assertEquals(eventId.toString(), context.path("event").path("id").asText());

        // API exposes the trigger fields and the item's trace
        given()
            .when()
                .get("/api/v1/workflow/runs/" + run.id)
            .then()
                .statusCode(200)
                .body("triggerEventId", equalTo(eventId.toString()))
                .body("triggerLedgerId", equalTo(ledgerId.intValue()));
        given()
            .when()
                .get("/api/v1/workflow/runs?projectId=" + run.projectId)
            .then()
                .statusCode(200)
                .body("items[0].triggerEventId", equalTo(eventId.toString()))
                .body("items[0].triggerLedgerId", equalTo(ledgerId.intValue()));
        given()
            .when()
                .get("/api/v1/stream/events/" + eventId + "/processing")
            .then()
                .statusCode(200)
                .body("items[0].outcomes[0].traceId", equalTo(run.traceId.toString()))
                .body("items[0].outcomes[0].items[0].workflowRunId", equalTo(run.id.intValue()))
                .body("items[0].outcomes[0].items[0].traceId", equalTo(run.traceId.toString()));
    }

    @Test
    void dispatchThatResumesRunRecordsRunNodeAndResumingEvent() {
        String awaitedType = "links-await-" + UUID.randomUUID();
        long definitionId = createDefinition("Links Resume WF", awaitedType);
        long projectId = createProject("Links Resume Project");
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(projectId, definitionId));
        assertEquals("waiting", run.status);
        Long parkedNodeId = QuarkusTransaction.requiringNew().call(() ->
                TraceNodeEntity.<TraceNodeEntity>find(
                        "traceId = ?1 and entityType = 'workflow-event-subscription'",
                        run.traceId).firstResult().id);

        UUID eventId = createEventAndSubscription(awaitedType,
                "[{\"type\":\"workflow-dispatch\"}]");
        orchestrator.processNewEvents();

        RoutingOutcomeEntity outcome = singleOutcome("workflow-dispatch");
        assertEquals("completed", outcome.status);
        List<RoutingOutcomeItemEntity> items = items(outcome.id);
        assertEquals(1, items.size());
        RoutingOutcomeItemEntity item = items.get(0);
        assertEquals(RoutingOutcomeItemEntity.TYPE_WORKFLOW_RESUMED, item.itemType);
        assertEquals("completed", item.status);
        assertEquals(run.id, item.workflowRunId);
        assertEquals(parkedNodeId, item.traceNodeId);
        assertEquals(projectId, item.projectId);
        assertTrue(item.summary.contains("r1"), item.summary);

        // The resuming event is recorded against the run and on the receive-event trace node
        Long ledgerId = ledgerId(eventId);
        List<WorkflowRunResumeEntity> resumes = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunResumeEntity.<WorkflowRunResumeEntity>list("runId", run.id));
        assertEquals(1, resumes.size());
        assertEquals(eventId, resumes.get(0).eventId);
        assertEquals(ledgerId, resumes.get(0).ledgerId);
        assertEquals("r1", resumes.get(0).nodeId);
        assertEquals(parkedNodeId, resumes.get(0).traceNodeId);
        TraceNodeEntity node = QuarkusTransaction.requiringNew().call(() ->
                TraceNodeEntity.<TraceNodeEntity>findById(parkedNodeId));
        assertEquals("completed", node.status);
        assertTrue(node.summary.contains(eventId.toString()), node.summary);

        given()
            .when()
                .get("/api/v1/workflow/runs/" + run.id)
            .then()
                .statusCode(200)
                .body("status", equalTo("completed"))
                .body("resumedBy", hasSize(1))
                .body("resumedBy[0].eventId", equalTo(eventId.toString()))
                .body("resumedBy[0].nodeId", equalTo("r1"))
                .body("resumedBy[0].nodeName", equalTo("Await Event"))
                .body("resumedBy[0].traceNodeId", equalTo(parkedNodeId.intValue()));
        given()
            .when()
                .get("/api/v1/stream/events/" + eventId + "/processing")
            .then()
                .statusCode(200)
                .body("items[0].outcomes[0].items[0].type", equalTo("workflow-resumed"))
                .body("items[0].outcomes[0].items[0].workflowRunId", equalTo(run.id.intValue()))
                .body("items[0].outcomes[0].items[0].traceId", equalTo(run.traceId.toString()));
    }

    @Test
    void dispatchThatMatchesNothingIsRecordedExplicitly() {
        createEventAndSubscription("links-nobody-waits-" + UUID.randomUUID(),
                "[{\"type\":\"workflow-dispatch\"}]");

        orchestrator.processNewEvents();

        RoutingOutcomeEntity outcome = singleOutcome("workflow-dispatch");
        assertEquals("completed", outcome.status);
        assertTrue(outcome.summary.contains("No waiting workflow run"), outcome.summary);
        List<RoutingOutcomeItemEntity> items = items(outcome.id);
        assertEquals(1, items.size());
        assertEquals(RoutingOutcomeItemEntity.TYPE_NO_MATCH, items.get(0).itemType);
        assertNull(items.get(0).workflowRunId);
    }

    @Test
    void manualTriggerLeavesTriggerFieldsNull() {
        long definitionId = createDefinition("Links Manual WF",
                "links-await-" + UUID.randomUUID());
        long projectId = createProject("Links Manual Project");

        int runId = given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
            .when()
                .post("/api/v1/projects/" + projectId + "/workflow")
            .then()
                .statusCode(200)
                .body("status", equalTo("waiting"))
                .body("triggerEventId", nullValue())
                .body("triggerLedgerId", nullValue())
                .extract().path("runId");

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunEntity.<WorkflowRunEntity>findById((long) runId));
        assertNull(run.triggerEventId);
        assertNull(run.triggerLedgerId);
        TraceEntity trace = QuarkusTransaction.requiringNew().call(() ->
                TraceEntity.<TraceEntity>findById(run.traceId));
        assertNull(trace.eventId);
    }

    @Test
    void resumingOneParallelBranchCompletesAndRecordsThatBranchsNode() {
        String typeA = "links-par-a-" + UUID.randomUUID();
        String typeB = "links-par-b-" + UUID.randomUUID();
        long definitionId = createDefinitionWithContent("Links Parallel WF",
                parallelContent(
                        "{\"id\": \"ra\", \"type\": \"receive-event\", \"name\": \"Await A\","
                                + " \"config\": {\"eventType\": \"" + typeA + "\"},"
                                + " \"position\": {\"x\": 50, \"y\": 200}}",
                        "{\"id\": \"rb\", \"type\": \"receive-event\", \"name\": \"Await B\","
                                + " \"config\": {\"eventType\": \"" + typeB + "\"},"
                                + " \"position\": {\"x\": 150, \"y\": 200}}",
                        "ra", "rb"));
        long projectId = createProject("Links Parallel Project");
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(projectId, definitionId));
        Long nodeA = parkedNodeId(run.traceId, "workflow-event-subscription",
                subscriptionId(run.id, "ra"));
        Long nodeB = parkedNodeId(run.traceId, "workflow-event-subscription",
                subscriptionId(run.id, "rb"));

        UUID eventId = createEventAndSubscription(typeB, "[{\"type\":\"workflow-dispatch\"}]");
        orchestrator.processNewEvents();

        RoutingOutcomeItemEntity item = items(singleOutcome("workflow-dispatch").id).get(0);
        assertEquals(RoutingOutcomeItemEntity.TYPE_WORKFLOW_RESUMED, item.itemType);
        assertEquals(nodeB, item.traceNodeId);
        WorkflowRunResumeEntity resume = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunResumeEntity.<WorkflowRunResumeEntity>find("runId", run.id)
                        .firstResult());
        assertEquals("rb", resume.nodeId);
        assertEquals(nodeB, resume.traceNodeId);
        TraceNodeEntity a = traceNode(nodeA);
        TraceNodeEntity b = traceNode(nodeB);
        assertEquals("in-progress", a.status, "The other branch's node must stay parked");
        assertTrue(!a.summary.contains(eventId.toString()), a.summary);
        assertEquals("completed", b.status);
        assertTrue(b.summary.contains(eventId.toString()), b.summary);
    }

    @Test
    void elapsingOneParallelWaitCompletesThatWaitsNode() {
        long definitionId = createDefinitionWithContent("Links Parallel Wait WF",
                parallelContent(
                        "{\"id\": \"wa\", \"type\": \"wait\", \"name\": \"Wait A\","
                                + " \"config\": {\"duration\": \"PT5M\"},"
                                + " \"position\": {\"x\": 50, \"y\": 200}}",
                        "{\"id\": \"wb\", \"type\": \"wait\", \"name\": \"Wait B\","
                                + " \"config\": {\"duration\": \"PT10M\"},"
                                + " \"position\": {\"x\": 150, \"y\": 200}}",
                        "wa", "wb"));
        long projectId = createProject("Links Parallel Wait Project");
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(projectId, definitionId));
        long waitA = waitId(run.id, "wa");
        long waitB = waitId(run.id, "wb");
        Long nodeA = parkedNodeId(run.traceId, "workflow-wait", waitA);
        Long nodeB = parkedNodeId(run.traceId, "workflow-wait", waitB);

        QuarkusTransaction.requiringNew().run(() -> waitScheduler.resumeWait(waitB));

        assertEquals("in-progress", traceNode(nodeA).status,
                "The other branch's wait node must stay parked");
        assertEquals("completed", traceNode(nodeB).status);
    }

    @Test
    void deletingProjectDeletesItsResumeRows() {
        long projectId = createProject("Links Delete Project");
        long runId = createRunWithResume(projectId,
                createDefinition("Links Delete WF", "links-await-" + UUID.randomUUID()));

        QuarkusTransaction.requiringNew().run(() -> projectDeletionService.deleteProject(
                ProjectEntity.<ProjectEntity>findById(projectId)));

        assertEquals(0L, QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunResumeEntity.count("runId", runId)));
    }

    @Test
    void deletingDefinitionDeletesItsRunsResumeRows() {
        long definitionId = createDefinition("Links Delete Def WF",
                "links-await-" + UUID.randomUUID());
        long runId = createRunWithResume(createProject("Links Delete Def Project"), definitionId);

        given()
            .when()
                .delete("/api/v1/workflow/definitions/" + definitionId)
            .then()
                .statusCode(204);

        assertEquals(0L, QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunResumeEntity.count("runId", runId)));
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private long createRunWithResume(long projectId, long definitionId) {
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(projectId, definitionId));
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunResumeEntity resume = new WorkflowRunResumeEntity();
            resume.runId = run.id;
            resume.nodeId = "r1";
            resume.eventId = UUID.randomUUID();
            resume.resumedOn = Instant.now();
            resume.persist();
        });
        return run.id;
    }

    private static String parallelContent(String nodeA, String nodeB, String idA, String idB) {
        return """
            {
                "id": "links-par-wf",
                "name": "Links Parallel WF",
                "nodes": [
                    {"id": "s1", "type": "start", "name": "Start",
                     "config": {}, "position": {"x": 100, "y": 100}},
                    %s,
                    %s,
                    {"id": "e1", "type": "end", "name": "End",
                     "config": {}, "position": {"x": 100, "y": 300}}
                ],
                "edges": [
                    {"id": "edge1", "source": "s1", "target": "%s",
                     "priority": 0, "isDefault": false},
                    {"id": "edge2", "source": "s1", "target": "%s",
                     "priority": 1, "isDefault": false},
                    {"id": "edge3", "source": "%s", "target": "e1",
                     "priority": 0, "isDefault": true},
                    {"id": "edge4", "source": "%s", "target": "e1",
                     "priority": 0, "isDefault": true}
                ]
            }
            """.formatted(nodeA, nodeB, idA, idB, idA, idB);
    }

    private long subscriptionId(long runId, String nodeId) {
        return QuarkusTransaction.requiringNew().call(() ->
                WorkflowEventSubscriptionEntity.<WorkflowEventSubscriptionEntity>find(
                        "runId = ?1 and nodeId = ?2", runId, nodeId).firstResult().id);
    }

    private long waitId(long runId, String nodeId) {
        return QuarkusTransaction.requiringNew().call(() ->
                WorkflowWaitEntity.<WorkflowWaitEntity>find(
                        "runId = ?1 and nodeId = ?2", runId, nodeId).firstResult().id);
    }

    private Long parkedNodeId(UUID traceId, String entityType, long entityId) {
        TraceNodeEntity node = QuarkusTransaction.requiringNew().call(() ->
                TraceNodeEntity.<TraceNodeEntity>find(
                        "traceId = ?1 and entityType = ?2 and entityId = ?3",
                        traceId, entityType, String.valueOf(entityId)).firstResult());
        assertNotNull(node, entityType + " node " + entityId);
        return node.id;
    }

    private TraceNodeEntity traceNode(Long id) {
        return QuarkusTransaction.requiringNew().call(() ->
                TraceNodeEntity.<TraceNodeEntity>findById(id));
    }

    private static String receiveEventContent(String awaitedType) {
        return """
            {
                "id": "links-wf",
                "name": "Links WF",
                "nodes": [
                    {"id": "s1", "type": "start", "name": "Start",
                     "config": {}, "position": {"x": 100, "y": 100}},
                    {"id": "r1", "type": "receive-event", "name": "Await Event",
                     "config": {"eventType": "%s"},
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
            """.formatted(awaitedType);
    }

    private long createDefinition(String name, String awaitedType) {
        return createDefinitionWithContent(name, receiveEventContent(awaitedType));
    }

    private long createDefinitionWithContent(String name, String content) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = name + " " + UUID.randomUUID();
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
            return def.id;
        });
    }

    private long createProject(String name) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ProjectEntity project = new ProjectEntity();
            project.name = name;
            project.type = "other";
            project.status = "Created";
            project.ref = "test/links-" + UUID.randomUUID();
            project.createdOn = Instant.now();
            project.updatedOn = Instant.now();
            project.persist();
            return project.id;
        });
    }

    private UUID createEventAndSubscription(String eventType, String routing) {
        UUID eventId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = eventId;
            event.sourceEventId = "links-" + eventId;
            event.source = "github";
            event.connectionId = "conn-links";
            event.type = eventType;
            event.ref = "https://github.com/test-org/links/issues/" + eventId;
            event.timestamp = Instant.now();
            event.actor = "{\"login\":\"testuser\"}";
            event.payload = "{\"issue\":{\"title\":\"Test\",\"state\":\"open\",\"number\":\"1\"}}";
            event.createdOn = Instant.now();
            event.persist();

            EventSubscriptionEntity sub = new EventSubscriptionEntity();
            sub.name = "links-" + UUID.randomUUID();
            sub.enabled = true;
            sub.routing = routing;
            sub.createdOn = Instant.now();
            sub.modifiedOn = Instant.now();
            sub.persist();
        });
        return eventId;
    }

    private Long ledgerId(UUID eventId) {
        return QuarkusTransaction.requiringNew().call(() ->
                EventProcessingLedgerEntity.<EventProcessingLedgerEntity>find("eventId", eventId)
                        .firstResult().id);
    }

    private RoutingOutcomeEntity singleOutcome(String routingType) {
        List<RoutingOutcomeEntity> found = QuarkusTransaction.requiringNew().call(() ->
                RoutingOutcomeEntity.<RoutingOutcomeEntity>list(
                        "routingType = ?1 order by id", routingType));
        assertEquals(1, found.size(), "Expected exactly one " + routingType + " outcome");
        return found.get(0);
    }

    private List<RoutingOutcomeItemEntity> items(Long outcomeId) {
        return QuarkusTransaction.requiringNew().call(() ->
                RoutingOutcomeItemEntity.<RoutingOutcomeItemEntity>list(
                        "outcomeId = ?1 order by id", outcomeId));
    }
}
