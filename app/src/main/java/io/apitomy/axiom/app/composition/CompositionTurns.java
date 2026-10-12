package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.app.validation.ConfigProblem;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.entities.CompositionRevisionEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.flow.changeset.ChangeSetError;
import io.apitomy.flow.changeset.ChangeSetResult;
import io.apitomy.flow.changeset.ChangeSets;
import io.apitomy.flow.changeset.ContentRevision;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs AI turns on a composition. A turn accumulates workflow change sets in memory against a working copy of
 * the workflow; when the turn completes they are combined into one pending change set that the editor applies.
 * Action types and tools written by the agent are saved directly, tagged with the turn id. The agent is never
 * called while a transaction is open.
 */
@ApplicationScoped
public class CompositionTurns {

    /** The author of change sets produced by the workbench agent. */
    public static final String AUTHOR = "agent:axiom-workbench";

    /** The prompt sent when a turn's change set could not be applied because the workflow changed. */
    public static final String STALE_RETRY_PROMPT = "Your workflow changes from the last turn could not be applied "
            + "because the user changed the workflow while you were working. Call get_composition to see the current "
            + "workflow, then resubmit the workflow changes that still make sense.";

    /** The notice posted when a stale retry is itself stale. */
    public static final String SECOND_STALE_NOTICE = "The workflow changed again before my changes could be "
            + "applied, so I stopped. Ask me to try again when you are ready.";

    /** The start of the notice posted when a turn fails. */
    public static final String FAILED_NOTICE_PREFIX = "The AI turn ended with an error: ";

    private static final String FAILED_NOTICE_SUFFIX = " Workflow changes from this turn were discarded; action type "
            + "and tool changes were kept (use Undo AI turn to revert them).";

    /**
     * The result of applying a change set to the turn's working workflow.
     *
     * @param applied whether the change set was applied
     * @param contentRevision the working revision after applying, or null
     * @param problems validation problems of the new working workflow (empty when not applied)
     * @param error why the change set was rejected, or null
     */
    public record ChangeSetOutcome(boolean applied, String contentRevision, List<ConfigProblem> problems,
                                   ChangeSetError error) {
    }

    /**
     * The result of an agent write.
     *
     * @param item the saved item
     * @param problems its validation problems
     */
    public record AgentWrite(CompositionItemEntity item, List<ConfigProblem> problems) {
    }

    private static final class Turn {
        final String turnId;
        Long workflowItemId;
        String workflowName;
        String baseRevision;
        JsonNode baseContent;
        JsonNode working;
        final ArrayNode ops;

        Turn(String turnId, ArrayNode ops) {
            this.turnId = turnId;
            this.ops = ops;
        }
    }

    private final Map<Long, Turn> turns = new ConcurrentHashMap<>();
    private final Set<String> staleRetryTurnIds = ConcurrentHashMap.newKeySet();

    @Inject
    WorkbenchSessions sessions;

    @Inject
    TurnPreamble preamble;

    @Inject
    CompositionService service;

    @Inject
    CompositionValidator validator;

    @Inject
    ObjectMapper mapper;

    @Inject
    Event<SseEvent> sseEvents;

