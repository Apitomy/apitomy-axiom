package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
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
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies starting a workflow run manually from a stream event, filtering runs by triggering
 * event, and the workflow suggestions of an event.
 */
@QuarkusTest
class EventManualRunTest {

    @InjectMock
    AgentPool agentPool;

    @Inject
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        Mockito.when(agentPool.tryLease(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
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
    void manualTriggerStartsRunOnEventSubject() throws Exception {
        long definitionId = createDefinition("Manual Event WF", "manual-await-" + UUID.randomUUID());
        UUID eventId = UUID.randomUUID();
        createEvent(eventId, "issue.created", eventRef(eventId));

        int runId = given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d, \"labels\": [\"trigger:x\", \"team:a\"]}"
                        .formatted(definitionId))
            .when()
                .post("/api/v1/stream/events/" + eventId + "/runs")
            .then()
                .statusCode(200)
                .body("triggerEventId", equalTo(eventId.toString()))
                .extract().path("runId");

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunEntity.<WorkflowRunEntity>findById((long) runId));
        assertEquals(eventId, run.triggerEventId);
        assertNull(run.triggerLedgerId);
        SubjectEntity subject = QuarkusTransaction.requiringNew().call(() ->
                SubjectEntity.<SubjectEntity>findById(run.subjectId));
        assertEquals(eventRef(eventId), subject.ref);
        assertEquals(java.util.List.of("team:a", "trigger:manual"), run.labels);
        JsonNode context = objectMapper.readTree(run.instanceState).path("context");
        assertEquals(eventId.toString(), context.path("event").path("id").asText());
        assertEquals(0L, QuarkusTransaction.requiringNew().call(() ->
                EventProcessingLedgerEntity.count("eventId", eventId)));

        given()
            .when()
                .get("/api/v1/workflow/runs?triggerEventId=" + eventId)
            .then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].runId", equalTo(runId));

