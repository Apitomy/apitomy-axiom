package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.TaskEntity;
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

import java.time.Instant;
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

    private static final String SUBJECTS_PATH = "/api/v1/subjects";

    @InjectSpy
    TraceService traceService;

    @Test
    void callerTraceIdIsInMdcDuringRequestAndNotAfterwards() {
        long subjectId = createSubject();
        TraceContext caller = QuarkusTransaction.requiringNew().call(() ->
                traceService.createTrace("event-pipeline", "caller trace", null, null,
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
        }).when(traceService).completeTrace(ArgumentMatchers.any(), ArgumentMatchers.any());

        cancelTask(subjectId, Map.of("X-Axiom-Trace-Id", caller.traceId().toString()));

        assertFalse(seen.isEmpty(), "Resource did not call TraceService.completeTrace");
        assertEquals(caller.traceId().toString(), seen.get(0).get("traceId"));
        assertTrue(seen.get(0).get("summary").contains("traceId=" + caller.traceId()));

        // A later request without caller headers must not see the previous trace ID.
        seen.clear();
        cancelTask(subjectId, Map.of());
        assertFalse(seen.isEmpty(), "Resource did not call TraceService.completeTrace");
        assertEquals("", seen.get(0).get("traceId"),
                "Caller trace ID leaked into a later request");
        assertEquals("", seen.get(0).get("summary"));

        // The test thread never had the request's context.
        assertNull(MDC.get(LogContext.TRACE_ID));
    }

    @Test
    void invalidCallerTraceDoesNotSetMdc() {
        long subjectId = createSubject();
        List<String> seen = new CopyOnWriteArrayList<>();
        Mockito.doAnswer(inv -> {
            Object traceId = MDC.get(LogContext.TRACE_ID);
            seen.add(traceId != null ? traceId.toString() : "");
            return inv.callRealMethod();
        }).when(traceService).completeTrace(ArgumentMatchers.any(), ArgumentMatchers.any());

        cancelTask(subjectId, Map.of("X-Axiom-Trace-Id", "not-a-uuid"));

        assertFalse(seen.isEmpty(), "Resource did not call TraceService.completeTrace");
        assertEquals("", seen.get(0));
    }

    private long createSubject() {
        int id = given()
                .contentType(ContentType.JSON)
                .body("""
                    {"name": "Caller Trace MDC", "type": "feature", "refSource": "github",
                     "ref": "owner/repo#%d", "repository": "owner/repo"}
                    """.formatted(System.nanoTime() % 1_000_000))
                .when()
                .post(SUBJECTS_PATH)
                .then()
                .statusCode(200)
                .extract().path("id");
        return id;
    }

    /**
     * Seeds a pending task that belongs to a fresh trace and cancels it via REST, which makes the resource
     * call {@link TraceService#completeTrace} while handling the request.
     */
    private void cancelTask(long subjectId, Map<String, String> headers) {
        long taskId = QuarkusTransaction.requiringNew().call(() -> {
            TraceContext taskTrace = traceService.createTrace("workflow", "task trace", null, subjectId,
                    "task", "task root", null, null);
            TaskEntity task = new TaskEntity();
            task.subjectId = subjectId;
            task.actionType = "analyze";
            task.createdBy = "workflow";
            task.status = "Pending";
            task.createdOn = Instant.now();
            task.traceId = taskTrace.traceId();
            task.persist();
            return task.id;
        });
        RequestSpecification spec = given();
        headers.forEach(spec::header);
        spec.when()
                .post(SUBJECTS_PATH + "/" + subjectId + "/tasks/" + taskId + "/cancel")
                .then()
                .statusCode(200);
    }
}
