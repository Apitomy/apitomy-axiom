package io.apitomy.axiom.app;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.PessimisticLockException;
import org.hibernate.exception.LockAcquisitionException;
import org.jboss.logging.Logger;

/**
 * Shared helper for the scheduled retention/cleanup jobs. Runs a unit of work in
 * a new transaction and retries a bounded number of times when the database
 * reports a deadlock ({@link LockAcquisitionException} /
 * {@link PessimisticLockException}).
 *
 * <p>Deadlocks are expected under concurrent event ingestion (H2's MVStore uses
 * row-level locking with deadlock detection) and are safely retryable. If all
 * retries are exhausted the failure is logged at {@code WARN} rather than
 * {@code ERROR}, because the next scheduled tick will recover the deferred work.
 */
final class CleanupRetry {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MILLIS = 200L;

    private CleanupRetry() {
    }

    /**
     * Executes {@code work} in a new transaction, retrying on database deadlocks.
     *
     * @param log     the logger of the calling cleanup job
     * @param jobName a human-readable name for the job, used in log messages
     * @param work    the cleanup work to run inside a transaction
     */
    static void runWithRetry(Logger log, String jobName, Runnable work) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                QuarkusTransaction.requiringNew().run(work);
                return;
            } catch (RuntimeException e) {
                if (!isDeadlock(e)) {
                    throw e;
                }
                if (attempt == MAX_ATTEMPTS) {
                    log.warnf("%s skipped after %d attempts due to a database deadlock; "
                            + "retention is deferred to the next scheduled run", jobName, MAX_ATTEMPTS);
                    return;
                }
                log.debugf("%s hit a database deadlock on attempt %d/%d; retrying",
                        jobName, attempt, MAX_ATTEMPTS);
                sleep(BACKOFF_MILLIS * attempt);
            }
        }
    }

    private static boolean isDeadlock(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof LockAcquisitionException || cause instanceof PessimisticLockException) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
