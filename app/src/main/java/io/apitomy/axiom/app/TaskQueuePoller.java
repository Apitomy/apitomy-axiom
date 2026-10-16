package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.TaskEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Polls for pending tasks and dispatches them to agents via the TaskExecutionService.
 * Respects subject-level task serialization — only one active task per subject.
 */
@ApplicationScoped
public class TaskQueuePoller {

    private static final Logger LOG = Logger.getLogger(TaskQueuePoller.class);

    @Inject
    TaskExecutionService taskExecutionService;

    private volatile boolean shuttingDown = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    /**
     * Checks for pending tasks every 5 seconds and dispatches them.
     */
    @Scheduled(every = "${axiom.task-queue.poll-interval:5s}",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void pollTaskQueue() {
        if (shuttingDown) {
            return;
        }
        // Find distinct subject IDs that have pending tasks
        List<Long> subjectIds = TaskEntity.find(
                "select distinct t.subjectId from TaskEntity t where t.status = 'Pending'")
                .project(Long.class)
                .list();

        if (subjectIds.isEmpty()) {
            return;
        }

        LOG.debugf("Task queue: %d subject(s) with pending tasks", subjectIds.size());

        for (Long subjectId : subjectIds) {
            taskExecutionService.executeNextTask(subjectId);
        }
    }
}
