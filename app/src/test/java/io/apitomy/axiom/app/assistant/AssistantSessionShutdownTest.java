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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Destroying a session must not depend on an enclosing transaction, and must not touch the database once
 * shutdown has begun: H2's JVM shutdown hook may already have closed it.
 */
@QuarkusTest
class AssistantSessionShutdownTest {

    @InjectMock
    InteractiveSessionDriverFactory driverFactory;

    @Inject
    AssistantSessionManager manager;

    @Inject
    ObjectMapper mapper;

    @AfterEach
    void reset() {
        manager.setShuttingDown(false);
    }

    private AssistantSession createWithOneTurn() throws IOException {
        when(driverFactory.createDriver(any())).thenAnswer(i -> {
            InteractiveSessionDriver driver = mock(InteractiveSessionDriver.class);
            when(driver.getStatus()).thenReturn(AssistantSession.Status.RUNNING);
            return driver;
        });
        AssistantSession session = manager.createSession("shutdown", "axiom-workbench", null, 7L, List.of());
        session.handleDriverEvent(new SseEvent("turn_complete", mapper.createObjectNode()
                .put("costUsd", 0.5).put("inputTokens", 10).put("outputTokens", 20).put("durationMs", 30)));
        assertEquals(1, session.getTurnCount());
        return session;
    }

    private long assistantUsageCount() {
        return QuarkusTransaction.requiringNew().call(
                () -> AiUsageEntity.count("invocationType", "assistant-session"));
    }

    /**
     * Without an enclosing transaction, usage is still recorded in its own transaction.
     */
    @Test
    void destroyRecordsUsageInItsOwnTransaction() throws IOException {
        AssistantSession session = createWithOneTurn();
        long before = assistantUsageCount();
        manager.destroySession(session.getId());
        assertEquals(before + 1, assistantUsageCount());
    }

    /**
     * During shutdown, usage is not persisted.
     */
    @Test
    void destroyDuringShutdownSkipsUsage() throws IOException {
        AssistantSession session = createWithOneTurn();
        long before = assistantUsageCount();
        manager.setShuttingDown(true);
        manager.destroySession(session.getId());
        assertEquals(before, assistantUsageCount());
    }
}
