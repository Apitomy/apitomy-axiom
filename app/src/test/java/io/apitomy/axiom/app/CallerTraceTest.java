package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that tasks created by an agent with caller trace headers join the caller's trace (#427).
 */
@QuarkusTest
class CallerTraceTest {

    private static final String PROJECTS_PATH = "/api/v1/projects";
    private static final String TASK_BODY = """
        {"actionType": "analyze", "input": "caller trace test"}
        """;

    @Inject
    TraceService traceService;

    @Test
    void taskJoinsCallerTraceUnderParentNode() {
        long projectId = createProject();
        TraceContext caller = createCallerTrace();
        Long agentNode = QuarkusTransaction.requiringNew().call(() ->
                traceService.addNode(caller, "agent-execution", "in-progress", "agent", null, null));

        Map<String, Object> task = postTask(projectId, Map.of(
                "X-Axiom-Trace-Id", caller.traceId().toString(),
                "X-Axiom-Parent-Node-Id", String.valueOf(agentNode)));

        assertEquals(caller.traceId().toString(), task.get("traceId"));
        TraceNodeEntity node = findTaskNode(caller.traceId(), task.get("id"));
        assertNotNull(node);
        assertEquals(agentNode, node.parentNodeId);
        long userActionTraces = QuarkusTransaction.requiringNew().call(() ->
                TraceEntity.count("projectId = ?1 and traceType = 'user-action'", projectId));
        assertEquals(0L, userActionTraces);
    }

    @Test
    void callerTraceStaysInProgressWhileTaskPending() {
        long projectId = createProject();
        TraceContext caller = createCallerTrace();

        postTask(projectId, Map.of("X-Axiom-Trace-Id", caller.traceId().toString()));

        TraceEntity trace = QuarkusTransaction.requiringNew().call(() ->
                TraceEntity.findById(caller.traceId()));
        assertEquals("in-progress", trace.status);
    }

    @Test
    void onlyTraceHeaderAttachesUnderRootNode() {
        long projectId = createProject();
        TraceContext caller = createCallerTrace();

        Map<String, Object> task = postTask(projectId,
                Map.of("X-Axiom-Trace-Id", caller.traceId().toString()));

        assertEquals(caller.traceId().toString(), task.get("traceId"));
        TraceNodeEntity node = findTaskNode(caller.traceId(), task.get("id"));
        assertNotNull(node);
        assertEquals(caller.currentParentNodeId(), node.parentNodeId);
    }

    @Test
    void malformedTraceIdFallsBackToUserAction() {
        assertFallsBack(Map.of("X-Axiom-Trace-Id", "not-a-uuid"), null);
    }

    @Test
    void malformedParentNodeFallsBackToUserAction() {
        TraceContext caller = createCallerTrace();
        assertFallsBack(Map.of("X-Axiom-Trace-Id", caller.traceId().toString(),
                "X-Axiom-Parent-Node-Id", "abc"), caller.traceId());
    }

    @Test
    void unknownTraceFallsBackToUserAction() {
        assertFallsBack(Map.of("X-Axiom-Trace-Id", UUID.randomUUID().toString()), null);
    }

    @Test
    void completedTraceFallsBackToUserAction() {
        TraceContext caller = createCallerTrace();
        QuarkusTransaction.requiringNew().run(() ->
                traceService.completeTrace(caller.traceId(), "completed"));
        assertFallsBack(Map.of("X-Axiom-Trace-Id", caller.traceId().toString()), caller.traceId());
    }

    @Test
    void parentNodeFromOtherTraceFallsBackToUserAction() {
        TraceContext caller = createCallerTrace();
        TraceContext other = createCallerTrace();
        assertFallsBack(Map.of("X-Axiom-Trace-Id", caller.traceId().toString(),
                "X-Axiom-Parent-Node-Id", String.valueOf(other.currentParentNodeId())),
                caller.traceId());
    }

    private void assertFallsBack(Map<String, String> headers, UUID callerTraceId) {
        long projectId = createProject();
        Map<String, Object> task = postTask(projectId, headers);
        String traceId = (String) task.get("traceId");
        assertNotNull(traceId);
        if (callerTraceId != null) {
            assertNotEquals(callerTraceId.toString(), traceId);
        }
        TraceEntity trace = QuarkusTransaction.requiringNew().call(() ->
                TraceEntity.findById(UUID.fromString(traceId)));
        assertEquals("user-action", trace.traceType);
    }

    private TraceContext createCallerTrace() {
        return QuarkusTransaction.requiringNew().call(() ->
                traceService.createTrace("event-pipeline", "caller trace", null, null, null,
                        "event-ingested", "caller root", null, null));
    }

    private TraceNodeEntity findTaskNode(UUID traceId, Object taskId) {
        return QuarkusTransaction.requiringNew().call(() ->
                TraceNodeEntity.<TraceNodeEntity>find(
                        "traceId = ?1 and nodeType = 'task' and entityId = ?2",
                        traceId, String.valueOf(taskId)).firstResult());
    }

    private long createProject() {
        int id = given()
                .contentType(ContentType.JSON)
                .body("""
                    {"name": "Caller Trace", "type": "feature", "refSource": "github",
                     "ref": "owner/repo#%d", "repository": "owner/repo"}
                    """.formatted(System.nanoTime() % 1_000_000))
                .when()
                .post(PROJECTS_PATH)
                .then()
                .statusCode(200)
                .extract().path("id");
        return id;
    }

    private Map<String, Object> postTask(long projectId, Map<String, String> headers) {
        RequestSpecification spec = given().contentType(ContentType.JSON).body(TASK_BODY);
        headers.forEach(spec::header);
        return spec.when()
                .post(PROJECTS_PATH + "/" + projectId + "/tasks")
                .then()
                .statusCode(200)
                .extract().jsonPath().getMap("$");
    }
}
