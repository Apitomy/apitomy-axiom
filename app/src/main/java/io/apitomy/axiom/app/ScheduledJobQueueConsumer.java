package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ScheduledJobEntity;
import io.apitomy.axiom.core.entities.ScheduledJobRunEntity;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Sequential scheduled job execution queue. Uses a {@link BlockingQueue} with a
 * daemon consumer thread that blocks on {@code take()} until a run ID
 * is enqueued. Runs execute one at a time in FIFO order.
 *
 * <p>On startup, any runs left in "Pending" status from a previous application
 * run are re-enqueued automatically.</p>
 */
@ApplicationScoped
public class ScheduledJobQueueConsumer {

    private static final Logger LOG = Logger.getLogger(ScheduledJobQueueConsumer.class);

    private final BlockingQueue<Long> queue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private Thread consumerThread;

    @Inject
    ScheduledJobExecutionService executionService;

    /**
     * Enqueues a job run for execution. The consumer thread will pick it up
     * and execute it when the current run (if any) completes.
     *
     * @param runId the scheduled job run entity ID to execute
     */
    public void enqueue(Long runId) {
        LOG.debugf("Enqueuing scheduled job run %d for execution", runId);
        queue.add(runId);
    }

    /**
     * Starts the consumer thread and re-enqueues any pending runs from
     * a previous application run.
     */
    @Transactional
    void onStart(@Observes StartupEvent event) {
        ScheduledJobRunEntity.<ScheduledJobRunEntity>list(
                        "status = 'Pending' order by createdOn asc")
                .forEach(r -> {
                    LOG.infof("Re-enqueuing pending scheduled job run %d from previous run",
                            r.id);
                    queue.add(r.id);
                });

        consumerThread = new Thread(this::consumeLoop, "scheduled-job-queue-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        LOG.info("Scheduled job queue consumer started");
    }

    /**
     * Signals the consumer thread to stop.
     */
    void onStop(@Observes ShutdownEvent event) {
        running = false;
        consumerThread.interrupt();
    }

    /**
     * Consumer loop — blocks on {@code take()} until a run ID arrives,
     * then executes it synchronously before taking the next one.
     */
    private void consumeLoop() {
        while (running) {
            try {
                Long runId = queue.take();

                ManagedContext requestContext = Arc.container().requestContext();
                requestContext.activate();
                try {
                    executeRun(runId);
                } finally {
                    requestContext.terminate();
                }
            } catch (InterruptedException e) {
                if (!running) {
                    LOG.info("Scheduled job queue consumer shutting down");
                    return;
                }
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.errorf(e, "Unexpected error in scheduled job queue consumer");
            }
        }
    }

    /**
     * Executes a single job run synchronously. Looks up the job entity,
     * launches execution, and waits for the async result to complete.
     */
    private void executeRun(Long runId) {
        try {
            ScheduledJobEntity job = lookupJob(runId);
            if (job == null) {
                LOG.warnf("Job for run %d not found, skipping", runId);
                return;
            }

            LOG.infof("Starting scheduled job execution: '%s' (run ID: %d)",
                    job.name, runId);

            executionService.executeRun(job, runId);

            waitForCompletion(runId);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to execute scheduled job run %d", runId);
        }
    }

    /**
     * Looks up the job entity for a given run ID.
     */
    @Transactional
    ScheduledJobEntity lookupJob(Long runId) {
        ScheduledJobRunEntity run = ScheduledJobRunEntity.findById(runId);
        if (run == null) {
            return null;
        }
        ScheduledJobEntity job = ScheduledJobEntity.findById(run.jobId);
        if (job == null) {
            LOG.warnf("Run %d references missing job %d, marking as failed",
                    runId, run.jobId);
            run.status = "Failed";
            run.error = "Scheduled job not found.";
        }
        return job;
    }

    /**
     * Waits for a run to leave the "Running" or "Pending" status,
     * checking every 5 seconds with a 30-minute timeout.
     */
    private void waitForCompletion(Long runId) {
        long maxWaitMs = 30 * 60 * 1000L;
        long waited = 0;
        long pollMs = 5000;

        while (waited < maxWaitMs && running) {
            try {
                Thread.sleep(pollMs);
                waited += pollMs;
            } catch (InterruptedException e) {
                if (!running) return;
                Thread.currentThread().interrupt();
                return;
            }

            String status = checkStatus(runId);
            if (status == null || "Completed".equals(status) || "Failed".equals(status)) {
                LOG.infof("Scheduled job run %d finished with status: %s", runId, status);
                return;
            }
        }

        LOG.warnf("Scheduled job run %d did not complete within 30 minutes", runId);
    }

    @Transactional
    String checkStatus(Long runId) {
        ScheduledJobRunEntity run = ScheduledJobRunEntity.findById(runId);
        return run != null ? run.status : null;
    }
}