    /**
     * Starts an AI turn. Locks the composition row, marks it {@code ai-working}, fires
     * {@code compositionTurn(ai-working)} after commit and then sends the preamble and message to the agent.
     *
     * @param compositionId the composition id
     * @param message the user's message
     * @param nodeIds the selected node ids
     * @param edgeIds the selected edge ids
     * @return the new turn id
     * @throws CompositionConflictException if a turn is already running
     * @throws IllegalArgumentException if the message is blank
     * @throws IllegalStateException if the message could not be sent
     */
    public synchronized String start(long compositionId, String message, List<String> nodeIds, List<String> edgeIds) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("'message' is required");
        }
        String turnId = UUID.randomUUID().toString();
        String context = QuarkusTransaction.requiringNew().call(() -> {
            CompositionEntity composition = CompositionEntity.findById(compositionId, LockModeType.PESSIMISTIC_WRITE);
            if (composition == null) {
                throw new CompositionNotFoundException("Composition not found: " + compositionId);
            }
            if (turns.containsKey(compositionId) || CompositionEntity.TURN_AI_WORKING.equals(composition.turnState)) {
                throw new CompositionConflictException("An AI turn is already running");
            }
            String text = preamble.build(compositionId,
                    nodeIds == null ? List.of() : nodeIds, edgeIds == null ? List.of() : edgeIds);
            Instant now = Instant.now();
            composition.turnState = CompositionEntity.TURN_AI_WORKING;
            composition.currentTurnId = turnId;
            composition.lastTurnStartedOn = now;
            composition.updatedOn = now;
            Turn turn = new Turn(turnId, mapper.createArrayNode());
            CompositionItemEntity workflow = workflowItem(compositionId);
            if (workflow != null) {
                setBase(turn, workflow);
            }
            turns.put(compositionId, turn);
            return text;
        });
        sseEvents.fire(SseEvent.compositionTurn(compositionId, turnId, CompositionEntity.TURN_AI_WORKING));
        String recap = sessions.takeRecap(compositionId);
        try {
            sessions.send(compositionId, (recap == null ? "" : recap + "\n\n") + context + "\n\n" + message);
        } catch (IOException | RuntimeException e) {
            finish(compositionId, WorkbenchSessionEvent.Kind.FAILED, String.valueOf(e.getMessage()));
            throw new IllegalStateException("Could not send the message to the workbench agent", e);
        }
        return turnId;
    }

    /**
     * Applies a change set to the running turn's working workflow. Nothing is persisted and no events fire.
     *
     * @param compositionId the composition id
     * @param changeSet the change set
     * @return the outcome
     * @throws CompositionConflictException if no turn is running
     * @throws IllegalArgumentException if the composition has no workflow
     */
    public synchronized ChangeSetOutcome applyChangeSet(long compositionId, JsonNode changeSet) {
        Turn turn = requireTurn(compositionId);
        if (turn.workflowItemId == null) {
            throw new IllegalArgumentException(
                    "The composition has no workflow yet; create one with upsert_item type workflow");
        }
        String workingRevision = ContentRevision.of(turn.working);
        if (!workingRevision.equals(changeSet.path("baseRevision").asText(null))) {
            return new ChangeSetOutcome(false, null, List.of(), new ChangeSetError("stale", null,
                    "Base revision is not the current working revision " + workingRevision + "; call get_composition"));
        }
        ChangeSetResult result = ChangeSets.applyChecked(turn.working, changeSet);
        return switch (result) {
            case ChangeSetResult.Applied applied -> {
                turn.working = applied.workflow();
                changeSet.path("ops").forEach(op -> turn.ops.add(op.deepCopy()));
                List<ConfigProblem> problems = QuarkusTransaction.requiringNew().call(() -> validator.validateContent(
                        compositionId, ConfigItemType.WORKFLOW, turn.workflowName, turn.working));
                yield new ChangeSetOutcome(true, ContentRevision.of(turn.working), problems, null);
            }
            case ChangeSetResult.Rejected rejected -> new ChangeSetOutcome(false, null, List.of(), rejected.error());
        };
    }

    /**
     * Creates or replaces an action type or tool (or the first workflow) as the agent, tagged with the turn id.
     * Fires {@code compositionItemChanged} through {@link CompositionService#save}.
     *
     * @param compositionId the composition id
     * @param type {@code action-type}, {@code tool}, or {@code workflow} when there is none yet
     * @param content the item content
     * @return the saved item and its problems
     * @throws CompositionConflictException if no turn is running
     */
    public synchronized AgentWrite upsert(long compositionId, String type, JsonNode content) {
        Turn turn = requireTurn(compositionId);
        ConfigItemType itemType = agentType(type);
        if (itemType == ConfigItemType.WORKFLOW && turn.workflowItemId != null) {
            throw new IllegalArgumentException(
                    "The composition already has a workflow; change it with apply_change_set");
        }
        if (content == null || !content.isObject()) {
            throw new IllegalArgumentException("'content' must be a JSON object");
        }
        String name = CompositionItems.nameOf(content);
        if (name == null) {
            throw new IllegalArgumentException("'content.name' is required");
        }
        AgentWrite write = QuarkusTransaction.requiringNew().call(() -> {
            CompositionItemEntity existing = itemType == ConfigItemType.WORKFLOW ? null
                    : find(compositionId, itemType, name);
            long itemId = existing != null ? existing.id : service.addBlank(compositionId, itemType, name).id;
            CompositionItemEntity saved = service.save(compositionId, itemId, content, null,
                    CompositionService.AUTHOR_AI, turn.turnId);
            if (existing == null) {
                // addBlank recorded the item as a user creation; the agent created it in this turn. When the
                // content equals the blank, save records no new revision, so tag the item and its first revision.
                saved.lastAuthor = CompositionService.AUTHOR_AI;
                saved.lastTurnId = turn.turnId;
                CompositionRevisionEntity.update("author = ?1, turnId = ?2 where itemId = ?3 and author = ?4",
                        CompositionService.AUTHOR_AI, turn.turnId, saved.id, CompositionService.AUTHOR_USER);
            }
            List<ConfigProblem> problems = validator.validateContent(compositionId, itemType, saved.name, content);
            return new AgentWrite(saved, problems);
        });
        if (itemType == ConfigItemType.WORKFLOW) {
            setBase(turn, write.item());
        }
        return write;
    }

    /**
     * Removes an action type or tool as the agent.
     *
     * @param compositionId the composition id
     * @param type {@code action-type} or {@code tool}
     * @param name the item name
     * @throws CompositionConflictException if no turn is running
     * @throws CompositionNotFoundException if there is no such item
     */
    public synchronized void remove(long compositionId, String type, String name) {
        requireTurn(compositionId);
        ConfigItemType itemType = agentType(type);
        if (itemType == ConfigItemType.WORKFLOW) {
            throw new IllegalArgumentException("The workflow cannot be removed");
        }
        QuarkusTransaction.requiringNew().run(() -> {
            CompositionItemEntity item = find(compositionId, itemType, name);
            if (item == null) {
                throw new CompositionNotFoundException("No " + itemType.jsonName() + " named " + name);
            }
            service.removeItem(compositionId, item.id);
        });
    }

    /**
     * @param compositionId the composition id
     * @return the running turn's working workflow, else the saved workflow, or null if there is none
     */
    public synchronized JsonNode currentWorkflow(long compositionId) {
        Turn turn = turns.get(compositionId);
        if (turn != null && turn.working != null) {
            return turn.working;
        }
        return QuarkusTransaction.requiringNew().call(() -> {
            CompositionItemEntity workflow = workflowItem(compositionId);
            return workflow == null ? null : service.contentOf(workflow);
        });
    }

    /**
     * @param compositionId the composition id
     * @return the running turn id, or null
     */
    public synchronized String currentTurnId(long compositionId) {
        Turn turn = turns.get(compositionId);
        return turn == null ? null : turn.turnId;
    }

    /**
     * Records how the editor resolved a pending change set. Locks the composition row and clears the pending
     * change set; a {@code stale} outcome starts one retry turn, or posts {@link #SECOND_STALE_NOTICE}.
     *
     * @param compositionId the composition id
     * @param turnId the turn whose change set was resolved
     * @param outcome {@code applied}, {@code accepted}, {@code rejected} or {@code stale}
     */
    public synchronized void resolve(long compositionId, String turnId, String outcome) {
        boolean cleared = QuarkusTransaction.requiringNew().call(() -> {
            CompositionEntity composition = CompositionEntity.findById(compositionId, LockModeType.PESSIMISTIC_WRITE);
            if (composition == null || turnId == null || !turnId.equals(composition.pendingTurnId)) {
                return false;
            }
            composition.pendingChangeSet = null;
            composition.pendingTurnId = null;
            composition.updatedOn = Instant.now();
            return true;
        });
        boolean wasRetry = staleRetryTurnIds.remove(turnId);
        if (!cleared || !"stale".equals(outcome)) {
            return;
        }
        if (wasRetry || turns.containsKey(compositionId)) {
            sessions.notice(compositionId, SECOND_STALE_NOTICE);
            return;
        }
        String retry = start(compositionId, STALE_RETRY_PROMPT, List.of(), List.of());
        staleRetryTurnIds.add(retry);
    }

    /**
     * Interrupts the running turn. The turn ends when the session reports it.
     *
     * @param compositionId the composition id
     */
    public void interrupt(long compositionId) {
        sessions.interrupt(compositionId);
    }

    /**
     * Finishes the composition's turn when its session reports completion or failure. Locks the composition
     * row, sets it idle, stores the combined change set on completion, then fires {@code compositionChangeSet}
     * (if any) and {@code compositionTurn(idle)}.
     *
     * @param event the session event
     */
    public synchronized void onSessionEvent(@Observes WorkbenchSessionEvent event) {
        finish(event.compositionId(), event.kind(), event.detail());
    }

    /**
     * Resets compositions left {@code ai-working} by a restart to {@code idle}.
     *
     * @param event the startup event
     */
    public void onStartup(@Observes StartupEvent event) {
        QuarkusTransaction.requiringNew().run(() -> CompositionEntity.update(
                "turnState = ?1, currentTurnId = null where turnState = ?2",
                CompositionEntity.TURN_IDLE, CompositionEntity.TURN_AI_WORKING));
    }

    private void finish(long compositionId, WorkbenchSessionEvent.Kind kind, String detail) {
        Turn turn = turns.remove(compositionId);
        String changeSet = null;
        if (turn != null && kind == WorkbenchSessionEvent.Kind.TURN_COMPLETE && !turn.ops.isEmpty()) {
            ObjectNode combined = mapper.createObjectNode();
            combined.put("id", turn.turnId);
            combined.put("baseRevision", turn.baseRevision);
            combined.put("author", AUTHOR);
            combined.put("summary", "AI turn: " + turn.ops.size() + " workflow change(s)");
            combined.set("ops", turn.ops);
            changeSet = combined.toString();
        }
        String pending = changeSet;
        Boolean exists = QuarkusTransaction.requiringNew().call(() -> {
            CompositionEntity composition = CompositionEntity.findById(compositionId, LockModeType.PESSIMISTIC_WRITE);
            if (composition == null) {
                return false;
            }
            composition.turnState = CompositionEntity.TURN_IDLE;
            composition.currentTurnId = null;
            if (pending != null) {
                composition.pendingChangeSet = pending;
                composition.pendingTurnId = turn.turnId;
            }
            composition.updatedOn = Instant.now();
            return true;
        });
        if (turn == null || !exists) {
            return;
        }
        if (pending != null) {
            sseEvents.fire(SseEvent.compositionChangeSet(compositionId, turn.turnId, pending));
        }
        sseEvents.fire(SseEvent.compositionTurn(compositionId, turn.turnId, CompositionEntity.TURN_IDLE));
        if (kind == WorkbenchSessionEvent.Kind.FAILED) {
            staleRetryTurnIds.remove(turn.turnId);
            sessions.notice(compositionId, FAILED_NOTICE_PREFIX + detail + FAILED_NOTICE_SUFFIX);
        }
    }

    private Turn requireTurn(long compositionId) {
        Turn turn = turns.get(compositionId);
        if (turn == null) {
            throw new CompositionConflictException("No AI turn is running");
        }
        return turn;
    }

    private static ConfigItemType agentType(String type) {
        ConfigItemType itemType = type == null ? null : CompositionItems.supportedType(type);
        if (itemType == null) {
            throw new IllegalArgumentException("'type' must be workflow, action-type or tool");
        }
        return itemType;
    }

    private void setBase(Turn turn, CompositionItemEntity workflow) {
        JsonNode content = service.contentOf(workflow);
        turn.workflowItemId = workflow.id;
        turn.workflowName = workflow.name;
        turn.baseContent = content;
        turn.working = content;
        turn.baseRevision = ContentRevision.of(content);
    }

    private CompositionItemEntity workflowItem(long compositionId) {
        return CompositionItemEntity.find("compositionId = ?1 and itemType = ?2",
                compositionId, ConfigItemType.WORKFLOW.jsonName()).firstResult();
    }

    private static CompositionItemEntity find(long compositionId, ConfigItemType type, String name) {
        return CompositionItemEntity.find("compositionId = ?1 and itemType = ?2 and name = ?3",
                compositionId, type.jsonName(), name).firstResult();
    }
}
