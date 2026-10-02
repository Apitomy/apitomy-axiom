package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.core.tracing.TraceContext;
import jakarta.enterprise.context.RequestScoped;

import java.util.Optional;

/**
 * Request-scoped holder for the trace of the agent (or other caller) making the current API request.
 */
@RequestScoped
public class CallerTraceContext {

    private TraceContext traceContext;

    /**
     * Returns the validated caller trace context, if the request carried one.
     *
     * @return the caller trace context, or empty
     */
    public Optional<TraceContext> get() {
        return Optional.ofNullable(traceContext);
    }

    /**
     * Sets the validated caller trace context.
     *
     * @param traceContext the trace context to use for this request
     */
    public void set(TraceContext traceContext) {
        this.traceContext = traceContext;
    }
}
