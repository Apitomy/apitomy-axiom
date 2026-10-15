package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies a run's title can be set after the run started: by the {@code axiom_set_workflow_run_title} SDK
 * function (used by workflow {@code sdk:} nodes) and by its REST endpoint (used by the SDK MCP server).
 */
@QuarkusTest
class WorkflowRunTitleTest {

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    SdkCallService sdkCallService;

    /**
     * Starts an untitled run that waits, so it stays active.
     *
     * @param name a unique name for the subject and definition
     * @return the run id
     */
    private long startRun(String name) {
        long subjectId = TestFixtures.createSubject(name);
        long definitionId = TestFixtures.createWaitDefinition(name + " Def");
        return QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, definitionId, RunRequest.EMPTY, null).id);
    }

    private String titleOf(long runId) {
        return QuarkusTransaction.requiringNew().call(() -> WorkflowRunEntity.<WorkflowRunEntity>findById(runId).title);
    }

    private SdkCallService.SdkCallResult setTitle(Long runId, String title) {
        Map<String, Object> params = new HashMap<>();
        params.put("runId", runId);
        params.put("title", title);
        return QuarkusTransaction.requiringNew().call(() ->
                sdkCallService.execute("axiom_set_workflow_run_title", params));
    }

    /**
     * The SDK function sets the title (trimmed) and returns it.
     */
    @Test
    void sdkSetsTheTitle() {
        long runId = startRun("title-sdk-set");
        SdkCallService.SdkCallResult result = setTitle(runId, "  Triage PR #12  ");
        assertTrue(result.success(), result.output());
        assertEquals("Triage PR #12", result.outputMap().get("title"));
        assertEquals("Triage PR #12", titleOf(runId));
    }

    /**
     * A blank title clears the run's title.
     */
    @Test
    void sdkBlankTitleClearsTheTitle() {
        long runId = startRun("title-sdk-clear");
        setTitle(runId, "Something");
        assertTrue(setTitle(runId, " ").success());
        assertNull(titleOf(runId));
    }

    /**
     * Over-long titles, unknown runs and a missing run id are errors, and leave the title unchanged.
     */
    @Test
    void sdkRejectsInvalidCalls() {
        long runId = startRun("title-sdk-invalid");
        SdkCallService.SdkCallResult tooLong = setTitle(runId, "x".repeat(501));
        assertFalse(tooLong.success());
        assertTrue(tooLong.output().contains("500"), tooLong.output());
        assertNull(titleOf(runId));
        assertFalse(setTitle(999_999_999L, "T").success());
        assertFalse(setTitle(null, "T").success());
    }

    /**
     * A workflow's {@code sdk:} node sets its own run's title: the engine supplies {@code runId}, and the
     * title is an EL expression over the run context.
     */
    @Test
    void workflowSdkNodeSetsItsOwnRunTitle() {
        long subjectId = TestFixtures.createSubject("title-from-workflow");
        long definitionId = TestFixtures.createDefinition("Title Setter", """
            {
                "id": "title-wf", "name": "Title Setter",
                "nodes": [
                    {"id": "s1", "type": "start", "name": "Start", "config": {}, "position": {"x": 0, "y": 0}},
                    {"id": "a1", "type": "action", "name": "Set title", "position": {"x": 0, "y": 100},
                     "config": {"actionType": "sdk:axiom_set_workflow_run_title",
                                "inputs": {"title": "'Checking ' += context.subjectName"}}},
                    {"id": "w1", "type": "wait", "name": "Hold", "config": {"duration": "PT1H"},
                     "position": {"x": 0, "y": 200}},
                    {"id": "e1", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 300}}
                ],
                "edges": [
                    {"id": "x1", "source": "s1", "target": "a1", "priority": 0, "isDefault": false},
                    {"id": "x2", "source": "a1", "target": "w1", "priority": 0, "isDefault": false},
                    {"id": "x3", "source": "w1", "target": "e1", "priority": 0, "isDefault": false}
                ]
            }
            """);
        long runId = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, definitionId, RunRequest.EMPTY, null).id);
        assertEquals("Checking title-from-workflow", titleOf(runId));
    }

    /**
     * The REST endpoint sets and clears the title; the run reflects it.
     */
    @Test
    void restSetsAndClearsTheTitle() {
        long runId = startRun("title-rest");
        given().contentType(ContentType.JSON).body("{\"title\": \"From an agent\"}")
                .when().put("/api/v1/workflow/runs/" + runId + "/title")
                .then().statusCode(204);
        given().when().get("/api/v1/workflow/runs/" + runId)
                .then().statusCode(200).body("title", equalTo("From an agent"));
        given().contentType(ContentType.JSON).body("{}")
                .when().put("/api/v1/workflow/runs/" + runId + "/title")
                .then().statusCode(204);
        assertNull(titleOf(runId));
    }

    /**
     * The REST endpoint rejects over-long titles and unknown runs.
     */
    @Test
    void restRejectsInvalidRequests() {
        long runId = startRun("title-rest-invalid");
        given().contentType(ContentType.JSON).body("{\"title\": \"%s\"}".formatted("x".repeat(501)))
                .when().put("/api/v1/workflow/runs/" + runId + "/title")
                .then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"title\": \"T\"}")
                .when().put("/api/v1/workflow/runs/999999999/title")
                .then().statusCode(404);
    }
}
