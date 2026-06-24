package io.apitomy.axiom.core.tracing;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TraceContextTest {

    @Test
    void constructionSetsTraceIdAndRootNode() {
        UUID traceId = UUID.randomUUID();
        TraceContext ctx = new TraceContext(traceId, 100L);

        assertEquals(traceId, ctx.traceId());
        assertEquals(100L, ctx.currentParentNodeId());
    }

    @Test
    void pushMakesNewNodeTheCurrentParent() {
        TraceContext ctx = new TraceContext(UUID.randomUUID(), 1L);

        ctx.push(2L);
        assertEquals(2L, ctx.currentParentNodeId());

        ctx.push(3L);
        assertEquals(3L, ctx.currentParentNodeId());
    }

    @Test
    void popReturnsToThePreviousLevel() {
        TraceContext ctx = new TraceContext(UUID.randomUUID(), 1L);
        ctx.push(2L);
        ctx.push(3L);

        assertEquals(3L, ctx.pop());
        assertEquals(2L, ctx.currentParentNodeId());

        assertEquals(2L, ctx.pop());
        assertEquals(1L, ctx.currentParentNodeId());
    }

    @Test
    void popReturnsThePoppedNodeId() {
        TraceContext ctx = new TraceContext(UUID.randomUUID(), 1L);
        ctx.push(42L);

        Long popped = ctx.pop();
        assertEquals(42L, popped);
    }

    @Test
    void poppingPastRootThrows() {
        TraceContext ctx = new TraceContext(UUID.randomUUID(), 1L);
        ctx.pop();

        assertThrows(NoSuchElementException.class, ctx::pop);
    }

    @Test
    void currentParentNodeIdReturnsNullWhenEmpty() {
        TraceContext ctx = new TraceContext(UUID.randomUUID(), 1L);
        ctx.pop();

        assertNull(ctx.currentParentNodeId());
    }

    @Test
    void traceIdIsImmutable() {
        UUID traceId = UUID.randomUUID();
        TraceContext ctx = new TraceContext(traceId, 1L);

        ctx.push(2L);
        ctx.pop();

        assertEquals(traceId, ctx.traceId());
    }

    @Test
    void typicalPipelineFlow() {
        TraceContext ctx = new TraceContext(UUID.randomUUID(), 1L);
        assertEquals(1L, ctx.currentParentNodeId());

        // Manager evaluation — child of root
        ctx.push(10L);
        assertEquals(10L, ctx.currentParentNodeId());
        ctx.pop();

        // Decision — child of root
        ctx.push(20L);
        assertEquals(20L, ctx.currentParentNodeId());

        // Task — child of decision
        ctx.push(30L);
        assertEquals(30L, ctx.currentParentNodeId());
        ctx.pop();

        // Back to decision level
        assertEquals(20L, ctx.currentParentNodeId());
        ctx.pop();

        // Back to root
        assertEquals(1L, ctx.currentParentNodeId());
    }
}
