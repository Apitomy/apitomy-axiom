package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
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
