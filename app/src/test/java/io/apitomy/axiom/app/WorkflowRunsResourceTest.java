package io.apitomy.axiom.app;

import io.apitomy.axiom.agents.spi.AgentResult;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.TraceNodeEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests workflow run history and trace creation.
 */
@QuarkusTest
class WorkflowRunsResourceTest {

    private static final String PROJECTS_PATH = "/api/v1/projects";
    private static final String WORKFLOWS_PATH = "/api/v1/workflow/definitions";

    @Inject
    TaskExecutionService taskExecutionService;

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

    /**
     * Verifies that the workflow node task is stamped with workflowRunId,
     * nodeId, and traceId, and that a matching "task" trace node exists.
     */
    @Test
    void nodeTaskCarriesRunNodeAndTrace() {
        int projectId = createProject("WF Node Task Project");
        int definitionId = createAndPublishActionWorkflow("Node Task WF");

        given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("waiting"));

        // Query the spawned task entity directly
        TaskEntity task = QuarkusTransaction.requiringNew().call(() ->
                TaskEntity.find("projectId", (long) projectId).firstResult());
        assertNotNull(task, "Task should exist");
        assertNotNull(task.workflowRunId, "Task should have workflowRunId");
        assertNotNull(task.nodeId, "Task should have nodeId");
        assertNotNull(task.traceId, "Task should have traceId");

        // Query the run to get its traceId
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunEntity.findById(task.workflowRunId));
        assertNotNull(run, "Run should exist");

        // Verify a matching "task" trace node exists
        TraceNodeEntity traceNode = QuarkusTransaction.requiringNew().call(() ->
                TraceNodeEntity.find("traceId = ?1 and nodeType = ?2", run.traceId, "task")
                        .firstResult());
        assertNotNull(traceNode, "Task trace node should exist");
        assertEquals(task.id, traceNode.entityId,
                "Trace node entityId should match task id");
    }

    /**
     * Verifies that the project workflow instance returns the latest run and
     * includes runId and traceId fields.
     */
    @Test
    void projectWorkflowReturnsLatestRunWithRunIdAndTrace() {
        int projectId = createProject("WF Latest Run Project");
        int definitionId = createAndPublishActionWorkflow("Latest Run WF");

        Integer firstRunId = given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when().post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then().statusCode(200).extract().path("runId");

        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("runId", equalTo(firstRunId))
                    .body("traceId", notNullValue());
    }

    /**
     * Verifies that completing the first node of a multi-node workflow does
     * NOT complete the run's trace. The trace must remain open until the
     * entire run reaches a terminal state.
     */
    @Test
    void runTraceStaysOpenUntilRunCompletes() {
        int projectId = createProject("WF Trace Lifecycle Project");
        int definitionId = createAndPublishTwoActionWorkflow("Trace Lifecycle WF");

        given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("waiting"));

        // Query the run and the first action node's task
        Object[] runAndTask = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowRunEntity run = WorkflowRunEntity.find("projectId", (long) projectId)
                    .firstResult();
            assertNotNull(run, "Run should exist");
            assertNotNull(run.traceId, "Run should have traceId");

            TaskEntity a1Task = TaskEntity.find(
                    "workflowRunId = ?1 and nodeId = ?2", run.id, "a1")
                    .firstResult();
            assertNotNull(a1Task, "Action a1 task should exist");

            return new Object[] { run.traceId, a1Task.id };
        });

        java.util.UUID runTraceId = (java.util.UUID) runAndTask[0];
        Long a1TaskId = (Long) runAndTask[1];

        // Simulate the first action node completing successfully
        AgentResult successResult = AgentResult.success("Test action completed");
        QuarkusTransaction.requiringNew().run(() ->
                taskExecutionService.onTaskCompleted(a1TaskId, successResult));

        // After node a1 completes, the run should have advanced to a2 and
        // still be active (non-terminal). The trace must remain open because
        // WorkflowExecutionService owns the trace lifecycle and only completes
        // it when the run reaches a terminal state.
        TraceEntity trace = QuarkusTransaction.requiringNew().call(() ->
                TraceEntity.findById(runTraceId));
        assertNotNull(trace, "Trace should exist");
        assertNotEquals("completed", trace.status,
                "Trace should NOT be completed after first node finishes "
                        + "(workflow trace lifecycle is owned by WorkflowExecutionService)");
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

    /**
     * Creates and publishes a start→action→action→end workflow with two
     * action nodes (a1, a2) in series. Stops at the first action node.
     */
    private int createAndPublishTwoActionWorkflow(String name) {
        int id = createDefinition(name);

        given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "id": "wf-two-action",
                        "name": "Two Action Test",
                        "nodes": [
                            {"id": "s1", "type": "start",
                             "name": "Start",
                             "config": {},
                             "position": {"x": 100, "y": 100}},
                            {"id": "a1", "type": "action",
                             "name": "Action 1",
                             "config": {
                                 "actionType": "test-action"
                             },
                             "position": {"x": 100, "y": 200}},
                            {"id": "a2", "type": "action",
                             "name": "Action 2",
                             "config": {
                                 "actionType": "test-action"
                             },
                             "position": {"x": 100, "y": 300}},
                            {"id": "e1", "type": "end",
                             "name": "End",
                             "config": {},
                             "position": {"x": 100, "y": 400}}
                        ],
                        "edges": [
                            {"id": "edge1", "source": "s1",
                             "target": "a1",
                             "priority": 0, "isDefault": true},
                            {"id": "edge2", "source": "a1",
                             "target": "a2",
                             "priority": 0, "isDefault": true},
                            {"id": "edge3", "source": "a2",
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

    @Test
    void listAndGetWorkflowRuns() {
        int projectId = createProject("WF Runs List Project");
        int definitionId = createAndPublishActionWorkflow("Runs List WF");

        Integer runId = given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when().post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then().statusCode(200).extract().path("runId");

        given()
                .when().get("/api/v1/workflow/runs?projectId=" + projectId)
                .then()
                    .statusCode(200)
                    .body("totalCount", equalTo(1))
                    .body("items[0].runId", equalTo(runId))
                    .body("items[0].projectName", equalTo("WF Runs List Project"))
                    .body("items[0].definitionName", equalTo("Runs List WF"));

        given()
                .when().get("/api/v1/workflow/runs/" + runId)
                .then()
                    .statusCode(200)
                    .body("runId", equalTo(runId))
                    .body("projectId", equalTo(projectId));

        given()
                .when().get("/api/v1/workflow/runs/999999")
                .then().statusCode(404);
    }

    @Test
    void listRunsForDefinition() {
        int projectId = createProject("WF DefRuns Project");
        int definitionId = createAndPublishActionWorkflow("DefRuns WF");

        given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitionId\": %d}".formatted(definitionId))
                .when().post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then().statusCode(200);

        given()
                .when().get(WORKFLOWS_PATH + "/" + definitionId + "/runs")
                .then()
                    .statusCode(200)
                    .body("totalCount", equalTo(1))
                    .body("items[0].definitionId", equalTo(definitionId));
    }

    @Test
    void listRunsForMissingDefinitionReturns404() {
        given()
                .when().get(WORKFLOWS_PATH + "/999999/runs")
                .then().statusCode(404);
    }
}
