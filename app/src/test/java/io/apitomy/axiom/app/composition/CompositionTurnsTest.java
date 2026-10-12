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

import java.util.List;

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
}
