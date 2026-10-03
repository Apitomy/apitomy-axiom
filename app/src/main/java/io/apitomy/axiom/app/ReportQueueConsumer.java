package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ReportDefinitionEntity;
import io.apitomy.axiom.core.entities.ReportEntity;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import io.apitomy.axiom.core.logging.LogContext;
import org.jboss.logging.Logger;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Sequential report execution queue. Uses a {@link BlockingQueue} with a
 * daemon consumer thread that blocks on {@code take()} until a report ID
 * is enqueued. Reports execute one at a time in FIFO order.
 *
 * <p>On startup, any reports left in "Pending" status from a previous run
 * are re-enqueued automatically.</p>
 */
@ApplicationScoped
public class ReportQueueConsumer {

    private static final Logger LOG = Logger.getLogger(ReportQueueConsumer.class);

    private final BlockingQueue<Long> queue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private Thread consumerThread;

    @Inject
    ReportExecutionService reportExecutionService;

    /**
     * Enqueues a report for generation. The consumer thread will pick it up
     * and execute it when the current report (if any) completes.
     *
     * @param reportId the report entity ID to generate
     */
    public void enqueue(Long reportId) {
        LOG.debugf("Enqueuing report %d for generation", reportId);
        queue.add(reportId);
    }

    /**
     * Starts the consumer thread and re-enqueues any pending reports from
     * a previous application run.
     */
    @Transactional
    void onStart(@Observes StartupEvent event) {
        // Re-enqueue any reports that were pending when the app last stopped
        ReportEntity.<ReportEntity>list("status = 'Pending' order by createdOn asc")
                .forEach(r -> {
                    LOG.infof("Re-enqueuing pending report %d from previous run", r.id);
                    queue.add(r.id);
                });

        consumerThread = new Thread(this::consumeLoop, "report-queue-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        LOG.info("Report queue consumer started");
    }

    /**
     * Signals the consumer thread to stop.
     */
    void onStop(@Observes ShutdownEvent event) {
        running = false;
        consumerThread.interrupt();
    }

    /**
     * Consumer loop — blocks on {@code take()} until a report ID arrives,
     * then executes it synchronously before taking the next one.
     */
    private void consumeLoop() {
        while (running) {
            try {
                Long reportId = queue.take();

                // Activate a CDI request context for this thread so that
                // Panache/Hibernate sessions and transactions work correctly.
                ManagedContext requestContext = Arc.container().requestContext();
                requestContext.activate();
                try {
                    try (LogContext ignored = LogContext.create().reportId(reportId)) {
                        executeReport(reportId);
                    }
                } finally {
                    requestContext.terminate();
                }
            } catch (InterruptedException e) {
                if (!running) {
                    LOG.info("Report queue consumer shutting down");
                    return;
                }
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.errorf(e, "Unexpected error in report queue consumer");
            }
        }
    }

    /**
     * Executes a single report synchronously. Looks up the definition,
     * launches generation, and waits for the async result to complete.
     */
    private void executeReport(Long reportId) {
        try {
            ReportDefinitionEntity definition = lookupDefinition(reportId);
            if (definition == null) {
                LOG.warnf("Report %d not found, skipping", reportId);
                return;
            }

            LOG.infof("Starting report generation: '%s' (report ID: %d)",
                    definition.name, reportId);

            reportExecutionService.generateReport(definition, reportId);

            // Wait for the report to finish (generation is async)
            waitForCompletion(reportId);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to execute report %d", reportId);
        }
    }

    @Transactional
    ReportDefinitionEntity lookupDefinition(Long reportId) {
        ReportEntity report = ReportEntity.findById(reportId);
        if (report == null) {
            return null;
        }
        ReportDefinitionEntity definition = ReportDefinitionEntity.findById(report.definitionId);
        if (definition == null) {
            LOG.warnf("Report %d references missing definition %d, marking as failed",
                    reportId, report.definitionId);
            report.status = "Failed";
            report.content = "Report definition not found.";
        }
        return definition;
    }

    /**
     * Waits for a report to leave the "Generating" or "Pending" status,
     * checking every 5 seconds with a 30-minute timeout.
     */
    private void waitForCompletion(Long reportId) {
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

            String status = checkStatus(reportId);
            if (status == null || "Completed".equals(status) || "Failed".equals(status)) {
                LOG.infof("Report %d finished with status: %s", reportId, status);
                return;
            }
        }

        LOG.warnf("Report %d did not complete within 30 minutes", reportId);
    }

    @Transactional
    String checkStatus(Long reportId) {
        ReportEntity report = ReportEntity.findById(reportId);
        return report != null ? report.status : null;
    }
}
