package io.apitomy.axiom.core.logging;

import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LogContext}.
 */
class LogContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void setsAndRestoresKeys() {
        UUID traceId = UUID.randomUUID();
        try (LogContext ignored = LogContext.create().traceId(traceId).taskId(42L)) {
            assertEquals(traceId.toString(), MDC.get(LogContext.TRACE_ID));
            assertEquals("42", MDC.get(LogContext.TASK_ID));
            assertEquals("[traceId=" + traceId + " taskId=42] ", MDC.get(LogContext.SUMMARY));
        }
        assertNull(MDC.get(LogContext.TRACE_ID));
        assertNull(MDC.get(LogContext.TASK_ID));
        assertNull(MDC.get(LogContext.SUMMARY));
        assertTrue(MDC.getMap().isEmpty());
    }

    @Test
    void nullValuesAreIgnored() {
        try (LogContext ignored = LogContext.create().traceId(null).projectId(7L)) {
            assertNull(MDC.get(LogContext.TRACE_ID));
            assertEquals("[projectId=7] ", MDC.get(LogContext.SUMMARY));
        }
        assertTrue(MDC.getMap().isEmpty());
    }

    @Test
    void nestedContextsRestoreOuterValues() {
        try (LogContext outer = LogContext.create().traceId("t-outer").projectId(1L)) {
            try (LogContext inner = LogContext.create().traceId("t-inner").taskId(5L)) {
                assertEquals("t-inner", MDC.get(LogContext.TRACE_ID));
                assertEquals("1", MDC.get(LogContext.PROJECT_ID));
                assertEquals("5", MDC.get(LogContext.TASK_ID));
            }
            assertEquals("t-outer", MDC.get(LogContext.TRACE_ID));
            assertNull(MDC.get(LogContext.TASK_ID));
            assertEquals("[traceId=t-outer projectId=1] ", MDC.get(LogContext.SUMMARY));
        }
        assertTrue(MDC.getMap().isEmpty());
    }

    @Test
    void clearsOnException() {
        assertThrows(IllegalStateException.class, () -> {
            try (LogContext ignored = LogContext.create().traceId("t1").reportId(3L)) {
                throw new IllegalStateException("boom");
            }
        });
        assertTrue(MDC.getMap().isEmpty());
    }

    @Test
    void closeIsIdempotent() {
        LogContext outer = LogContext.create().traceId("a");
        LogContext inner = LogContext.create().traceId("b");
        inner.close();
        inner.close();
        assertEquals("a", MDC.get(LogContext.TRACE_ID));
        outer.close();
        assertTrue(MDC.getMap().isEmpty());
    }

    @Test
    void restoreReplacesStaleThreadValues() {
        Map<String, String> snapshot;
        try (LogContext ignored = LogContext.create().traceId("t1").taskId(9L)) {
            snapshot = LogContext.capture();
        }
        try (LogContext stale = LogContext.create().traceId("stale").runId(77L)) {
            try (LogContext restored = LogContext.restore(snapshot)) {
                assertEquals("t1", MDC.get(LogContext.TRACE_ID));
                assertEquals("9", MDC.get(LogContext.TASK_ID));
                assertNull(MDC.get(LogContext.RUN_ID));
            }
            assertEquals("stale", MDC.get(LogContext.TRACE_ID));
            assertEquals("77", MDC.get(LogContext.RUN_ID));
        }
        assertTrue(MDC.getMap().isEmpty());
    }

    @Test
    void wrapPropagatesToOtherThreadAndCleansUp() throws Exception {
        AtomicReference<Object> seen = new AtomicReference<>();
        AtomicReference<Object> after = new AtomicReference<>("unset");
        Consumer<String> callback;
        try (LogContext ignored = LogContext.create().traceId("t-async")) {
            callback = LogContext.wrap((String s) -> seen.set(MDC.get(LogContext.TRACE_ID)));
        }
        Thread thread = new Thread(() -> {
            callback.accept("x");
            after.set(MDC.get(LogContext.TRACE_ID));
        });
        thread.start();
        thread.join();
        assertEquals("t-async", seen.get());
        assertNull(after.get());

        CompletableFuture<Void> future = CompletableFuture.completedFuture("v").thenAccept(callback);
        future.get();
        assertTrue(MDC.getMap().isEmpty());
    }
}
