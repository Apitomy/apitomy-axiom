package io.apitomy.axiom.app;

import jakarta.persistence.PessimisticLockException;
import org.hibernate.exception.LockAcquisitionException;
import org.hibernate.exception.LockTimeoutException;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CleanupRetry}. Exercises the retry/backoff loop and the
 * lock-failure classification directly, using an injected transaction-runner
 * strategy so no Quarkus transaction manager is required.
 */
class CleanupRetryTest {

    private static final Logger LOG = Logger.getLogger(CleanupRetryTest.class);
    private static final BooleanSupplier NOT_SHUTTING_DOWN = () -> false;

    // ---------------------------------------------------------------------
    // Retry / backoff loop
    // ---------------------------------------------------------------------

    @Test
    void runsWorkOnceOnSuccess() {
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger txInvocations = new AtomicInteger();
        Consumer<Runnable> txRunner = work -> {
            txInvocations.incrementAndGet();
            work.run();
        };

        CleanupRetry.runWithRetry(LOG, "test", NOT_SHUTTING_DOWN, runs::incrementAndGet, txRunner);

        assertEquals(1, runs.get());
        assertEquals(1, txInvocations.get());
    }

    @Test
    void retriesOnLockFailureThenSucceeds() {
        AtomicInteger txInvocations = new AtomicInteger();
        Consumer<Runnable> txRunner = work -> {
            if (txInvocations.incrementAndGet() == 1) {
                throw deadlock();
            }
            work.run();
        };
        AtomicInteger runs = new AtomicInteger();

        CleanupRetry.runWithRetry(LOG, "test", NOT_SHUTTING_DOWN, runs::incrementAndGet, txRunner);

        assertEquals(2, txInvocations.get());
        assertEquals(1, runs.get());
    }

    @Test
    void exhaustsRetriesWithoutThrowing() {
        AtomicInteger txInvocations = new AtomicInteger();
        Consumer<Runnable> txRunner = work -> {
            txInvocations.incrementAndGet();
            throw deadlock();
        };

        // Should return normally (WARN-logged) rather than propagate.
        CleanupRetry.runWithRetry(LOG, "test", NOT_SHUTTING_DOWN, () -> {
        }, txRunner);

        assertEquals(3, txInvocations.get());
    }

    @Test
    void rethrowsNonRetryableException() {
        AtomicInteger txInvocations = new AtomicInteger();
        RuntimeException boom = new IllegalStateException("boom");
        Consumer<Runnable> txRunner = work -> {
            txInvocations.incrementAndGet();
            throw boom;
        };

        RuntimeException thrown = assertThrows(IllegalStateException.class,
                () -> CleanupRetry.runWithRetry(LOG, "test", NOT_SHUTTING_DOWN, () -> {
                }, txRunner));

        assertEquals(boom, thrown);
        assertEquals(1, txInvocations.get());
    }

    @Test
    void doesNotRunWorkWhenAlreadyShuttingDown() {
        AtomicInteger txInvocations = new AtomicInteger();
        Consumer<Runnable> txRunner = work -> {
            txInvocations.incrementAndGet();
            work.run();
        };

        CleanupRetry.runWithRetry(LOG, "test", () -> true, () -> {
        }, txRunner);

        assertEquals(0, txInvocations.get());
    }

    @Test
    void stopsRetryingOnceShuttingDownMidLoop() {
        AtomicBoolean shuttingDown = new AtomicBoolean(false);
        AtomicInteger txInvocations = new AtomicInteger();
        Consumer<Runnable> txRunner = work -> {
            txInvocations.incrementAndGet();
            // Trigger shutdown after the first (failed) attempt so the loop
            // aborts on its next iteration instead of retrying.
            shuttingDown.set(true);
            throw deadlock();
        };

        CleanupRetry.runWithRetry(LOG, "test", shuttingDown::get, () -> {
        }, txRunner);

        assertEquals(1, txInvocations.get());
    }

    // ---------------------------------------------------------------------
    // Lock-failure classification
    // ---------------------------------------------------------------------

    @Test
    void classifiesHibernateDeadlock() {
        assertTrue(CleanupRetry.isRetryableLockFailure(deadlock()));
    }

    @Test
    void classifiesHibernateLockTimeout() {
        assertTrue(CleanupRetry.isRetryableLockFailure(
                new LockTimeoutException("lock timeout", new SQLException("timeout", null, 50200))));
    }

    @Test
    void classifiesJakartaPessimisticLock() {
        assertTrue(CleanupRetry.isRetryableLockFailure(new PessimisticLockException("locked")));
    }

    @Test
    void classifiesJakartaLockTimeout() {
        assertTrue(CleanupRetry.isRetryableLockFailure(
                new jakarta.persistence.LockTimeoutException("timeout")));
    }

    @Test
    void classifiesNestedCause() {
        Throwable wrapped = new RuntimeException("wrapper",
                new IllegalStateException("mid", deadlock()));
        assertTrue(CleanupRetry.isRetryableLockFailure(wrapped));
    }

    @Test
    void classifiesH2DeadlockByErrorCode() {
        assertTrue(CleanupRetry.isRetryableLockFailure(
                new RuntimeException(new SQLException("deadlock", "40001", 40001))));
    }

    @Test
    void classifiesH2LockTimeoutByErrorCode() {
        assertTrue(CleanupRetry.isRetryableLockFailure(
                new RuntimeException(new SQLException("lock timeout", "HY000", 50200))));
    }

    @Test
    void classifiesBySqlState40001() {
        assertTrue(CleanupRetry.isRetryableLockFailure(
                new RuntimeException(new SQLException("deadlock", "40001", 0))));
    }

    @Test
    void nonLockFailureIsNotRetryable() {
        assertFalse(CleanupRetry.isRetryableLockFailure(new IllegalStateException("boom")));
        assertFalse(CleanupRetry.isRetryableLockFailure(
                new RuntimeException(new SQLException("syntax error", "42000", 42000))));
    }

    private static LockAcquisitionException deadlock() {
        return new LockAcquisitionException("deadlock detected",
                new SQLException("deadlock", "40001", 40001));
    }
}
