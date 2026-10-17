package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.List;

/**
 * Deletes pending human (Inbox) tasks whose workflow run can no longer consume them.
 *
 * <p>A human-task node parks its run and creates an {@code AwaitingInput} task. If the run then
 * ends without that task being completed (cancelled, failed), the task can never be completed
 * or dismissed, so it is deleted together with the run's other parked state. A one-time startup
 * pass removes such tasks left behind in existing databases.
 *
 * <p>No foreign keys point at {@code task}; the free-form {@code task_id} references held by
 * activity log, AI usage and routing outcome rows are cleared so nothing dangles.
 */
@ApplicationScoped
public class OrphanedHumanTaskCleanup {

    private static final Logger LOG = Logger.getLogger(OrphanedHumanTaskCleanup.class);

    /** Statuses of a human task still waiting for a person. */
    static final List<String> PENDING_HUMAN_TASK_STATUSES = List.of("AwaitingInput");

    @Inject
    Event<SseEvent> sseEvents;

    /**
     * Deletes the pending human tasks of the given workflow runs, clearing references to them.
     * Must be called inside a transaction.
     *
     * @param runIds the workflow run ids whose pending human tasks should be deleted
     * @return the number of tasks deleted
     */
    public int deletePendingHumanTasks(Collection<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return 0;
        }
        List<Long> taskIds = TaskEntity.<TaskEntity>find(
                        "workflowRunId in ?1 and status in ?2", runIds, PENDING_HUMAN_TASK_STATUSES)
                .stream()
                .map(task -> task.id)
                .toList();
        return deleteTasks(taskIds);
    }

    /**
     * Deletes pending human tasks whose workflow run has already reached a terminal state.
     *
     * @return the number of tasks deleted
     */
    @Transactional
    public int cleanupOrphans() {
        List<Long> taskIds = TaskEntity.getEntityManager().createQuery(
                        "select t.id from TaskEntity t where t.status in :pending "
                                + "and t.workflowRunId in (select r.id from WorkflowRunEntity r "
                                + "where r.status in :finished)", Long.class)
                .setParameter("pending", PENDING_HUMAN_TASK_STATUSES)
                .setParameter("finished", HistoryCleanup.FINISHED_WORKFLOW_RUN_STATUSES)
                .getResultList();
        int deleted = deleteTasks(taskIds);
        if (deleted > 0) {
            LOG.infof("Deleted %d orphaned human task(s) of finished workflow runs", deleted);
        }
        return deleted;
    }

    void onStartup(@Observes StartupEvent event) {
        try {
            cleanupOrphans();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to clean up orphaned human tasks");
        }
    }

    private int deleteTasks(List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return 0;
        }
        ActivityLogEntity.update("taskId = null where taskId in ?1", taskIds);
        AiUsageEntity.update("taskId = null where taskId in ?1", taskIds);
        RoutingOutcomeEntity.update("taskId = null where taskId in ?1", taskIds);
        RoutingOutcomeItemEntity.update("taskId = null where taskId in ?1", taskIds);
        int deleted = (int) TaskEntity.delete("id in ?1", taskIds);
        long inboxCount = TaskEntity.count("status", "AwaitingInput");
        taskIds.forEach(taskId -> sseEvents.fire(SseEvent.inboxUpdated(taskId, "removed", inboxCount)));
        return deleted;
    }
}
