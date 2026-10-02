package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a workflow-dispatch whose matched run fails to resume is recorded as a failed
 * item, not as "no match" (#421).
 */
@QuarkusTest
class WorkflowDispatchFailureTest {

    @InjectMock
    AgentPool agentPool;

    @InjectSpy
    WorkflowExecutionService workflowExecutionService;

    @Inject
    EventStreamOrchestrator orchestrator;

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
    void throwingResumeIsRecordedAsFailedItem() {
        Mockito.when(agentPool.tryLease(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Optional.empty());
        String awaitedType = "dispatch-fail-" + UUID.randomUUID();
        long[] ids = setup(awaitedType);
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(ids[0], ids[1]));
        Mockito.doThrow(new IllegalStateException("resume exploded"))
                .when(workflowExecutionService).onEventReceived(ArgumentMatchers.anyLong(),
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.any());
        createEventAndSubscription(awaitedType);

        orchestrator.processNewEvents();

        List<RoutingOutcomeEntity> outcomes = QuarkusTransaction.requiringNew().call(() ->
                RoutingOutcomeEntity.<RoutingOutcomeEntity>list("routingType",
                        "workflow-dispatch"));
        assertEquals(1, outcomes.size(), "A failed resume must not fail (and retry) the outcome");
        assertEquals("completed", outcomes.get(0).status);
        List<RoutingOutcomeItemEntity> items = QuarkusTransaction.requiringNew().call(() ->
                RoutingOutcomeItemEntity.<RoutingOutcomeItemEntity>list("outcomeId",
                        outcomes.get(0).id));
        assertEquals(1, items.size());
        RoutingOutcomeItemEntity item = items.get(0);
        assertEquals(RoutingOutcomeItemEntity.TYPE_WORKFLOW_RESUMED, item.itemType);
        assertEquals("failed", item.status);
        assertEquals(run.id, item.workflowRunId);
        assertTrue(item.errorMessage.contains("resume exploded"), item.errorMessage);
    }

    private long[] setup(String awaitedType) {
        String content = """
            {
                "id": "dispatch-fail-wf",
                "name": "Dispatch Fail WF",
                "nodes": [
                    {"id": "s1", "type": "start", "name": "Start",
                     "config": {}, "position": {"x": 100, "y": 100}},
                    {"id": "r1", "type": "receive-event", "name": "Await",
                     "config": {"eventType": "%s"}, "position": {"x": 100, "y": 200}},
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
        return QuarkusTransaction.requiringNew().call(() -> {
            ProjectEntity project = new ProjectEntity();
            project.name = "Dispatch Fail Project";
            project.type = "other";
            project.status = "Created";
            project.ref = "test/dispatch-fail-" + UUID.randomUUID();
            project.createdOn = Instant.now();
            project.updatedOn = Instant.now();
            project.persist();

            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = "Dispatch Fail WF " + UUID.randomUUID();
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

    private void createEventAndSubscription(String eventType) {
        UUID eventId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = eventId;
            event.sourceEventId = "dispatch-fail-" + eventId;
            event.source = "github";
            event.connectionId = "conn-dispatch-fail";
            event.type = eventType;
            event.ref = "https://github.com/test-org/dispatch-fail/issues/" + eventId;
            event.timestamp = Instant.now();
            event.actor = "{\"login\":\"testuser\"}";
            event.payload = "{}";
            event.createdOn = Instant.now();
            event.persist();

            EventSubscriptionEntity sub = new EventSubscriptionEntity();
            sub.name = "dispatch-fail-" + UUID.randomUUID();
            sub.enabled = true;
            sub.routing = "[{\"type\":\"workflow-dispatch\"}]";
            sub.createdOn = Instant.now();
            sub.modifiedOn = Instant.now();
            sub.persist();
        });
    }
}
