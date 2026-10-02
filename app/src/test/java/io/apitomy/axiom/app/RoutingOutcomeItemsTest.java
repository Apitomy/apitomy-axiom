package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.manager.ManagerDecision;
import io.apitomy.axiom.manager.ManagerEvaluationResult;
import io.apitomy.axiom.manager.ManagerService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every result of event routing is recorded as a routing outcome item (#418).
 */
@QuarkusTest
class RoutingOutcomeItemsTest {

    @InjectMock
    AgentPool agentPool;

    @InjectMock
    ManagerService managerService;

    @InjectMock
    WorkflowExecutionService workflowExecutionService;

    @Inject
    EventStreamOrchestrator orchestrator;

    @Inject
    StreamEventCleanup streamEventCleanup;

    private final List<UUID> eventIds = new ArrayList<>();
    private final List<String> actionNames = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Mockito.when(agentPool.tryLease(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Optional.empty());
        Mockito.when(managerService.meetsConfidenceThreshold(ArgumentMatchers.any()))
                .thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            RoutingOutcomeItemEntity.deleteAll();
            RoutingOutcomeEntity.deleteAll();
            EventProcessingLedgerEntity.deleteAll();
            for (UUID id : eventIds) {
                TaskEntity.delete("eventId", id);
            }
            for (String name : actionNames) {
                ActionTypeEntity.delete("name", name);
            }
            StreamEventEntity.deleteAll();
            EventSubscriptionEntity.deleteAll();
        });
        eventIds.clear();
        actionNames.clear();
    }

    @Test
    void managerDecisionsProduceOneItemEach() {
        stubEvaluation(ManagerEvaluationResult.success(List.of(
                createTask("items-action-a"),
                createTask("items-action-b"),
                decision("ignore", "not relevant")), null));
        UUID eventId = createEventAndSubscription("[{\"type\":\"manager\"}]");

        orchestrator.processNewEvents();

        RoutingOutcomeEntity outcome = singleOutcome("manager");
        List<RoutingOutcomeItemEntity> items = items(outcome.id);
        assertEquals(3, items.size());
        List<TaskEntity> tasks = QuarkusTransaction.requiringNew().call(() ->
                TaskEntity.<TaskEntity>list("eventId = ?1 order by id", eventId));
        assertEquals(2, tasks.size());

        for (int i = 0; i < 2; i++) {
            RoutingOutcomeItemEntity item = items.get(i);
            TaskEntity task = tasks.get(i);
            assertEquals(RoutingOutcomeItemEntity.TYPE_TASK, item.itemType);
            assertEquals("completed", item.status);
            assertEquals(task.id, item.taskId);
            assertEquals(task.projectId, item.projectId);
            assertNotNull(item.traceNodeId);
            TraceNodeEntity node = QuarkusTransaction.requiringNew().call(() ->
                    TraceNodeEntity.<TraceNodeEntity>findById(item.traceNodeId));
            assertEquals("manager-decision", node.nodeType);
        }
        RoutingOutcomeItemEntity ignored = items.get(2);
        assertEquals(RoutingOutcomeItemEntity.TYPE_IGNORED, ignored.itemType);
        assertEquals("completed", ignored.status);
        assertNull(ignored.taskId);
        assertTrue(ignored.summary.contains("not relevant"), ignored.summary);

        // Backward compatibility: the outcome still holds the first project and task
        assertEquals(tasks.get(0).id, outcome.taskId);
        assertEquals(tasks.get(0).projectId, outcome.projectId);
    }

    @Test
    void failedDecisionProducesFailedItemWithError() {
        stubEvaluation(ManagerEvaluationResult.success(List.of(
                decision("bogus_decision", "weird")), null));
        createEventAndSubscription("[{\"type\":\"manager\"}]");

        orchestrator.processNewEvents();

        RoutingOutcomeItemEntity item = items(singleOutcome("manager").id).get(0);
        assertEquals("failed", item.status);
        assertEquals(RoutingOutcomeItemEntity.TYPE_DECISION, item.itemType);
        assertTrue(item.errorMessage.contains("Unknown Manager decision type"), item.errorMessage);
        assertNotNull(item.traceNodeId);
    }

    @Test
    void invokeActionProducesTaskItem() {
        Long actionTypeId = createActionType();
        UUID eventId = createEventAndSubscription(
                "[{\"type\":\"invoke-action\",\"actionTypeId\":" + actionTypeId + "}]");

        orchestrator.processNewEvents();

        List<RoutingOutcomeItemEntity> items = items(singleOutcome("invoke-action").id);
        assertEquals(1, items.size());
        TaskEntity task = QuarkusTransaction.requiringNew().call(() ->
                TaskEntity.<TaskEntity>find("eventId", eventId).firstResult());
        assertEquals(RoutingOutcomeItemEntity.TYPE_TASK, items.get(0).itemType);
        assertEquals("completed", items.get(0).status);
        assertEquals(task.id, items.get(0).taskId);
        assertEquals(task.projectId, items.get(0).projectId);
    }

    @Test
    void createWorkflowProducesWorkflowRunItem() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.id = 987654L;
        Mockito.when(workflowExecutionService.triggerWorkflow(ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.any())).thenReturn(run);
        createEventAndSubscription("[{\"type\":\"create-workflow\",\"workflowDefinitionId\":42}]");

        orchestrator.processNewEvents();

        RoutingOutcomeEntity outcome = singleOutcome("create-workflow");
        List<RoutingOutcomeItemEntity> items = items(outcome.id);
        assertEquals(1, items.size());
        assertEquals(RoutingOutcomeItemEntity.TYPE_WORKFLOW_RUN, items.get(0).itemType);
        assertEquals(987654L, items.get(0).workflowRunId);
        assertEquals(outcome.projectId, items.get(0).projectId);
        assertNotNull(items.get(0).projectId);
    }

    @Test
    void processingApiReturnsItems() {
        stubEvaluation(ManagerEvaluationResult.success(List.of(
                createTask("items-action-api"),
                decision("escalate", "needs a human")), null));
        UUID eventId = createEventAndSubscription("[{\"type\":\"manager\"}]");

        orchestrator.processNewEvents();

        TaskEntity task = QuarkusTransaction.requiringNew().call(() ->
                TaskEntity.<TaskEntity>find("eventId", eventId).firstResult());
        given()
            .when()
                .get("/api/v1/stream/events/" + eventId + "/processing")
            .then()
                .statusCode(200)
                .body("items[0].outcomes[0].items", hasSize(2))
                .body("items[0].outcomes[0].items.type", hasItems("task", "escalated"))
                .body("items[0].outcomes[0].items[0].taskId", is(task.id.intValue()))
                .body("items[0].outcomes[0].items[0].projectId", is(task.projectId.intValue()))
                .body("items[0].outcomes[0].items[0].status", is("completed"));
    }

    @Test
    void eventCleanupCascadesToItems() {
        Integer[] original = new Integer[1];
        Long[] outcomeId = new Long[1];
        UUID eventId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                    .firstResult();
            if (config == null) {
                config = new RetentionConfigEntity();
                config.closedProjectRetentionDays = 30;
                config.eventRetentionDays = 30;
                config.traceRetentionDays = 30;
                config.persist();
            }
            original[0] = config.eventRetentionDays;
            config.eventRetentionDays = 1;

            StreamEventEntity event = newEvent(eventId);
            event.createdOn = Instant.now().minus(5, ChronoUnit.DAYS);
            event.persist();
            EventSubscriptionEntity sub = newSubscription("[]");
            sub.enabled = false;
            sub.persist();

            EventProcessingLedgerEntity ledger = new EventProcessingLedgerEntity();
            ledger.eventId = eventId;
            ledger.subscriptionId = sub.id;
            ledger.status = "completed";
            ledger.createdOn = Instant.now();
            ledger.persist();

            RoutingOutcomeEntity outcome = new RoutingOutcomeEntity();
            outcome.ledgerId = ledger.id;
            outcome.routingType = "manager";
            outcome.status = "completed";
            outcome.createdOn = Instant.now();
            outcome.persist();
            outcomeId[0] = outcome.id;

            RoutingOutcomeItemEntity item = new RoutingOutcomeItemEntity();
            item.outcomeId = outcome.id;
            item.itemType = RoutingOutcomeItemEntity.TYPE_IGNORED;
            item.status = "completed";
            item.createdOn = Instant.now();
            item.persist();
        });
        try {
            QuarkusTransaction.requiringNew().run(() -> streamEventCleanup.doCleanup());

            assertNull(QuarkusTransaction.requiringNew().call(() ->
                    StreamEventEntity.findById(eventId)));
            assertNull(QuarkusTransaction.requiringNew().call(() ->
                    RoutingOutcomeEntity.findById(outcomeId[0])), "Outcome must be deleted");
            assertEquals(0, items(outcomeId[0]).size());
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                RetentionConfigEntity config = RetentionConfigEntity
                        .<RetentionConfigEntity>findAll().firstResult();
                config.eventRetentionDays = original[0];
            });
        }
    }

    @Test
    void itemsOfAnAttemptStayWithThatAttemptsOutcome() {
        // Attempt 1: the Manager fails. Attempt 2 (retry): it succeeds with two decisions.
        stubEvaluation(ManagerEvaluationResult.failure("temporarily broken", null));
        createEventAndSubscription("[{\"type\":\"manager\"}]");
        orchestrator.processNewEvents();
        stubEvaluation(ManagerEvaluationResult.success(List.of(
                decision("ignore", "retry ignore"),
                decision("escalate", "retry escalate")), null));
        orchestrator.processNewEvents();

        // A tick runs the first pass and then the retry pass, so the first tick records two
        // failed attempts; the second tick's retry succeeds.
        List<RoutingOutcomeEntity> outcomes = outcomes("manager");
        assertEquals(3, outcomes.size());
        RoutingOutcomeEntity succeeded = outcomes.get(2);
        assertEquals("completed", succeeded.status);
        assertEquals(2, items(succeeded.id).size(),
                "Only the successful attempt's results are on its outcome");
        for (RoutingOutcomeEntity failed : outcomes.subList(0, 2)) {
            assertEquals("failed", failed.status);
            assertEquals(0, items(failed.id).size(), "A failed attempt produced no results");
        }
    }

    @Test
    void retriesNeitherDuplicateNorMoveItemsOfCompletedRules() {
        // The manager rule completes (with items), the invoke-action rule keeps failing.
        stubEvaluation(ManagerEvaluationResult.success(List.of(
                decision("ignore", "first"),
                decision("escalate", "second")), null));
        UUID eventId = createEventAndSubscription(
                "[{\"type\":\"manager\"},{\"type\":\"invoke-action\",\"actionTypeId\":-1}]");

        for (int i = 0; i < 5; i++) {
            orchestrator.processNewEvents();
        }

        assertEquals(1, outcomes("manager").size(), "Completed rule is not replayed");
        RoutingOutcomeEntity manager = outcomes("manager").get(0);
        assertEquals(2, items(manager.id).size());
        List<RoutingOutcomeEntity> failed = outcomes("invoke-action");
        assertEquals(3, failed.size(), "Items do not change the attempt count");
        failed.forEach(o -> {
            assertEquals("failed", o.status);
            assertEquals(0, items(o.id).size());
        });
        EventProcessingLedgerEntity entry = QuarkusTransaction.requiringNew().call(() ->
                EventProcessingLedgerEntity.<EventProcessingLedgerEntity>find("eventId", eventId)
                        .firstResult());
        assertTrue(entry.errorMessage.contains("giving up after 3 attempts"), entry.errorMessage);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private void stubEvaluation(ManagerEvaluationResult result) {
        Mockito.when(managerService.evaluateStreamEvent(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(result);
    }

    private static ManagerDecision decision(String type, String reasoning) {
        return new ManagerDecision(type, null, null, null, 0.9, reasoning, null, null);
    }

    private ManagerDecision createTask(String actionType) {
        return new ManagerDecision("create_task", actionType, null, "input", 0.9,
                "needs " + actionType, null, null);
    }

    private Long createActionType() {
        String name = "items-action-" + UUID.randomUUID();
        actionNames.add(name);
        return QuarkusTransaction.requiringNew().call(() -> {
            ActionTypeEntity at = new ActionTypeEntity();
            at.name = name;
            at.description = "Outcome item test";
            at.executionMode = "agent";
            at.managerTriggerable = false;
            at.userTriggerable = false;
            at.workflowEnabled = false;
            at.emitsEvent = false;
            at.persist();
            return at.id;
        });
    }

    private UUID createEventAndSubscription(String routing) {
        UUID eventId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            newEvent(eventId).persist();
            newSubscription(routing).persist();
        });
        return eventId;
    }

    private StreamEventEntity newEvent(UUID eventId) {
        eventIds.add(eventId);
        StreamEventEntity event = new StreamEventEntity();
        event.id = eventId;
        event.sourceEventId = "items-" + eventId;
        event.source = "github";
        event.connectionId = "conn-items";
        event.type = "issue.created";
        event.ref = "https://github.com/test-org/items/issues/" + eventId;
        event.timestamp = Instant.now();
        event.actor = "{\"login\":\"testuser\"}";
        event.payload = "{\"issue\":{\"title\":\"Test\",\"state\":\"open\",\"number\":\"1\"}}";
        event.createdOn = Instant.now();
        return event;
    }

    private static EventSubscriptionEntity newSubscription(String routing) {
        EventSubscriptionEntity sub = new EventSubscriptionEntity();
        sub.name = "items-" + UUID.randomUUID();
        sub.enabled = true;
        sub.routing = routing;
        sub.createdOn = Instant.now();
        sub.modifiedOn = Instant.now();
        return sub;
    }

    private List<RoutingOutcomeEntity> outcomes(String routingType) {
        return QuarkusTransaction.requiringNew().call(() ->
                RoutingOutcomeEntity.<RoutingOutcomeEntity>list(
                        "routingType = ?1 order by id", routingType));
    }

    private RoutingOutcomeEntity singleOutcome(String routingType) {
        List<RoutingOutcomeEntity> found = outcomes(routingType);
        assertEquals(1, found.size(), "Expected exactly one " + routingType + " outcome");
        return found.get(0);
    }

    private List<RoutingOutcomeItemEntity> items(Long outcomeId) {
        return QuarkusTransaction.requiringNew().call(() ->
                RoutingOutcomeItemEntity.<RoutingOutcomeItemEntity>list(
                        "outcomeId = ?1 order by id", outcomeId));
    }
}
