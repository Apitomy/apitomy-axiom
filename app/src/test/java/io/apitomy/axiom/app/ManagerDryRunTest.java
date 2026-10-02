package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the manual Manager evaluation endpoint records a {@code manager-dry-run} trace
 * without acting on the decisions (#419).
 */
@QuarkusTest
class ManagerDryRunTest {

    private static final String TRACE_HEADER = "X-Axiom-Trace-Id";

    @InjectMock
    AgentRegistry agentRegistry;

    private final Agent agent = Mockito.mock(Agent.class);
    private UUID eventId;

    @BeforeEach
    void setUp() {
        Mockito.when(agentRegistry.getAgent(ArgumentMatchers.any())).thenReturn(agent);
        eventId = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = eventId;
            event.sourceEventId = "mgr-dry-run-" + eventId;
            event.source = "github";
            event.connectionId = "conn-mgr-dry-run";
            event.type = "issue.created";
            event.ref = "https://github.com/test-org/mgr-dry-run/issues/" + eventId;
            event.timestamp = Instant.now();
            event.actor = "{\"login\":\"testuser\"}";
            event.payload = "{\"issue\":{\"title\":\"Test\"}}";
            event.createdOn = Instant.now();
            event.persist();
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            ActivityLogEntity.delete("eventId", eventId);
            AiUsageEntity.delete("eventId", eventId);
            StreamEventEntity.deleteById(eventId);
        });
    }

    @Test
    void dryRunRecordsTraceWithDecisionNodesAndActsOnNothing() {
        agentReturns(AgentResult.success("{\"decisions\":["
                + "{\"decision\":\"create_task\",\"actionType\":\"x\",\"confidence\":0.9,"
                + "\"reasoning\":\"needs work\"},"
                + "{\"decision\":\"ignore\",\"confidence\":0.9,\"reasoning\":\"not relevant\"}]}"));

        Response response = evaluate();
        assertEquals(200, response.statusCode());
        assertEquals(2, response.jsonPath().getList("$").size());
        UUID traceId = UUID.fromString(response.header(TRACE_HEADER));

        TraceEntity trace = trace(traceId);
        assertEquals("manager-dry-run", trace.traceType);
        assertEquals(eventId, trace.eventId);
        assertEquals("completed", trace.status);
        assertTrue(trace.summary.contains("manual"));

        TraceNodeEntity eval = singleNode(traceId, "manager-evaluation");
        assertEquals("completed", eval.status);
        assertEquals("activity-log", eval.entityType);
        List<TraceNodeEntity> decisions = nodes(traceId, "manager-decision");
        assertEquals(2, decisions.size());
        decisions.forEach(d -> {
            assertEquals(eval.id, d.parentNodeId);
            assertEquals("completed", d.status);
            assertTrue(d.summary.startsWith("[dry run]"), d.summary);
        });
        assertTrue(decisions.stream().anyMatch(d -> d.summary.contains("needs work")));
        assertTrue(decisions.stream().anyMatch(d -> d.summary.contains("not relevant")));
        assertTrue(nodes(traceId, "task").isEmpty());
        assertEquals(0, QuarkusTransaction.requiringNew().call(() ->
                TraceNodeEntity.count("traceId = ?1 and status = 'in-progress'", traceId)));

        assertEquals(0, QuarkusTransaction.requiringNew().call(() ->
                TaskEntity.count("eventId", eventId)));
        assertEquals(0, QuarkusTransaction.requiringNew().call(() ->
                RoutingOutcomeEntity.count("traceId", traceId)));
        assertEquals(0, QuarkusTransaction.requiringNew().call(() ->
                EventProcessingLedgerEntity.count("eventId", eventId)));
        assertEquals(0, QuarkusTransaction.requiringNew().call(() ->
                ActivityLogEntity.count("eventId = ?1 and entryType in ('event-ignored', "
                        + "'manager-escalation', 'task-created')", eventId)));
    }

    @Test
    void activityAndAiUsageCarryDryRunTraceId() {
        agentReturns(AgentResult.success(
                "{\"decisions\":[{\"decision\":\"ignore\",\"confidence\":0.9,\"reasoning\":\"r\"}]}"));

        UUID traceId = UUID.fromString(evaluate().header(TRACE_HEADER));

        ActivityLogEntity row = QuarkusTransaction.requiringNew().call(() ->
                ActivityLogEntity.<ActivityLogEntity>find(
                        "eventId = ?1 and entryType = 'manager-evaluated'", eventId).firstResult());
        assertNotNull(row);
        assertEquals(traceId, row.traceId);
        assertEquals(String.valueOf(row.id), singleNode(traceId, "manager-evaluation").entityId);
        List<AiUsageEntity> usage = QuarkusTransaction.requiringNew().call(() ->
                AiUsageEntity.<AiUsageEntity>list("eventId", eventId));
        assertEquals(1, usage.size());
        assertEquals(traceId, usage.get(0).traceId);
    }

    @Test
    void managerFailureGivesFailedTraceAndUnchangedResponse() {
        agentReturns(AgentResult.failure("rate limited"));

        Response response = evaluate();
        assertEquals(200, response.statusCode());
        assertTrue(response.jsonPath().getList("$").isEmpty());
        UUID traceId = UUID.fromString(response.header(TRACE_HEADER));

        assertEquals("failed", trace(traceId).status);
        TraceNodeEntity eval = singleNode(traceId, "manager-evaluation");
        assertEquals("failed", eval.status);
        assertTrue(eval.summary.contains("rate limited"), eval.summary);
        assertEquals("activity-log", eval.entityType);
        assertTrue(nodes(traceId, "manager-decision").isEmpty());
    }

    @Test
    void tracesFilteredByEventIncludeDryRun() {
        agentReturns(AgentResult.success("{\"decisions\":[]}"));

        String traceId = evaluate().header(TRACE_HEADER);

        given().queryParam("filterEventId", eventId.toString())
                .when().get("/api/v1/traces")
                .then().statusCode(200)
                .body("items.traceId", org.hamcrest.Matchers.hasItem(traceId))
                .body("items.find { it.traceId == '" + traceId + "' }.traceType",
                        org.hamcrest.Matchers.equalTo("manager-dry-run"));
        assertFalse(traceId.isBlank());
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private Response evaluate() {
        return given().when().post("/api/v1/manager/evaluate/" + eventId);
    }

    private void agentReturns(AgentResult result) {
        Mockito.when(agent.executeWithSchema(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(result));
    }

    private TraceEntity trace(UUID traceId) {
        TraceEntity trace = QuarkusTransaction.requiringNew().call(() ->
                TraceEntity.<TraceEntity>findById(traceId));
        assertNotNull(trace);
        return trace;
    }

    private List<TraceNodeEntity> nodes(UUID traceId, String nodeType) {
        return QuarkusTransaction.requiringNew().call(() ->
                TraceNodeEntity.<TraceNodeEntity>list("traceId = ?1 and nodeType = ?2 order by id",
                        traceId, nodeType));
    }

    private TraceNodeEntity singleNode(UUID traceId, String nodeType) {
        List<TraceNodeEntity> found = nodes(traceId, nodeType);
        assertEquals(1, found.size(), "Expected exactly one " + nodeType + " node");
        return found.get(0);
    }
}
