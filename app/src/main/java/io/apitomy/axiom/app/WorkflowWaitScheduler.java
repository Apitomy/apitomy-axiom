package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.WorkflowWaitEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * Polls for {@link WorkflowWaitEntity} rows whose wait duration has elapsed
 * and resumes the corresponding workflow branch. Each due wait is resumed in
 * its own transaction that deletes the row and advances the workflow
 * instance together, so a resumption is never applied twice.
 */
@ApplicationScoped
public class WorkflowWaitScheduler {

    private static final Logger LOG = Logger.getLogger(WorkflowWaitScheduler.class);

    @Inject
    WorkflowExecutionService workflowExecutionService;

    private volatile boolean shuttingDown = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    @Scheduled(every = "${axiom.workflow.wait-poll-interval:10s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void checkDueWaits() {
        if (shuttingDown) {
            return;
        }
        for (long waitId : findDueWaitIds()) {
            resumeWait(waitId);
        }
    }

    List<Long> findDueWaitIds() {
        return WorkflowWaitEntity
                .<WorkflowWaitEntity>list("resumeAt <= ?1", Instant.now())
                .stream().map(w -> w.id).toList();
    }

    /**
     * Resumes a single wait, deleting its row and advancing the workflow in
     * one transaction. Re-checks the row still exists first, since a prior
     * (possibly concurrent, possibly retried) invocation may have already
     * consumed it.
     */
    @Transactional
    void resumeWait(long waitId) {
        WorkflowWaitEntity wait = WorkflowWaitEntity.findById(waitId);
        if (wait == null) {
            return;
        }
        long runId = wait.runId;
        String nodeId = wait.nodeId;
        wait.delete();

        try {
            workflowExecutionService.onWaitElapsed(runId, nodeId);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to resume wait node %s for run %d", nodeId, runId);
            throw e;
        }
    }
}
