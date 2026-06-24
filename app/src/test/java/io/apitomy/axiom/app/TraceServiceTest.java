package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TraceService}. Verifies trace and node lifecycle operations
 * including creation, completion, and parent-child relationships.
 */
@QuarkusTest
class TraceServiceTest {

    @Inject
    TraceService traceService;

    @AfterEach
    @Transactional
    void cleanup() {
        TraceNodeEntity.deleteAll();
        TraceEntity.deleteAll();
    }

    @Test
    void createTraceCreatesTraceAndRootNode() {
        TraceContext ctx = traceService.createTrace("event-pipeline",
                "Processing test event", 1L, null, null,
                "event-ingested", "Event received: test-event",
                null, null);

        assertNotNull(ctx);
        assertNotNull(ctx.traceId());
        assertNotNull(ctx.currentParentNodeId());

        TraceEntity trace = TraceEntity.findById(ctx.traceId());
        assertNotNull(trace);
        assertEquals("event-pipeline", trace.traceType);
        assertEquals("in-progress", trace.status);
        assertEquals("Processing test event", trace.summary);
        assertEquals(1L, trace.eventId);
        assertNull(trace.projectId);
        assertNull(trace.reportId);
        assertNotNull(trace.startedOn);
        assertNull(trace.completedOn);

        TraceNodeEntity rootNode = TraceNodeEntity.findById(ctx.currentParentNodeId());
        assertNotNull(rootNode);
        assertEquals(ctx.traceId(), rootNode.traceId);
        assertNull(rootNode.parentNodeId);
        assertEquals("event-ingested", rootNode.nodeType);
        assertEquals("completed", rootNode.status);
        assertEquals("Event received: test-event", rootNode.summary);
        assertNotNull(rootNode.startedOn);
        assertNotNull(rootNode.completedOn);
        assertEquals(0L, rootNode.durationMs);
    }

    @Test
    void addNodeCreatesChildWithCorrectParent() {
        TraceContext ctx = traceService.createTrace("test", "test trace",
                null, null, null, "root", "root node",
                null, null);

        Long childId = traceService.addNode(ctx, "manager-evaluation", "in-progress",
                "Evaluating event", "activity-log", 42L);

        assertNotNull(childId);

        TraceNodeEntity child = TraceNodeEntity.findById(childId);
        assertNotNull(child);
        assertEquals(ctx.traceId(), child.traceId);
        assertEquals(ctx.currentParentNodeId(), child.parentNodeId);
        assertEquals("manager-evaluation", child.nodeType);
        assertEquals("in-progress", child.status);
        assertEquals("Evaluating event", child.summary);
        assertEquals("activity-log", child.entityType);
        assertEquals(42L, child.entityId);
        assertNotNull(child.startedOn);
        assertNull(child.completedOn);
    }

    @Test
    void addNodeRespectsStackForNestedChildren() {
        TraceContext ctx = traceService.createTrace("test", "test trace",
                null, null, null, "root", "root node",
                null, null);
        Long rootNodeId = ctx.currentParentNodeId();

        Long decisionId = traceService.addNode(ctx, "decision", "in-progress",
                "Decision 1", null, null);
        ctx.push(decisionId);

        Long taskId = traceService.addNode(ctx, "task-created", "completed",
                "Task created", "task", 10L);

        // Verify parent chain
        TraceNodeEntity decisionNode = TraceNodeEntity.findById(decisionId);
        assertEquals(rootNodeId, decisionNode.parentNodeId);

        TraceNodeEntity taskNode = TraceNodeEntity.findById(taskId);
        assertEquals(decisionId, taskNode.parentNodeId);

        ctx.pop();

        // After pop, next child is under root again
        Long secondDecisionId = traceService.addNode(ctx, "decision", "in-progress",
                "Decision 2", null, null);
        TraceNodeEntity secondDecision = TraceNodeEntity.findById(secondDecisionId);
        assertEquals(rootNodeId, secondDecision.parentNodeId);
    }

    @Test
    void completeNodeSetsTimingAndStatus() {
        TraceContext ctx = traceService.createTrace("test", "test trace",
                null, null, null, "root", "root node",
                null, null);
        Long nodeId = traceService.addNode(ctx, "work-item", "in-progress",
                "Doing work", null, null);

        traceService.completeNode(nodeId, "completed");

        TraceNodeEntity node = TraceNodeEntity.findById(nodeId);
        assertNotNull(node);
        assertEquals("completed", node.status);
        assertNotNull(node.completedOn);
        assertNotNull(node.durationMs);
        assertTrue(node.durationMs >= 0);
    }

