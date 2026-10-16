package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class WorkflowInstanceResourceTest {

    private static final String SUBJECTS_PATH = "/api/v1/subjects";
    private static final String WORKFLOWS_PATH = "/api/v1/workflow/definitions";

    @Test
    void testTriggerAndGetWorkflow() {
        int subjectId = createSubject("WF Trigger Test Subject");
        int definitionId = createAndPublishDefinition(
                "Trigger Test WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(TestRunsResource.path(subjectId))
                .then()
                    .statusCode(200)
                    .body("id", notNullValue())
                    .body("subjectId", equalTo(subjectId))
                    .body("definitionId", equalTo(definitionId))
                    .body("definitionVersion", equalTo(1))
                    .body("definitionName", equalTo("Trigger Test WF"))
                    .body("status", anyOf(
                            equalTo("running"), equalTo("completed")))
                    .body("startedOn", notNullValue())
                    .body("workflowContent", notNullValue())
                    .body("history", notNullValue());

        given()
                .when()
                    .get(TestRunsResource.path(subjectId))
                .then()
                    .statusCode(200)
                    .body("subjectId", equalTo(subjectId))
                    .body("definitionName", equalTo("Trigger Test WF"));
    }

    @Test
    void testTriggerWithMissingRequiredFieldReturns400() {
        int subjectId = createSubject("WF Missing Field Subject");

        // Body present but missing the required workflowDefinitionId field.
        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "definitionId": 1
                    }
                    """)
                .when()
                    .post(TestRunsResource.path(subjectId))
                .then()
                    .statusCode(400);
    }

    @Test
    void testTriggerWithEmptyBodyReturns400() {
        int subjectId = createSubject("WF Empty Body Subject");

        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                    .post(TestRunsResource.path(subjectId))
                .then()
                    .statusCode(400);
    }

    @Test
    void testTriggerDuplicateReturns409() {
        int subjectId = createSubject("WF Dup Test Subject");
        int definitionId = createAndPublishActionWorkflow(
                "Dup Test WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(TestRunsResource.path(subjectId))
                .then()
                    .statusCode(200)
                    .body("status", equalTo("waiting"));

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(TestRunsResource.path(subjectId))
                .then()
                    .statusCode(409);
    }

    @Test
    void testTriggerUnpublishedReturns400() {
        int subjectId = createSubject("WF Unpub Test Subject");
        int definitionId = createDefinition("Unpublished Test WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(TestRunsResource.path(subjectId))
                .then()
                    .statusCode(400);
    }

    @Test
    void testPublishWithUnknownNodeTypesReturns400() {
        int definitionId = createDefinition(
                "Unsupported Nodes WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-unsupported",
                        "name": "Unsupported",
                        "nodes": [
                            {"id": "s1", "type": "start", "name": "Start",
                             "config": {}, "position": {"x": 100, "y": 100}},
                            {"id": "re1", "type": "sub-workflow",
                             "name": "Sub Workflow",
                             "config": {"workflowId": "other"},
                             "position": {"x": 100, "y": 200}},
                            {"id": "e1", "type": "end", "name": "End",
                             "config": {}, "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1",
                             "target": "re1",
                             "priority": 0, "isDefault": true},
                            {"id": "edge2", "source": "re1",
                             "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(WORKFLOWS_PATH + "/" + definitionId + "/content")
                .then()
                    .statusCode(204);

        // Unknown node types are rejected at publish time (every node type
        // known to the flow model is now executable, so publish validation is
        // the earliest and only gate for unknown types).
        given()
                .when()
                    .post(WORKFLOWS_PATH + "/" + definitionId + "/publish")
                .then()
                    .statusCode(400);
    }

    @Test
    void testHumanTaskWorkflowSurfacesInboxItemAndAdvancesOnCompletion() {
        int subjectId = createSubject("WF Human Task Subject");
        int definitionId = createDefinition("Human Task WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-human",
                        "name": "Human",
                        "nodes": [
                            {"id": "s1", "type": "start", "name": "Start",
                             "config": {}, "position": {"x": 100, "y": 100}},
                            {"id": "ht1", "type": "human-task", "name": "Approve",
                             "config": {"description": "Approve the request",
                                        "outputs": [{"name": "approved", "type": "boolean",
                                                     "required": true, "label": "Approve?"}]},
                             "position": {"x": 100, "y": 200}},
                            {"id": "e1", "type": "end", "name": "End",
                             "config": {}, "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1", "target": "ht1",
                             "priority": 0, "isDefault": true},
                            {"id": "edge2", "source": "ht1", "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(WORKFLOWS_PATH + "/" + definitionId + "/content")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .post(WORKFLOWS_PATH + "/" + definitionId + "/publish")
                .then()
                    .statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"workflowDefinitionId": %d}
                    """.formatted(definitionId))
                .when()
                    .post(TestRunsResource.path(subjectId))
                .then()
                    .statusCode(200)
                    .body("status", equalTo("waiting"));

        Integer taskId = given()
                .when()
                    .get("/api/v1/inbox")
                .then()
                    .statusCode(200)
                    .body("items.find { it.subjectId == " + subjectId + " }.actionType",
                            equalTo("Approve"))
                    .extract()
                    .path("items.find { it.subjectId == " + subjectId + " }.id");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"approved": true}
                    """)
                .when()
                    .post("/api/v1/inbox/" + taskId + "/complete")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .get(TestRunsResource.path(subjectId))
                .then()
                    .statusCode(200)
                    .body("status", equalTo("completed"));
    }

    @Test
    void testCancelWorkflow() {
        int subjectId = createSubject("WF Cancel Test Subject");
        int definitionId = createAndPublishActionWorkflow(
                "Cancel Test WF");
        int runId = triggerAndExpect(subjectId, definitionId, "waiting");

        given()
                .when()
                    .post("/api/v1/workflow/runs/" + runId + "/cancel")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .get(TestRunsResource.path(subjectId))
                .then()
                    .statusCode(200)
                    .body("status", equalTo("cancelled"));
    }

    @Test
    void testCancelTerminalReturns409() {
        int subjectId = createSubject("WF Cancel Term Subject");
        int definitionId = createAndPublishDefinition(
                "Cancel Term WF");
        int runId = triggerAndExpect(subjectId, definitionId, "completed");

        given()
                .when()
                    .post("/api/v1/workflow/runs/" + runId + "/cancel")
                .then()
                    .statusCode(409);
    }

    @Test
    void testCancelUnknownRunReturns404() {
        given()
                .when()
                    .post("/api/v1/workflow/runs/999999999/cancel")
                .then()
                    .statusCode(404);
    }

    @Test
    void testSameDefinitionCanRunAgainAfterCancel() {
        int subjectId = createSubject("WF Cancel Active Run Subject");
        int definitionId = createAndPublishActionWorkflow(
                "Cancel Active Run WF");
        int firstRunId = triggerAndExpect(subjectId, definitionId, "waiting");

        // A second active run of the same definition on the same subject conflicts
        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(TestRunsResource.path(subjectId))
                .then()
                    .statusCode(409);

        given()
                .when()
                    .post("/api/v1/workflow/runs/" + firstRunId + "/cancel")
                .then()
                    .statusCode(204);

        int secondRunId = triggerAndExpect(subjectId, definitionId, "waiting");

        given()
                .when()
                    .post("/api/v1/workflow/runs/" + secondRunId + "/cancel")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .get("/api/v1/workflow/runs/" + firstRunId)
                .then()
                    .statusCode(200)
                    .body("status", equalTo("cancelled"));
    }

    private int triggerAndExpect(int subjectId, int definitionId, String expectedStatus) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(TestRunsResource.path(subjectId))
                .then()
                    .statusCode(200)
                    .body("status", equalTo(expectedStatus))
                    .extract().path("id");
    }

    @Test
    void testGetNoInstanceReturns404() {
        int subjectId = createSubject("WF No Instance Subject");

        given()
                .when()
                    .get(TestRunsResource.path(subjectId))
                .then()
                    .statusCode(404);
    }

    @Test
    void testHasWorkflowInstanceOnSubject() {
        int subjectId = createSubject(
                "WF HasInstance Test Subject");

        given()
                .when()
                    .get(SUBJECTS_PATH + "/" + subjectId)
                .then()
                    .statusCode(200)
                    .body("hasWorkflowInstance", equalTo(false));

        int definitionId = createAndPublishDefinition(
                "HasInstance Test WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(TestRunsResource.path(subjectId))
                .then()
                    .statusCode(200);

        given()
                .when()
                    .get(SUBJECTS_PATH + "/" + subjectId)
                .then()
                    .statusCode(200)
                    .body("hasWorkflowInstance", equalTo(true));
    }

    // -- Helpers --

    @Test
    void aRunStartedWithoutATitleHasNoTitle() {
        int subjectId = createSubject("WF No Title Subject");
        int definitionId = createAndPublishDefinition("No Title WF");
        given().contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when().post(TestRunsResource.path(subjectId))
                .then().statusCode(200)
                .body("title", nullValue())
                .body("definitionName", equalTo("No Title WF"))
                .body("subjectName", equalTo("WF No Title Subject"));
    }

    @Test
    void rerunsAFailedRunAsANewRunOfTheLatestVersion() {
        int subjectId = createSubject("WF Rerun Subject");
        int definitionId = createAndPublishDefinition("Rerun WF");
        long failedRunId = persistRun(subjectId, definitionId, "failed",
                "{\"team\":\"core\"}", "{\"attempts\":2}");

        int newRunId = given().contentType(ContentType.JSON)
                .when().post("/api/v1/workflow/runs/" + failedRunId + "/rerun")
                .then().statusCode(200)
                .body("subjectId", equalTo(subjectId))
                .body("definitionId", equalTo(definitionId))
                .body("definitionVersion", equalTo(1))
                .body("title", equalTo("Original title"))
                .body("labels.team", equalTo("core"))
                .body("labels.rerunOf", equalTo(String.valueOf(failedRunId)))
                .extract().path("runId");
        org.junit.jupiter.api.Assertions.assertNotEquals(failedRunId, (long) newRunId);
    }

    @Test
    void rerunRejectsRunsThatDidNotFailAndUnknownRuns() {
        int subjectId = createSubject("WF Rerun Rejects Subject");
        int definitionId = createAndPublishDefinition("Rerun Rejects WF");
        long completed = persistRun(subjectId, definitionId, "completed", null, null);
        given().when().post("/api/v1/workflow/runs/" + completed + "/rerun").then().statusCode(409);
        given().when().post("/api/v1/workflow/runs/999999999/rerun").then().statusCode(404);
    }

    /** Persists a terminal run directly (no engine), as the run to re-run. */
    private static long persistRun(int subjectId, int definitionId, String status, String labels, String inputs) {
        return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> {
            io.apitomy.axiom.core.entities.WorkflowRunEntity run = new io.apitomy.axiom.core.entities.WorkflowRunEntity();
            run.subjectId = (long) subjectId;
            run.definitionId = (long) definitionId;
            run.definitionVersion = 1;
            run.title = "Original title";
            run.labels = labels;
            run.inputs = inputs;
            run.instanceState = "{}";
            run.status = status;
            run.startedOn = java.time.Instant.now();
            run.completedOn = java.time.Instant.now();
            run.persist();
            return run.id;
        });
    }

    private int createSubject(String name) {
        return given()
                .contentType(ContentType.JSON)
                .body(String.format("""
                    {
                        "name": "%s",
                        "type": "other",
                        "ref": "%s"
                    }
                    """, name,
                        "test/" + name.toLowerCase().replace(" ", "-")))
                .when()
                    .post(SUBJECTS_PATH)
                .then()
                    .statusCode(200)
                    .extract().path("id");
    }

    private int createDefinition(String name) {
        return given()
                .contentType(ContentType.JSON)
                .body(String.format("""
                    {
                        "name": "%s"
                    }
                    """, name))
                .when()
                    .post(WORKFLOWS_PATH)
                .then()
                    .statusCode(200)
                    .extract().path("id");
    }

    /**
     * Creates and publishes a simple start→end workflow (completes
     * immediately on trigger).
     */
    private int createAndPublishDefinition(String name) {
        int id = createDefinition(name);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-test",
                        "name": "Test",
                        "nodes": [
                            {"id": "s1", "type": "start",
                             "name": "Start",
                             "config": {},
                             "position": {"x": 100, "y": 100}},
                            {"id": "e1", "type": "end",
                             "name": "End",
                             "config": {},
                             "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1",
                             "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(WORKFLOWS_PATH + "/" + id + "/content")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .post(WORKFLOWS_PATH + "/" + id + "/publish")
                .then()
                    .statusCode(200);

        return id;
    }

    /**
     * Creates and publishes a start→action→end workflow (stops at the
     * action node with status "waiting").
     */
    private int createAndPublishActionWorkflow(String name) {
        int id = createDefinition(name);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-action",
                        "name": "Action Test",
                        "nodes": [
                            {"id": "s1", "type": "start",
                             "name": "Start",
                             "config": {},
                             "position": {"x": 100, "y": 100}},
                            {"id": "a1", "type": "action",
                             "name": "Do Something",
                             "config": {
                                 "actionType": "test-action"
                             },
                             "position": {"x": 100, "y": 200}},
                            {"id": "e1", "type": "end",
                             "name": "End",
                             "config": {},
                             "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1",
                             "target": "a1",
                             "priority": 0, "isDefault": true},
                            {"id": "edge2", "source": "a1",
                             "target": "e1",
                             "priority": 0, "isDefault": true}
                        ]
                    }
                    """)
                .when()
                    .put(WORKFLOWS_PATH + "/" + id + "/content")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .post(WORKFLOWS_PATH + "/" + id + "/publish")
                .then()
                    .statusCode(200);

        return id;
    }
}
