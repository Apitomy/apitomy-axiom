package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how Manager evaluations are traced and linked to their event (#416), and that
 * Manager failures are distinguished from an empty decision list (#417).
 */
@QuarkusTest
class ManagerTracingTest {

    @InjectMock
    AgentPool agentPool;

    @InjectMock
    ManagerService managerService;

    @Inject
    EventStreamOrchestrator orchestrator;

    private final List<UUID> eventIds = new ArrayList<>();

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
            RoutingOutcomeEntity.deleteAll();
            EventProcessingLedgerEntity.deleteAll();
            for (UUID id : eventIds) {
                TaskEntity.delete("eventId", id);
            }
            StreamEventEntity.deleteAll();
            EventSubscriptionEntity.deleteAll();
        });
        eventIds.clear();
    }

    // ── #416: evaluation and decision nodes ─────────────────────────

    @Test
    void evaluationNodeHasOneDecisionNodePerDecisionWithReasoning() {
        stubEvaluation(ManagerEvaluationResult.success(List.of(
                decision("ignore", "first reason"),
                decision("escalate", "second reason")), null));
        UUID eventId = createEventAndSubscription();

        orchestrator.processNewEvents();

        RoutingOutcomeEntity outcome = managerOutcome();
        TraceNodeEntity eval = singleNode(outcome.traceId, "manager-evaluation");
        assertEquals("completed", eval.status);
        List<TraceNodeEntity> decisions = nodes(outcome.traceId, "manager-decision");
        assertEquals(2, decisions.size());
        decisions.forEach(d -> {
            assertEquals(eval.id, d.parentNodeId);
            assertEquals("completed", d.status);
        });
        assertTrue(decisions.stream().anyMatch(d -> d.summary.contains("first reason")));
        assertTrue(decisions.stream().anyMatch(d -> d.summary.contains("second reason")));

        TraceEntity trace = trace(outcome.traceId);
        assertEquals(eventId, trace.eventId);
        assertEquals("completed", trace.status);
    }

    @Test
    void evaluationNodeLinksToManagerActivityRow() {
        stubEvaluation(ManagerEvaluationResult.success(List.of(), 4242L));
        createEventAndSubscription();

        orchestrator.processNewEvents();

        TraceNodeEntity eval = singleNode(managerOutcome().traceId, "manager-evaluation");
        assertEquals("activity-log", eval.entityType);
        assertEquals("4242", eval.entityId);
    }

    @Test
    void managerReceivesTraceIdOfItsTrace() {
        stubEvaluation(ManagerEvaluationResult.success(List.of(), null));
        createEventAndSubscription();

        orchestrator.processNewEvents();

        UUID traceId = managerOutcome().traceId;
        Mockito.verify(managerService).evaluateStreamEvent(ArgumentMatchers.any(),
                ArgumentMatchers.eq(traceId));
    }

    @Test
    void ignoreAndEscalateNodesLinkActivityRowsWithTraceAndEvent() {
        stubEvaluation(ManagerEvaluationResult.success(List.of(
                decision("ignore", "not relevant"),
                decision("escalate", "needs a human")), null));
        UUID eventId = createEventAndSubscription();

        orchestrator.processNewEvents();

        UUID traceId = managerOutcome().traceId;
        for (String entryType : List.of("event-ignored", "manager-escalation")) {
            ActivityLogEntity row = QuarkusTransaction.requiringNew().call(() ->
                    ActivityLogEntity.<ActivityLogEntity>find(
                            "eventId = ?1 and entryType = ?2", eventId, entryType).firstResult());
            assertNotNull(row, "Missing " + entryType + " row");
            assertEquals(traceId, row.traceId);
            assertTrue(nodes(traceId, "manager-decision").stream().anyMatch(n ->
                    "activity-log".equals(n.entityType) && String.valueOf(row.id).equals(n.entityId)),
                    "A decision node must reference the " + entryType + " row");
        }
    }

    @Test
    void createTaskNodeIsChildOfDecisionAndTraceGetsSingleProject() {
        stubEvaluation(ManagerEvaluationResult.success(List.of(
                new ManagerDecision("create_task", "manager-tracing-action", null, "input", 0.9,
                        "needs work", null, null)), null));
        UUID eventId = createEventAndSubscription();

        orchestrator.processNewEvents();

        RoutingOutcomeEntity outcome = managerOutcome();
        TaskEntity task = QuarkusTransaction.requiringNew().call(() ->
                TaskEntity.<TaskEntity>find("eventId", eventId).firstResult());
        assertNotNull(task);
        assertEquals(outcome.traceId, task.traceId);

        TraceNodeEntity decisionNode = singleNode(outcome.traceId, "manager-decision");
        assertEquals("completed", decisionNode.status);
        assertTrue(decisionNode.summary.contains("needs work"));
        TraceNodeEntity taskNode = singleNode(outcome.traceId, "task");
        assertEquals(decisionNode.id, taskNode.parentNodeId);

        TraceEntity trace = trace(outcome.traceId);
        assertEquals(task.projectId, trace.projectId);
        assertEquals("in-progress", trace.status, "Open task keeps the trace open");
    }

    // ── #417: failures vs. no decisions ─────────────────────────────

    @Test
    void emptyDecisionListIsCompletedNoDecisions() {
        stubEvaluation(ManagerEvaluationResult.success(List.of(), null));
        UUID eventId = createEventAndSubscription();

        orchestrator.processNewEvents();

        RoutingOutcomeEntity outcome = managerOutcome();
        assertEquals("completed", outcome.status);
        assertEquals("No decisions", outcome.summary);
        assertEquals("completed", ledger(eventId).status);
        assertEquals("completed", trace(outcome.traceId).status);
        assertEquals("completed", singleNode(outcome.traceId, "manager-evaluation").status);
    }

    @Test
    void managerFailureFailsOutcomeLedgerAndTraceAndIsRetried() {
        stubEvaluation(ManagerEvaluationResult.failure("AI engine exploded", 77L));
        UUID eventId = createEventAndSubscription();

        orchestrator.processNewEvents();

        RoutingOutcomeEntity outcome = managerOutcome();
        assertEquals("failed", outcome.status);
        assertTrue(outcome.errorMessage.contains("AI engine exploded"));
        assertNotNull(outcome.traceId);
        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("failed", entry.status);
        assertTrue(entry.errorMessage.contains("AI engine exploded"));
        assertEquals("failed", trace(outcome.traceId).status);
        TraceNodeEntity eval = singleNode(outcome.traceId, "manager-evaluation");
        assertEquals("failed", eval.status);
        assertEquals("77", eval.entityId);

        // The failed ledger entry is retried (the retry pass runs on every tick)
        long callsBefore = evaluationCalls();
        orchestrator.processNewEvents();
        long callsAfter = evaluationCalls();
        assertTrue(callsAfter > callsBefore, "Failed entry must be retried");
        long failedOutcomes = QuarkusTransaction.requiringNew().call(() ->
                RoutingOutcomeEntity.count("routingType = 'manager' and status = 'failed'"));
        assertEquals(callsAfter, failedOutcomes, "Each failed attempt records a failed outcome");
        assertEquals("failed", ledger(eventId).status);
    }

    @Test
    void alwaysFailingManagerIsEvaluatedAtMostMaxAttemptsTimes() {
        stubEvaluation(ManagerEvaluationResult.failure("still broken", null));
        UUID eventId = createEventAndSubscription();

        for (int i = 0; i < 6; i++) {
            orchestrator.processNewEvents();
        }

        assertEquals(3, evaluationCalls(), "Default axiom.stream-pipeline.max-attempts is 3");
        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("failed", entry.status);
        assertTrue(entry.errorMessage.contains("giving up after 3 attempts"), entry.errorMessage);
    }

    private long evaluationCalls() {
        return Mockito.mockingDetails(managerService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("evaluateStreamEvent"))
                .count();
    }

    @Test
    void perDecisionFailureIsRecordedOnItsNodeAndInSummary() {
        stubEvaluation(ManagerEvaluationResult.success(List.of(
                decision("bogus_decision", "weird"),
                decision("ignore", "fine")), null));
        UUID eventId = createEventAndSubscription();

        orchestrator.processNewEvents();

        RoutingOutcomeEntity outcome = managerOutcome();
        List<TraceNodeEntity> decisionNodes = nodes(outcome.traceId, "manager-decision");
        assertEquals(2, decisionNodes.size());
        TraceNodeEntity failed = decisionNodes.stream()
                .filter(n -> n.summary.contains("bogus_decision")).findFirst().orElseThrow();
        assertEquals("failed", failed.status);
        assertTrue(failed.summary.contains("Unknown Manager decision type"));
        TraceNodeEntity ok = decisionNodes.stream()
                .filter(n -> n.summary.contains("fine")).findFirst().orElseThrow();
        assertEquals("completed", ok.status);
        assertTrue(outcome.summary.contains("bogus_decision failed"), outcome.summary);
        assertNotEquals("failed", ledger(eventId).status, "Per-decision errors are not retried");
        assertEquals("completed", trace(outcome.traceId).status);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private void stubEvaluation(ManagerEvaluationResult result) {
        Mockito.when(managerService.evaluateStreamEvent(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(result);
    }

    private static ManagerDecision decision(String type, String reasoning) {
        return new ManagerDecision(type, null, null, null, 0.9, reasoning, null, null);
    }

    private UUID createEventAndSubscription() {
        UUID eventId = UUID.randomUUID();
        eventIds.add(eventId);
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = eventId;
            event.sourceEventId = "mgr-trace-" + eventId;
            event.source = "github";
            event.connectionId = "conn-mgr-trace";
            event.type = "issue.created";
            event.ref = "https://github.com/test-org/mgr-trace/issues/" + eventId;
            event.timestamp = Instant.now();
            event.actor = "{\"login\":\"testuser\"}";
            event.payload = "{\"issue\":{\"title\":\"Test\",\"state\":\"open\",\"number\":\"1\"}}";
            event.createdOn = Instant.now();
            event.persist();

            EventSubscriptionEntity sub = new EventSubscriptionEntity();
            sub.name = "mgr-trace-" + eventId;
            sub.enabled = true;
            sub.routing = "[{\"type\":\"manager\"}]";
            sub.createdOn = Instant.now();
            sub.modifiedOn = Instant.now();
            sub.persist();
        });
        return eventId;
    }

    private RoutingOutcomeEntity managerOutcome() {
        RoutingOutcomeEntity outcome = QuarkusTransaction.requiringNew().call(() ->
                RoutingOutcomeEntity.<RoutingOutcomeEntity>find(
                        "routingType = 'manager' order by id").firstResult());
        assertNotNull(outcome);
        assertNotNull(outcome.traceId);
        return outcome;
    }

    private EventProcessingLedgerEntity ledger(UUID eventId) {
        return QuarkusTransaction.requiringNew().call(() ->
                EventProcessingLedgerEntity.<EventProcessingLedgerEntity>find("eventId", eventId)
                        .firstResult());
    }

    private TraceEntity trace(UUID traceId) {
        return QuarkusTransaction.requiringNew().call(() ->
                TraceEntity.<TraceEntity>findById(traceId));
    }

    private List<TraceNodeEntity> nodes(UUID traceId, String nodeType) {
        return QuarkusTransaction.requiringNew().call(() ->
                TraceNodeEntity.<TraceNodeEntity>list("traceId = ?1 and nodeType = ?2 order by id",
                        traceId, nodeType));
    }

    private TraceNodeEntity singleNode(UUID traceId, String nodeType) {
        List<TraceNodeEntity> found = nodes(traceId, nodeType);
        assertEquals(1, found.size(), "Expected exactly one " + nodeType + " node");
        return found.get(0);
    }
}
