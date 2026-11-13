package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentMcpManager;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.quarkus.test.junit.QuarkusMock;
import io.apitomy.axiom.agents.spi.AgentRequest;
import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.app.AgentLease;
import io.apitomy.axiom.app.AgentPool;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunSummaryEntity;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/** Tests background generation of run summaries with a mocked agent. */
@QuarkusTest
class RunSummaryGenerationTest {

    @Inject
    RunSummaryService service;

    @InjectMock
    AgentPool agentPool;

    @InjectMock
    RunSummaryMcp runSummaryMcp;

    Agent agent;

    @BeforeEach
    void setUp() throws Exception {
        agent = Mockito.mock(Agent.class);
        when(agent.getType()).thenReturn("claude-code");
        when(agentPool.tryLease(any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(new AgentLease(null, "fake-agent", agent)));
        Path config = Files.createTempFile("run-summary-mcp", ".json");
        when(runSummaryMcp.writeConfigFile(anyLong())).thenReturn(config);
    }

    private long pendingRun() {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.title = "Gen";
            run.subjectId = -1L;
            run.definitionId = -1L;
            run.definitionVersion = 1;
            run.instanceState = "{}";
            run.status = "completed";
            run.startedOn = Instant.now();
            run.completedOn = Instant.now();
            run.persist();
            WorkflowRunSummaryEntity.newPending(run, WorkflowRunSummaryEntity.AUTO).persist();
            return run.id;
        });
    }

    private WorkflowRunSummaryEntity row(long id) {
        return QuarkusTransaction.requiringNew().call(() -> WorkflowRunSummaryEntity.findById(id));
    }

    private String runStatus(long id) {
        return QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunEntity.<WorkflowRunEntity>findById(id).status);
    }

    private void claimAndGenerate(long id) {
        assertTrue(service.claimPending(50).contains(id));
        service.generate(id);
    }

    @Test
    void successStoresSummaryAndRecordsUsage() {
        when(agent.execute(any())).thenReturn(CompletableFuture.completedFuture(new AgentResult(true,
                "{\"headline\":\"Done it\",\"body\":\"- a\"}", null, null, null, 0.01, 10L, 5L,
                "claude-code", "haiku")));
        long id = pendingRun();
        claimAndGenerate(id);
        WorkflowRunSummaryEntity s = row(id);
        assertEquals(WorkflowRunSummaryEntity.READY, s.status);
        assertEquals("Done it", s.headline);
        assertEquals("haiku", s.model);
        assertEquals("completed", runStatus(id));
        long usage = QuarkusTransaction.requiringNew().call(() ->
                AiUsageEntity.count("workflowRunId = ?1 and invocationType = 'run-summary'", id));
        assertEquals(1L, usage);
        ArgumentCaptor<AgentRequest> req = ArgumentCaptor.forClass(AgentRequest.class);
        Mockito.verify(agent).execute(req.capture());
        assertEquals(List.of(
                "mcp__axiom-run-summary__get_run",
                "mcp__axiom-run-summary__get_task",
                "mcp__axiom-run-summary__get_task_log",
                "mcp__axiom-run-summary__get_trace"), RunSummaryMcp.ALLOWED_TOOLS);
        assertEquals(RunSummaryMcp.ALLOWED_TOOLS, req.getValue().getAllowedTools());
        assertEquals(10, req.getValue().getMaxSteps());
        Mockito.verify(runSummaryMcp).cleanup(id);
    }

    @Test
    void openCodeSummaryDeniesBuiltInTools() {
        when(agent.getType()).thenReturn("opencode");
        AgentMcpManager mcpManager = Mockito.mock(AgentMcpManager.class);
        AgentRegistry agentRegistry = Mockito.mock(AgentRegistry.class);
        when(agentRegistry.getMcpManager("opencode")).thenReturn(mcpManager);
        // OpenCode MCP registration reads tool and server definitions from the database.
        AtomicReference<Boolean> inTransaction = new AtomicReference<>();
        when(mcpManager.configureMcpServers(any(), any(), any())).thenAnswer(inv -> {
            inTransaction.set(QuarkusTransaction.isActive());
            return null;
        });
        QuarkusMock.installMockForType(agentRegistry, AgentRegistry.class);
        when(agent.execute(any())).thenReturn(CompletableFuture.completedFuture(
                AgentResult.success("{\"headline\":\"Done it\",\"body\":\"- a\"}")));
        long id = pendingRun();
        claimAndGenerate(id);
        ArgumentCaptor<AgentRequest> req = ArgumentCaptor.forClass(AgentRequest.class);
        Mockito.verify(agent).execute(req.capture());
        List<String> denied = req.getValue().getDisallowedTools();
        for (String tool : List.of("Bash", "Read", "Write", "Edit", "Glob", "Grep", "WebFetch", "WebSearch",
                "Task", "list", "patch", "skill")) {
            assertTrue(denied != null && denied.contains(tool), "should deny " + tool + ": " + denied);
        }
        Mockito.verify(mcpManager).configureMcpServers(id, java.util.Map.of(), RunSummaryMcp.ALLOWED_TOOLS);
        assertEquals(Boolean.TRUE, inTransaction.get(), "MCP registration must run in a transaction");
    }

    @Test
    void badOutputRetriesThenFails() {
        when(agent.execute(any())).thenReturn(CompletableFuture.completedFuture(AgentResult.success("nope")));
        long id = pendingRun();
        claimAndGenerate(id);
        assertEquals(WorkflowRunSummaryEntity.PENDING, row(id).status);
        claimAndGenerate(id);
        WorkflowRunSummaryEntity s = row(id);
        assertEquals(WorkflowRunSummaryEntity.FAILED, s.status);
        assertTrue(s.error.contains("JSON"));
        assertEquals("completed", runStatus(id));
    }

    @Test
    void noLeaseLeavesRowPending() {
        when(agentPool.tryLease(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        long id = pendingRun();
        claimAndGenerate(id);
        WorkflowRunSummaryEntity s = row(id);
        assertEquals(WorkflowRunSummaryEntity.PENDING, s.status);
        assertEquals(0, s.attempts);
    }

    @Test
    void recoveryResetsGeneratingRows() {
        long id = pendingRun();
        assertTrue(service.claimPending(50).contains(id));
        assertEquals(WorkflowRunSummaryEntity.GENERATING, row(id).status);
        assertTrue(service.recoverInterrupted() >= 1);
        assertEquals(WorkflowRunSummaryEntity.PENDING, row(id).status);
    }

    private RunSummaryService target() {
        return ClientProxy.unwrap(service);
    }

    @Test
    void timeoutCancelsTheAgentAndCountsAnAttempt() {
        when(agent.execute(any())).thenReturn(new CompletableFuture<>());
        RunSummaryService target = target();
        int timeout = target.timeoutSeconds;
        long grace = target.waitGraceSeconds;
        target.timeoutSeconds = 0;
        target.waitGraceSeconds = 1;
        try {
            long id = pendingRun();
            claimAndGenerate(id);
            ArgumentCaptor<AgentRequest> req = ArgumentCaptor.forClass(AgentRequest.class);
            Mockito.verify(agent).execute(req.capture());
            assertEquals("run-summary-" + id, req.getValue().getExecutionId());
            Mockito.verify(agent).cancel("run-summary-" + id);
            WorkflowRunSummaryEntity s = row(id);
            assertEquals(WorkflowRunSummaryEntity.PENDING, s.status);
            assertEquals(1, s.attempts);
            Mockito.verify(agentPool).release(any());
        } finally {
            target.timeoutSeconds = timeout;
            target.waitGraceSeconds = grace;
        }
    }

    @Test
    void interruptionCancelsTheAgentAndDoesNotCountAnAttempt() throws Exception {
        CompletableFuture<AgentResult> never = new CompletableFuture<>();
        when(agent.execute(any())).thenReturn(never);
        long id = pendingRun();
        assertTrue(service.claimPending(50).contains(id));
        AtomicReference<Boolean> flagAfter = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            service.generate(id);
            flagAfter.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        Mockito.verify(agent, Mockito.timeout(10_000)).execute(any());
        worker.interrupt();
        worker.join(10_000);
        assertFalse(worker.isAlive());
        Mockito.verify(agent).cancel("run-summary-" + id);
        assertTrue(never.isCancelled());
        assertTrue(flagAfter.get());
        WorkflowRunSummaryEntity s = row(id);
        assertEquals(WorkflowRunSummaryEntity.PENDING, s.status);
        assertEquals(0, s.attempts);
    }

    @Test
    void leaseErrorResetsToPendingWithoutAnAttempt() {
        when(agentPool.tryLease(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        long id = pendingRun();
        claimAndGenerate(id);
        WorkflowRunSummaryEntity s = row(id);
        assertEquals(WorkflowRunSummaryEntity.PENDING, s.status);
        assertEquals(0, s.attempts);
    }
}
