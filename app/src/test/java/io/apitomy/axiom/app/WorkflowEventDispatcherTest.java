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
        dispatcher.dispatchEvent(eventId);

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
        dispatcher.dispatchEvent(eventId);

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
        dispatcher.dispatchEvent(eventId);

        assertRunStatus(run.id, "completed");
        assertSubscriptionCount(run.id, 0);
    }

    @Test
    void falseMatchExpressionLeavesRunParked() {
        long[] ids = setup("Dispatcher Match Expr Project", MATCH_EXPRESSION_CONTENT);
        WorkflowRunEntity run = trigger(ids);

        long smallPr = createEvent("pr-merged", "github", projectRef(ids[0]),
                "{\"number\": 5}");
        dispatcher.dispatchEvent(smallPr);
        assertRunStatus(run.id, "waiting");
        assertSubscriptionCount(run.id, 1);

        long bigPr = createEvent("pr-merged", "github", projectRef(ids[0]),
                "{\"number\": 500}");
        dispatcher.dispatchEvent(bigPr);
        assertRunStatus(run.id, "completed");
        assertSubscriptionCount(run.id, 0);
    }

    @Test
    void failingOfferDoesNotPoisonOtherResumptions() {
        long[] idsHealthy = setup("Dispatcher Isolation Healthy Project", RECEIVE_EVENT_CONTENT);
        long[] idsCorrupt = setup("Dispatcher Isolation Corrupt Project", RECEIVE_EVENT_CONTENT);
        WorkflowRunEntity healthyRun = trigger(idsHealthy);
        WorkflowRunEntity corruptRun = trigger(idsCorrupt);

        // Corrupt one run's instance state so its offer fails safely inside
        // its own per-subscription transaction.
        long corruptRunId = corruptRun.id;
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunEntity run = WorkflowRunEntity.findById(corruptRunId);
            run.instanceState = "not json";
        });

        // Broadcast event (no issueRef/projectId) matches both subscriptions.
        long eventId = createEvent("pr-merged", "github", null, null);
        dispatcher.dispatchEvent(eventId);

        // The healthy run resumed and completed despite the corrupt run's
        // offer failing; the corrupt run remains parked.
        assertRunStatus(healthyRun.id, "completed");
        assertSubscriptionCount(healthyRun.id, 0);
        assertRunStatus(corruptRun.id, "waiting");
        assertSubscriptionCount(corruptRun.id, 1);
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
            // payload is NOT NULL in the schema; default to an empty object.
            event.payload = payload != null ? payload : "{}";
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
