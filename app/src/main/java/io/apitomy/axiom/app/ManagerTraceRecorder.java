package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.apitomy.axiom.manager.ManagerDecision;
import io.apitomy.axiom.manager.ManagerService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Builds the trace nodes of a Manager evaluation ({@code event-ingested} root,
 * {@code manager-evaluation}, {@code manager-decision}). Shared by the event pipeline
 * ({@code manager} traces) and the manual evaluation endpoint ({@code manager-dry-run}
 * traces). Every operation is non-fatal: failures are logged and never interrupt the caller,
 * and a {@code null} context or node ID is ignored.
 */
@ApplicationScoped
public class ManagerTraceRecorder {

    private static final Logger LOG = Logger.getLogger(ManagerTraceRecorder.class);

    @Inject
    TraceService traceService;

    @Inject
    ManagerService managerService;

    /**
     * Creates a trace for a Manager evaluation of the event, with an {@code event-ingested}
     * root node.
     *
     * @param traceType   the trace type ({@code manager} or {@code manager-dry-run})
     * @param summary     the trace summary
     * @param rootSummary the root node summary
     * @param event       the evaluated event; its ID is stored as the trace's event ID
     * @return the trace context, or {@code null} if the trace could not be created
     */
    public TraceContext startTrace(String traceType, String summary, String rootSummary,
                                   StreamEventEntity event) {
        try {
            return traceService.createTrace(traceType, summary, event.id, null, null,
                    "event-ingested", rootSummary, null, null);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to create %s trace for event %s", traceType, event.id);
            return null;
        }
    }

    /**
     * Adds an in-progress node under the current parent of the context.
     *
     * @param traceCtx the trace context (may be {@code null})
     * @param nodeType the node type
     * @param summary  the node summary
     * @return the node ID, or {@code null} if no node was created
     */
    public Long addNode(TraceContext traceCtx, String nodeType, String summary) {
        if (traceCtx == null) return null;
        try {
            return traceService.addNode(traceCtx, nodeType, "in-progress", summary, null, null);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to add %s trace node to trace %s", nodeType, traceCtx.traceId());
            return null;
        }
    }

    /**
     * Completes a node, linking it to an activity row when one is given.
     *
     * @param nodeId        the node ID (may be {@code null})
     * @param status        the final status
     * @param activityLogId the activity row to reference (may be {@code null})
     */
    public void completeNode(Long nodeId, String status, Long activityLogId) {
        if (nodeId == null) return;
        try {
            if (activityLogId != null) {
                traceService.completeNode(nodeId, status, "activity-log", activityLogId);
            } else {
                traceService.completeNode(nodeId, status);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to complete trace node %d", nodeId);
        }
    }

    /**
     * Fails a node with the error, linking it to an activity row when one is given.
     *
     * @param nodeId        the node ID (may be {@code null})
     * @param error         the error message
     * @param activityLogId the activity row to reference (may be {@code null})
     */
    public void failNode(Long nodeId, String error, Long activityLogId) {
        if (nodeId == null) return;
        try {
            traceService.failNode(nodeId, error,
                    activityLogId != null ? "activity-log" : null, activityLogId);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to record error on trace node %d", nodeId);
        }
    }

    /**
     * Completes the trace with the given status.
     *
     * @param traceCtx the trace context (may be {@code null})
     * @param status   the final status
     */
    public void completeTrace(TraceContext traceCtx, String status) {
        if (traceCtx == null) return;
        try {
            traceService.completeTrace(traceCtx.traceId(), status);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to complete trace %s", traceCtx.traceId());
        }
    }

    /**
     * Returns a short label for a decision: its type, with the action type when present.
     *
     * @param decision the decision
     * @return the label, e.g. {@code create_task(label-issue)}
     */
    public static String decisionLabel(ManagerDecision decision) {
        return decision.actionType() != null
                ? decision.decision() + "(" + decision.actionType() + ")"
                : decision.decision();
    }

    /**
     * Returns the summary of a {@code manager-decision} node: the decision, its confidence
     * and its reasoning. Decisions below the confidence threshold are labelled as escalated.
     *
     * @param decision the decision
     * @return the node summary
     */
    public String decisionNodeSummary(ManagerDecision decision) {
        String prefix = managerService.meetsConfidenceThreshold(decision)
                ? "Decision: " : "Escalated (low confidence): ";
        return prefix + decisionLabel(decision)
                + String.format(" [confidence %.0f%%]", decision.confidence() * 100)
                + " — " + decision.reasoning();
    }
}
