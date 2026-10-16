package io.apitomy.axiom.app.composition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.app.validation.ConfigProblem;
import io.apitomy.axiom.core.entities.CompositionEntity;
import io.apitomy.axiom.core.entities.CompositionItemEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.apitomy.flow.changeset.ChangeSetError;
import io.apitomy.flow.changeset.ChangeSetResult;
import io.apitomy.flow.changeset.ChangeSets;
import io.apitomy.flow.changeset.ContentRevision;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs AI turns on a composition. A turn accumulates workflow change sets in memory against a working copy of
 * the workflow; when the turn completes they are combined into one pending change set that the editor applies.
 * Action types and tools written by the agent are saved directly, tagged with the turn id. The agent is never
 * called while a transaction is open, nor while this bean's monitor is held: session events reach
 * {@link #onSessionEvent} from another thread, and sending while holding the monitor could deadlock with it.
 */
@ApplicationScoped
public class CompositionTurns {

    /** The author of change sets produced by the workbench agent. */
    public static final String AUTHOR = "agent:axiom-workbench";

    /** What the conversation shows for the server-originated stale retry prompt. */
    public static final String STALE_RETRY_DISPLAY =
            "(Axiom) The workflow changed while the AI was working; asking it to try again.";

    /** The prompt sent when a turn's change set could not be applied because the workflow changed. */
    public static final String STALE_RETRY_PROMPT = "Your workflow changes from the last turn could not be applied "
            + "because the user changed the workflow while you were working. Call get_composition to see the current "
            + "workflow, then resubmit the workflow changes that still make sense.";

    /** The notice posted when a stale retry is itself stale. */
    public static final String SECOND_STALE_NOTICE = "The workflow changed again before my changes could be "
            + "applied, so I stopped. Ask me to try again when you are ready.";

    /** The notice posted when a stale change set from an earlier visit to the workbench is discarded. */
    public static final String STALE_PENDING_NOTICE = "My earlier workflow changes no longer fit the current "
            + "workflow, so I discarded them. Ask me to try again if you still want them.";

    /** The notice posted when a change set is stale while another turn is already running. */
    public static final String STALE_DURING_TURN_NOTICE = "My earlier workflow changes no longer fit the current "
            + "workflow; I'll take your latest edits into account in this turn.";

    /** The start of the notice posted when a stale retry turn could not be started. */
    public static final String RETRY_FAILED_NOTICE_PREFIX = "My earlier workflow changes no longer fit the current "
            + "workflow, and I could not retry them: ";

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
     * @param created true when the write created the item (false when it updated an existing one)
     */
    public record AgentWrite(CompositionItemEntity item, List<ConfigProblem> problems, boolean created) {
    }

    private static final Logger LOG = Logger.getLogger(CompositionTurns.class);

    private static final class Turn {
        final String turnId;
        /** Completion events with a lower session event index belong to an earlier exchange. */
        volatile int firstEventIndex = Integer.MAX_VALUE;
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

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "workbench-interrupt-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    @ConfigProperty(name = "axiom.workbench.interrupt-timeout-seconds", defaultValue = "30")
    long interruptTimeoutSeconds;

    private volatile Duration interruptTimeoutOverride;

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
     * @throws AgentUnavailableException if the agent could not be started or the message could not be sent
     */
    public String start(long compositionId, String message, List<String> nodeIds, List<String> edgeIds) {
        return start(compositionId, message, nodeIds, edgeIds, false);
    }

    private String start(long compositionId, String message, List<String> nodeIds, List<String> edgeIds,
                         boolean staleRetry) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("'message' is required");
        }
        Turn turn;
        String context;
        synchronized (this) {
            turn = new Turn(UUID.randomUUID().toString(), mapper.createArrayNode());
            context = QuarkusTransaction.requiringNew().call(() -> {
                CompositionEntity composition = CompositionEntity.findById(compositionId,
                        LockModeType.PESSIMISTIC_WRITE);
                if (composition == null) {
                    throw new CompositionNotFoundException("Composition not found: " + compositionId);
                }
                if (turns.containsKey(compositionId)
                        || CompositionEntity.TURN_AI_WORKING.equals(composition.turnState)) {
                    throw new CompositionConflictException("An AI turn is already running");
                }
                String text = preamble.build(compositionId,
                        nodeIds == null ? List.of() : nodeIds, edgeIds == null ? List.of() : edgeIds);
                Instant now = Instant.now();
                composition.turnState = CompositionEntity.TURN_AI_WORKING;
                composition.currentTurnId = turn.turnId;
                composition.lastTurnStartedOn = now;
                composition.updatedOn = now;
                CompositionItemEntity workflow = workflowItem(compositionId);
                if (workflow != null) {
                    setBase(turn, workflow);
                }
                return text;
            });
            turns.put(compositionId, turn);
            if (staleRetry) {
                staleRetryTurnIds.add(turn.turnId);
            }
        }
        sseEvents.fire(SseEvent.compositionTurn(compositionId, turn.turnId, CompositionEntity.TURN_AI_WORKING));
        // Outside the monitor: starting the agent can take long and sending dispatches session events.
        String recap = sessions.takeRecap(compositionId);
        try {
            turn.firstEventIndex = sessions.nextEventIndex(compositionId);
            String displayText = staleRetry ? STALE_RETRY_DISPLAY : message;
            sessions.send(compositionId, displayText,
                    (recap == null ? "" : recap + "\n\n") + context + "\n\n" + message);
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                if (turns.get(compositionId) == turn) {
                    finish(compositionId, WorkbenchSessionEvent.Kind.FAILED, String.valueOf(e.getMessage()));
                }
            }
            if (e instanceof AgentUnavailableException unavailable) {
                throw unavailable;
            }
            throw new AgentUnavailableException("Could not send the message to the workbench agent", e);
        }
        return turn.turnId;
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
            CompositionItemEntity saved = existing != null
                    ? service.save(compositionId, existing.id, content, null, CompositionService.AUTHOR_AI,
                            turn.turnId)
                    : service.addWithContent(compositionId, itemType, name, content, CompositionService.AUTHOR_AI,
                            turn.turnId);
            List<ConfigProblem> problems = validator.validateContent(compositionId, itemType, saved.name, content);
            return new AgentWrite(saved, problems, existing == null);
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
            return turn.working.deepCopy();
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
     * Records how the editor resolved a change set delivered at the end of a live turn.
     *
     * @param compositionId the composition id
     * @param turnId the turn whose change set was resolved
     * @param outcome {@code applied}, {@code accepted}, {@code rejected} or {@code stale}
     * @see #resolve(long, String, String, boolean)
     */
    public void resolve(long compositionId, String turnId, String outcome) {
        resolve(compositionId, turnId, outcome, false);
    }

    /**
     * Records how the editor resolved a pending change set. Locks the composition row and clears the pending
     * change set. A {@code stale} outcome posts {@link #STALE_PENDING_NOTICE} when the change set came from the
     * stored pending change set, {@link #SECOND_STALE_NOTICE} when it came from a stale retry,
     * {@link #STALE_DURING_TURN_NOTICE} while a turn is running, and otherwise starts one retry turn.
     *
     * @param compositionId the composition id
     * @param turnId the turn whose change set was resolved
     * @param outcome {@code applied}, {@code accepted}, {@code rejected} or {@code stale}
     * @param fromPending whether the change set was delivered from the stored pending change set
     */
    public void resolve(long compositionId, String turnId, String outcome, boolean fromPending) {
        String notice;
        synchronized (this) {
            boolean cleared = QuarkusTransaction.requiringNew().call(() -> {
                CompositionEntity composition = CompositionEntity.findById(compositionId,
                        LockModeType.PESSIMISTIC_WRITE);
                if (composition == null || turnId == null || !turnId.equals(composition.pendingTurnId)) {
                    return false;
                }
                composition.pendingChangeSet = null;
                composition.pendingTurnId = null;
                composition.updatedOn = Instant.now();
                return true;
            });
            if (!cleared) {
                return;
            }
            boolean wasRetry = staleRetryTurnIds.remove(turnId);
            if (!"stale".equals(outcome)) {
                return;
            }
            if (fromPending) {
                notice = STALE_PENDING_NOTICE;
            } else if (wasRetry) {
                notice = SECOND_STALE_NOTICE;
            } else if (turns.containsKey(compositionId)) {
                notice = STALE_DURING_TURN_NOTICE;
            } else {
                notice = null;
            }
        }
        if (notice != null) {
            sessions.notice(compositionId, notice);
            return;
        }
        try {
            start(compositionId, STALE_RETRY_PROMPT, List.of(), List.of(), true);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Could not start the stale retry turn for composition %d", compositionId);
            sessions.notice(compositionId, RETRY_FAILED_NOTICE_PREFIX + e.getMessage());
        }
    }

    /**
     * Interrupts the running turn. The turn ends when the session reports it; if it has not after
     * {@code axiom.workbench.interrupt-timeout-seconds}, it is finished as failed.
     *
     * @param compositionId the composition id
     */
    public void interrupt(long compositionId) {
        String turnId = currentTurnId(compositionId);
        sessions.interrupt(compositionId);
        if (turnId == null) {
            return;
        }
        Duration timeout = interruptTimeoutOverride != null ? interruptTimeoutOverride
                : Duration.ofSeconds(interruptTimeoutSeconds);
        watchdog.schedule(() -> expireInterrupted(compositionId, turnId), timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private synchronized void expireInterrupted(long compositionId, String turnId) {
        try {
            Turn turn = turns.get(compositionId);
            if (turn != null && turn.turnId.equals(turnId)) {
                finish(compositionId, WorkbenchSessionEvent.Kind.FAILED, "interrupted");
            }
        } catch (RuntimeException e) {
            LOG.warnf(e, "Could not finish interrupted turn %s of composition %d", turnId, compositionId);
        }
    }

    /**
     * Overrides the interrupt timeout. For tests.
     *
     * @param timeout the timeout, or null for the configured one
     */
    void setInterruptTimeoutForTests(Duration timeout) {
        interruptTimeoutOverride = timeout;
    }

    /**
     * Stops the interrupt watchdog.
     */
    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
    }

    /**
     * Finishes the composition's turn when its session reports completion or failure. Locks the composition
     * row, sets it idle, stores the combined change set on completion, then fires {@code compositionChangeSet}
     * (if any) and {@code compositionTurn(idle)}. Events older than the running turn's message are ignored:
     * they end an earlier exchange.
     *
     * @param event the session event
     */
    public synchronized void onSessionEvent(@Observes WorkbenchSessionEvent event) {
        Turn turn = turns.get(event.compositionId());
        if (turn != null && event.eventIndex() < turn.firstEventIndex) {
            return;
        }
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
        if (turn != null && pending == null) {
            staleRetryTurnIds.remove(turn.turnId);
        }
        if (turn == null || !exists) {
            return;
        }
        if (pending != null) {
            sseEvents.fire(SseEvent.compositionChangeSet(compositionId, turn.turnId, pending));
        }
        sseEvents.fire(SseEvent.compositionTurn(compositionId, turn.turnId, CompositionEntity.TURN_IDLE));
        if (kind == WorkbenchSessionEvent.Kind.FAILED) {
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
            throw new IllegalArgumentException("'type' must be workflow, action-type, tool or subscription");
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
