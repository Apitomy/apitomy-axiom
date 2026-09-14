package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
