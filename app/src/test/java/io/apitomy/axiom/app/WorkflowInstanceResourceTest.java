package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class WorkflowInstanceResourceTest {

    private static final String PROJECTS_PATH = "/api/v1/projects";
    private static final String WORKFLOWS_PATH = "/api/v1/workflow/definitions";

    @Test
    void testTriggerAndGetWorkflow() {
        int projectId = createProject("WF Trigger Test Project");
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
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("id", notNullValue())
                    .body("projectId", equalTo(projectId))
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
                    .get(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("projectId", equalTo(projectId))
                    .body("definitionName", equalTo("Trigger Test WF"));
    }

    @Test
    void testTriggerDuplicateReturns409() {
        int projectId = createProject("WF Dup Test Project");
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
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
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
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(409);
    }

    @Test
    void testTriggerUnpublishedReturns400() {
        int projectId = createProject("WF Unpub Test Project");
        int definitionId = createDefinition("Unpublished Test WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(400);
    }

    @Test
    void testTriggerWithUnsupportedNodeTypesReturns400() {
        int projectId = createProject("WF Unsupported Nodes Project");
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
                            {"id": "ht1", "type": "human-task",
                             "name": "Human Task",
                             "config": {"description": "Do something"},
                             "position": {"x": 100, "y": 200}},
                            {"id": "e1", "type": "end", "name": "End",
                             "config": {}, "position": {"x": 100, "y": 300}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1",
                             "target": "ht1",
                             "priority": 0, "isDefault": true},
                            {"id": "edge2", "source": "ht1",
                             "target": "e1",
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
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(400);
    }

    @Test
    void testCancelWorkflow() {
        int projectId = createProject("WF Cancel Test Project");
        int definitionId = createAndPublishActionWorkflow(
                "Cancel Test WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("waiting"));

        given()
                .when()
                    .delete(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("cancelled"));
    }

    @Test
    void testCancelTerminalReturns409() {
        int projectId = createProject("WF Cancel Term Project");
        int definitionId = createAndPublishDefinition(
                "Cancel Term WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("completed"));

        given()
                .when()
                    .delete(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(409);
    }

    @Test
    void testCancelActiveRunCancelsLatestNotPriorTerminal() {
        int projectId = createProject("WF Cancel Active Run Project");
        int definitionId = createAndPublishActionWorkflow(
                "Cancel Active Run WF");

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("waiting"));

        given()
                .when()
                    .delete(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(204);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "workflowDefinitionId": %d
                    }
                    """.formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("waiting"));

        given()
                .when()
                    .delete(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("cancelled"));
    }

    @Test
    void testGetNoInstanceReturns404() {
        int projectId = createProject("WF No Instance Project");

        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(404);
    }

    @Test
    void testHasWorkflowInstanceOnProject() {
        int projectId = createProject(
                "WF HasInstance Test Project");

        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId)
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
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200);

        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId)
                .then()
                    .statusCode(200)
                    .body("hasWorkflowInstance", equalTo(true));
    }

    // -- Helpers --

    private int createProject(String name) {
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
                    .post(PROJECTS_PATH)
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
