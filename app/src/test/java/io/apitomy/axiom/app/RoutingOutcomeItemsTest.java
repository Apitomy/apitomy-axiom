package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
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
    WorkflowExecutionService workflowExecutionService;

    @Inject
    EventStreamOrchestrator orchestrator;

    @Inject
    StreamEventCleanup streamEventCleanup;

    private final List<UUID> eventIds = new ArrayList<>();

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
        eventIds.clear();
    }

    @Test
    void createWorkflowProducesWorkflowRunItem() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.title = "Test run";
        run.id = 987654L;
        Mockito.when(workflowExecutionService.triggerWorkflow(ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(RunRequest.class), ArgumentMatchers.any()))
                .thenReturn(run);
        createEventAndSubscription("[{\"type\":\"create-workflow\",\"workflowDefinitionId\":42}]");

        orchestrator.processNewEvents();

        RoutingOutcomeEntity outcome = singleOutcome("create-workflow");
        List<RoutingOutcomeItemEntity> items = items(outcome.id);
        assertEquals(1, items.size());
        assertEquals(RoutingOutcomeItemEntity.TYPE_WORKFLOW_RUN, items.get(0).itemType);
        assertEquals(987654L, items.get(0).workflowRunId);
        assertEquals(outcome.subjectId, items.get(0).subjectId);
        assertNotNull(items.get(0).subjectId);
    }

    @Test
    void processingApiReturnsItems() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.title = "Test run";
        run.id = 987653L;
        Mockito.when(workflowExecutionService.triggerWorkflow(ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(RunRequest.class), ArgumentMatchers.any()))
                .thenReturn(run);
        UUID eventId = createEventAndSubscription(
                "[{\"type\":\"create-workflow\",\"workflowDefinitionId\":42}]");

        orchestrator.processNewEvents();

        RoutingOutcomeEntity outcome = singleOutcome("create-workflow");
        given()
            .when()
                .get("/api/v1/stream/events/" + eventId + "/processing")
            .then()
                .statusCode(200)
                .body("items[0].outcomes[0].items", hasSize(1))
                .body("items[0].outcomes[0].items.type", hasItems("workflow-run"))
                .body("items[0].outcomes[0].items[0].workflowRunId", is(987653))
                .body("items[0].outcomes[0].items[0].subjectId", is(outcome.subjectId.intValue()))
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
                config.inactiveSubjectRetentionDays = 30;
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
            outcome.routingType = "workflow-dispatch";
            outcome.status = "completed";
            outcome.createdOn = Instant.now();
            outcome.persist();
            outcomeId[0] = outcome.id;

            RoutingOutcomeItemEntity item = new RoutingOutcomeItemEntity();
            item.outcomeId = outcome.id;
            item.itemType = RoutingOutcomeItemEntity.TYPE_NO_MATCH;
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
        // Attempt 1: workflow creation fails. Attempt 2 (retry): it succeeds with one run.
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.title = "Test run";
        run.id = 987655L;
        Mockito.when(workflowExecutionService.triggerWorkflow(ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(RunRequest.class), ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("temporarily broken"))
                .thenReturn(run);
        createEventAndSubscription("[{\"type\":\"create-workflow\",\"workflowDefinitionId\":42}]");
        orchestrator.processNewEvents();
        makeDue();
        orchestrator.processNewEvents();

        // An entry runs at most once per tick (#422): the first tick records one failed
        // attempt and the second tick's retry succeeds.
        List<RoutingOutcomeEntity> outcomes = outcomes("create-workflow");
        assertEquals(2, outcomes.size());
        RoutingOutcomeEntity succeeded = outcomes.get(1);
        assertEquals("completed", succeeded.status);
        assertEquals(1, items(succeeded.id).size(),
                "Only the successful attempt's results are on its outcome");
        for (RoutingOutcomeEntity failed : outcomes.subList(0, 1)) {
            assertEquals("failed", failed.status);
            assertEquals(0, items(failed.id).size(), "A failed attempt produced no results");
        }
    }

    @Test
    void retriesNeitherDuplicateNorMoveItemsOfCompletedRules() {
        // The first create-workflow rule completes (with an item), the second keeps failing.
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.title = "Test run";
        run.id = 987656L;
        Mockito.when(workflowExecutionService.triggerWorkflow(ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(RunRequest.class), ArgumentMatchers.any()))
                .thenReturn(run);
        Mockito.when(workflowExecutionService.triggerWorkflow(ArgumentMatchers.anyLong(),
                ArgumentMatchers.eq(-1L), ArgumentMatchers.any(RunRequest.class), ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("no such workflow definition"));
        UUID eventId = createEventAndSubscription(
                "[{\"type\":\"create-workflow\",\"workflowDefinitionId\":42},"
                        + "{\"type\":\"create-workflow\",\"workflowDefinitionId\":-1}]");

        for (int i = 0; i < 5; i++) {
            orchestrator.processNewEvents();
            makeDue();
        }

        List<RoutingOutcomeEntity> completedOutcomes = outcomes("create-workflow").stream()
                .filter(o -> "completed".equals(o.status)).toList();
        assertEquals(1, completedOutcomes.size(), "Completed rule is not replayed");
        RoutingOutcomeEntity completed = completedOutcomes.get(0);
        assertEquals(1, items(completed.id).size());
        List<RoutingOutcomeEntity> failed = outcomes("create-workflow").stream()
                .filter(o -> !"completed".equals(o.status)).toList();
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

    /** Moves the next attempt of every waiting entry into the past (backoff, #422). */
    private static void makeDue() {
        QuarkusTransaction.requiringNew().run(() -> EventProcessingLedgerEntity.update(
                "nextAttemptAt = ?1 where nextAttemptAt is not null",
                Instant.now().minusSeconds(1)));
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
