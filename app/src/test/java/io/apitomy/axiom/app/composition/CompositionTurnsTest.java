package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.apitomy.flow.changeset.ContentRevision;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The AI turn lifecycle: starting, accumulating change sets, agent writes, finishing and stale retries.
 */
@QuarkusTest
class CompositionTurnsTest {

    @InjectMock
    WorkbenchSessions sessions;

    @Inject
    CompositionTurns turns;

    @Inject
    CompositionService service;

    @Inject
    ObjectMapper mapper;

    @AfterEach
    void cleanUp() {
        turns.setInterruptTimeoutForTests(null);
        QuarkusTransaction.requiringNew().call(() -> CompositionEntity.<CompositionEntity>listAll())
                .forEach(c -> turns.onSessionEvent(new WorkbenchSessionEvent(c.id, WorkbenchSessionEvent.Kind.FAILED, "cleanup")));
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionRevisionEntity.deleteAll();
            CompositionItemEntity.deleteAll();
            CompositionEntity.deleteAll();
        });
    }

    private CompositionEntity withWorkflow() {
        CompositionEntity composition = service.create("c", "goal", null, null, null);
        service.addBlank(composition.id, ConfigItemType.WORKFLOW, "ct-wf");
        return composition;
    }

    private JsonNode renameStart(String baseRevision, String name) throws Exception {
        return mapper.readTree("""
                {"id":"cs-%s","baseRevision":"%s","author":"agent:axiom-workbench","summary":"rename",
                 "ops":[{"op":"updateNode","id":"start-1","patch":{"name":"%s"}}]}""".formatted(name, baseRevision, name));
    }

    private CompositionEntity reload(long id) {
        return QuarkusTransaction.requiringNew().call(() -> CompositionEntity.<CompositionEntity>findById(id));
    }

    @Test
    void startSendsPreambleAndMarksWorking() throws Exception {
        CompositionEntity composition = withWorkflow();
        String turnId = turns.start(composition.id, "Add a triage step", List.of("start-1"), List.of());
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sessions).send(eq(composition.id), text.capture());
        assertTrue(text.getValue().contains("[Workbench context]"), text.getValue());
        assertTrue(text.getValue().endsWith("Add a triage step"), text.getValue());
        CompositionEntity saved = reload(composition.id);
        assertEquals(CompositionEntity.TURN_AI_WORKING, saved.turnState);
        assertEquals(turnId, saved.currentTurnId);
        assertNotNull(saved.lastTurnStartedOn);
        assertThrows(CompositionConflictException.class, () -> turns.start(composition.id, "again", List.of(), List.of()));
    }

    @Test
    void changeSetsAccumulateAgainstTheWorkingRevision() throws Exception {
        CompositionEntity composition = withWorkflow();
        String base = ContentRevision.of(turns.currentWorkflow(composition.id));
        String turnId = turns.start(composition.id, "rename", List.of(), List.of());

        CompositionTurns.ChangeSetOutcome first = turns.applyChangeSet(composition.id, renameStart(base, "Begin"));
        assertTrue(first.applied());
        CompositionTurns.ChangeSetOutcome stale = turns.applyChangeSet(composition.id, renameStart(base, "Again"));
        assertFalse(stale.applied());
        assertEquals("stale", stale.error().code());
        CompositionTurns.ChangeSetOutcome second = turns.applyChangeSet(composition.id, renameStart(first.contentRevision(), "Go"));
        assertTrue(second.applied());
        assertEquals("Go", turns.currentWorkflow(composition.id).path("nodes").get(0).path("name").asText());

        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));
        CompositionEntity saved = reload(composition.id);
        assertEquals(CompositionEntity.TURN_IDLE, saved.turnState);
        assertEquals(turnId, saved.pendingTurnId);
        JsonNode pending = mapper.readTree(saved.pendingChangeSet);
        assertEquals(base, pending.path("baseRevision").asText());
        assertEquals(turnId, pending.path("id").asText());
        assertEquals("agent:axiom-workbench", pending.path("author").asText());
        assertEquals(2, pending.path("ops").size());
        // Only the editor commits workflow change sets: the saved draft is unchanged by the turn.
        assertEquals("Start", service.contentOf(service.items(composition.id).get(0))
                .path("nodes").get(0).path("name").asText());
    }

    @Test
    void agentUpsertIsTaggedWithTheTurn() throws Exception {
        CompositionEntity composition = withWorkflow();
        String turnId = turns.start(composition.id, "make a tool", List.of(), List.of());
        ObjectNode tool = CompositionItems.blank(ConfigItemType.TOOL, "ct-tool", mapper);
        CompositionTurns.AgentWrite write = turns.upsert(composition.id, "tool", tool);
        assertEquals("ai", write.item().lastAuthor);
        assertEquals(turnId, write.item().lastTurnId);
        tool.put("description", "v2");
        assertEquals(turnId, turns.upsert(composition.id, "tool", tool).item().lastTurnId);
        assertEquals(1, service.items(composition.id).stream().filter(i -> "tool".equals(i.itemType)).count());
        assertThrows(IllegalArgumentException.class,
                () -> turns.upsert(composition.id, "workflow", CompositionItems.blank(ConfigItemType.WORKFLOW, "x", mapper)));
    }

    @Test
    void agentWritesRequireARunningTurn() {
        CompositionEntity composition = withWorkflow();
        assertThrows(CompositionConflictException.class, () -> turns.upsert(composition.id, "tool",
                CompositionItems.blank(ConfigItemType.TOOL, "t", mapper)));
    }

    @Test
    void failedTurnDiscardsWorkflowOpsAndNotifies() throws Exception {
        CompositionEntity composition = withWorkflow();
        String base = ContentRevision.of(turns.currentWorkflow(composition.id));
        turns.start(composition.id, "rename", List.of(), List.of());
        turns.applyChangeSet(composition.id, renameStart(base, "Begin"));
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.FAILED, "boom"));
        CompositionEntity saved = reload(composition.id);
        assertEquals(CompositionEntity.TURN_IDLE, saved.turnState);
        assertNull(saved.pendingChangeSet);
        verify(sessions).notice(eq(composition.id), org.mockito.ArgumentMatchers.startsWith(CompositionTurns.FAILED_NOTICE_PREFIX + "boom"));
    }

    @Test
    void staleResolutionRetriesOnceThenStops() throws Exception {
        CompositionEntity composition = withWorkflow();
        String base = ContentRevision.of(turns.currentWorkflow(composition.id));
        String turnId = turns.start(composition.id, "rename", List.of(), List.of());
        turns.applyChangeSet(composition.id, renameStart(base, "Begin"));
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));

        turns.resolve(composition.id, turnId, "stale");
        assertNull(reload(composition.id).pendingChangeSet);
        verify(sessions).send(eq(composition.id), org.mockito.ArgumentMatchers.endsWith(CompositionTurns.STALE_RETRY_PROMPT));
        String retryTurn = reload(composition.id).currentTurnId;
        String retryBase = ContentRevision.of(turns.currentWorkflow(composition.id));
        turns.applyChangeSet(composition.id, renameStart(retryBase, "Again"));
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));

        turns.resolve(composition.id, retryTurn, "stale");
        verify(sessions).notice(composition.id, CompositionTurns.SECOND_STALE_NOTICE);
        verify(sessions, times(2)).send(anyLong(), anyString());
    }

    @Test
    void earlyResolutionOfARetryTurnKeepsItsRetryFlag() throws Exception {
        CompositionEntity composition = withWorkflow();
        String base = ContentRevision.of(turns.currentWorkflow(composition.id));
        String turnId = turns.start(composition.id, "rename", List.of(), List.of());
        turns.applyChangeSet(composition.id, renameStart(base, "Begin"));
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));
        turns.resolve(composition.id, turnId, "stale");
        String retryTurn = reload(composition.id).currentTurnId;

        // A resolution that does not match the pending change set must not clear the retry flag.
        turns.resolve(composition.id, retryTurn, "applied");
        String retryBase = ContentRevision.of(turns.currentWorkflow(composition.id));
        turns.applyChangeSet(composition.id, renameStart(retryBase, "Again"));
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));
        turns.resolve(composition.id, retryTurn, "stale");
        verify(sessions).notice(composition.id, CompositionTurns.SECOND_STALE_NOTICE);
        verify(sessions, times(2)).send(anyLong(), anyString());
    }

    @Test
    void retryTurnWithoutOpsLeavesNoResidue() throws Exception {
        CompositionEntity composition = withWorkflow();
        String base = ContentRevision.of(turns.currentWorkflow(composition.id));
        String turnId = turns.start(composition.id, "rename", List.of(), List.of());
        turns.applyChangeSet(composition.id, renameStart(base, "Begin"));
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));
        turns.resolve(composition.id, turnId, "stale");
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));
        assertNull(reload(composition.id).pendingChangeSet);

        String next = turns.start(composition.id, "rename again", List.of(), List.of());
        String nextBase = ContentRevision.of(turns.currentWorkflow(composition.id));
        turns.applyChangeSet(composition.id, renameStart(nextBase, "Again"));
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));
        turns.resolve(composition.id, next, "stale");
        verify(sessions, never()).notice(eq(composition.id), anyString());
        verify(sessions, times(2)).send(eq(composition.id), org.mockito.ArgumentMatchers.endsWith(CompositionTurns.STALE_RETRY_PROMPT));
    }

    @Test
    void acceptedResolutionClearsPendingAndIgnoresOtherTurns() throws Exception {
        CompositionEntity composition = withWorkflow();
        String base = ContentRevision.of(turns.currentWorkflow(composition.id));
        String turnId = turns.start(composition.id, "rename", List.of(), List.of());
        turns.applyChangeSet(composition.id, renameStart(base, "Begin"));
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));
        turns.resolve(composition.id, "other-turn", "applied");
        assertNotNull(reload(composition.id).pendingChangeSet);
        turns.resolve(composition.id, turnId, "applied");
        assertNull(reload(composition.id).pendingChangeSet);
        verify(sessions, never()).notice(eq(composition.id), anyString());
        verify(sessions, atLeastOnce()).send(anyLong(), anyString());
    }

    @Test
    void sendRunsOutsideTheMonitorSoSessionEventsAreNotBlocked() throws Exception {
        CompositionEntity composition = withWorkflow();
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            sending.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return null;
        }).when(sessions).send(eq(composition.id), anyString());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> started = pool.submit(() -> turns.start(composition.id, "hi", List.of(), List.of()));
            assertTrue(sending.await(10, TimeUnit.SECONDS));
            // While send blocks (as when it waits on the session's event lock), a session event must get through.
            Future<?> delivered = pool.submit(() -> turns.onSessionEvent(new WorkbenchSessionEvent(composition.id,
                    WorkbenchSessionEvent.Kind.TURN_COMPLETE, null, -1)));
            delivered.get(5, TimeUnit.SECONDS);
            release.countDown();
            String turnId = started.get(10, TimeUnit.SECONDS);
            // The event predates the turn's message, so the turn is still running.
            assertEquals(turnId, turns.currentTurnId(composition.id));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void completionFromAnEarlierExchangeIsIgnored() throws Exception {
        CompositionEntity composition = withWorkflow();
        when(sessions.nextEventIndex(composition.id)).thenReturn(10);
        String turnId = turns.start(composition.id, "hi", List.of(), List.of());
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.TURN_COMPLETE,
                null, 9));
        assertEquals(turnId, reload(composition.id).currentTurnId);
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.TURN_COMPLETE,
                null, 11));
        assertEquals(CompositionEntity.TURN_IDLE, reload(composition.id).turnState);
    }

    @Test
    void interruptedTurnThatNeverEndsIsFailedByTheWatchdog() throws Exception {
        CompositionEntity composition = withWorkflow();
        turns.setInterruptTimeoutForTests(Duration.ofMillis(200));
        turns.start(composition.id, "hi", List.of(), List.of());
        turns.interrupt(composition.id);
        verify(sessions).interrupt(composition.id);
        long deadline = System.currentTimeMillis() + 10_000;
        while (turns.currentTurnId(composition.id) != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertNull(turns.currentTurnId(composition.id));
        assertEquals(CompositionEntity.TURN_IDLE, reload(composition.id).turnState);
        verify(sessions).notice(eq(composition.id),
                org.mockito.ArgumentMatchers.startsWith(CompositionTurns.FAILED_NOTICE_PREFIX + "interrupted"));
    }

    @Test
    void watchdogLeavesALaterTurnAlone() throws Exception {
        CompositionEntity composition = withWorkflow();
        turns.setInterruptTimeoutForTests(Duration.ofMillis(300));
        turns.start(composition.id, "hi", List.of(), List.of());
        turns.interrupt(composition.id);
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.FAILED, "stopped"));
        String next = turns.start(composition.id, "again", List.of(), List.of());
        Thread.sleep(800);
        assertEquals(next, turns.currentTurnId(composition.id));
    }

    @Test
    void sendFailureEndsTheTurnAndReportsTheAgentUnavailable() throws Exception {
        CompositionEntity composition = withWorkflow();
        doThrow(new IOException("pipe closed")).when(sessions).send(eq(composition.id), anyString());
        assertThrows(AgentUnavailableException.class, () -> turns.start(composition.id, "hi", List.of(), List.of()));
        assertEquals(CompositionEntity.TURN_IDLE, reload(composition.id).turnState);
        assertNull(turns.currentTurnId(composition.id));
    }

    private String completeTurnWithPendingChangeSet(CompositionEntity composition) throws Exception {
        String base = ContentRevision.of(turns.currentWorkflow(composition.id));
        String turnId = turns.start(composition.id, "rename", List.of(), List.of());
        turns.applyChangeSet(composition.id, renameStart(base, "Begin"));
        turns.onSessionEvent(new WorkbenchSessionEvent(composition.id, WorkbenchSessionEvent.Kind.TURN_COMPLETE, null));
        return turnId;
    }

    @Test
    void staleChangeSetFromPendingIsDiscardedWithoutRetry() throws Exception {
        CompositionEntity composition = withWorkflow();
        String turnId = completeTurnWithPendingChangeSet(composition);
        turns.resolve(composition.id, turnId, "stale", true);
        assertNull(reload(composition.id).pendingChangeSet);
        assertEquals(CompositionEntity.TURN_IDLE, reload(composition.id).turnState);
        verify(sessions).notice(composition.id, CompositionTurns.STALE_PENDING_NOTICE);
        verify(sessions, times(1)).send(anyLong(), anyString());
    }

    @Test
    void staleChangeSetDuringARunningTurnPostsANotice() throws Exception {
        CompositionEntity composition = withWorkflow();
        String turnId = completeTurnWithPendingChangeSet(composition);
        String running = turns.start(composition.id, "next", List.of(), List.of());
        turns.resolve(composition.id, turnId, "stale");
        verify(sessions).notice(composition.id, CompositionTurns.STALE_DURING_TURN_NOTICE);
        assertEquals(running, turns.currentTurnId(composition.id));
        verify(sessions, times(2)).send(anyLong(), anyString());
    }

    @Test
    void failedRetryPostsANoticeInsteadOfThrowing() throws Exception {
        CompositionEntity composition = withWorkflow();
        String turnId = completeTurnWithPendingChangeSet(composition);
        doThrow(new IOException("pipe closed")).when(sessions).send(eq(composition.id),
                org.mockito.ArgumentMatchers.endsWith(CompositionTurns.STALE_RETRY_PROMPT));
        turns.resolve(composition.id, turnId, "stale");
        assertNull(reload(composition.id).pendingChangeSet);
        assertEquals(CompositionEntity.TURN_IDLE, reload(composition.id).turnState);
        verify(sessions).notice(eq(composition.id),
                org.mockito.ArgumentMatchers.startsWith(CompositionTurns.RETRY_FAILED_NOTICE_PREFIX));
    }
}
