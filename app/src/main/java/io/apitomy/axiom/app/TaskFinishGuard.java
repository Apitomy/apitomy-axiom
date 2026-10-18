package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * Enforces the lock order for finishing a task: the workflow run row first, then the task row (the order
 * {@code WorkflowExecutionService.cancelWorkflowRun} uses). Finishing a workflow task in the opposite order
 * can deadlock with a concurrent cancel, and without re-checking the task under the run lock a late result
 * would overwrite the cancellation.
 */
final class TaskFinishGuard {

    private static final Logger LOG = Logger.getLogger(TaskFinishGuard.class);

    /** Statuses after which a task's result must no longer be recorded. */
    static final Set<String> FINISHED_TASK_STATUSES = Set.of("Completed", "Failed", HumanTaskStatuses.CANCELLED);

    private TaskFinishGuard() {
    }

    /**
     * Locks the task's workflow run (when it has one) and re-reads the task, then reports whether the task
     * may still be finished. Must be called in the transaction that then updates the task, before the task
     * is modified.
     *
     * @param task the managed task about to be finished
     * @return true when the task can be finished; false when it already finished (e.g. its run was
     *         cancelled), in which case the caller must not record a result or advance the workflow
     */
    static boolean lockRunAndCheckUnfinished(TaskEntity task) {
        if (task.workflowRunId != null) {
            EntityManager em = TaskEntity.getEntityManager();
            WorkflowRunEntity.findById(task.workflowRunId, LockModeType.PESSIMISTIC_WRITE);
            // Write any pending change of the caller's transaction (now that the run is locked), then
            // re-read the task, which a racing cancel holding the same lock may have finished.
            em.flush();
            em.refresh(task);
        }
        if (FINISHED_TASK_STATUSES.contains(task.status)) {
            LOG.infof("Task %d is already %s; not recording its late result", task.id, task.status);
            return false;
        }
        return true;
    }
}
