package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity;
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
import jakarta.inject.Inject;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Matches allowed pipeline events against parked receive-event workflow
 * branches and resumes the ones that match. Scoping is hybrid: an event that
 * correlates to a project (by {@code issueRef} → {@code project.ref}, else
 * {@code event.projectId}) is offered only to that project's subscriptions;
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
     * @return the resumed runs, in subscription order; empty if nothing matched
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
            try {
                ResumedRun run = QuarkusTransaction.requiringNew().call(() ->
                        offerToSubscription(subId, eventMap, origin));
                if (run != null) {
                    resumed.add(run);
                }
            } catch (Exception e) {
                LOG.errorf(e, "Failed to offer stream event to workflow subscription %d", subId);
            }
        }
        return resumed;
    }

    /**
     * Read phase for stream events: prefilters subscriptions by event type.
     * Stream events don't have project scoping the way old events do — the
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
                                           EventOrigin origin) {
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

        if (!workflowEngine.matchesEvent(workflow, instance, sub.nodeId, eventMap)) {
            return null;
        }

        long runId = sub.runId;
        String nodeId = sub.nodeId;
        sub.delete();
        ResumedRun resumed = workflowExecutionService.onEventReceived(runId, nodeId, eventMap,
                origin);
        LOG.infof("Event %s resumed workflow run %d at receive-event node %s",
                origin != null ? origin.eventId() : "(unknown)", runId, nodeId);
        return resumed;
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
