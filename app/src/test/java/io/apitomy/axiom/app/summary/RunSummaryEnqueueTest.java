package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.app.TestRunsResource;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunSummaryEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests queuing of automatic AI run summaries when runs finish.
 */
@QuarkusTest
class RunSummaryEnqueueTest {

    private static final String SUBJECTS_PATH = "/api/v1/subjects";
    private static final String WORKFLOWS_PATH = "/api/v1/workflow/definitions";

    @Inject
    RunSummaryService service;

    private long definition(boolean summarize) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowDefinitionEntity d = new WorkflowDefinitionEntity();
            d.name = "enqueue-" + System.nanoTime();
            d.content = "{}";
            d.summarizeRuns = summarize;
            d.createdOn = Instant.now();
            d.updatedOn = Instant.now();
            d.persist();
            return d.id;
        });
    }

    private long enqueueFor(long definitionId, String status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.title = "t";
            run.subjectId = -1L;
            run.definitionId = definitionId;
            run.definitionVersion = 1;
            run.instanceState = "{}";
            run.status = status;
            run.startedOn = Instant.now();
            run.persist();
            service.enqueueIfEnabled(run);
            service.enqueueIfEnabled(run);
            return run.id;
        });
    }

    private long rows(long runId) {
        return QuarkusTransaction.requiringNew().call(() -> WorkflowRunSummaryEntity.count("runId", runId));
    }

    @Test
    void optedInCompletedAndFailedRunsEnqueueOnce() {
        long def = definition(true);
        assertEquals(1, rows(enqueueFor(def, "completed")));
        assertEquals(1, rows(enqueueFor(def, "failed")));
    }

    @Test
    void cancelledAndActiveRunsAreNotEnqueued() {
        long def = definition(true);
        assertEquals(0, rows(enqueueFor(def, "cancelled")));
        assertEquals(0, rows(enqueueFor(def, "running")));
    }

    @Test
    void workflowsAreNotOptedInByDefault() {
        long def = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowDefinitionEntity d = new WorkflowDefinitionEntity();
            d.name = "default-" + System.nanoTime();
            d.content = "{}";
            d.createdOn = Instant.now();
            d.updatedOn = Instant.now();
            d.persist();
            return d.id;
        });
        assertEquals(0, rows(enqueueFor(def, "completed")));
    }

    @Test
    void finishingAnOptedInRunThroughTheEngineQueuesAnAutoSummary() {
        int subjectId = createSubject("Enqueue E2E " + System.nanoTime());
        int definitionId = createAndPublishDefinition("Enqueue E2E WF " + System.nanoTime());
        given()
                .contentType(ContentType.JSON)
                .body("{\"summarizeRuns\":true}")
                .when().put(WORKFLOWS_PATH + "/" + definitionId)
                .then().statusCode(200);

        int runId = given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when().post(TestRunsResource.path(subjectId))
                .then().statusCode(200)
                .body("status", equalTo("completed"))
                .extract().path("id");

        WorkflowRunSummaryEntity summary = QuarkusTransaction.requiringNew()
                .call(() -> WorkflowRunSummaryEntity.<WorkflowRunSummaryEntity>findById((long) runId));
        assertNotNull(summary);
        assertEquals(WorkflowRunSummaryEntity.PENDING, summary.status);
        assertEquals(WorkflowRunSummaryEntity.AUTO, summary.triggerType);
        assertEquals(1, rows(runId));
    }

    private int createSubject(String name) {
        return given()
                .contentType(ContentType.JSON)
                .body(String.format("""
                    {"name": "%s", "type": "other", "ref": "%s"}
                    """, name, "test/" + name.toLowerCase().replace(" ", "-")))
                .when().post(SUBJECTS_PATH)
                .then().statusCode(200)
                .extract().path("id");
    }

    private int createAndPublishDefinition(String name) {
        int id = given()
                .contentType(ContentType.JSON)
                .body(String.format("{\"name\": \"%s\"}", name))
                .when().post(WORKFLOWS_PATH)
                .then().statusCode(200)
                .extract().path("id");
        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-test",
                        "name": "Test",
                        "nodes": [
                            {"id": "s1", "type": "start", "name": "Start", "config": {},
                             "position": {"x": 100, "y": 100}},
                            {"id": "e1", "type": "end", "name": "End", "config": {},
                             "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1", "target": "e1", "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when().put(WORKFLOWS_PATH + "/" + id + "/content")
                .then().statusCode(204);
        given()
                .when().post(WORKFLOWS_PATH + "/" + id + "/publish")
                .then().statusCode(200);
        return id;
    }
}