        // A second run of the same workflow while the first is active conflicts
        given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
            .when()
                .post("/api/v1/stream/events/" + eventId + "/runs")
            .then()
                .statusCode(409);
    }

    @Test
    void rerunOfAnEventTriggeredRunStartsFromTheSameEvent() throws Exception {
        long definitionId = createDefinition("Rerun Event WF", "rerun-await-" + UUID.randomUUID());
        UUID eventId = UUID.randomUUID();
        createEvent(eventId, "issue.created", eventRef(eventId));
        int firstRunId = given().contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
            .when().post("/api/v1/stream/events/" + eventId + "/runs")
            .then().statusCode(200).extract().path("runId");
        // Mark the first run failed, as if it had failed.
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunEntity run = WorkflowRunEntity.findById((long) firstRunId);
            run.status = "failed";
        });

        int rerunId = given().when().post("/api/v1/workflow/runs/" + firstRunId + "/rerun")
            .then().statusCode(200)
                .body("triggerEventId", equalTo(eventId.toString()))
                .body("labels", org.hamcrest.Matchers.contains("trigger:manual", "rerunOf:" + firstRunId))
                .extract().path("runId");
        WorkflowRunEntity rerun = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunEntity.<WorkflowRunEntity>findById((long) rerunId));
        JsonNode context = objectMapper.readTree(rerun.instanceState).path("context");
        assertEquals(eventId.toString(), context.path("event").path("id").asText());
    }

    @Test
    void manualTriggerUnknownEventIs404() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": 1}")
            .when()
                .post("/api/v1/stream/events/" + UUID.randomUUID() + "/runs")
            .then()
                .statusCode(404);
    }

    @Test
    void suggestionsComeFromCompletedLedgerEntries() {
        long definitionId = createDefinition("Suggest WF", "suggest-await-" + UUID.randomUUID());
        String routing = "[{\"type\":\"create-workflow\",\"workflowDefinitionId\":" + definitionId
                + "},{\"type\":\"create-workflow\",\"workflowDefinitionId\":" + definitionId
                + "},{\"type\":\"create-workflow\",\"workflowDefinitionId\":999999999}]";
        long subscriptionId = createSubscription("suggest-sub", routing);
        long skippedSubscriptionId = createSubscription("skipped-sub", routing);
        UUID eventId = UUID.randomUUID();
        createEvent(eventId, "issue.created", eventRef(eventId));
        createLedger(eventId, subscriptionId, "completed");
        createLedger(eventId, skippedSubscriptionId, "skipped");

        given()
            .when()
                .get("/api/v1/stream/events/" + eventId + "/workflow-suggestions")
            .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].workflowDefinitionId", equalTo((int) definitionId))
                .body("[0].subscriptionId", equalTo((int) subscriptionId))
                .body("[0].subscriptionName", equalTo("suggest-sub"));
    }

    /**
     * Pending and failed ledger entries are matches and yield suggestions; skipped and filter-error
     * entries are not matches and yield none.
     */
    @Test
    void suggestionsExcludeSkippedAndFilterErrorEntries() {
        long pendingDef = createDefinition("Pending WF", "suggest-await-" + UUID.randomUUID());
        long failedDef = createDefinition("Failed WF", "suggest-await-" + UUID.randomUUID());
        long skippedDef = createDefinition("Skipped WF", "suggest-await-" + UUID.randomUUID());
        long errorDef = createDefinition("Error WF", "suggest-await-" + UUID.randomUUID());
        long pendingSub = createSubscription("pending-sub", routingTo(pendingDef));
        long failedSub = createSubscription("failed-sub", routingTo(failedDef));
        long skippedSub = createSubscription("skipped-sub", routingTo(skippedDef));
        long errorSub = createSubscription("error-sub", routingTo(errorDef));
        UUID eventId = UUID.randomUUID();
        createEvent(eventId, "issue.created", eventRef(eventId));
        createLedger(eventId, pendingSub, EventProcessingLedgerEntity.PENDING);
        createLedger(eventId, failedSub, EventProcessingLedgerEntity.FAILED);
        createLedger(eventId, skippedSub, EventProcessingLedgerEntity.SKIPPED);
        createLedger(eventId, errorSub, EventProcessingLedgerEntity.FILTER_ERROR);

        given()
            .when()
                .get("/api/v1/stream/events/" + eventId + "/workflow-suggestions")
            .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("subscriptionId", containsInAnyOrder((int) pendingSub, (int) failedSub));
    }

    private static String routingTo(long definitionId) {
        return "[{\"type\":\"create-workflow\",\"workflowDefinitionId\":" + definitionId + "}]";
    }

    private long createSubscription(String name, String routing) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EventSubscriptionEntity sub = new EventSubscriptionEntity();
            sub.name = name;
            sub.enabled = false;
            sub.routing = routing;
            sub.createdOn = Instant.now();
            sub.modifiedOn = Instant.now();
            sub.persist();
            return sub.id;
        });
    }

    private void createLedger(UUID eventId, long subscriptionId, String status) {
        QuarkusTransaction.requiringNew().run(() -> {
            EventProcessingLedgerEntity entry = new EventProcessingLedgerEntity();
            entry.eventId = eventId;
            entry.subscriptionId = subscriptionId;
            entry.status = status;
            entry.createdOn = Instant.now();
            entry.processedOn = Instant.now();
            entry.persist();
        });
    }

    private static String receiveEventContent(String awaitedType) {
        return """
            {
                "id": "links-wf",
                "name": "Links WF",
                "nodes": [
                    {"id": "s1", "type": "start", "name": "Start",
                     "config": {"inputs": [{"name": "event", "type": "object", "required": false}]},
                     "position": {"x": 100, "y": 100}},
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

    private static String eventRef(UUID eventId) {
        return "https://github.com/test-org/links/issues/" + eventId;
    }

    private UUID createEvent(UUID eventId, String eventType, String ref) {
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = eventId;
            event.sourceEventId = "links-" + eventId;
            event.source = "github";
            event.eventSourceId = "conn-links";
            event.type = eventType;
            event.ref = ref;
            event.timestamp = Instant.now();
            event.actor = "{\"login\":\"testuser\"}";
            event.payload = "{\"issue\":{\"title\":\"Test\",\"state\":\"open\",\"number\":\"1\"}}";
            event.createdOn = Instant.now();
            event.persist();
        });
        return eventId;
    }

}
