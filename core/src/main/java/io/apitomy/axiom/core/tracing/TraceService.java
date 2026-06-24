package io.apitomy.axiom.core.tracing;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.jboss.logging.Logger;

import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Central service for creating and managing execution traces. All write
 * operations use {@code QuarkusTransaction.requiringNew()} so trace data
 * is persisted even if the caller's transaction rolls back.
 */
@ApplicationScoped
public class TraceService {

    private static final Logger LOG = Logger.getLogger(TraceService.class);

    /**
     * Creates a new trace and its root node.
     *
     * @param traceType       trace category (e.g. "event-pipeline")
     * @param summary         human-readable trace summary
     * @param eventId         associated event ID (nullable)
     * @param projectId       associated project ID (nullable)
     * @param reportId        associated report ID (nullable)
     * @param rootNodeType    node type for the root node
     * @param rootNodeSummary summary for the root node
     * @return a {@link TraceContext} with the root node on the stack
     */
    public TraceContext createTrace(String traceType, String summary,
            Long eventId, Long projectId, Long reportId,
            String rootNodeType, String rootNodeSummary) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Instant now = Instant.now();

            TraceEntity trace = new TraceEntity();
            trace.traceId = UUID.randomUUID();
            trace.traceType = traceType;
            trace.status = "in-progress";
            trace.summary = truncate(summary, 1024);
            trace.eventId = eventId;
            trace.projectId = projectId;
            trace.reportId = reportId;
            trace.startedOn = now;
            trace.persist();

            TraceNodeEntity rootNode = new TraceNodeEntity();
            rootNode.traceId = trace.traceId;
            rootNode.parentNodeId = null;
            rootNode.nodeType = rootNodeType;
            rootNode.status = "completed";
            rootNode.summary = truncate(rootNodeSummary, 1024);
            rootNode.startedOn = now;
            rootNode.completedOn = now;
            rootNode.durationMs = 0L;
            rootNode.persist();

            LOG.debugf("Created trace %s (%s) with root node %d",
                    trace.traceId, traceType, rootNode.id);
            return new TraceContext(trace.traceId, rootNode.id);
        });
    }

    /**
     * Adds a child node to an existing trace. The parent is determined by
     * {@code ctx.currentParentNodeId()} (top of the context stack).
     *
     * @param ctx        current trace context
     * @param nodeType   node type identifier
     * @param status     initial status (e.g. "in-progress", "completed")
     * @param summary    human-readable node summary
     * @param entityType type of the referenced detail entity (nullable)
     * @param entityId   ID of the referenced detail entity (nullable)
     * @return the new node's ID
     */
    public Long addNode(TraceContext ctx, String nodeType, String status,
            String summary, String entityType, Long entityId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            TraceNodeEntity node = new TraceNodeEntity();
            node.traceId = ctx.traceId();
            node.parentNodeId = ctx.currentParentNodeId();
            node.nodeType = nodeType;
            node.status = status;
            node.summary = truncate(summary, 1024);
            node.startedOn = Instant.now();
            node.entityType = entityType;
            node.entityId = entityId;
            node.persist();

            LOG.debugf("Added trace node %d (%s) to trace %s under parent %d",
                    node.id, nodeType, ctx.traceId(), node.parentNodeId);
            return node.id;
        });
    }

    /**
     * Completes a trace node by setting its completion timestamp, duration,
     * and final status.
     *
     * @param nodeId the node to complete
     * @param status final status (e.g. "completed", "failed")
     */
    public void completeNode(Long nodeId, String status) {
        QuarkusTransaction.requiringNew().run(() -> {
            TraceNodeEntity node = TraceNodeEntity.findById(nodeId);
            if (node == null) {
                LOG.warnf("Trace node %d not found for completion", nodeId);
                return;
            }
            Instant now = Instant.now();
            node.completedOn = now;
            node.durationMs = Duration.between(node.startedOn, now).toMillis();
            node.status = status;
        });
    }

    /**
     * Completes a trace node and sets its entity reference. Use this overload
     * when the referenced entity is not known at node creation time.
     *
     * @param nodeId     the node to complete
     * @param status     final status
     * @param entityType type of the referenced detail entity
     * @param entityId   ID of the referenced detail entity
     */
    public void completeNode(Long nodeId, String status,
            String entityType, Long entityId) {
        QuarkusTransaction.requiringNew().run(() -> {
            TraceNodeEntity node = TraceNodeEntity.findById(nodeId);
            if (node == null) {
                LOG.warnf("Trace node %d not found for completion", nodeId);
                return;
            }
            Instant now = Instant.now();
            node.completedOn = now;
            node.durationMs = Duration.between(node.startedOn, now).toMillis();
            node.status = status;
            node.entityType = entityType;
            node.entityId = entityId;
        });
    }

    /**
     * Completes the trace itself by setting its completion timestamp and
     * final status.
     *
     * @param traceId the trace to complete
     * @param status  final status (e.g. "completed", "failed")
     */
    public void completeTrace(UUID traceId, String status) {
        QuarkusTransaction.requiringNew().run(() -> {
            TraceEntity trace = TraceEntity.findById(traceId);
            if (trace == null) {
                LOG.warnf("Trace %s not found for completion", traceId);
                return;
            }
            trace.completedOn = Instant.now();
            trace.status = status;
            LOG.debugf("Completed trace %s with status %s", traceId, status);
        });
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }
}
