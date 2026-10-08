package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunResumeEntity;
import io.apitomy.axiom.core.events.model.RoutingRule;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers Axiom's host side of the Flow 2.6/2.7 node features: receive-event correlation keys, look-back
 * and timeouts, and human-task titles.
 */
@QuarkusTest
class WorkflowReceiveEventFeaturesTest {

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    WorkflowEventDispatcher dispatcher;

    @Inject
    WorkflowWaitScheduler waitScheduler;

    /** start(inputs: orderId) → receive-event(config) → end; plus an optional timeout path. */
    private static String receiveWorkflow(String eventType, String receiveConfig, boolean withTimeoutEdge) {
        String timeoutNode = withTimeoutEdge
                ? """
                  ,{"id": "expired", "type": "end", "name": "Expired", "config": {},
                    "position": {"x": 300, "y": 300}}"""
                : "";
        String timeoutEdge = withTimeoutEdge
                ? """
                  ,{"id": "edge3", "source": "r1", "target": "expired", "priority": 1,
                    "isDefault": false, "isTimeout": true}"""
                : "";
        return """
            {
                "id": "rx-wf", "name": "Receive WF",
                "nodes": [
                    {"id": "s1", "type": "start", "name": "Start",
                     "config": {"inputs": [{"name": "orderId", "type": "string"}]},
                     "position": {"x": 100, "y": 100}},
                    {"id": "r1", "type": "receive-event", "name": "Await",
                     "config": %s, "position": {"x": 100, "y": 200}},
                    {"id": "e1", "type": "end", "name": "End", "config": {},
                     "position": {"x": 100, "y": 300}}%s
                ],
                "edges": [
                    {"id": "edge1", "source": "s1", "target": "r1", "priority": 0, "isDefault": true},
                    {"id": "edge2", "source": "r1", "target": "e1", "priority": 0, "isDefault": false}%s
                ]
            }
            """.formatted(receiveConfig.replace("$TYPE", eventType), timeoutNode, timeoutEdge);
    }

    // -- correlation key --

