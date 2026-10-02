package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.tracing.TraceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Finalizes the trace bookkeeping for a task that has reached a terminal state.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>The task's own {@code task} trace node is always completed with the task's final status.</li>
 *   <li>Workflow tasks never complete the trace: the workflow run owns its trace lifecycle.</li>
 *   <li>Otherwise the trace is completed only once no other {@code task} node in the trace is still
 *       in progress (a Manager evaluation may create several tasks under one trace). The final status
 *       is {@code failed} if any task node in the trace failed.</li>
 *   <li>A trace that is already in a final state is never completed a second time.</li>
 * </ul>
 */
@ApplicationScoped
public class TaskTraceFinalizer {

    private static final Logger LOG = Logger.getLogger(TaskTraceFinalizer.class);

    @Inject
    TraceService traceService;

    /**
     * Completes the task's trace node and, when appropriate, the trace itself. Best-effort:
     * failures are logged and never propagated to the caller.
     *
     * @param task       the task that reached a final state
     * @param nodeStatus final status for the task node (e.g. "completed", "failed", "cancelled")
     */
    public void finalizeTaskTrace(TaskEntity task, String nodeStatus) {
        if (task == null || task.traceId == null) {
            return;
        }
        try {
            TraceNodeEntity taskNode = TraceNodeEntity.find(
                    "traceId = ?1 and nodeType = 'task' and entityType = 'task' and entityId = ?2",
                    task.traceId, String.valueOf(task.id)).firstResult();
            if (taskNode != null) {
                traceService.completeNode(taskNode.id, nodeStatus);
            }

            // Workflow runs own their trace lifecycle: WorkflowExecutionService
            // completes the trace when the run reaches a terminal state.
            if (task.workflowRunId != null) {
                return;
            }

            long openTaskNodes = TraceNodeEntity.count(
                    "traceId = ?1 and nodeType = 'task' and status = 'in-progress'"
                            + (taskNode != null ? " and id <> ?2" : ""),
                    taskNode != null
                            ? new Object[] { task.traceId, taskNode.id }
                            : new Object[] { task.traceId });
            if (openTaskNodes > 0) {
                LOG.debugf("Trace %s still has %d open task node(s); not completing yet",
                        task.traceId, openTaskNodes);
                return;
            }

            TraceEntity trace = TraceEntity.findById(task.traceId);
            if (trace != null && !"in-progress".equals(trace.status)) {
                return;
            }

            boolean anyFailed = !"completed".equals(nodeStatus)
                    || TraceNodeEntity.count(
                            "traceId = ?1 and nodeType = 'task' and status = 'failed'",
                            task.traceId) > 0;
            traceService.completeTrace(task.traceId, anyFailed ? "failed" : "completed");
        } catch (Exception e) {
            LOG.warnf(e, "Failed to finalize trace for task %d", task.id);
        }
    }
}
