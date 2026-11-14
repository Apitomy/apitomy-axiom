package io.apitomy.axiom.app.summary;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Picks up pending run summaries and generates them in the background, off the scheduler thread. */
@ApplicationScoped
public class RunSummaryPoller {

    private static final Logger LOG = Logger.getLogger(RunSummaryPoller.class);

    @Inject
    RunSummaryService service;

    @ConfigProperty(name = "axiom.run-summary.batch-size", defaultValue = "2")
    int batchSize;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean shuttingDown;

    /**
     * Re-queues summaries interrupted by a previous shutdown.
     *
     * @param event the startup event
     */
    void onStartup(@Observes StartupEvent event) {
        try {
            int reset = service.recoverInterrupted();
            if (reset > 0) {
                LOG.infof("Re-queued %d interrupted run summaries", reset);
            }
        } catch (RuntimeException e) {
            LOG.warnf(e, "Failed to recover interrupted run summaries");
        }
    }

    /** Stops accepting work and interrupts in-flight generations (re-queued on the next startup). */
    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.debug("Run summary generations still running at shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Claims up to the free batch capacity and generates each claimed summary on a virtual thread. */
    @Scheduled(every = "${axiom.run-summary.poll-interval:5s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        if (shuttingDown || !service.isEnabled()) {
            return;
        }
        int free = batchSize - inFlight.get();
        if (free <= 0) {
            return;
        }
        List<Long> claimed = service.claimPending(free);
        for (Long runId : claimed) {
            inFlight.incrementAndGet();
            try {
                executor.submit(() -> {
                    try {
                        service.generate(runId);
                    } catch (RuntimeException e) {
                        LOG.errorf(e, "Run summary generation failed for run %d", runId);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            } catch (RejectedExecutionException e) {
                // Shutting down: the row stays generating and is re-queued on the next startup.
                inFlight.decrementAndGet();
            }
        }
    }
}
