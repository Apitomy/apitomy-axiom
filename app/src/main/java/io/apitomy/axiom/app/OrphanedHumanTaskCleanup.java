package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * Cancels pending human (Inbox) tasks whose workflow run can no longer consume them.
 *
 * <p>A human-task node parks its run and creates an {@code AwaitingInput} task. If the run then
 * ends without that task being completed (cancelled, failed), the task can never be completed,
 * so it is marked {@code Cancelled}. The task row is kept (not deleted) to preserve audit
 * history: run timeline links, the Tasks page and trace lineage still resolve. Since the Inbox
 * only lists {@code AwaitingInput} tasks, a cancelled task leaves the Inbox. A one-time startup
 * pass cancels such tasks left behind in existing databases.
 */
@ApplicationScoped
public class OrphanedHumanTaskCleanup {

    private static final Logger LOG = Logger.getLogger(OrphanedHumanTaskCleanup.class);

    @Inject
    Event<SseEvent> sseEvents;

    @Inject
    Event<HumanTasksCancelled> humanTasksCancelledEvents;

    /**
     * Fired (synchronously, inside the cancelling transaction) when human tasks were cancelled;
     * observed after a successful commit to notify SSE clients.
     *
     * @param tasks the cancelled tasks
     */
    record HumanTasksCancelled(List<CancelledTask> tasks) {
    }

    /**
     * A cancelled human task.
     *
     * @param taskId the task id
     * @param subjectId the task's subject id
     */
    record CancelledTask(Long taskId, Long subjectId) {
    }

    /**
     * Marks the pending human tasks of a workflow run {@code Cancelled}, recording why. Inbox
     * update events are sent once the surrounding transaction commits.
     *
     * @param runId the workflow run id whose pending human tasks should be cancelled
     * @param runOutcome how the run ended, e.g. {@code cancelled} or {@code failed}
     * @return the number of tasks cancelled
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public int cancelPendingHumanTasks(long runId, String runOutcome) {
        List<TaskEntity> tasks = TaskEntity.list("workflowRunId = ?1 and status = ?2",
                runId, HumanTaskStatuses.AWAITING_INPUT);
        if (tasks.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        String output = cancellationOutput(runId, runOutcome);
        tasks.forEach(task -> {
            task.status = HumanTaskStatuses.CANCELLED;
            task.output = output;
            task.completedOn = now;
        });
        humanTasksCancelledEvents.fire(new HumanTasksCancelled(tasks.stream()
                .map(task -> new CancelledTask(task.id, task.subjectId))
                .toList()));
        return tasks.size();
    }

    /**
     * Cancels pending human tasks whose workflow run has already reached a terminal state.
     *
     * @return the number of tasks cancelled
     */
    @Transactional
    public int cancelOrphans() {
        Instant now = Instant.now();
        int cancelled = HistoryCleanup.FINISHED_WORKFLOW_RUN_STATUSES.stream()
                .mapToInt(runStatus -> TaskEntity.getEntityManager().createQuery(
                                "update TaskEntity t set t.status = :cancelled, t.completedOn = :now, "
                                        + "t.output = concat('Cancelled: workflow run ', "
                                        + "str(t.workflowRunId), :suffix) "
                                        + "where t.status = :pending and t.workflowRunId in "
                                        + "(select r.id from WorkflowRunEntity r where r.status = :runStatus)")
                        .setParameter("cancelled", HumanTaskStatuses.CANCELLED)
                        .setParameter("now", now)
                        .setParameter("suffix", " was " + runStatus)
                        .setParameter("pending", HumanTaskStatuses.AWAITING_INPUT)
                        .setParameter("runStatus", runStatus)
                        .executeUpdate())
                .sum();
        if (cancelled > 0) {
            LOG.infof("Cancelled %d orphaned human task(s) of finished workflow runs", cancelled);
        }
        return cancelled;
    }

    /**
     * Startup pass over existing data. Ordering relative to the interrupted-task startup
     * observer (priority {@code PLATFORM_BEFORE - 1}) does not matter: that one only touches
     * {@code InProgress} tasks, this one only {@code AwaitingInput} tasks. The transaction is
     * started explicitly (rather than relying on {@code @Transactional} through a self-call) so a
     * failure is caught here and never aborts startup.
     */
    void onStartup(@Observes StartupEvent event) {
        try {
            QuarkusTransaction.requiringNew().call(this::cancelOrphans);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to cancel orphaned human tasks");
        }
    }

    /**
     * Notifies SSE clients once the cancelling transaction has committed, so they never see a
     * task leave the Inbox that is then restored by a rollback.
     */
    void onHumanTasksCancelled(@Observes(during = TransactionPhase.AFTER_SUCCESS)
                               HumanTasksCancelled cancelled) {
        try {
            long inboxCount = QuarkusTransaction.requiringNew().call(() ->
                    TaskEntity.count("status", HumanTaskStatuses.AWAITING_INPUT));
            cancelled.tasks().forEach(task -> {
                sseEvents.fire(SseEvent.taskUpdated(task.subjectId(), task.taskId(),
                        HumanTaskStatuses.CANCELLED));
                sseEvents.fire(SseEvent.inboxUpdated(task.taskId(), "removed", inboxCount));
            });
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to send Inbox updates for cancelled human tasks");
        }
    }

    static String cancellationOutput(long runId, String runOutcome) {
        return "Cancelled: workflow run " + runId + " was " + runOutcome;
    }
}
