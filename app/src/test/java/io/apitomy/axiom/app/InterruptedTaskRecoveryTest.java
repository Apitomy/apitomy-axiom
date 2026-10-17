package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.services.WorkspaceService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for #343: tasks left {@code InProgress} by a previous process are failed at startup (and their
 * workflow runs notified), and failures during task setup release the agent lease and fail the task.
 */
@QuarkusTest
class InterruptedTaskRecoveryTest {

    @InjectMock
    AgentPool agentPool;

    @InjectMock
    WorkspaceService workspaceService;

    @InjectMock
    McpConfigGenerator mcpConfigGenerator;

    @InjectSpy
    WorkflowExecutionService workflowExecutionService;

    @Inject
    TaskExecutionService taskExecutionService;

    @BeforeEach
    void setUp() throws Exception {
        Path workspace = Files.createTempDirectory("axiom-interrupted-");
        Mockito.when(workspaceService.ensureWorkspace(ArgumentMatchers.any())).thenReturn(workspace);
    }

    @Test
    void interruptedInProgressTasksAreFailedAndWorkflowNotified() {
        Long subjectId = createSubject();
        Long inProgressId = createTask(subjectId, "InProgress", 987654321L);
        Long awaitingId = createTask(subjectId, "AwaitingInput", null);

        taskExecutionService.failInterruptedTasks();

        TaskEntity failed = load(inProgressId);
        assertEquals("Failed", failed.status);
        assertTrue(failed.output.contains("Interrupted by a server restart"), failed.output);
        assertNotNull(failed.completedOn);
        Mockito.verify(workflowExecutionService).onTaskCompleted(inProgressId);

        TaskEntity awaiting = load(awaitingId);
        assertEquals("AwaitingInput", awaiting.status);
        assertNull(awaiting.completedOn);
        Mockito.verify(agentPool).releaseOrphanedLeases();
    }

    @Test
    void setupFailureAfterLeaseReleasesLeaseAndFailsTask() {
        Long subjectId = createSubject();
        Long taskId = createTask(subjectId, "Pending", null);
        Agent agent = Mockito.mock(Agent.class);
        AgentLease lease = new AgentLease(42L, "fake-agent", agent);
        Mockito.when(agentPool.tryLease(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Optional.of(lease));
        Mockito.when(mcpConfigGenerator.generateMcpConfig(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenThrow(new IllegalStateException("mcp config exploded"));

        taskExecutionService.executeTask(load(taskId));

        Mockito.verify(agentPool, Mockito.times(1)).release(lease);
        Mockito.verify(agent, Mockito.never()).execute(ArgumentMatchers.any());
        TaskEntity task = load(taskId);
        assertEquals("Failed", task.status);
        assertTrue(task.output.contains("mcp config exploded"), task.output);
    }

    @Test
    void synchronousLaunchFailureReleasesLeaseOnce() {
        Long subjectId = createSubject();
        Long taskId = createTask(subjectId, "Pending", null);
        Agent agent = Mockito.mock(Agent.class);
        Mockito.when(agent.execute(ArgumentMatchers.any())).thenThrow(new IllegalStateException("launch exploded"));
        AgentLease lease = new AgentLease(43L, "fake-agent", agent);
        Mockito.when(agentPool.tryLease(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Optional.of(lease));

        taskExecutionService.executeTask(load(taskId));

        Mockito.verify(agentPool, Mockito.times(1)).release(lease);
        TaskEntity task = load(taskId);
        assertEquals("Failed", task.status);
        assertTrue(task.output.contains("launch exploded"), task.output);
    }

    @Test
    void interruptedTaskOfCancelledRunIsFailed() {
        long[] ids = createSubjectAndWaitingRun();
        QuarkusTransaction.requiringNew().run(() -> workflowExecutionService.cancelWorkflowRun(ids[1]));
        assertEquals("cancelled", loadRun(ids[1]).status);
        Long taskId = createTask(ids[0], "InProgress", ids[1]);

        taskExecutionService.failInterruptedTasks();

        TaskEntity task = load(taskId);
        assertEquals("Failed", task.status);
        assertTrue(task.output.contains("Interrupted by a server restart"), task.output);
        assertEquals("cancelled", loadRun(ids[1]).status);
    }

    @Test
    void interruptedTaskOfFailedRunIsFailed() {
        long[] ids = createSubjectAndWaitingRun();
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunEntity run = WorkflowRunEntity.findById(ids[1]);
            run.status = "failed";
            run.completedOn = Instant.now();
        });
        Long taskId = createTask(ids[0], "InProgress", ids[1]);

        taskExecutionService.failInterruptedTasks();

        TaskEntity task = load(taskId);
        assertEquals("Failed", task.status);
        assertTrue(task.output.contains("Interrupted by a server restart"), task.output);
    }

    @Test
    void cancellingRunFailsAllActiveTasks() {
        long[] ids = createSubjectAndWaitingRun();
        Long inProgressId = createTask(ids[0], "InProgress", ids[1]);
        Long pendingId = createTask(ids[0], "Pending", ids[1]);

        QuarkusTransaction.requiringNew().run(() -> workflowExecutionService.cancelWorkflowRun(ids[1]));

        for (Long taskId : List.of(inProgressId, pendingId)) {
            TaskEntity task = load(taskId);
            assertEquals("Failed", task.status, "task " + taskId);
            assertNotNull(task.completedOn);
        }
    }

    @Test
    void interruptedScriptTaskIsFailed() {
        Long subjectId = createSubject();
        String actionType = "interrupted-script-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            ActionTypeEntity at = new ActionTypeEntity();
            at.name = actionType;
            at.executionMode = "script";
            at.scriptTemplate = "#!/usr/bin/env bash\necho '{}'\n";
            at.persist();
        });
        Long taskId = createTask(subjectId, "InProgress", null);
        QuarkusTransaction.requiringNew().run(() -> {
            TaskEntity task = TaskEntity.findById(taskId);
            task.actionType = actionType;
        });

        taskExecutionService.failInterruptedTasks();

        TaskEntity task = load(taskId);
        assertEquals("Failed", task.status);
        assertTrue(task.output.contains("Interrupted by a server restart"), task.output);
    }

