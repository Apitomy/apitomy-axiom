package io.apitomy.axiom.app;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exercises {@link WorkflowEventDispatcher#dispatchStreamEvent(String, Map)}
 * directly (bypassing the pipeline scheduler, disabled in the test profile),
 * verifying event-type prefiltering, match-expression filtering, and
 * end-to-end run resumption.
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

        Map<String, Object> eventMap = buildStreamEventMap("issue-created", "github",
                projectRef(ids[0]), null);
        dispatcher.dispatchStreamEvent("issue-created", eventMap);

        assertRunStatus(run.id, "waiting");
        assertSubscriptionCount(run.id, 1);
    }

    @Test
    void matchingEventTypeResumesRun() {
        long[] ids = setup("Dispatcher Match Project", RECEIVE_EVENT_CONTENT);
        WorkflowRunEntity run = trigger(ids);

        Map<String, Object> eventMap = buildStreamEventMap("pr-merged", "github",
                projectRef(ids[0]), null);
        dispatcher.dispatchStreamEvent("pr-merged", eventMap);

        assertRunStatus(run.id, "completed");
        assertSubscriptionCount(run.id, 0);
    }

    @Test
    void falseMatchExpressionLeavesRunParked() {
        long[] ids = setup("Dispatcher Match Expr Project", MATCH_EXPRESSION_CONTENT);
        WorkflowRunEntity run = trigger(ids);

        Map<String, Object> smallPr = buildStreamEventMap("pr-merged", "github",
                projectRef(ids[0]), Map.of("number", 5));
        dispatcher.dispatchStreamEvent("pr-merged", smallPr);
        assertRunStatus(run.id, "waiting");
        assertSubscriptionCount(run.id, 1);

        Map<String, Object> bigPr = buildStreamEventMap("pr-merged", "github",
                projectRef(ids[0]), Map.of("number", 500));
        dispatcher.dispatchStreamEvent("pr-merged", bigPr);
        assertRunStatus(run.id, "completed");
        assertSubscriptionCount(run.id, 0);
    }

    @Test
    void dispatchStreamEventNoMatchingSubscriptionsDoesNotThrow() {
        Map<String, Object> eventMap = Map.of(
                "type", "some.unmatched.type",
                "source", "github",
                "connectionId", "test-conn",
                "ref", "https://github.com/owner/repo/issues/1",
                "timestamp", Instant.now().toString(),
                "payload", Map.of());
        dispatcher.dispatchStreamEvent("some.unmatched.type", eventMap);
        // No exception means the method handled the empty-candidates path.
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildStreamEventMap(String eventType, String source,
            String ref, Map<String, Object> payload) {
        return Map.of(
                "type", eventType,
                "source", source,
                "connectionId", "test-conn",
                "ref", ref != null ? ref : "",
                "timestamp", Instant.now().toString(),
                "payload", payload != null ? payload : Map.of());
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
