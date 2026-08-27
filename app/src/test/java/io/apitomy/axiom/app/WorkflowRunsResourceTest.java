package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests workflow run history and trace creation.
 */
@QuarkusTest
class WorkflowRunsResourceTest {

    private static final String PROJECTS_PATH = "/api/v1/projects";
    private static final String WORKFLOWS_PATH = "/api/v1/workflow-definitions";

    /**
     * Verifies that triggering an ACTION workflow creates a trace root.
     * The traceId field is stored in the entity but not yet exposed in the
     * response bean (that happens in Task 9/12).
     */
    @Test
    void triggeringActionWorkflowStoresTraceId() {
        int projectId = createProject("WF Trace Project");
        int definitionId = createAndPublishActionWorkflow("Trace WF");

        given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("waiting"));

        // Verify trace was created by querying the entity directly
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunEntity.find("projectId", (long) projectId).firstResult());
        assertNotNull(run, "Run should exist");
        assertNotNull(run.traceId, "Trace ID should be set on the run entity");
    }

    /**
     * Verifies that after a start→end run completes immediately, a second
     * trigger is allowed and creates a new run row (history accumulation).
     */
    @Test
    void completedRunAllowsNewTrigger() {
        int projectId = createProject("WF History Project");
        int definitionId = createAndPublishDefinition("History WF");

        // First trigger completes immediately (start→end)
        given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("completed"));

        // Second trigger should succeed
        given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("completed"));

        // Verify 2 run rows exist
        long count = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunEntity.count("projectId", (long) projectId));
        assertEquals(2, count, "Should have 2 run rows (history)");
    }

    /**
     * Verifies that while a run is ACTIVE (waiting), a second trigger
     * returns 409.
     */
    @Test
    void activeRunRejectsDuplicate() {
        int projectId = createProject("WF Active Run Project");
        int definitionId = createAndPublishActionWorkflow("Active Run WF");

        // First trigger stays active (waiting at action node)
        given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("waiting"));

        // Second trigger should fail with 409
        given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(409);
    }

    // -- Helpers copied from WorkflowInstanceResourceTest --

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
