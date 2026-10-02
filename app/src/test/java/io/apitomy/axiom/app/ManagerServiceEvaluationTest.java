package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.manager.ManagerEvaluationResult;
import io.apitomy.axiom.manager.ManagerService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real {@link ManagerService} against a mocked agent: failures must be
 * reported as failed results (#417), and the Manager's activity and AI usage rows must
 * carry the event and trace IDs (#416).
 */
@QuarkusTest
class ManagerServiceEvaluationTest {

    @InjectMock
    AgentRegistry agentRegistry;

    @Inject
    ManagerService managerService;

    private final Agent agent = Mockito.mock(Agent.class);
    private StreamEventEntity event;

    @BeforeEach
    void setUp() {
        Mockito.when(agentRegistry.getAgent(ArgumentMatchers.any())).thenReturn(agent);
        event = new StreamEventEntity();
        event.id = UUID.randomUUID();
        event.source = "github";
        event.type = "issue.created";
        event.ref = "https://github.com/test-org/mgr-eval/issues/" + event.id;
        event.payload = "{}";
        event.timestamp = Instant.now();
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            ActivityLogEntity.delete("eventId", event.id);
            AiUsageEntity.delete("eventId", event.id);
        });
    }

    @Test
    void engineFailureIsFailedResult() {
        agentReturns(AgentResult.failure("rate limited"));

        ManagerEvaluationResult result = managerService.evaluateStreamEvent(event, UUID.randomUUID());

        assertTrue(result.failed());
        assertTrue(result.errorMessage().contains("rate limited"));
        assertEquals("manager-error", activityRow(result.activityLogId()).entryType);
    }

    @Test
    void unparseableOutputIsFailedResult() {
        agentReturns(AgentResult.success("I could not decide"));

        ManagerEvaluationResult result = managerService.evaluateStreamEvent(event, UUID.randomUUID());

        assertTrue(result.failed());
        assertTrue(result.errorMessage().contains("could not be parsed"));
    }

    @Test
    void agentExceptionIsFailedResult() {
        Mockito.when(agent.executeWithSchema(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        ManagerEvaluationResult result = managerService.evaluateStreamEvent(event, UUID.randomUUID());

        assertTrue(result.failed());
        assertTrue(result.errorMessage().contains("boom"));
    }

    @Test
    void emptyDecisionArrayIsSuccessWithNoDecisions() {
        agentReturns(AgentResult.success("{\"decisions\":[]}"));

        ManagerEvaluationResult result = managerService.evaluateStreamEvent(event, UUID.randomUUID());

        assertFalse(result.failed());
        assertTrue(result.decisions().isEmpty());
    }

    @Test
    void activityAndAiUsageCarryEventAndTrace() {
        agentReturns(AgentResult.success(
                "{\"decisions\":[{\"decision\":\"ignore\",\"confidence\":0.9,\"reasoning\":\"r\"}]}"));
        UUID traceId = UUID.randomUUID();

        ManagerEvaluationResult result = managerService.evaluateStreamEvent(event, traceId);

        assertFalse(result.failed());
        assertEquals(1, result.decisions().size());
        ActivityLogEntity row = activityRow(result.activityLogId());
        assertEquals("manager-evaluated", row.entryType);
        assertEquals(event.id, row.eventId);
        assertEquals(traceId, row.traceId);
        List<AiUsageEntity> usage = QuarkusTransaction.requiringNew().call(() ->
                AiUsageEntity.<AiUsageEntity>list("eventId", event.id));
        assertEquals(1, usage.size());
        assertEquals(traceId, usage.get(0).traceId);
    }

    private void agentReturns(AgentResult result) {
        Mockito.when(agent.executeWithSchema(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(result));
    }

    private ActivityLogEntity activityRow(Long id) {
        assertNotNull(id, "Result must reference its activity row");
        ActivityLogEntity row = QuarkusTransaction.requiringNew().call(() ->
                ActivityLogEntity.<ActivityLogEntity>findById(id));
        assertNotNull(row);
        return row;
    }
}
