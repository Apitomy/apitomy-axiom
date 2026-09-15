package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Exercises fork/join parallel execution end-to-end through the REST layer: a workflow
 * that forks into two unconditional branches which reconverge at a join node before
 * reaching {@code end}. Verifies that {@link WorkflowExecutionService} spawns one task
 * per active branch, that completing only one branch's task leaves the run WAITING with
 * the other branch's task still open (not duplicated), and that completing both advances
 * the run through the join to COMPLETED.
 *
 * <p>Branches are modeled as human-task nodes (surfaced via the inbox) rather than action
 * nodes. The underlying {@code apitomy-flow-engine} (2.0.0-SNAPSHOT) has a structural
 * limitation where an ACTION node whose executor returns {@code PENDING} — exactly what
 * every real Axiom action does — flips the whole instance to WAITING mid-fork-loop and
 * causes sibling fork branches to never be entered (only one of N branches gets an active
 * branch / task at all). Human-task branches don't hit this because they park without
 * mutating instance-level status. See the Task 6 report for full repro details; this is
 * an upstream engine issue out of scope for this axiom-only task, and does not affect the
 * axiom-side per-branch task-creation logic under test here.
 */
@QuarkusTest
class WorkflowExecutionParallelTest {

    private static final String PROJECTS_PATH = "/api/v1/projects";
    private static final String WORKFLOWS_PATH = "/api/v1/workflow/definitions";
    private static final String INBOX_PATH = "/api/v1/inbox";

    private static final String FORK_JOIN_CONTENT = """
        {
            "id": "fork-join-wf",
            "name": "Fork Join WF",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start",
                 "config": {}, "position": {"x": 100, "y": 100}},
                {"id": "a1", "type": "human-task", "name": "Branch A",
                 "config": {"description": "Approve branch A",
                            "outputs": [{"name": "approved", "type": "boolean",
                                         "required": true, "label": "Approve?"}]},
                 "position": {"x": 50, "y": 200}},
                {"id": "a2", "type": "human-task", "name": "Branch B",
                 "config": {"description": "Approve branch B",
                            "outputs": [{"name": "approved", "type": "boolean",
                                         "required": true, "label": "Approve?"}]},
                 "position": {"x": 150, "y": 200}},
                {"id": "j1", "type": "human-task", "name": "Join",
                 "config": {"description": "Confirm join",
                            "outputs": [{"name": "confirmed", "type": "boolean",
                                         "required": true, "label": "Confirm?"}]},
                 "position": {"x": 100, "y": 300}},
                {"id": "e1", "type": "end", "name": "End",
                 "config": {}, "position": {"x": 100, "y": 400}}
            ],
            "edges": [
                {"id": "edge1", "source": "s1", "target": "a1",
                 "priority": 0, "isDefault": false},
                {"id": "edge2", "source": "s1", "target": "a2",
                 "priority": 1, "isDefault": false},
                {"id": "edge3", "source": "a1", "target": "j1",
                 "priority": 0, "isDefault": true},
                {"id": "edge4", "source": "a2", "target": "j1",
                 "priority": 0, "isDefault": true},
                {"id": "edge5", "source": "j1", "target": "e1",
                 "priority": 0, "isDefault": true}
            ]
        }
        """;

    @Test
    void forkCreatesOneTaskPerBranchAndJoinCompletesRunAfterBoth() {
        int projectId = createProject("Fork Join Project");
        int definitionId = createAndPublishForkJoinDefinition("Fork Join WF");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("workflowDefinitionId", definitionId))
                .when()
                    .post(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("waiting"));

        // 1. Two inbox items created, one per active branch (a1 and a2).
        given()
                .when()
                    .get(INBOX_PATH)
                .then()
                    .statusCode(200)
                    .body("items.findAll { it.projectId == " + projectId + " }.size()",
                            equalTo(2));

        Integer branchATaskId = given()
                .when()
                    .get(INBOX_PATH)
                .then()
                    .statusCode(200)
                    .body("items.find { it.projectId == " + projectId
                            + " && it.actionType == 'Branch A' }.id", notNullValue())
                    .extract()
                    .path("items.find { it.projectId == " + projectId
                            + " && it.actionType == 'Branch A' }.id");
        Integer branchBTaskId = given()
                .when()
                    .get(INBOX_PATH)
                .then()
                    .statusCode(200)
                    .extract()
                    .path("items.find { it.projectId == " + projectId
                            + " && it.actionType == 'Branch B' }.id");

        // 2. Completing only branch A leaves the run WAITING and branch B's task still open
        //    (not duplicated, not removed).
        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"approved": true}
                    """)
                .when()
                    .post(INBOX_PATH + "/" + branchATaskId + "/complete")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("waiting"));

        given()
                .when()
                    .get(INBOX_PATH)
                .then()
                    .statusCode(200)
                    .body("items.findAll { it.projectId == " + projectId + " }.size()",
                            equalTo(1))
                    .body("items.find { it.projectId == " + projectId + " }.id",
                            equalTo(branchBTaskId));

        // 3. Completing branch B too joins the branches; run parks at the join's task.
        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"approved": true}
                    """)
                .when()
                    .post(INBOX_PATH + "/" + branchBTaskId + "/complete")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("waiting"));

        Integer joinTaskId = given()
                .when()
                    .get(INBOX_PATH)
                .then()
                    .statusCode(200)
                    .body("items.find { it.projectId == " + projectId + " }.actionType",
                            equalTo("Join"))
                    .extract()
                    .path("items.find { it.projectId == " + projectId + " }.id");

        // 4. Completing the join's task advances the run to completed.
        given()
                .contentType(ContentType.JSON)
                .body("""
                    {"confirmed": true}
                    """)
                .when()
                    .post(INBOX_PATH + "/" + joinTaskId + "/complete")
                .then()
                    .statusCode(204);

        given()
                .when()
                    .get(PROJECTS_PATH + "/" + projectId + "/workflow")
                .then()
                    .statusCode(200)
                    .body("status", equalTo("completed"));
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

    private int createAndPublishForkJoinDefinition(String name) {
        int id = given()
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

        given()
                .contentType(ContentType.JSON)
                .body(FORK_JOIN_CONTENT)
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
