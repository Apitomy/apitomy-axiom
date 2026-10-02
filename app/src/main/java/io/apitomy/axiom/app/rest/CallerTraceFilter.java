package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Reads the caller's trace from the {@value #TRACE_HEADER} and {@value #PARENT_NODE_HEADER} request
 * headers, validates it and stores it in the request-scoped {@link CallerTraceContext}.
 * <p>
 * The filter never fails a request: invalid or unknown values are logged at debug level, removed from
 * the request headers (so typed header parameters cannot reject the request) and otherwise ignored.
 */
@Provider
public class CallerTraceFilter implements ContainerRequestFilter {

    /** Header carrying the caller's trace ID. */
    public static final String TRACE_HEADER = "X-Axiom-Trace-Id";

    /** Header carrying the caller's current trace node ID. */
    public static final String PARENT_NODE_HEADER = "X-Axiom-Parent-Node-Id";

    private static final Logger LOG = Logger.getLogger(CallerTraceFilter.class);

    @Inject
    CallerTraceContext callerTraceContext;

    /**
     * Validates the caller trace headers and populates {@link CallerTraceContext} when they are valid.
     *
     * @param requestContext the request context
     */
    @Override
    public void filter(ContainerRequestContext requestContext) {
        String traceHeader = requestContext.getHeaderString(TRACE_HEADER);
        String parentHeader = requestContext.getHeaderString(PARENT_NODE_HEADER);
        if (traceHeader == null && parentHeader == null) {
            return;
        }
        try {
            TraceContext ctx = resolve(traceHeader, parentHeader);
            if (ctx != null) {
                callerTraceContext.set(ctx);
                return;
            }
        } catch (Exception e) {
            LOG.debugf(e, "Failed to resolve caller trace %s / %s", traceHeader, parentHeader);
        }
        // Invalid: drop the headers so they cannot affect request handling.
        requestContext.getHeaders().remove(TRACE_HEADER);
        requestContext.getHeaders().remove(PARENT_NODE_HEADER);
    }

    private TraceContext resolve(String traceHeader, String parentHeader) {
        if (traceHeader == null || traceHeader.isBlank()) {
            LOG.debugf("Ignoring %s without %s", PARENT_NODE_HEADER, TRACE_HEADER);
            return null;
        }
        UUID traceId;
        try {
            traceId = UUID.fromString(traceHeader.trim());
        } catch (IllegalArgumentException e) {
            LOG.debugf("Ignoring malformed %s header: %s", TRACE_HEADER, traceHeader);
            return null;
        }
        Long requestedParent = null;
        if (parentHeader != null && !parentHeader.isBlank()) {
            try {
                requestedParent = Long.valueOf(parentHeader.trim());
            } catch (NumberFormatException e) {
                LOG.debugf("Ignoring malformed %s header: %s", PARENT_NODE_HEADER, parentHeader);
                return null;
            }
        }
        final Long parentId = requestedParent;
        Long resolvedParent = QuarkusTransaction.requiringNew().call(() -> {
            TraceEntity trace = TraceEntity.findById(traceId);
            if (trace == null || !"in-progress".equals(trace.status)) {
                LOG.debugf("Ignoring caller trace %s: unknown or not in progress", traceId);
                return null;
            }
            if (parentId != null) {
                TraceNodeEntity node = TraceNodeEntity.findById(parentId);
                if (node == null || !traceId.equals(node.traceId)) {
                    LOG.debugf("Ignoring parent node %d: not part of trace %s", parentId, traceId);
                    return null;
                }
                return node.id;
            }
            TraceNodeEntity root = TraceNodeEntity.<TraceNodeEntity>find(
                    "traceId = ?1 and parentNodeId is null order by id", traceId).firstResult();
            if (root == null) {
                LOG.debugf("Ignoring caller trace %s: no root node", traceId);
                return null;
            }
            return root.id;
        });
        return resolvedParent == null ? null : new TraceContext(traceId, resolvedParent);
    }
}
