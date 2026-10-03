package io.apitomy.axiom.core.logging;

import org.jboss.logging.MDC;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Scoped logging context that puts Axiom correlation IDs into the logging MDC.
 * <p>
 * Use it with try-with-resources:
 *
 * <pre>{@code
 * try (LogContext ignored = LogContext.create().traceId(task.traceId).taskId(task.id)) {
 *     LOG.info("...");   // carries traceId and taskId
 * }
 * }</pre>
 *
 * Every key set through a {@code LogContext} remembers its previous value, and {@link #close()} restores
 * those values. Contexts can therefore nest, and nothing leaks into the next unit of work that runs on a
 * pooled, virtual or Vert.x thread.
 * <p>
 * <b>Nesting order.</b> Contexts must be closed in the reverse order they were opened (LIFO), which
 * try-with-resources guarantees. An inner context only touches the keys it sets: keys it does not set keep
 * the outer value, and keys it overrides get the outer value back on close. Closing an outer context while an
 * inner one is still open restores the outer's previous values and breaks the inner one's scope; do not do
 * that. A context may be extended after it is opened (for example adding {@code traceId} once a trace has
 * been created); the added keys are restored by the same {@link #close()}.
 * <p>
 * Besides the individual keys, the context maintains the composite key {@value #SUMMARY}, which holds only
 * the correlation keys that are present (for example {@code "[traceId=... taskId=42] "}) and is empty
 * otherwise. The log format uses {@code %X{axiomCtx}} so that log lines stay compact.
 * <p>
 * The MDC is thread-bound (or bound to the Vert.x duplicated context). Work handed to another thread (for
 * example a {@code CompletableFuture} callback) must capture the context with {@link #capture()} and
 * re-apply it with {@link #restore(Map)} or one of the {@code wrap} methods.
 */
public final class LogContext implements AutoCloseable {

    /** MDC key for the trace (correlation) ID. */
    public static final String TRACE_ID = "traceId";
    /** MDC key for the stream event ID. */
    public static final String EVENT_ID = "eventId";
    /** MDC key for the project ID. */
    public static final String PROJECT_ID = "projectId";
    /** MDC key for the task ID. */
    public static final String TASK_ID = "taskId";
    /** MDC key for the scheduled job run ID. */
    public static final String RUN_ID = "runId";
    /** MDC key for the workflow run ID. */
    public static final String WORKFLOW_RUN_ID = "workflowRunId";
    /** MDC key for the report ID. */
    public static final String REPORT_ID = "reportId";
    /** MDC key holding a compact rendering of all present correlation keys, used by the log format. */
    public static final String SUMMARY = "axiomCtx";

    /** All correlation keys, in the order they are rendered in {@link #SUMMARY}. */
    public static final List<String> KEYS = List.of(
            TRACE_ID, EVENT_ID, PROJECT_ID, TASK_ID, RUN_ID, WORKFLOW_RUN_ID, REPORT_ID);

    /** Previous MDC values of the keys changed by this context ({@code null} = absent). */
    private final Map<String, Object> previous = new LinkedHashMap<>();
    private boolean closed;

    private LogContext() {
    }

    /**
     * Creates an empty context. Nothing changes in the MDC until a key is set.
     *
     * @return a new context
     */
    public static LogContext create() {
        return new LogContext();
    }

    /**
     * Sets an MDC key for the lifetime of this context. A {@code null} value removes the key.
     *
     * @param key   the MDC key
     * @param value the value; its {@code toString()} is stored
     * @return this context
     */
    public LogContext put(String key, Object value) {
        if (closed) {
            throw new IllegalStateException("LogContext already closed");
        }
        if (!previous.containsKey(key)) {
            previous.put(key, MDC.get(key));
        }
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value.toString());
        }
        updateSummary();
        return this;
    }

    /**
     * Sets {@value #TRACE_ID}; ignored when {@code value} is null.
     *
     * @param value the trace ID
     * @return this context
     */
    public LogContext traceId(Object value) {
        return putIfNotNull(TRACE_ID, value);
    }

    /**
     * Sets {@value #EVENT_ID}; ignored when {@code value} is null.
     *
     * @param value the event ID
     * @return this context
     */
    public LogContext eventId(Object value) {
        return putIfNotNull(EVENT_ID, value);
    }

    /**
     * Sets {@value #PROJECT_ID}; ignored when {@code value} is null.
     *
     * @param value the project ID
     * @return this context
     */
    public LogContext projectId(Object value) {
        return putIfNotNull(PROJECT_ID, value);
    }

    /**
     * Sets {@value #TASK_ID}; ignored when {@code value} is null.
     *
     * @param value the task ID
     * @return this context
     */
    public LogContext taskId(Object value) {
        return putIfNotNull(TASK_ID, value);
    }

    /**
     * Sets {@value #RUN_ID} (scheduled job run); ignored when {@code value} is null.
     *
     * @param value the scheduled job run ID
     * @return this context
     */
    public LogContext runId(Object value) {
        return putIfNotNull(RUN_ID, value);
    }

    /**
     * Sets {@value #WORKFLOW_RUN_ID}; ignored when {@code value} is null.
     *
     * @param value the workflow run ID
     * @return this context
     */
    public LogContext workflowRunId(Object value) {
        return putIfNotNull(WORKFLOW_RUN_ID, value);
    }

    /**
     * Sets {@value #REPORT_ID}; ignored when {@code value} is null.
     *
     * @param value the report ID
     * @return this context
     */
    public LogContext reportId(Object value) {
        return putIfNotNull(REPORT_ID, value);
    }

    private LogContext putIfNotNull(String key, Object value) {
        return value == null ? this : put(key, value);
    }

    /**
     * Restores every key changed by this context to its previous value. Calling it more than once has no
     * further effect.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        previous.forEach((key, value) -> {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
        updateSummary();
    }

    /**
     * Returns a snapshot of the correlation keys currently in the MDC, for propagation to another thread.
     *
     * @return an immutable map of the present correlation keys
     */
    public static Map<String, String> capture() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String key : KEYS) {
            Object value = MDC.get(key);
            if (value != null) {
                snapshot.put(key, value.toString());
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Applies a snapshot from {@link #capture()} as the complete correlation context: keys missing from the
     * snapshot are removed for the lifetime of the returned context, so stale values on the current thread
     * cannot leak in.
     *
     * @param snapshot the captured keys
     * @return the context to close when the work is done
     */
    public static LogContext restore(Map<String, String> snapshot) {
        LogContext ctx = new LogContext();
        for (String key : KEYS) {
            ctx.put(key, snapshot.get(key));
        }
        return ctx;
    }

    /**
     * Wraps a consumer so that it runs with the correlation context of the calling thread.
     *
     * @param delegate the consumer to wrap
     * @param <T>      the argument type
     * @return the wrapping consumer
     */
    public static <T> Consumer<T> wrap(Consumer<T> delegate) {
        Map<String, String> snapshot = capture();
        return value -> {
            try (LogContext ignored = restore(snapshot)) {
                delegate.accept(value);
            }
        };
    }

    /**
     * Wraps a function so that it runs with the correlation context of the calling thread.
     *
     * @param delegate the function to wrap
     * @param <T>      the argument type
     * @param <R>      the result type
     * @return the wrapping function
     */
    public static <T, R> Function<T, R> wrap(Function<T, R> delegate) {
        Map<String, String> snapshot = capture();
        return value -> {
            try (LogContext ignored = restore(snapshot)) {
                return delegate.apply(value);
            }
        };
    }

    /**
     * Wraps a runnable so that it runs with the correlation context of the calling thread.
     *
     * @param delegate the runnable to wrap
     * @return the wrapping runnable
     */
    public static Runnable wrap(Runnable delegate) {
        Map<String, String> snapshot = capture();
        return () -> {
            try (LogContext ignored = restore(snapshot)) {
                delegate.run();
            }
        };
    }

    private static void updateSummary() {
        StringBuilder sb = new StringBuilder();
        for (String key : KEYS) {
            Object value = MDC.get(key);
            if (value != null) {
                sb.append(sb.length() == 0 ? "[" : " ").append(key).append('=').append(value);
            }
        }
        if (sb.length() == 0) {
            MDC.remove(SUMMARY);
        } else {
            MDC.put(SUMMARY, sb.append("] ").toString());
        }
    }
}
