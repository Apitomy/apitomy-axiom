package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowWaitEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Exercises {@link WorkflowWaitScheduler} directly (bypassing the
 * {@code @Scheduled} trigger, which is disabled in the test profile — see
 * {@code %test.axiom.workflow.wait-poll-interval=off}), verifying it only
 * resumes waits whose {@code resumeAt} has elapsed, and that resuming a wait
 * deletes its row and advances the owning run.
 */
@QuarkusTest
class WorkflowWaitSchedulerTest {

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    WorkflowWaitScheduler scheduler;

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

    @Test
    void findDueWaitIdsOnlyReturnsElapsedWaits() {
        long[] ids = createProjectAndDefinition("Wait Scheduler Project");
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(ids[0], ids[1]));

        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowWaitEntity wait = WorkflowWaitEntity.find("runId", run.id).firstResult();
            // Backdate resumeAt into the future so it's not yet due.
            wait.resumeAt = Instant.now().plus(1, ChronoUnit.HOURS);
        });

        java.util.List<Long> due =
                QuarkusTransaction.requiringNew().call(() -> scheduler.findDueWaitIds());
        assertEquals(0, due.stream().filter(id -> isForRun(id, run.id)).count(),
                "A wait not yet due should not be returned");

        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowWaitEntity wait = WorkflowWaitEntity.find("runId", run.id).firstResult();
            wait.resumeAt = Instant.now().minus(1, ChronoUnit.MINUTES);
        });

        java.util.List<Long> dueNow =
                QuarkusTransaction.requiringNew().call(() -> scheduler.findDueWaitIds());
        assertEquals(1, dueNow.stream().filter(id -> isForRun(id, run.id)).count(),
                "A wait whose resumeAt has elapsed should be returned");
    }

    @Test
    void resumeWaitDeletesRowAndAdvancesRunToCompleted() {
        long[] ids = createProjectAndDefinition("Wait Resume Project");
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(ids[0], ids[1]));

        long waitId = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowWaitEntity wait = WorkflowWaitEntity.find("runId", run.id).firstResult();
            assertNotNull(wait);
            wait.resumeAt = Instant.now().minus(1, ChronoUnit.MINUTES);
            return wait.id;
        });

        QuarkusTransaction.requiringNew().run(() -> scheduler.resumeWait(waitId));

        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(WorkflowWaitEntity.findById(waitId),
                    "Resuming a wait should delete its row");
            WorkflowRunEntity completed = WorkflowRunEntity.findById(run.id);
            assertEquals("completed", completed.status,
                    "Run should advance to completed after the wait resumes");
        });

        // Resuming again (e.g. a retried/duplicate invocation) is a no-op.
        QuarkusTransaction.requiringNew().run(() -> scheduler.resumeWait(waitId));
    }

    private boolean isForRun(long waitId, long runId) {
        WorkflowWaitEntity wait = WorkflowWaitEntity.findById(waitId);
        return wait != null && wait.runId == runId;
    }

    private long[] createProjectAndDefinition(String projectName) {
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
            def.content = WAIT_CONTENT;
            def.currentVersion = 1;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();

            WorkflowDefinitionVersionEntity version = new WorkflowDefinitionVersionEntity();
            version.definitionId = def.id;
            version.version = 1;
            version.content = WAIT_CONTENT;
            version.createdOn = Instant.now();
            version.persist();

            return new long[] { project.id, def.id };
        });
    }
}