    @Test
    void correlationKeyIsStoredAndOnlyEqualKeysMatch() {
        String config = """
            {"eventType": "$TYPE", "lookback": "none",
             "correlationKey": {"subscriptionKey": "context.orderId", "eventKey": "event.payload.orderId"}}""";
        long[] ids = setup("Correlation Subject", receiveWorkflow("order.shipped", config, false));
        WorkflowRunEntity run = trigger(ids, Map.of("orderId", "A-1"));

        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowEventSubscriptionEntity sub = WorkflowEventSubscriptionEntity.find("runId", run.id).firstResult();
            assertEquals("A-1", sub.correlationKey);
        });

        dispatcher.dispatchStreamEvent("order.shipped", eventMap("order.shipped", Map.of("orderId", "B-2")));
        assertRunStatus(run.id, "waiting");

        dispatcher.dispatchStreamEvent("order.shipped", eventMap("order.shipped", Map.of("orderId", "A-1")));
        assertRunStatus(run.id, "completed");
    }

    // -- timeout --

    @Test
    void timedOutBranchFollowsTimeoutEdge() {
        String config = "{\"eventType\": \"$TYPE\", \"lookback\": \"none\", \"timeout\": \"PT1H\"}";
        long[] ids = setup("Timeout Subject", receiveWorkflow("never.happens", config, true));
        WorkflowRunEntity run = trigger(ids, Map.of());

        long subId = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowEventSubscriptionEntity sub = WorkflowEventSubscriptionEntity.find("runId", run.id).firstResult();
            assertNotNull(sub.timeoutAt);
            assertTrue(sub.timeoutAt.isAfter(Instant.now().plusSeconds(3500)));
            sub.timeoutAt = Instant.now().minusSeconds(1);
            return sub.id;
        });

        assertTrue(waitScheduler.findDueEventTimeoutIds().contains(subId));
        waitScheduler.timeoutSubscription(subId);

        assertRunStatus(run.id, "completed");
        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(0, WorkflowEventSubscriptionEntity.count("runId", run.id));
            WorkflowRunEntity r = WorkflowRunEntity.findById(run.id);
            assertTrue(r.instanceState.contains("\"expired\""), "should have taken the timeout edge");
        });
    }

    @Test
    void notYetDueTimeoutDoesNothing() {
        String config = "{\"eventType\": \"$TYPE\", \"lookback\": \"none\", \"timeout\": \"PT1H\"}";
        long[] ids = setup("Timeout Not Due Subject", receiveWorkflow("never.happens", config, true));
        WorkflowRunEntity run = trigger(ids, Map.of());
        long subId = QuarkusTransaction.requiringNew().call(() ->
                ((WorkflowEventSubscriptionEntity) WorkflowEventSubscriptionEntity.find("runId", run.id)
                        .firstResult()).id);

        waitScheduler.timeoutSubscription(subId);

        assertRunStatus(run.id, "waiting");
    }

    // -- look-back --

    @Test
    void storedDispatchedEventIsDeliveredWhenTheBranchParks() {
        String type = "lookback.hit";
        UUID eventId = storeEvent(type, true);
        long[] ids = setup("Lookback Hit Subject", receiveWorkflow(type, "{\"eventType\": \"$TYPE\"}", false));

        WorkflowRunEntity run = trigger(ids, Map.of());

        assertRunStatus(run.id, "completed");
        QuarkusTransaction.requiringNew().run(() -> assertEquals(1,
                WorkflowRunResumeEntity.count("runId = ?1 and eventId = ?2", run.id, eventId)));
    }

    @Test
    void lookbackNoneIgnoresStoredEvents() {
        String type = "lookback.none";
        storeEvent(type, true);
        long[] ids = setup("Lookback None Subject",
                receiveWorkflow(type, "{\"eventType\": \"$TYPE\", \"lookback\": \"none\"}", false));

        WorkflowRunEntity run = trigger(ids, Map.of());

        assertRunStatus(run.id, "waiting");
        QuarkusTransaction.requiringNew().run(() -> assertNull(
                ((WorkflowEventSubscriptionEntity) WorkflowEventSubscriptionEntity.find("runId", run.id)
                        .firstResult()).lookbackFrom));
    }

    @Test
    void eventsNotRoutedByWorkflowDispatchAreNotReplayed() {
        String type = "lookback.unrouted";
        storeEvent(type, false);
        long[] ids = setup("Lookback Unrouted Subject", receiveWorkflow(type, "{\"eventType\": \"$TYPE\"}", false));

        WorkflowRunEntity run = trigger(ids, Map.of());

        assertRunStatus(run.id, "waiting");
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowEventSubscriptionEntity sub = WorkflowEventSubscriptionEntity.find("runId", run.id).firstResult();
            assertNull(sub.lookbackFrom, "look-back runs once");
        });
    }

    @Test
    void lookbackWindowStart() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = Instant.parse("2026-01-01T01:00:00Z");
        assertEquals(start, WorkflowExecutionService.lookbackFrom(null, start, now));
        assertEquals(start, WorkflowExecutionService.lookbackFrom(
                io.apitomy.flow.model.EventLookback.RUN_START, start, now));
        assertNull(WorkflowExecutionService.lookbackFrom(io.apitomy.flow.model.EventLookback.NONE, start, now));
        assertEquals(Instant.parse("2026-01-01T00:50:00Z"), WorkflowExecutionService.lookbackFrom(
                io.apitomy.flow.model.EventLookback.parse("PT10M"), start, now));
        assertEquals(start, WorkflowExecutionService.lookbackFrom(
                io.apitomy.flow.model.EventLookback.parse("PT5H"), start, now));
    }

    // -- human-task title --

    @Test
    void humanTaskTitleIsTheInboxSubject() {
        String content = """
            {
                "id": "ht-wf", "name": "Human Title WF",
                "nodes": [
                    {"id": "s1", "type": "start", "name": "Start",
                     "config": {"inputs": [{"name": "orderId", "type": "string"}]},
                     "position": {"x": 100, "y": 100}},
                    {"id": "h1", "type": "human-task", "name": "Review",
                     "config": {"title": "'Review order ' += context.orderId",
                                "outputs": [{"name": "ok", "type": "boolean"}]},
                     "position": {"x": 100, "y": 200}},
                    {"id": "e1", "type": "end", "name": "End", "config": {},
                     "position": {"x": 100, "y": 300}}
                ],
                "edges": [
                    {"id": "edge1", "source": "s1", "target": "h1", "priority": 0, "isDefault": true},
                    {"id": "edge2", "source": "h1", "target": "e1", "priority": 0, "isDefault": true}
                ]
            }
            """;
        long[] ids = setup("Human Title Subject", content);
        WorkflowRunEntity run = trigger(ids, Map.of("orderId", "A-7"));

        QuarkusTransaction.requiringNew().run(() -> {
            TaskEntity task = TaskEntity.find("workflowRunId", run.id).firstResult();
            assertEquals("Review order A-7", task.actionType);
        });
    }

    // -- helpers --

    /**
     * Stores a stream event timestamped just after now (so it falls inside a run started right after),
     * optionally routed by a workflow-dispatch rule.
     */
    private UUID storeEvent(String type, boolean routedByWorkflowDispatch) {
        return QuarkusTransaction.requiringNew().call(() -> {
            UUID id = UUID.randomUUID();
            StreamEventEntity event = new StreamEventEntity();
            event.id = id;
            event.sourceEventId = "lookback-" + id;
            event.source = "github";
            event.eventSourceId = "conn-lookback";
            event.type = type;
            event.ref = "https://github.com/o/r/issues/1";
            event.timestamp = Instant.now().plusSeconds(2);
            event.actor = "{\"login\":\"u\"}";
            event.payload = "{}";
            event.createdOn = Instant.now();
            event.persist();

            EventSubscriptionEntity sub = new EventSubscriptionEntity();
            sub.name = "lookback-sub-" + id;
            sub.routing = "[]";
            sub.createdOn = Instant.now();
            sub.modifiedOn = Instant.now();
            sub.persist();

            EventProcessingLedgerEntity ledger = new EventProcessingLedgerEntity();
            ledger.eventId = id;
            ledger.subscriptionId = sub.id;
            ledger.status = "completed";
            ledger.createdOn = Instant.now();
            ledger.persist();

            RoutingOutcomeEntity outcome = new RoutingOutcomeEntity();
            outcome.ledgerId = ledger.id;
            outcome.routingType = routedByWorkflowDispatch
                    ? RoutingRule.TYPE_WORKFLOW_DISPATCH : RoutingRule.TYPE_CREATE_WORKFLOW;
            outcome.status = "completed";
            outcome.createdOn = Instant.now();
            outcome.persist();
            return id;
        });
    }

    private long[] setup(String subjectName, String content) {
        return QuarkusTransaction.requiringNew().call(() -> {
            SubjectEntity subject = new SubjectEntity();
            subject.name = subjectName;
            subject.type = "other";
            subject.ref = "test/" + subjectName.toLowerCase().replace(" ", "-") + "-" + UUID.randomUUID();
            subject.createdOn = Instant.now();
            subject.updatedOn = Instant.now();
            subject.persist();

            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = subjectName + " WF " + UUID.randomUUID();
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
            return new long[] {subject.id, def.id};
        });
    }

    private WorkflowRunEntity trigger(long[] ids, Map<String, Object> inputs) {
        return QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(ids[0], ids[1], inputs));
    }

    private static Map<String, Object> eventMap(String type, Map<String, Object> payload) {
        return Map.of("type", type, "source", "github", "eventSourceId", "test-conn",
                "ref", "", "timestamp", Instant.now().toString(), "payload", payload);
    }

    private void assertRunStatus(long runId, String expected) {
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
            assertNotNull(run);
            assertEquals(expected, run.status);
        });
    }
}
