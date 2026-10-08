package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity;
import io.apitomy.axiom.core.events.model.RoutingRule;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.flow.engine.WorkflowEngine;
import io.apitomy.flow.model.Workflow;
import io.apitomy.flow.model.WorkflowInstance;
import io.apitomy.flow.spi.NodeExecutionContext;
import io.apitomy.flow.spi.NodeExecutor;
import io.apitomy.flow.spi.NodeExecutorProvider;
import io.apitomy.flow.spi.NodeResult;
import io.apitomy.flow.spi.NodeResultStatus;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Matches allowed pipeline events against parked receive-event workflow
 * branches and resumes the ones that match. Scoping is hybrid: an event that
 * correlates to a subject (by {@code issueRef} → {@code subject.ref}, else
 * {@code event.subjectId}) is offered only to that subject's subscriptions;
 * an uncorrelated event is broadcast to all subscriptions of its event type.
 * Matching itself (event type, WAITING status, {@code match} EL expressions)
 * is delegated to the Flow engine's {@code matchesEvent}, so stale
 * subscription rows are harmless.
 */
@ApplicationScoped
public class WorkflowEventDispatcher {

    private static final Logger LOG = Logger.getLogger(WorkflowEventDispatcher.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    private WorkflowEngine workflowEngine;

    @PostConstruct
    void init() {
        // matchesEvent never executes nodes; the stub provider mirrors
        // WorkflowExecutionService.init().
        NodeExecutorProvider provider = actionType -> new NodeExecutor() {
            @Override
            public String actionType() {
                return actionType;
            }

            @Override
            public NodeResult execute(NodeExecutionContext context) {
                return new NodeResult(NodeResultStatus.PENDING, Map.of());
            }
        };
        this.workflowEngine = new WorkflowEngine(provider, List.of(), null);
    }

    /**
     * Dispatches a stream event to all candidate receive-event subscriptions.
     * Unlike {@code dispatchEvent(long)}, this method accepts a pre-built
     * event map from the EventStreamOrchestrator, avoiding entity conversion.
     *
     * @param eventType the normalized event type string (e.g., "issue.created")
     * @param eventMap  the curated event map for EL evaluation and context merging
     */
    public void dispatchStreamEvent(String eventType, Map<String, Object> eventMap) {
        dispatchStreamEvent(eventType, eventMap, null);
    }

    /**
     * Dispatches a stream event to all candidate receive-event subscriptions and returns the
     * runs it resumed. When the origin is given, each resumed run records the resuming event.
     *
     * @param eventType the normalized event type string (e.g., "issue.created")
     * @param eventMap  the curated event map for EL evaluation and context merging
     * @param origin    the stream event (and ledger entry) being dispatched; may be null
     * @return the matched runs, in subscription order: resumed ones, and ones that matched but
     *         failed to resume ({@link ResumedRun#failed()}); empty if nothing matched
     */
    public List<ResumedRun> dispatchStreamEvent(String eventType, Map<String, Object> eventMap,
                                                EventOrigin origin) {
        List<Long> subscriptionIds = QuarkusTransaction.requiringNew()
                .call(() -> planStreamDispatch(eventType, eventMap));

        if (subscriptionIds == null || subscriptionIds.isEmpty()) {
            return List.of();
        }

        List<ResumedRun> resumed = new ArrayList<>();
        for (Long subId : subscriptionIds) {
            // Set once the event has matched, so a failure while resuming is reported against
            // the matched run (the transaction rolls back, leaving the run parked).
            MatchedSubscription[] matched = new MatchedSubscription[1];
            try {
                ResumedRun run = QuarkusTransaction.requiringNew().call(() ->
                        offerToSubscription(subId, eventMap, origin, matched));
                if (run != null) {
                    resumed.add(run);
                }
            } catch (Exception e) {
                LOG.errorf(e, "Failed to offer stream event to workflow subscription %d", subId);
                if (matched[0] != null) {
                    String error = e.getMessage() != null
                            ? e.getMessage() : e.getClass().getSimpleName();
                    resumed.add(new ResumedRun(matched[0].runId(), matched[0].subjectId(),
                            matched[0].nodeId(), null, null, error));
                }
            }
        }
        return resumed;
    }

    /**
     * Read phase for stream events: prefilters subscriptions by event type.
     * Stream events don't have subject scoping the way old events do — the
     * subscription's filter rules handle scoping instead.
     */
    private List<Long> planStreamDispatch(String eventType, Map<String, Object> eventMap) {
        List<WorkflowEventSubscriptionEntity> candidates =
                WorkflowEventSubscriptionEntity.list("eventType", eventType);
        if (candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream().map(sub -> sub.id).toList();
    }

    /**
     * Checks a single subscription against the event map and, on match,
     * deletes the subscription and resumes the run — atomically, within the
     * caller-supplied per-subscription transaction. Subscriptions whose run
     * is missing or terminal are cleaned up opportunistically; an already
     * consumed (deleted) subscription is silently skipped.
     */
    private ResumedRun offerToSubscription(long subscriptionId, Map<String, Object> eventMap,
                                           EventOrigin origin, MatchedSubscription[] matched) {
        WorkflowEventSubscriptionEntity sub =
                WorkflowEventSubscriptionEntity.findById(subscriptionId);
        if (sub == null) {
            return null;
        }
        WorkflowRunEntity run = WorkflowRunEntity.findById(sub.runId);
        if (run == null || run.completedOn != null) {
            sub.delete();
            return null;
        }

        Workflow workflow = loadWorkflowContent(run.definitionId, run.definitionVersion);
        WorkflowInstance instance = deserializeInstance(run.instanceState);
        if (workflow == null || instance == null) {
            return null;
        }

        // Cheap correlation-key check first (Flow's matchesEvent re-checks it).
        if (sub.correlationKey != null && !sub.correlationKey.equals(
                evaluateEventKey(workflow, sub.nodeId, eventMap))) {
            return null;
        }
        if (!workflowEngine.matchesEvent(workflow, instance, sub.nodeId, eventMap)) {
            return null;
        }

        long runId = sub.runId;
        String nodeId = sub.nodeId;
        matched[0] = new MatchedSubscription(runId, run.subjectId, nodeId);
        sub.delete();
        ResumedRun resumed = workflowExecutionService.onEventReceived(runId, nodeId, eventMap,
                origin, subscriptionId);
        LOG.infof("Event %s resumed workflow run %d at receive-event node %s",
                origin != null ? origin.eventId() : "(unknown)", runId, nodeId);
        return resumed;
    }

    private String evaluateEventKey(Workflow workflow, String nodeId, Map<String, Object> eventMap) {
        try {
            return workflowEngine.evaluateEventKey(workflow, nodeId, eventMap);
        } catch (RuntimeException e) {
            LOG.debugf(e, "Failed to evaluate the event key of node %s", nodeId);
            return null;
        }
    }

    /**
     * Runs a new subscription's look-back once the parking transaction has committed.
     *
     * @param request the subscription to run the look-back for
     */
    void onLookbackRequested(@Observes(during = TransactionPhase.AFTER_SUCCESS)
                             EventLookbackRequested request) {
        try {
            replayLookback(request.subscriptionId());
        } catch (Exception e) {
            LOG.errorf(e, "Event look-back failed for workflow subscription %d",
                    request.subscriptionId());
        }
    }

    /**
     * Offers stored events that arrived before a branch parked to its subscription, oldest first,
     * until one resumes the run. Eligible events have the subscription's event type, a timestamp at
     * or after its {@code lookbackFrom}, were routed by a {@code workflow-dispatch} rule (so event
     * subscription filters applied, as for live delivery), and have not already resumed this run.
     * Clears {@code lookbackFrom} afterwards so the look-back runs once.
     *
     * @param subscriptionId the subscription
     * @return the resumed run, or null if no stored event matched
     */
    public ResumedRun replayLookback(long subscriptionId) {
        List<Object[]> candidates = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowEventSubscriptionEntity sub =
                    WorkflowEventSubscriptionEntity.findById(subscriptionId);
            if (sub == null || sub.lookbackFrom == null) {
                return List.<Object[]>of();
            }
            return StreamEventEntity.getEntityManager().createQuery(
                    "select e.id, min(l.id) from StreamEventEntity e, EventProcessingLedgerEntity l"
                            + " where l.eventId = e.id and e.type = :type and e.timestamp >= :from"
                            + " and exists (select o.id from RoutingOutcomeEntity o"
                            + "   where o.ledgerId = l.id and o.routingType = :routing)"
                            + " and not exists (select r.id from WorkflowRunResumeEntity r"
                            + "   where r.runId = :runId and r.eventId = e.id)"
                            + " group by e.id, e.timestamp, e.createdOn"
                            + " order by e.timestamp, e.createdOn", Object[].class)
                    .setParameter("type", sub.eventType)
                    .setParameter("from", sub.lookbackFrom)
                    .setParameter("routing", RoutingRule.TYPE_WORKFLOW_DISPATCH)
                    .setParameter("runId", sub.runId)
                    .getResultList();
        });

        ResumedRun resumed = null;
        for (Object[] row : candidates) {
            UUID eventId = (UUID) row[0];
            EventOrigin origin = new EventOrigin(eventId, (Long) row[1]);
            MatchedSubscription[] matched = new MatchedSubscription[1];
            try {
                resumed = QuarkusTransaction.requiringNew().call(() -> {
                    StreamEventEntity event = StreamEventEntity.findById(eventId);
                    if (event == null) {
                        return null;
                    }
                    Map<String, Object> eventMap = WorkflowEventMapper.toEventMap(event, objectMapper);
                    eventMap.put("id", eventId.toString());
                    return offerToSubscription(subscriptionId, eventMap, origin, matched);
                });
            } catch (Exception e) {
                // Matched but failed to resume: the run stays parked for live events.
                LOG.errorf(e, "Look-back failed to deliver stored event %s to workflow subscription %d",
                        eventId, subscriptionId);
                break;
            }
            if (resumed != null || matched[0] != null) {
                LOG.infof("Look-back delivered stored event %s to workflow subscription %d",
                        eventId, subscriptionId);
                break;
            }
        }
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowEventSubscriptionEntity sub =
                    WorkflowEventSubscriptionEntity.findById(subscriptionId);
            if (sub != null) {
                sub.lookbackFrom = null;
            }
        });
        return resumed;
    }

    /**
     * Ids of subscriptions whose look-back has not run (e.g. the server stopped before the
     * parking transaction's after-commit callback ran).
     *
     * @return the subscription ids
     */
    public List<Long> findPendingLookbacks() {
        return QuarkusTransaction.requiringNew().call(() -> WorkflowEventSubscriptionEntity
                .<WorkflowEventSubscriptionEntity>list("lookbackFrom is not null")
                .stream().map(s -> s.id).toList());
    }

    /** The run and node of a subscription that matched the event. */
    private record MatchedSubscription(long runId, Long subjectId, String nodeId) {
    }

    private Workflow loadWorkflowContent(long definitionId, int definitionVersion) {
        WorkflowDefinitionVersionEntity version = WorkflowDefinitionVersionEntity
                .find("definitionId = ?1 and version = ?2", definitionId, definitionVersion)
                .firstResult();
        if (version == null) {
            LOG.warnf("Workflow version %d/%d not found for event dispatch",
                    definitionId, definitionVersion);
            return null;
        }
        try {
            return objectMapper.readValue(version.content, Workflow.class);
        } catch (Exception e) {
            LOG.warnf(e, "Invalid workflow JSON for definition %d v%d",
                    definitionId, definitionVersion);
            return null;
        }
    }

    private WorkflowInstance deserializeInstance(String json) {
        try {
            return objectMapper.readValue(json, WorkflowInstance.class);
        } catch (Exception e) {
            LOG.warnf(e, "Invalid workflow instance state during event dispatch");
            return null;
        }
    }
}
