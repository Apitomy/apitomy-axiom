package io.apitomy.axiom.core.tracing;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Mutable context threaded through pipeline and report processing to track the
 * current position in the trace tree. Uses an internal stack so callers can
 * {@link #push(Long)} when descending into a child scope and {@link #pop()}
 * when returning to the parent.
 */
public class TraceContext {

    private final UUID traceId;
    private final Deque<Long> nodeStack = new ArrayDeque<>();

    /**
     * Creates a new context rooted at the given node.
     *
     * @param traceId    the trace UUID
     * @param rootNodeId the ID of the root {@code TraceNodeEntity}
     */
    public TraceContext(UUID traceId, Long rootNodeId) {
        this.traceId = traceId;
        this.nodeStack.push(rootNodeId);
    }

    /**
     * Returns the trace UUID.
     */
    public UUID traceId() {
        return traceId;
    }

    /**
     * Returns the current parent node ID (top of stack).
     */
    public Long currentParentNodeId() {
        return nodeStack.peek();
    }

    /**
     * Pushes a node ID onto the stack, making it the current parent for
     * subsequently created child nodes.
     *
     * @param nodeId the node ID to push
     */
    public void push(Long nodeId) {
        nodeStack.push(nodeId);
    }

    /**
     * Pops the current parent, returning to the previous level.
     *
     * @return the popped node ID
     */
    public Long pop() {
        return nodeStack.pop();
    }
}
