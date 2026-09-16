package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
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
     * Offers an event to all candidate receive-event subscriptions, resuming
     * every run whose parked node matches. Each offer runs in its own
     * transaction so a failing resume cannot roll back resumes already
     * applied for other runs; failures are contained per subscription. This
     * method itself only throws if the read phase (event load, scoping)
     * fails.
     *
     * <p>Must be called <b>outside</b> an active transaction: the read phase
     * and each per-subscription offer each start their own
     * {@link QuarkusTransaction#requiringNew() new transaction}.</p>
     *
     * @param eventId the id of the (filter-allowed) event to dispatch
     */
    public void dispatchEvent(long eventId) {
        DispatchPlan plan = QuarkusTransaction.requiringNew().call(() -> planDispatch(eventId));
        if (plan == null) {
            return;
        }

        for (Long subId : plan.subscriptionIds()) {
            try {
                QuarkusTransaction.requiringNew().run(() ->
                        offerToSubscription(subId, plan.eventMap()));
            } catch (Exception e) {
                LOG.errorf(e, "Failed to offer event %d to workflow subscription %d",
                        eventId, subId);
            }
        }
    }

    /**
     * Read phase: loads the event, prefilters subscriptions by event type,
     * applies hybrid project scoping, and builds the event map. Returns
     * {@code null} when there is nothing to dispatch.
     */
    private DispatchPlan planDispatch(long eventId) {
        EventEntity event = EventEntity.findById(eventId);
        if (event == null) {
            LOG.warnf("Event %d not found for workflow dispatch", eventId);
            return null;
        }

        List<WorkflowEventSubscriptionEntity> candidates =
                WorkflowEventSubscriptionEntity.list("eventType", event.eventType);
        if (candidates.isEmpty()) {
            return null;
        }

        ProjectEntity project = findProjectForEvent(event);
        if (project != null) {
            Long projectId = project.id;
            candidates = candidates.stream()
                    .filter(sub -> projectId.equals(sub.projectId))
                    .toList();
        }
        if (candidates.isEmpty()) {
            return null;
        }

        Map<String, Object> eventMap = WorkflowEventMapper.toEventMap(event, objectMapper);
        List<Long> subscriptionIds = candidates.stream().map(sub -> sub.id).toList();
        return new DispatchPlan(subscriptionIds, eventMap);
    }

    /** Candidate subscription ids plus the event map computed in the read phase. */
    private record DispatchPlan(List<Long> subscriptionIds, Map<String, Object> eventMap) {
    }

    /**
     * Checks a single subscription against the event map and, on match,
     * deletes the subscription and resumes the run — atomically, within the
     * caller-supplied per-subscription transaction. Subscriptions whose run
     * is missing or terminal are cleaned up opportunistically; an already
     * consumed (deleted) subscription is silently skipped.
     */
    private void offerToSubscription(long subscriptionId, Map<String, Object> eventMap) {
        WorkflowEventSubscriptionEntity sub =
                WorkflowEventSubscriptionEntity.findById(subscriptionId);
        if (sub == null) {
            return;
        }
        WorkflowRunEntity run = WorkflowRunEntity.findById(sub.runId);
        if (run == null || run.completedOn != null) {
            sub.delete();
            return;
        }

        Workflow workflow = loadWorkflowContent(run.definitionId, run.definitionVersion);
        WorkflowInstance instance = deserializeInstance(run.instanceState);
        if (workflow == null || instance == null) {
            return;
        }

        if (!workflowEngine.matchesEvent(workflow, instance, sub.nodeId, eventMap)) {
            return;
        }

        long runId = sub.runId;
        String nodeId = sub.nodeId;
        sub.delete();
        workflowExecutionService.onEventReceived(runId, nodeId, eventMap);
        LOG.infof("Event resumed workflow run %d at receive-event node %s", runId, nodeId);
    }

    /** Same correlation rule as PipelineOrchestrator.findProjectForEvent. */
    private ProjectEntity findProjectForEvent(EventEntity event) {
        if (event.issueRef != null) {
            return ProjectEntity.find("ref", event.issueRef).firstResult();
        }
        if (event.projectId != null) {
            return ProjectEntity.findById(event.projectId);
        }
        return null;
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
