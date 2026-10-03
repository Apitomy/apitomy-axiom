package io.apitomy.axiom.app;

import io.apitomy.axiom.api.beans.LineageEdge;
import io.apitomy.axiom.api.beans.LineageGraph;
import io.apitomy.axiom.api.beans.LineageNode;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.ReportDefinitionEntity;
import io.apitomy.axiom.core.entities.ReportEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.ScheduledJobEntity;
import io.apitomy.axiom.core.entities.ScheduledJobRunEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunResumeEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the lineage API (#430): {@code GET /lineage/{entityType}/{id}}.
 */
@QuarkusTest
class LineageTest {

    private static Long projectId;

    @Inject
    LineageService lineageService;

    @Inject
    EntityManagerFactory entityManagerFactory;

    @Inject
    EntityManager entityManager;

    /** Rows created by the current test (entity class and ID), deleted after each test. */
    private final List<Object[]> created = new ArrayList<>();

    @BeforeAll
    static void createProject() {
        projectId = QuarkusTransaction.requiringNew().call(() -> {
            ProjectEntity project = new ProjectEntity();
            project.name = "lineage-" + UUID.randomUUID();
            project.type = "other";
            project.status = "Created";
            project.ref = "test/lineage-" + UUID.randomUUID();
            project.createdOn = Instant.now();
            project.updatedOn = Instant.now();
            project.persist();
            return project.id;
        });
    }

    @AfterAll
    static void deleteProject() {
        QuarkusTransaction.requiringNew().run(() -> ProjectEntity.deleteById(projectId));
    }

    /**
     * Deletes the rows created by the test, so that tests which pick up "all" events, outcomes or
     * traces (for example {@code ManagerTracingTest}) do not see them.
     */
    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (Object[] row : created) {
                Object entity = entityManager.find((Class<?>) row[0], row[1]);
                if (entity != null) {
                    entityManager.remove(entity);
                }
            }
        });
        created.clear();
    }

    private <T> T track(Class<?> type, T id) {
        created.add(new Object[] { type, id });
        return id;
    }

    // ── Event → Manager → tasks ─────────────────────────────────────

    @Test
    void eventToManagerToTwoTasksWithCost() {
        UUID eventId = createEvent();
        UUID traceId = createTrace("manager", eventId);
        Long task1 = createTask(traceId, eventId, null);
        Long task2 = createTask(traceId, eventId, null);
        Long outcomeId = createOutcome(eventId, "manager", traceId);
        createItem(outcomeId, RoutingOutcomeItemEntity.TYPE_TASK, task1, null);
        createItem(outcomeId, RoutingOutcomeItemEntity.TYPE_TASK, task2, null);
        Long ignoredItem = createItem(outcomeId, RoutingOutcomeItemEntity.TYPE_IGNORED, null, null);
        createUsage(null, eventId, traceId, null, null, 0.10);
        createUsage(task1, eventId, traceId, null, null, 0.20);
        createUsage(task1, eventId, traceId, null, null, 0.05);
        createUsage(task2, eventId, traceId, null, null, 0.30);

        LineageGraph graph = get("event", eventId.toString(), "");

        assertEquals("event:" + eventId, graph.getRoot());
        assertFalse(graph.getTruncated());
        LineageNode root = node(graph, "event:" + eventId);
        assertEquals("root", root.getDirection());
        assertEquals("/events/stream/" + eventId, root.getLinkPath());
        LineageNode trace = node(graph, "trace:" + traceId);
        assertEquals("manager", trace.getSubtype());
        assertEquals(0.10, trace.getCostUsd(), 0.0001);
        assertEquals("downstream", trace.getDirection());
        assertEquals(0.25, node(graph, "task:" + task1).getCostUsd(), 0.0001);
        assertEquals(0.30, node(graph, "task:" + task2).getCostUsd(), 0.0001);
        assertEquals("/logs/tasks?taskId=" + task1, node(graph, "task:" + task1).getLinkPath());
        assertEquals("ignored", node(graph, "outcome:" + ignoredItem).getSubtype());
        assertEquals(0.65, graph.getTotalCostUsd(), 0.0001);

        assertEdge(graph, "event:" + eventId, "trace:" + traceId, "triggered");
        assertEdge(graph, "trace:" + traceId, "task:" + task1, "created");
        assertEdge(graph, "trace:" + traceId, "task:" + task2, "created");
        assertEdge(graph, "trace:" + traceId, "outcome:" + ignoredItem, "produced");
        assertEquals(5, graph.getNodes().size());

        // Origin of a task: task ← Manager trace ← event
        LineageGraph up = get("task", task1.toString(), "?direction=upstream");
        assertEquals(3, up.getNodes().size());
        assertEdge(up, "trace:" + traceId, "task:" + task1, "created");
        assertEdge(up, "event:" + eventId, "trace:" + traceId, "triggered");
        assertEquals("upstream", node(up, "event:" + eventId).getDirection());
        assertEquals(2, node(up, "event:" + eventId).getDepth());
    }

    // ── Event → workflow run → tasks, resume ────────────────────────

    @Test
    void eventToWorkflowRunToTasksWithResume() {
        UUID eventId = createEvent();
        UUID resumeEventId = createEvent();
        UUID runTrace = createTrace("workflow", eventId);
        Long runId = createRun(eventId, runTrace);
        Long task1 = createTask(runTrace, null, runId);
        Long task2 = createTask(runTrace, null, runId);
        createResume(runId, resumeEventId);
        Long outcomeId = createOutcome(eventId, "create-workflow", runTrace);
        createItem(outcomeId, RoutingOutcomeItemEntity.TYPE_WORKFLOW_RUN, null, runId);

        LineageGraph graph = get("event", eventId.toString(), "");
        assertEdge(graph, "event:" + eventId, "workflow-run:" + runId, "triggered");
        assertEdge(graph, "workflow-run:" + runId, "task:" + task1, "created");
        assertEdge(graph, "workflow-run:" + runId, "task:" + task2, "created");
        assertNull(findNode(graph, "trace:" + runTrace), "Workflow traces collapse into their run");
        assertEquals("/logs/workflow-runs/" + runId,
                node(graph, "workflow-run:" + runId).getLinkPath());

        LineageGraph resumed = get("event", resumeEventId.toString(), "");
        assertEdge(resumed, "event:" + resumeEventId, "workflow-run:" + runId, "resumed");

        LineageGraph run = get("workflow-run", runId.toString(), "");
        assertEdge(run, "event:" + eventId, "workflow-run:" + runId, "triggered");
        assertEdge(run, "event:" + resumeEventId, "workflow-run:" + runId, "resumed");
        assertEdge(run, "workflow-run:" + runId, "task:" + task1, "created");
        assertEquals("upstream", node(run, "event:" + resumeEventId).getDirection());
    }

    // ── Job run whose agent created a task ──────────────────────────

    @Test
    void jobRunAgentTaskUpstreamWalk() {
        UUID eventId = createEvent();
        UUID managerTrace = createTrace("manager", eventId);
        Long[] job = createJobRun(managerTrace);
        UUID runTrace = createTrace("scheduled-job-execution", null);
        setJobRunTrace(job[1], runTrace);
        Long rootNode = createTraceNode(runTrace, null, "scheduled-job-run", job[1].toString());
        Long joined = createTask(runTrace, null, null);
        createTraceNode(runTrace, rootNode, "task", joined.toString());
        createUsage(null, null, runTrace, job[1], null, 1.50);

        LineageGraph up = get("task", joined.toString(), "?direction=upstream");
        assertEdge(up, "scheduled-job-run:" + job[1], "task:" + joined, "created");
        assertEdge(up, "trace:" + managerTrace, "scheduled-job-run:" + job[1], "triggered-by-agent");
        assertEdge(up, "event:" + eventId, "trace:" + managerTrace, "triggered");
        LineageNode run = node(up, "scheduled-job-run:" + job[1]);
        assertEquals(1.50, run.getCostUsd(), 0.0001);
        assertEquals("/logs/job-runs?runId=" + job[1], run.getLinkPath());
        assertTrue(run.getLabel().contains("lineage-job-"), run.getLabel());
        assertNull(findNode(up, "trace:" + runTrace));

        LineageGraph down = get("scheduled-job-run", job[1].toString(), "?direction=downstream");
        assertEdge(down, "scheduled-job-run:" + job[1], "task:" + joined, "created");
        assertEquals(2, down.getNodes().size());

        LineageGraph fromManager = get("trace", managerTrace.toString(), "?direction=downstream");
        assertEdge(fromManager, "trace:" + managerTrace, "scheduled-job-run:" + job[1],
                "triggered-by-agent");
        assertEdge(fromManager, "scheduled-job-run:" + job[1], "task:" + joined, "created");
    }

    @Test
    void taskCreatedByAgentTaskLinksToParentTask() {
        UUID eventId = createEvent();
        UUID traceId = createTrace("manager", eventId);
        Long agentTask = createTask(traceId, eventId, null);
        Long agentNode = createTraceNode(traceId, null, "task", agentTask.toString());
        Long child = createTask(traceId, null, null);
        createTraceNode(traceId, agentNode, "task", child.toString());

        LineageGraph up = get("task", child.toString(), "?direction=upstream");
        assertEdge(up, "task:" + agentTask, "task:" + child, "created");
        assertEdge(up, "trace:" + traceId, "task:" + agentTask, "created");

        LineageGraph down = get("trace", traceId.toString(), "?direction=downstream");
        assertEdge(down, "task:" + agentTask, "task:" + child, "created");
        assertTrue(down.getEdges().stream().noneMatch(e -> e.getTo().equals("task:" + child)
                && e.getFrom().equals("trace:" + traceId)));
    }

    // ── Report ──────────────────────────────────────────────────────

    @Test
    void reportTriggeredByWorkflowAgentWithJoinedTask() {
        UUID eventId = createEvent();
        UUID runTrace = createTrace("workflow", eventId);
        Long runId = createRun(eventId, runTrace);
        UUID reportTrace = createTrace("report-generation", null);
        Long reportId = createReport(reportTrace, runTrace);
        Long joined = createTask(reportTrace, null, null);
        createUsage(null, null, reportTrace, null, reportId, 0.40);

        LineageGraph graph = get("report", reportId.toString(), "");
        LineageNode report = node(graph, "report:" + reportId);
        assertEquals("root", report.getDirection());
        assertEquals("/reports/" + reportId, report.getLinkPath());
        assertEquals(0.40, report.getCostUsd(), 0.0001);
        assertEdge(graph, "workflow-run:" + runId, "report:" + reportId, "triggered-by-agent");
        assertEdge(graph, "event:" + eventId, "workflow-run:" + runId, "triggered");
        assertEdge(graph, "report:" + reportId, "task:" + joined, "created");

        LineageGraph trace = get("trace", reportTrace.toString(), "?direction=upstream");
        assertEdge(trace, "report:" + reportId, "trace:" + reportTrace, "part-of");
    }

    // ── Truncation ──────────────────────────────────────────────────

    @Test
    void nodeCapTruncates() {
        UUID eventId = createEvent();
        UUID traceId = createTrace("manager", eventId);
        for (int i = 0; i < 5; i++) {
            createTask(traceId, eventId, null);
        }
        LineageGraph full = get("event", eventId.toString(), "");
        assertFalse(full.getTruncated());
        assertEquals(7, full.getNodes().size());

        LineageGraph capped = get("event", eventId.toString(), "?maxNodes=3");
        assertTrue(capped.getTruncated());
        assertEquals(3, capped.getNodes().size());
        assertEquals(3, capped.getMaxNodes());
        capped.getEdges().forEach(e -> {
            assertNotNull(findNode(capped, e.getFrom()));
            assertNotNull(findNode(capped, e.getTo()));
        });
    }

    @Test
    void depthLimitTruncates() {
        UUID eventId = createEvent();
        UUID traceId = createTrace("manager", eventId);
        Long task = createTask(traceId, eventId, null);

        LineageGraph shallow = get("event", eventId.toString(), "?depth=1");
        assertTrue(shallow.getTruncated());
        assertEquals(1, shallow.getDepth());
        assertNotNull(findNode(shallow, "trace:" + traceId));
        assertNull(findNode(shallow, "task:" + task));

        LineageGraph exact = get("event", eventId.toString(), "?depth=2");
        assertFalse(exact.getTruncated());
        assertNotNull(findNode(exact, "task:" + task));
    }

    // ── Dangling references ─────────────────────────────────────────

    @Test
    void danglingReferencesBecomeUnavailableNodes() {
        UUID eventId = createEvent();
        UUID runTrace = createTrace("workflow", eventId);
        Long runId = createRun(eventId, runTrace);
        Long task = createTask(runTrace, null, runId);
        UUID callerTrace = createTrace("manager", null);
        Long[] job = createJobRun(callerTrace);
        UUID otherEvent = createEvent();
        Long outcomeId = createOutcome(otherEvent, "invoke-action", null);
        Long deletedTask = createTask(null, otherEvent, null);
        createItem(outcomeId, RoutingOutcomeItemEntity.TYPE_TASK, deletedTask, null);

        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity.deleteById(eventId);
            TraceEntity.deleteById(callerTrace);
            TaskEntity.deleteById(deletedTask);
        });

        LineageGraph run = get("workflow-run", runId.toString(), "");
        LineageNode event = node(run, "event:" + eventId);
        assertFalse(event.getAvailable());
        assertEquals("unavailable", event.getStatus());
        assertNull(event.getLinkPath());
        assertTrue(node(run, "task:" + task).getAvailable());

        LineageGraph jobGraph = get("scheduled-job-run", job[1].toString(), "");
        assertFalse(node(jobGraph, "trace:" + callerTrace).getAvailable());

        LineageGraph other = get("event", otherEvent.toString(), "");
        LineageNode gone = node(other, "task:" + deletedTask);
        assertFalse(gone.getAvailable());
        assertEdge(other, "event:" + otherEvent, "task:" + deletedTask, "created");

        given().when().get("/api/v1/lineage/event/" + eventId).then().statusCode(404);
    }

    // ── Errors ──────────────────────────────────────────────────────

    @Test
    void badRequestsReturn400() {
        given().when().get("/api/v1/lineage/bogus/1").then().statusCode(400);
        given().when().get("/api/v1/lineage/task/abc").then().statusCode(400);
        given().when().get("/api/v1/lineage/event/not-a-uuid").then().statusCode(400);
        given().when().get("/api/v1/lineage/trace/123").then().statusCode(400);
        given().when().get("/api/v1/lineage/task/1?direction=sideways").then().statusCode(400);
        given().when().get("/api/v1/lineage/task/1?depth=0").then().statusCode(400);
        given().when().get("/api/v1/lineage/task/1?depth=11").then().statusCode(400);
        given().when().get("/api/v1/lineage/task/1?maxNodes=0").then().statusCode(400);
        given().when().get("/api/v1/lineage/task/1?maxNodes=1001").then().statusCode(400);
    }

    @Test
    void unknownRootReturns404() {
        given().when().get("/api/v1/lineage/task/999999999").then().statusCode(404);
        given().when().get("/api/v1/lineage/event/" + UUID.randomUUID()).then().statusCode(404);
        given().when().get("/api/v1/lineage/trace/" + UUID.randomUUID()).then().statusCode(404);
        given().when().get("/api/v1/lineage/workflow-run/999999999").then().statusCode(404);
        given().when().get("/api/v1/lineage/scheduled-job-run/999999999").then().statusCode(404);
        given().when().get("/api/v1/lineage/report/999999999").then().statusCode(404);
    }

    // ── N+1 guard ───────────────────────────────────────────────────

    @Test
    void queryCountDoesNotGrowWithFanOut() {
        long small = countQueries(2);
        long large = countQueries(12);
        assertTrue(small > 0, "Statistics must count the lineage queries");
        assertTrue(small < 40, "Unexpectedly many lineage queries: " + small);
        assertEquals(small, large, "Lineage queries must be batched per level, not per node");
    }

    private long countQueries(int taskCount) {
        UUID eventId = createEvent();
        UUID traceId = createTrace("manager", eventId);
        Long outcomeId = createOutcome(eventId, "manager", traceId);
        for (int i = 0; i < taskCount; i++) {
            Long task = createTask(traceId, eventId, null);
            createItem(outcomeId, RoutingOutcomeItemEntity.TYPE_TASK, task, null);
            createUsage(task, eventId, traceId, null, null, 0.01);
        }
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertTrue(stats.isStatisticsEnabled(), "Hibernate statistics must be enabled in tests");
        stats.clear();
        LineageGraph graph = lineageService.getLineage("event", eventId.toString(), "both", 5, 200);
        long count = stats.getPrepareStatementCount();
        assertEquals(taskCount + 2, graph.getNodes().size());
        return count;
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private LineageGraph get(String type, String id, String query) {
        return given().when().get("/api/v1/lineage/" + type + "/" + id + query)
                .then().statusCode(200).extract().as(LineageGraph.class);
    }

    private static LineageNode findNode(LineageGraph graph, String key) {
        return graph.getNodes().stream().filter(n -> key.equals(n.getKey())).findFirst().orElse(null);
    }

    private static LineageNode node(LineageGraph graph, String key) {
        LineageNode node = findNode(graph, key);
        assertNotNull(node, "Missing node " + key + " in " + keys(graph));
        return node;
    }

    private static List<String> keys(LineageGraph graph) {
        return graph.getNodes().stream().map(LineageNode::getKey).toList();
    }

    private static void assertEdge(LineageGraph graph, String from, String to, String relation) {
        Optional<LineageEdge> edge = graph.getEdges().stream()
                .filter(e -> from.equals(e.getFrom()) && to.equals(e.getTo()))
                .findFirst();
        List<String> edges = new ArrayList<>();
        graph.getEdges().forEach(e -> edges.add(e.getFrom() + " -" + e.getRelation() + "-> " + e.getTo()));
        assertTrue(edge.isPresent(), "Missing edge " + from + " -> " + to + " in " + edges);
        assertEquals(relation, edge.get().getRelation());
    }

    private UUID createEvent() {
        UUID eventId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = eventId;
            event.sourceEventId = "lineage-" + eventId;
            event.source = "github";
            event.connectionId = "conn-lineage";
            event.type = "lineage.test";
            event.ref = "https://github.com/test-org/lineage/issues/1";
            event.timestamp = Instant.now();
            event.actor = "{\"login\":\"testuser\"}";
            event.payload = "{}";
            event.createdOn = Instant.now();
            event.persist();
        });
        return track(StreamEventEntity.class, eventId);
    }

    private UUID createTrace(String type, UUID eventId) {
        UUID traceId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            TraceEntity trace = new TraceEntity();
            trace.traceId = traceId;
            trace.traceType = type;
            trace.status = "completed";
            trace.summary = type + " trace";
            trace.eventId = eventId;
            trace.startedOn = Instant.now();
            trace.persist();
        });
        return track(TraceEntity.class, traceId);
    }

    private Long createTraceNode(UUID traceId, Long parentId, String entityType, String entityId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            TraceNodeEntity node = new TraceNodeEntity();
            node.traceId = traceId;
            node.parentNodeId = parentId;
            node.nodeType = entityType;
            node.status = "completed";
            node.summary = "node";
            node.startedOn = Instant.now();
            node.entityType = entityType;
            node.entityId = entityId;
            node.persist();
            track(TraceNodeEntity.class, node.id);
            return node.id;
        });
    }

    private Long createTask(UUID traceId, UUID eventId, Long runId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            TaskEntity task = new TaskEntity();
            task.projectId = projectId;
            task.actionType = "lineage-action";
            task.createdBy = "manager";
            task.status = "Completed";
            task.traceId = traceId;
            task.eventId = eventId;
            task.workflowRunId = runId;
            task.createdOn = Instant.now();
            task.persist();
            track(TaskEntity.class, task.id);
            return task.id;
        });
    }

    private Long createRun(UUID eventId, UUID traceId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.projectId = projectId;
            run.definitionId = 1L;
            run.definitionVersion = 1;
            run.instanceState = "{}";
            run.status = "Running";
            run.traceId = traceId;
            run.triggerEventId = eventId;
            run.startedOn = Instant.now();
            run.persist();
            track(WorkflowRunEntity.class, run.id);
            return run.id;
        });
    }

    private void createResume(Long runId, UUID eventId) {
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunResumeEntity resume = new WorkflowRunResumeEntity();
            resume.runId = runId;
            resume.nodeId = "wait";
            resume.eventId = eventId;
            resume.resumedOn = Instant.now();
            resume.persist();
            track(WorkflowRunResumeEntity.class, resume.id);
        });
    }

    private Long createOutcome(UUID eventId, String routingType, UUID traceId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EventProcessingLedgerEntity ledger = new EventProcessingLedgerEntity();
            ledger.eventId = eventId;
            ledger.subscriptionId = 1L;
            ledger.status = "completed";
            ledger.createdOn = Instant.now();
            ledger.persist();
            track(EventProcessingLedgerEntity.class, ledger.id);
            RoutingOutcomeEntity outcome = new RoutingOutcomeEntity();
            outcome.ledgerId = ledger.id;
            outcome.routingType = routingType;
            outcome.status = "completed";
            outcome.traceId = traceId;
            outcome.attemptNumber = 1;
            outcome.createdOn = Instant.now();
            outcome.persist();
            track(RoutingOutcomeEntity.class, outcome.id);
            return outcome.id;
        });
    }

    private Long createItem(Long outcomeId, String type, Long taskId, Long runId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            RoutingOutcomeItemEntity item = new RoutingOutcomeItemEntity();
            item.outcomeId = outcomeId;
            item.itemType = type;
            item.status = "completed";
            item.summary = type + " item";
            item.taskId = taskId;
            item.workflowRunId = runId;
            item.createdOn = Instant.now();
            item.persist();
            track(RoutingOutcomeItemEntity.class, item.id);
            return item.id;
        });
    }

    private void createUsage(Long taskId, UUID eventId, UUID traceId, Long jobRunId, Long reportId,
            double cost) {
        QuarkusTransaction.requiringNew().run(() -> {
            AiUsageEntity usage = new AiUsageEntity();
            usage.invocationType = "test";
            usage.taskId = taskId;
            usage.eventId = eventId;
            usage.traceId = traceId;
            usage.scheduledJobRunId = jobRunId;
            usage.reportId = reportId;
            usage.costUsd = cost;
            usage.createdOn = Instant.now();
            usage.persist();
            track(AiUsageEntity.class, usage.id);
        });
    }

    private Long[] createJobRun(UUID triggeredByTrace) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ScheduledJobEntity job = new ScheduledJobEntity();
            job.name = "lineage-job-" + UUID.randomUUID();
            job.enabled = false;
            job.schedule = "daily";
            job.executionMode = "agent";
            job.promptTemplate = "Do something";
            job.createdOn = Instant.now();
            job.updatedOn = Instant.now();
            job.persist();
            track(ScheduledJobEntity.class, job.id);
            ScheduledJobRunEntity run = new ScheduledJobRunEntity();
            run.jobId = job.id;
            run.status = "Completed";
            run.trigger = "manual";
            run.triggeredBy = "manual";
            run.triggeredByTraceId = triggeredByTrace;
            run.createdOn = Instant.now();
            run.persist();
            track(ScheduledJobRunEntity.class, run.id);
            return new Long[] { job.id, run.id };
        });
    }

    private void setJobRunTrace(Long runId, UUID traceId) {
        QuarkusTransaction.requiringNew().run(() -> {
            ScheduledJobRunEntity run = ScheduledJobRunEntity.findById(runId);
            run.traceId = traceId;
        });
    }

    private Long createReport(UUID traceId, UUID triggeredByTrace) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ReportDefinitionEntity def = new ReportDefinitionEntity();
            def.name = "lineage-report-" + UUID.randomUUID();
            def.schedule = "daily";
            def.promptTemplate = "Report";
            def.enabled = false;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();
            track(ReportDefinitionEntity.class, def.id);
            ReportEntity report = new ReportEntity();
            report.definitionId = def.id;
            report.status = "Completed";
            report.title = "Lineage report";
            report.trigger = "manual";
            report.triggeredBy = "manual";
            report.triggeredByTraceId = triggeredByTrace;
            report.traceId = traceId;
            report.createdOn = Instant.now();
            report.persist();
            track(ReportEntity.class, report.id);
            return report.id;
        });
    }
}