    @Test
    void completeNodeWithEntityRefSetsEntityFields() {
        TraceContext ctx = traceService.createTrace("test", "test trace",
                null, null, null, "root", "root node",
                null, null);
        Long nodeId = traceService.addNode(ctx, "manager-evaluation", "in-progress",
                "Evaluating", null, null);

        traceService.completeNode(nodeId, "completed", "activity-log", 99L);

        TraceNodeEntity node = TraceNodeEntity.findById(nodeId);
        assertNotNull(node);
        assertEquals("completed", node.status);
        assertEquals("activity-log", node.entityType);
        assertEquals(99L, node.entityId);
        assertNotNull(node.completedOn);
    }

    @Test
    void completeTraceSetsCompletionFields() {
        TraceContext ctx = traceService.createTrace("test", "test trace",
                null, null, null, "root", "root node",
                null, null);

        traceService.completeTrace(ctx.traceId(), "completed");

        TraceEntity trace = TraceEntity.findById(ctx.traceId());
        assertNotNull(trace);
        assertEquals("completed", trace.status);
        assertNotNull(trace.completedOn);
    }

    @Test
    void completeTraceWithFailedStatus() {
        TraceContext ctx = traceService.createTrace("test", "test trace",
                null, null, null, "root", "root node",
                null, null);

        traceService.completeTrace(ctx.traceId(), "failed");

        TraceEntity trace = TraceEntity.findById(ctx.traceId());
        assertEquals("failed", trace.status);
    }

    @Test
    void completeNodeHandlesMissingNodeGracefully() {
        // Should not throw — just logs a warning
        traceService.completeNode(999999L, "completed");
        traceService.completeNode(999999L, "completed", "task", 1L);
    }

    @Test
    void completeTraceHandlesMissingTraceGracefully() {
        // Should not throw — just logs a warning
        traceService.completeTrace(UUID.randomUUID(), "completed");
    }

    @Test
    void fullPipelineFlow() {
        // Simulate a complete event pipeline trace
        TraceContext ctx = traceService.createTrace("event-pipeline",
                "Processing event #1", 1L, null, null,
                "event-ingested", "Event received: issue-opened",
                null, null);

        // Manager evaluation
        Long evalNodeId = traceService.addNode(ctx, "manager-evaluation", "in-progress",
                "Manager evaluating event", null, null);
        traceService.completeNode(evalNodeId, "completed", "activity-log", 10L);

        // Decision 1: create_task
        Long decisionNodeId = traceService.addNode(ctx, "decision-processed", "in-progress",
                "create_task: implement-feature", null, null);
        ctx.push(decisionNodeId);

        Long taskCreatedId = traceService.addNode(ctx, "task-created", "completed",
                "Created task: implement-feature", "task", 100L);
        traceService.completeNode(decisionNodeId, "completed");
        ctx.pop();

        // Don't complete trace (async task pending)

        // Verify the tree structure
        List<TraceNodeEntity> nodes = TraceNodeEntity.list(
                "traceId = ?1 order by id asc", ctx.traceId());
        assertEquals(4, nodes.size());

        TraceNodeEntity root = nodes.get(0);
        assertNull(root.parentNodeId);
        assertEquals("event-ingested", root.nodeType);

        TraceNodeEntity eval = nodes.get(1);
        assertEquals(root.id, eval.parentNodeId);
        assertEquals("manager-evaluation", eval.nodeType);
        assertEquals("activity-log", eval.entityType);
        assertEquals(10L, eval.entityId);

        TraceNodeEntity decision = nodes.get(2);
        assertEquals(root.id, decision.parentNodeId);
        assertEquals("decision-processed", decision.nodeType);

        TraceNodeEntity taskCreated = nodes.get(3);
        assertEquals(decisionNodeId, taskCreated.parentNodeId);
        assertEquals("task-created", taskCreated.nodeType);
        assertEquals("task", taskCreated.entityType);
        assertEquals(100L, taskCreated.entityId);

        // Trace is still in-progress (async task) — use HQL query to bypass L1 cache
        assertEquals(1, TraceEntity.count("traceId = ?1 and status = 'in-progress'", ctx.traceId()));

        // Simulate async task completion
        traceService.completeTrace(ctx.traceId(), "completed");
        assertEquals(1, TraceEntity.count("traceId = ?1 and status = 'completed'", ctx.traceId()));
    }

    @Test
    void createTraceTruncatesLongSummary() {
        String longSummary = "A".repeat(2000);
        TraceContext ctx = traceService.createTrace("test", longSummary,
                null, null, null, "root", longSummary,
                null, null);

        TraceEntity trace = TraceEntity.findById(ctx.traceId());
        assertTrue(trace.summary.length() <= 1024);
        assertTrue(trace.summary.endsWith("..."));

        TraceNodeEntity rootNode = TraceNodeEntity.findById(ctx.currentParentNodeId());
        assertTrue(rootNode.summary.length() <= 1024);
        assertTrue(rootNode.summary.endsWith("..."));
    }
}
