package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriverFactory;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Assistant session usage is persisted after each completed turn (GH #517), so nothing is written when a
 * session is destroyed and no usage is lost on shutdown or crash.
 */
@QuarkusTest
class AssistantSessionUsageTest {

    @InjectMock
    InteractiveSessionDriverFactory driverFactory;

    @Inject
    AssistantSessionManager manager;

    @Inject
    ObjectMapper mapper;

    private AssistantSession create() throws IOException {
        when(driverFactory.createDriver(any())).thenAnswer(i -> {
            InteractiveSessionDriver driver = mock(InteractiveSessionDriver.class);
            when(driver.getStatus()).thenReturn(AssistantSession.Status.RUNNING);
            return driver;
        });
        return manager.createSession("usage", "axiom-workbench", null, 7L, List.of());
    }

    private void completeTurn(AssistantSession session, double cumulativeCost, long in, long out, long ms) {
        session.handleDriverEvent(new SseEvent("turn_complete", mapper.createObjectNode()
                .put("costUsd", cumulativeCost).put("inputTokens", in).put("outputTokens", out)
                .put("durationMs", ms)));
    }

    private long assistantUsageCount() {
        return QuarkusTransaction.requiringNew().call(
                () -> AiUsageEntity.count("invocationType", "assistant-session"));
    }

    private double[] assistantUsageTotals() {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<AiUsageEntity> rows = AiUsageEntity.list("invocationType", "assistant-session");
            double[] totals = new double[4];
            for (AiUsageEntity row : rows) {
                totals[0] += row.costUsd;
                totals[1] += row.inputTokens;
                totals[2] += row.outputTokens;
                totals[3] += row.durationMs;
            }
            return totals;
        });
    }

    /**
     * Each completed turn writes a usage row immediately.
     */
    @Test
    void usageRecordedAfterEachTurn() throws IOException {
        AssistantSession session = create();
        long before = assistantUsageCount();
        completeTurn(session, 0.5, 10, 20, 30);
        assertEquals(before + 1, assistantUsageCount());
        manager.destroySession(session.getId());
    }

    /**
     * Summed rows equal the session's cumulative totals across turns and a conversation reset.
     */
    @Test
    void usageTotalsAreCumulativelyCorrect() throws IOException {
        AssistantSession session = create();
        double[] before = assistantUsageTotals();
        completeTurn(session, 0.5, 10, 20, 30);
        completeTurn(session, 1.25, 5, 7, 11);
        session.handleDriverEvent(new SseEvent("conversation_reset", mapper.createObjectNode()));
        completeTurn(session, 0.25, 1, 2, 3);
        double[] after = assistantUsageTotals();
        assertEquals(1.5, after[0] - before[0], 1e-9);
        assertEquals(session.getTotalCostUsd(), after[0] - before[0], 1e-9);
        assertEquals(16, after[1] - before[1], 1e-9);
        assertEquals(29, after[2] - before[2], 1e-9);
        assertEquals(44, after[3] - before[3], 1e-9);
        manager.destroySession(session.getId());
    }

    /**
     * Destroying a session writes nothing.
     */
    @Test
    void destroyDoesNotWriteUsage() throws IOException {
        AssistantSession session = create();
        completeTurn(session, 0.5, 10, 20, 30);
        long before = assistantUsageCount();
        manager.destroySession(session.getId());
        assertEquals(before, assistantUsageCount());
    }

    /**
     * Usage rows carry the model the engine reported in its session init, even when the session uses the
     * engine's default model.
     */
    @Test
    void usageRecordsTheReportedModel() throws IOException {
        AssistantSession session = create();
        session.handleDriverEvent(new SseEvent("session_init", mapper.createObjectNode()
                .put("model", "claude-reported-model")));
        completeTurn(session, 0.5, 10, 20, 30);
        String model = QuarkusTransaction.requiringNew().call(() -> AiUsageEntity.<AiUsageEntity>find(
                "invocationType = ?1 order by id desc", "assistant-session").firstResult().model);
        assertEquals("claude-reported-model", model);
        manager.destroySession(session.getId());
    }
}
