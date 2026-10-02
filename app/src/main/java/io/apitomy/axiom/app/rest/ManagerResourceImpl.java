package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.ManagerResource;
import io.apitomy.axiom.api.beans.ManagerConfig;
import io.apitomy.axiom.app.ManagerTraceRecorder;
import io.apitomy.axiom.core.entities.ManagerConfigEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.manager.ManagerDecision;
import io.apitomy.axiom.manager.ManagerEvaluationResult;
import io.apitomy.axiom.manager.ManagerPromptBuilder;
import io.apitomy.axiom.manager.ManagerService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

/**
 * Implementation of the Manager REST API, including configuration
 * and the debug evaluation endpoint.
 */
@ApplicationScoped
@RunOnVirtualThread
public class ManagerResourceImpl implements ManagerResource {

    /** Response header carrying the ID of the {@code manager-dry-run} trace. */
    static final String TRACE_ID_HEADER = "X-Axiom-Trace-Id";

    /** Trace type of a manual (dry-run) Manager evaluation. */
    static final String DRY_RUN_TRACE_TYPE = "manager-dry-run";

    /** Prefix marking the nodes of a dry-run trace. */
    static final String DRY_RUN_PREFIX = "[dry run] ";

    /**
     * Who triggered the evaluation. Axiom has no authentication, so there is no user identity
     * to record; every evaluation through this endpoint is recorded as "manual".
     */
    static final String TRIGGERED_BY = "manual";

    @Inject
    ManagerService managerService;

    @Inject
    ManagerTraceRecorder traceRecorder;

    /**
     * {@inheritDoc}
     */
    @Override
    public ManagerConfig getManagerConfig() {
        ManagerConfigEntity entity = ManagerConfigEntity.<ManagerConfigEntity>findAll()
                .firstResult();

        ManagerConfig config = new ManagerConfig();
        if (entity != null) {
            config.setSystemPrompt(entity.systemPrompt);
            config.setPromptTemplate(entity.promptTemplate);
        } else {
            config.setSystemPrompt(ManagerPromptBuilder.DEFAULT_SYSTEM_PROMPT);
            config.setPromptTemplate(ManagerPromptBuilder.DEFAULT_PROMPT_TEMPLATE);
        }
        return config;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public ManagerConfig updateManagerConfig(ManagerConfig data) {
        ManagerConfigEntity entity = ManagerConfigEntity.<ManagerConfigEntity>findAll()
                .firstResult();
        if (entity == null) {
            entity = new ManagerConfigEntity();
        }
        entity.systemPrompt = data.getSystemPrompt();
        entity.promptTemplate = data.getPromptTemplate();
        entity.persist();

        return data;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Response evaluateEvent(String eventId) {
        UUID uuid;
        try {
            uuid = UUID.fromString(eventId);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("Invalid event ID format: " + eventId, 400);
        }
        StreamEventEntity event = StreamEventEntity.findById(uuid);
        if (event == null) {
            throw new WebApplicationException("Event not found: " + eventId, 404);
        }
        // Dry run: record a trace of the evaluation, but never act on the decisions
        // (no tasks, routing outcome or ledger entry).
        String eventLabel = event.type + " — " + event.ref;
        TraceContext traceCtx = traceRecorder.startTrace(DRY_RUN_TRACE_TYPE,
                "Manager dry run (" + TRIGGERED_BY + "): " + eventLabel,
                DRY_RUN_PREFIX + "Event: " + eventLabel, event);
        Long evalNodeId = traceRecorder.addNode(traceCtx, "manager-evaluation",
                DRY_RUN_PREFIX + "Manager evaluation: " + event.type);

        List<ManagerDecision> decisions = evaluateAndTrace(event, traceCtx, evalNodeId);

        // The response stays a plain decision list; a failed evaluation returns an empty list.
        Response.ResponseBuilder response = Response.ok(decisions);
        if (traceCtx != null) {
            response.header(TRACE_ID_HEADER, traceCtx.traceId().toString());
        }
        return response.build();
    }

    /**
     * Runs the Manager and records the evaluation and decision nodes. Every node and the trace
     * are completed (or failed) before returning.
     */
    private List<ManagerDecision> evaluateAndTrace(StreamEventEntity event, TraceContext traceCtx,
                                                   Long evalNodeId) {
        ManagerEvaluationResult evaluation;
        try {
            evaluation = managerService.evaluateStreamEvent(event,
                    traceCtx != null ? traceCtx.traceId() : null);
        } catch (RuntimeException e) {
            traceRecorder.failNode(evalNodeId, "Manager evaluation error: " + e.getMessage(), null);
            traceRecorder.completeTrace(traceCtx, "failed");
            throw e;
        }
        if (evaluation == null || evaluation.failed()) {
            String error = evaluation != null && evaluation.errorMessage() != null
                    ? evaluation.errorMessage() : "Manager evaluation failed";
            traceRecorder.failNode(evalNodeId, error,
                    evaluation != null ? evaluation.activityLogId() : null);
            traceRecorder.completeTrace(traceCtx, "failed");
            return evaluation != null ? evaluation.decisions() : List.of();
        }
        traceRecorder.completeNode(evalNodeId, "completed", evaluation.activityLogId());

        // Decision nodes are children of the evaluation node, or of the root if the
        // evaluation node could not be created (as in the event pipeline).
        if (traceCtx != null) {
            if (evalNodeId != null) {
                traceCtx.push(evalNodeId);
            }
            try {
                for (ManagerDecision decision : evaluation.decisions()) {
                    Long decisionNodeId = traceRecorder.addNode(traceCtx, "manager-decision",
                            DRY_RUN_PREFIX + traceRecorder.decisionNodeSummary(decision));
                    traceRecorder.completeNode(decisionNodeId, "completed", null);
                }
            } finally {
                if (evalNodeId != null) {
                    traceCtx.pop();
                }
            }
        }
        traceRecorder.completeTrace(traceCtx, "completed");
        return evaluation.decisions();
    }
}
