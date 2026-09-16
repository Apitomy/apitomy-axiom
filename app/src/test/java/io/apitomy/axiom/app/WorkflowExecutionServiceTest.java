package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowWaitEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link WorkflowExecutionService#onTaskCompleted(long)} for a
 * single-branch linear workflow (start -> action -> end), verifying the run
 * still advances correctly end-to-end via the nodeId-addressed
 * {@code WorkflowEngine.completeNode(workflow, instance, nodeId, result)}
 * call (replacing the removed {@code completeCurrentNode} overload).
 */
@QuarkusTest
class WorkflowExecutionServiceTest {

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    TaskExecutionService taskExecutionService;

    private static final String LINEAR_CONTENT = """
        {
            "id": "linear-wf",
            "name": "Linear WF",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start",
                 "config": {}, "position": {"x": 100, "y": 100}},
                {"id": "a1", "type": "action", "name": "Do Something",
                 "config": {"actionType": "test-action"},
                 "position": {"x": 100, "y": 200}},
                {"id": "e1", "type": "end", "name": "End",
                 "config": {}, "position": {"x": 100, "y": 300}}
            ],
            "edges": [
                {"id": "edge1", "source": "s1", "target": "a1",
                 "priority": 0, "isDefault": true},
                {"id": "edge2", "source": "a1", "target": "e1",
                 "priority": 0, "isDefault": true}
            ]
        }
        """;

    /**
     * Completing the single action task of a linear start -> action -> end
     * workflow should advance the run through {@code completeNode(workflow,
     * instance, task.nodeId, result)} and reach the COMPLETED terminal state.
     */
    @Test
    void completingActionTaskAdvancesLinearRunToCompleted() {
        long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            ProjectEntity project = new ProjectEntity();
            project.name = "Linear Run Project";
            project.type = "other";
            project.status = "new";
            project.ref = "test/linear-run";
            project.createdOn = Instant.now();
            project.updatedOn = Instant.now();
            project.persist();

            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = "Linear Run WF";
            def.content = LINEAR_CONTENT;
            def.currentVersion = 1;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();

            WorkflowDefinitionVersionEntity version =
                    new WorkflowDefinitionVersionEntity();
            version.definitionId = def.id;
            version.version = 1;
            version.content = LINEAR_CONTENT;
            version.createdOn = Instant.now();
            version.persist();

            return new long[] { project.id, def.id };
        });

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(ids[0], ids[1]));
        assertEquals("waiting", run.status, "Run should be waiting at the action node");

        long taskId = QuarkusTransaction.requiringNew().call(() -> {
            TaskEntity task = TaskEntity.find("workflowRunId", run.id).firstResult();
            assertNotNull(task, "Action task should have been created");
            assertEquals("a1", task.nodeId, "Task should be addressed to node a1");
            return task.id;
        });

        AgentResult successResult = AgentResult.success("Action completed");
        QuarkusTransaction.requiringNew().run(() ->
                taskExecutionService.onTaskCompleted(taskId, successResult));

        WorkflowRunEntity completedRun = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunEntity.findById(run.id));
        assertNotNull(completedRun, "Run should still exist");
        assertEquals("completed", completedRun.status,
                "Run should advance to completed after the action node's task completes");
    }

    private static final String WAIT_CONTENT = """
        {
            "id": "wait-wf",
            "name": "Wait WF",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start",
                 "config": {}, "position": {"x": 100, "y": 100}},
                {"id": "w1", "type": "wait", "name": "Cooldown",
                 "config": {"duration": "PT5M"},
                 "position": {"x": 100, "y": 200}},
                {"id": "e1", "type": "end", "name": "End",
                 "config": {}, "position": {"x": 100, "y": 300}}
            ],
            "edges": [
                {"id": "edge1", "source": "s1", "target": "w1",
                 "priority": 0, "isDefault": true},
                {"id": "edge2", "source": "w1", "target": "e1",
                 "priority": 0, "isDefault": true}
            ]
        }
        """;

    /**
     * Triggering a workflow that parks on a Wait node should create a
     * {@link WorkflowWaitEntity} (not a {@link TaskEntity}) addressed to the
     * wait node, with {@code resumeAt} set to roughly now + the configured
     * duration.
     */
    @Test
    void triggeringWaitWorkflowParksWithoutCreatingATask() {
        long[] ids = createProjectAndDefinition("Wait Run Project", WAIT_CONTENT);

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(ids[0], ids[1]));
        assertEquals("waiting", run.status, "Run should be waiting at the wait node");

        QuarkusTransaction.requiringNew().run(() -> {
            TaskEntity task = TaskEntity.find("workflowRunId", run.id).firstResult();
            assertNull(task, "Wait nodes must not create a task");

            WorkflowWaitEntity wait =
                    WorkflowWaitEntity.find("runId", run.id).firstResult();
            assertNotNull(wait, "A WorkflowWaitEntity should be created for the wait node");
            assertEquals("w1", wait.nodeId);
            assertTrue(wait.resumeAt.isAfter(Instant.now()),
                    "resumeAt should be in the future");
        });
    }

    /**
     * {@link WorkflowExecutionService#onWaitElapsed(long, String)} should
     * advance a WAITING run parked on a wait node through to completion,
     * mirroring what {@link WorkflowWaitScheduler} does once the wait's
     * duration has elapsed.
     */
    @Test
    void onWaitElapsedAdvancesRunToCompleted() {
        long[] ids = createProjectAndDefinition("Wait Elapsed Project", WAIT_CONTENT);

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(ids[0], ids[1]));
        assertEquals("waiting", run.status);

        QuarkusTransaction.requiringNew().run(() ->
                workflowExecutionService.onWaitElapsed(run.id, "w1"));

        WorkflowRunEntity completedRun = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunEntity.findById(run.id));
        assertNotNull(completedRun);
        assertEquals("completed", completedRun.status,
                "Run should advance to completed once the wait node's result is applied");
    }

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
     * completion, mirroring what WorkflowEventDispatcher does when a
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

    /**
     * Creates a project and a published single-version workflow definition
     * from the given content, returning {id[0]=projectId, id[1]=definitionId}.
     */
    private long[] createProjectAndDefinition(String projectName, String content) {
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

            WorkflowDefinitionVersionEntity version =
                    new WorkflowDefinitionVersionEntity();
            version.definitionId = def.id;
            version.version = 1;
            version.content = content;
            version.createdOn = Instant.now();
            version.persist();

            return new long[] { project.id, def.id };
        });
    }

    // Multi-branch (fork/join) per-branch task creation is covered end-to-end,
    // through the full public API (trigger -> inbox -> complete), by
    // WorkflowExecutionParallelTest. A reflection-based unit test invoking the
    // private createTasksForActiveBranches directly is not used here: the
    // injected WorkflowExecutionService is a CDI client proxy, and invoking a
    // private method reflectively on the proxy itself (rather than through
    // the proxy's normal dispatch) runs against the proxy's own uninitialized
    // field state, not the real bean instance's @PostConstruct-initialized
    // state, so workflowEngine is null and the call fails).
}
