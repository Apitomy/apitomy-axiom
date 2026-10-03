package io.apitomy.axiom.app;

import io.apitomy.axiom.core.logging.LogContext;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@code CallerTraceFilter} puts a valid caller trace ID into the logging MDC for the
 * duration of the REST request, and that it does not leak into later requests (#428).
 */
@QuarkusTest
class CallerTraceLogContextTest {

    private static final String PROJECTS_PATH = "/api/v1/projects";
    private static final String TASK_BODY = """
        {"actionType": "analyze", "input": "caller trace log context test"}
        """;

    @InjectSpy
    TraceService traceService;

    @Test
    void callerTraceIdIsInMdcDuringRequestAndNotAfterwards() {
        long projectId = createProject();
        TraceContext caller = QuarkusTransaction.requiringNew().call(() ->
                traceService.createTrace("event-pipeline", "caller trace", null, null, null,
                        "event-ingested", "caller root", null, null));

        // Record the MDC trace ID seen by the service the resource calls during the request.
        List<Map<String, String>> seen = new CopyOnWriteArrayList<>();
        Mockito.doAnswer(inv -> {
            Object traceId = MDC.get(LogContext.TRACE_ID);
            Object summary = MDC.get(LogContext.SUMMARY);
            seen.add(Map.of(
                    "traceId", traceId != null ? traceId.toString() : "",
                    "summary", summary != null ? summary.toString() : ""));
            return inv.callRealMethod();
        }).when(traceService).addNode(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());

        postTask(projectId, Map.of("X-Axiom-Trace-Id", caller.traceId().toString()));

        assertFalse(seen.isEmpty(), "Resource did not call TraceService.addNode");
        assertEquals(caller.traceId().toString(), seen.get(0).get("traceId"));
        assertTrue(seen.get(0).get("summary").contains("traceId=" + caller.traceId()));

        // A later request without caller headers must not see the previous trace ID.
        seen.clear();
        postTask(projectId, Map.of());
        assertFalse(seen.isEmpty(), "Resource did not call TraceService.addNode");
        assertEquals("", seen.get(0).get("traceId"),
                "Caller trace ID leaked into a later request");
        assertEquals("", seen.get(0).get("summary"));

        // The test thread never had the request's context.
        assertNull(MDC.get(LogContext.TRACE_ID));
    }

    @Test
    void invalidCallerTraceDoesNotSetMdc() {
        long projectId = createProject();
        List<String> seen = new CopyOnWriteArrayList<>();
        Mockito.doAnswer(inv -> {
            Object traceId = MDC.get(LogContext.TRACE_ID);
            seen.add(traceId != null ? traceId.toString() : "");
            return inv.callRealMethod();
        }).when(traceService).addNode(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());

        postTask(projectId, Map.of("X-Axiom-Trace-Id", "not-a-uuid"));

        assertFalse(seen.isEmpty(), "Resource did not call TraceService.addNode");
        assertEquals("", seen.get(0));
    }

    private long createProject() {
        int id = given()
                .contentType(ContentType.JSON)
                .body("""
                    {"name": "Caller Trace MDC", "type": "feature", "refSource": "github",
                     "ref": "owner/repo#%d", "repository": "owner/repo"}
                    """.formatted(System.nanoTime() % 1_000_000))
                .when()
                .post(PROJECTS_PATH)
                .then()
                .statusCode(200)
                .extract().path("id");
        return id;
    }

    private void postTask(long projectId, Map<String, String> headers) {
        RequestSpecification spec = given().contentType(ContentType.JSON).body(TASK_BODY);
        headers.forEach(spec::header);
        spec.when()
                .post(PROJECTS_PATH + "/" + projectId + "/tasks")
                .then()
                .statusCode(200);
    }
}
