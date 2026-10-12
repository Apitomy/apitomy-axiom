package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.app.assistant.AssistantSessionManager;
import io.apitomy.axiom.app.assistant.HistoryJournal;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriver;
import io.apitomy.axiom.app.assistant.runtime.InteractiveSessionDriverFactory;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Binding compositions to workbench sessions, restoring history and translating session events.
 */
@QuarkusTest
class WorkbenchSessionsTest {

    /** Collects events fired during the test. */
    @Singleton
    static class Recorder {
        final List<WorkbenchSessionEvent> events = new CopyOnWriteArrayList<>();

        void on(@Observes WorkbenchSessionEvent event) {
            events.add(event);
        }
    }

    @InjectMock
    InteractiveSessionDriverFactory driverFactory;

    @Inject
    WorkbenchSessions sessions;

    @Inject
    CompositionService compositions;

    @Inject
    AssistantSessionManager manager;

    @Inject
    Recorder recorder;

    @Inject
    ObjectMapper mapper;

    @AfterEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().call(() -> CompositionEntity.<CompositionEntity>listAll()).forEach(c -> sessions.discard(c.id));
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
        recorder.events.clear();
    }

    private void mockDriver() {
        try {
            stubDriver();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubDriver() throws IOException {
        when(driverFactory.createDriver(any())).thenAnswer(i -> {
            InteractiveSessionDriver driver = mock(InteractiveSessionDriver.class);
            when(driver.getStatus()).thenReturn(AssistantSession.Status.RUNNING);
            return driver;
        });
    }

    @Test
    void ensureBindsOneSessionPerComposition() {
        mockDriver();
        CompositionEntity composition = compositions.create("c", "goal", null, null, null);
        AssistantSession first = sessions.ensure(composition.id);
        assertSame(first, sessions.ensure(composition.id));
        assertEquals(first.getId(), compositions.get(composition.id).assistantSessionId);
        assertNull(sessions.takeRecap(composition.id));
    }

    @Test
    void turnCompleteAndErrorsAreTranslated() {
        mockDriver();
        CompositionEntity composition = compositions.create("c", null, null, null, null);
        AssistantSession session = sessions.ensure(composition.id);
        session.addEvent(new io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent("turn_complete", mapper.createObjectNode()));
        session.addEvent(new io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent("session_error",
                mapper.createObjectNode().put("name", "ProviderRetry").put("message", "retrying")));
        session.addEvent(new io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent("session_error",
                mapper.createObjectNode().put("name", "Boom").put("message", "broken")));
        assertEquals(List.of(WorkbenchSessionEvent.Kind.TURN_COMPLETE, WorkbenchSessionEvent.Kind.FAILED),
                recorder.events.stream().map(WorkbenchSessionEvent::kind).toList());
        assertEquals("broken", recorder.events.get(1).detail());
    }

    @Test
    void restartRestoresHistoryAndArmsRecap() throws IOException {
        mockDriver();
        CompositionEntity composition = compositions.create("c", "triage issues", null, null, null);
        AssistantSession first = sessions.ensure(composition.id);
        first.sendMessage("build me a triage workflow");
        manager.journal(first.getId()).flush();
        // Simulate a server restart: the process goes away but the directory is kept.
        manager.destroySession(first.getId(), true);
        assertTrue(Files.isDirectory(AssistantSessionManager.sessionDirectory(first.getId())));

        AssistantSession second = sessions.ensure(composition.id);
        assertNotEquals(first.getId(), second.getId());
        assertTrue(second.getEventHistory().stream()
                .anyMatch(e -> "build me a triage workflow".equals(e.data().path("content").asText())));
        assertFalse(Files.exists(AssistantSessionManager.sessionDirectory(first.getId())));
        String recap = sessions.takeRecap(composition.id);
        assertNotNull(recap);
        assertTrue(recap.contains("triage issues"), recap);
        assertTrue(recap.contains("build me a triage workflow"), recap);
        assertNull(sessions.takeRecap(composition.id));
        String welcome = first.getEventHistory().stream()
                .filter(e -> "assistant_text".equals(e.type())).map(e -> e.data().path("text").asText())
                .findFirst().orElse(null);
        if (welcome != null) {
            long count = second.getEventHistory().stream()
                    .filter(e -> welcome.equals(e.data().path("text").asText())).count();
            assertTrue(count <= 1, "welcome message repeated " + count + " times");
        }
    }

    @Test
    void noticeIsVisibleButNotSentToAgent() {
        mockDriver();
        CompositionEntity composition = compositions.create("c", null, null, null, null);
        AssistantSession session = sessions.ensure(composition.id);
        sessions.notice(composition.id, "The workflow changed while I worked.");
        assertTrue(session.getEventHistory().stream().anyMatch(e -> e.data().path("notice").asBoolean()));
    }
}
