package io.apitomy.axiom.app;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.PessimisticLockException;
import org.hibernate.exception.LockAcquisitionException;
import org.hibernate.exception.LockTimeoutException;
import org.jboss.logging.Logger;

import java.sql.SQLException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Shared helper for the scheduled retention/cleanup jobs. Runs a unit of work in
 * a new transaction and retries a bounded number of times when the database
 * reports a retryable lock failure (a deadlock or a lock-acquisition timeout).
 *
 * <p>Lock contention is expected under concurrent event ingestion (H2's MVStore
 * uses row-level locking with deadlock detection and lock timeouts) and is
 * safely retryable. Both true deadlocks ({@link LockAcquisitionException} /
 * {@link PessimisticLockException}) and lock-acquisition timeouts
 * ({@link LockTimeoutException} / {@link jakarta.persistence.LockTimeoutException})
 * are treated as retryable; as a defensive fallback the underlying
 * {@link SQLException} is also inspected for H2's deadlock (40001) and
 * lock-timeout (50200) error codes in case a future Hibernate version maps them
 * to a different exception type.
 *
 * <p>If all retries are exhausted the failure is logged at {@code WARN} rather
 * than {@code ERROR}, because the next scheduled tick will recover the deferred
 * work. Non-retryable exceptions are rethrown unchanged, preserving existing
 * behavior.
 */
final class CleanupRetry {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MILLIS = 200L;

    /** H2 SQL error code for a detected deadlock (also surfaced as SQLState {@code 40001}). */
    private static final int H2_DEADLOCK = 40001;
    /** H2 SQL error code for a lock-acquisition timeout. */
    private static final int H2_LOCK_TIMEOUT = 50200;

    /** Default strategy: run the work in a fresh, independent transaction. */
    private static final Consumer<Runnable> TX_RUNNER =
            work -> QuarkusTransaction.requiringNew().run(work);

    private CleanupRetry() {
    }

    /**
     * Executes {@code work} in a new transaction, retrying on database lock
     * failures (deadlocks and lock-acquisition timeouts).
     *
     * @param log          the logger of the calling cleanup job
     * @param jobName      a human-readable name for the job, used in log messages
     * @param shuttingDown supplies whether the application is shutting down; the
     *                     loop aborts (without further database work) as soon as
     *                     this returns {@code true}
     * @param work         the cleanup work to run inside a transaction
     */
    static void runWithRetry(Logger log, String jobName, BooleanSupplier shuttingDown, Runnable work) {
        runWithRetry(log, jobName, shuttingDown, work, TX_RUNNER);
    }

    /**
     * Package-private overload allowing the transaction strategy to be supplied,
     * so the retry/backoff/classification logic can be unit tested without a
     * running Quarkus transaction manager.
     */
    static void runWithRetry(Logger log, String jobName, BooleanSupplier shuttingDown,
                             Runnable work, Consumer<Runnable> txRunner) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // Re-check on every attempt so retries stop issuing DB work once
            // shutdown has begun.
            if (shuttingDown.getAsBoolean()) {
                return;
            }
            try {
                txRunner.accept(work);
                return;
            } catch (RuntimeException e) {
                if (!isRetryableLockFailure(e)) {
                    throw e;
                }
                if (attempt == MAX_ATTEMPTS) {
                    log.warnf("%s skipped after %d attempts due to database lock contention; "
                            + "retention is deferred to the next scheduled run", jobName, MAX_ATTEMPTS);
                    return;
                }
                log.debugf("%s hit database lock contention on attempt %d/%d; retrying",
                        jobName, attempt, MAX_ATTEMPTS);
                if (!sleep(BACKOFF_MILLIS * attempt)) {
                    // Interrupted (e.g. during shutdown) — stop retrying rather
                    // than continue holding the worker and opening new transactions.
                    return;
                }
            }
        }
    }

    /**
     * Returns {@code true} if the given throwable (or any of its causes)
     * represents a retryable database lock failure.
     */
    static boolean isRetryableLockFailure(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof LockAcquisitionException
                    || cause instanceof LockTimeoutException
                    || cause instanceof PessimisticLockException
                    || cause instanceof jakarta.persistence.LockTimeoutException) {
                return true;
            }
            if (cause instanceof SQLException sqlEx && isRetryableSqlError(sqlEx)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRetryableSqlError(SQLException e) {
        int code = e.getErrorCode();
        if (code == H2_DEADLOCK || code == H2_LOCK_TIMEOUT) {
            return true;
        }
        return "40001".equals(e.getSQLState());
    }

    /**
     * Sleeps for the given duration.
     *
     * @return {@code true} if the sleep completed, {@code false} if it was
     *         interrupted (in which case the interrupt flag is restored)
     */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