    private static final String WAIT_CONTENT = """
        {
            "id": "interrupted-wait-wf",
            "name": "Interrupted Wait WF",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start", "config": {}, "position": {"x": 0, "y": 0}},
                {"id": "w1", "type": "wait", "name": "Wait", "config": {"duration": "PT5M"},
                 "position": {"x": 0, "y": 100}},
                {"id": "e1", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 200}}
            ],
            "edges": [
                {"id": "edge1", "source": "s1", "target": "w1", "priority": 0, "isDefault": true},
                {"id": "edge2", "source": "w1", "target": "e1", "priority": 0, "isDefault": true}
            ]
        }
        """;

    /** Creates a subject and a real workflow run parked on a wait node: {subjectId, runId}. */
    private long[] createSubjectAndWaitingRun() {
        Long subjectId = createSubject();
        Long definitionId = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = "interrupted-wf-" + UUID.randomUUID();
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
            return def.id;
        });
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, definitionId));
        assertEquals("waiting", run.status);
        return new long[] { subjectId, run.id };
    }

    private WorkflowRunEntity loadRun(long runId) {
        return QuarkusTransaction.requiringNew().call(() -> WorkflowRunEntity.<WorkflowRunEntity>findById(runId));
    }

    private TaskEntity load(Long taskId) {
        return QuarkusTransaction.requiringNew().call(() -> TaskEntity.<TaskEntity>findById(taskId));
    }

    private Long createSubject() {
        return QuarkusTransaction.requiringNew().call(() -> {
            SubjectEntity subject = new SubjectEntity();
            subject.name = "interrupted-" + UUID.randomUUID();
            subject.type = "test-interrupted";
            subject.ref = "https://example.com/" + UUID.randomUUID();
            subject.createdOn = Instant.now();
            subject.updatedOn = Instant.now();
            subject.persist();
            return subject.id;
        });
    }

    private Long createTask(Long subjectId, String status, Long workflowRunId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            TaskEntity task = new TaskEntity();
            task.subjectId = subjectId;
            task.actionType = "interrupted-action";
            task.createdBy = "test";
            task.status = status;
            task.createdOn = Instant.now();
            task.workflowRunId = workflowRunId;
            task.persist();
            return task.id;
        });
    }
}
