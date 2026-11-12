package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunSummaryEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class RunSummaryEndpointTest {

    private long run(String status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.title = "t";
            run.subjectId = -1L;
            run.definitionId = -1L;
            run.definitionVersion = 1;
            run.instanceState = "{}";
            run.status = status;
            run.startedOn = Instant.now();
            run.persist();
            return run.id;
        });
    }

    private String path(long id) {
        return "/api/v1/workflow/runs/" + id + "/ai-summary";
    }

    @Test
    void queuesManualSummaryForCancelledRun() {
        long id = run("cancelled");
        given().when().post(path(id)).then().statusCode(202);
        WorkflowRunSummaryEntity s = QuarkusTransaction.requiringNew()
                .call(() -> WorkflowRunSummaryEntity.findById(id));
        assertEquals(WorkflowRunSummaryEntity.PENDING, s.status);
        assertEquals(WorkflowRunSummaryEntity.MANUAL, s.triggerType);
    }

    @Test
    void regenerateResetsFailedSummary() {
        long id = run("completed");
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunSummaryEntity s = WorkflowRunSummaryEntity.newPending(
                    WorkflowRunEntity.findById(id), WorkflowRunSummaryEntity.AUTO);
            s.status = WorkflowRunSummaryEntity.FAILED;
            s.attempts = 2;
            s.error = "boom";
            s.persist();
        });
        given().when().post(path(id)).then().statusCode(202);
        WorkflowRunSummaryEntity s = QuarkusTransaction.requiringNew()
                .call(() -> WorkflowRunSummaryEntity.findById(id));
        assertEquals(0, s.attempts);
        assertEquals(null, s.error);
    }

    @Test
    void rejectsActiveRunsMissingRunsAndGeneratingSummaries() {
        given().when().post(path(run("running"))).then().statusCode(409);
        given().when().post(path(Long.MAX_VALUE)).then().statusCode(404);
        long id = run("failed");
        given().when().post(path(id)).then().statusCode(202);
        QuarkusTransaction.requiringNew().run(() -> WorkflowRunSummaryEntity.update(
                "status = ?1 where runId = ?2", WorkflowRunSummaryEntity.GENERATING, id));
        given().when().post(path(id)).then().statusCode(409);
    }
}
