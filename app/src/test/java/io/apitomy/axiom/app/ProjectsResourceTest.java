package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for the Projects REST API endpoints, including tasks and threads.
 */
@QuarkusTest
class ProjectsResourceTest {

    private static final String PROJECTS_PATH = "/api/v1/projects";

    // ── Projects CRUD ─────────────────────────────────────────────────

    @Test
    void testListProjectsReturnsSearchResults() {
        given()
            .when()
                .get(PROJECTS_PATH)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("items", is(notNullValue()))
                .body("totalCount", is(notNullValue()))
                .body("page", equalTo(1))
                .body("limit", equalTo(20));
    }

    @Test
    void testCreateAndGetProject() {
        int id = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "Test Project",
                    "body": "A test project",
                    "type": "bug-fix",
                    "refSource": "github",
                    "ref": "owner/repo#1",
                    "repository": "owner/repo"
                }
                """)
            .when()
                .post(PROJECTS_PATH)
            .then()
                .statusCode(200)
                .body("name", equalTo("Test Project"))
                .body("body", equalTo("A test project"))
                .body("type", equalTo("bug-fix"))
                .body("active", equalTo(false))
                .body("refSource", equalTo("github"))
                .body("ref", equalTo("owner/repo#1"))
                .body("repository", equalTo("owner/repo"))
                .body("id", notNullValue())
                .body("createdOn", notNullValue())
                .body("updatedOn", notNullValue())
                .extract().path("id");

        given()
            .when()
                .get(PROJECTS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("name", equalTo("Test Project"))
                .body("id", equalTo(id));
    }

    @Test
    void testUpdateProject() {
        int id = createProject("Update Test", "feature", "owner/repo#100");

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "Updated Name",
                    "body": "Updated description"
                }
                """)
            .when()
                .put(PROJECTS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("name", equalTo("Updated Name"))
                .body("body", equalTo("Updated description"))
                .body("type", equalTo("feature"));
    }

    @Test
    void testDeleteProject() {
        int id = createProject("Delete Test", "other", "owner/repo#200");

        given()
            .when()
                .delete(PROJECTS_PATH + "/" + id)
            .then()
                .statusCode(204);

        given()
            .when()
                .get(PROJECTS_PATH + "/" + id)
            .then()
                .statusCode(404);
    }

    @Test
    void testCreateProjectMissingType() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "No Type Project",
                    "ref": "owner/repo#missing-type"
                }
                """)
            .when()
                .post(PROJECTS_PATH)
            .then()
                .statusCode(400);
    }

    @Test
    void testCreateProjectMissingName() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "type": "bug-fix",
                    "ref": "owner/repo#missing-name"
                }
                """)
            .when()
                .post(PROJECTS_PATH)
            .then()
                .statusCode(400);
    }

    @Test
    void testCreateProjectMissingRef() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "No Ref Project",
                    "type": "bug-fix"
                }
                """)
            .when()
                .post(PROJECTS_PATH)
            .then()
                .statusCode(400);
    }

    @Test
    void testGetProjectNotFound() {
        given()
            .when()
                .get(PROJECTS_PATH + "/999999")
            .then()
                .statusCode(404);
    }

    @Test
    void testUpdateProjectBody() {
        int id = createProject("Body Test", "feature", "owner/repo#150");

        given()
            .contentType("text/markdown")
            .body("# Hello\n\nThis is **markdown** body content.")
            .when()
                .put(PROJECTS_PATH + "/" + id + "/body")
            .then()
                .statusCode(204);

        given()
            .when()
                .get(PROJECTS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("body", equalTo("# Hello\n\nThis is **markdown** body content."));
    }

    @Test
    void testUpdateProjectBodyNotFound() {
        given()
            .contentType("text/markdown")
            .body("some content")
            .when()
                .put(PROJECTS_PATH + "/999999/body")
            .then()
                .statusCode(404);
    }

    // ── Project Lifecycle ─────────────────────────────────────────────

    @Test
    void creatingTaskDirectlyOnProjectIsNotAllowed() {
        int projectId = createProject("Direct Task", "feature", "owner/repo#399");

        given()
            .contentType(ContentType.JSON)
            .body("{\"actionType\": \"anything\"}")
            .when()
                .post(PROJECTS_PATH + "/" + projectId + "/tasks")
            .then()
                .statusCode(405);
    }

    @Test
    void testListTasks() {
        int projectId = createProject("Task Test", "feature", "owner/repo#400");
        seedTask(projectId, "analyze");

        given()
            .when()
                .get(PROJECTS_PATH + "/" + projectId + "/tasks")
            .then()
                .statusCode(200)
                .body("$.size()", equalTo(1))
                .body("[0].actionType", equalTo("analyze"));
    }

    @Test
    void testGetTask() {
        int projectId = createProject("Get Task Test", "feature", "owner/repo#401");
        long taskId = seedTask(projectId, "implement");

        given()
            .when()
                .get(PROJECTS_PATH + "/" + projectId + "/tasks/" + taskId)
            .then()
                .statusCode(200)
                .body("id", equalTo((int) taskId))
                .body("actionType", equalTo("implement"));
    }

    /**
     * Persists a pending task directly, standing in for a task created by a workflow action node.
     */
    private long seedTask(int projectId, String actionType) {
        return QuarkusTransaction.requiringNew().call(() -> {
            TaskEntity task = new TaskEntity();
            task.projectId = (long) projectId;
            task.actionType = actionType;
            task.createdBy = "workflow";
            task.status = "Pending";
            task.createdOn = Instant.now();
            task.persist();
            return task.id;
        });
    }

    @Test
    void testTasksForNonexistentProject() {
        given()
            .when()
                .get(PROJECTS_PATH + "/999999/tasks")
            .then()
                .statusCode(404);
    }

    // ── Threads ───────────────────────────────────────────────────────

    @Test
    void testListThreadEntriesEmpty() {
        int projectId = createProject("Thread Test", "feature", "owner/repo#500");

        given()
            .when()
                .get(PROJECTS_PATH + "/" + projectId + "/thread")
            .then()
                .statusCode(200)
                .body("$.size()", equalTo(0));
    }

    @Test
    void testThreadEntriesForNonexistentProject() {
        given()
            .when()
                .get(PROJECTS_PATH + "/999999/thread")
            .then()
                .statusCode(404);
    }

    // ── Pagination and Filtering ───────────────────────────────────────

    @Test
    void testPaginationDefaults() {
        // Create a few projects to have data
        createProject("Page Test 1", "bug-fix", "owner/repo#600");
        createProject("Page Test 2", "feature", "owner/repo#601");

        given()
            .when()
                .get(PROJECTS_PATH)
            .then()
                .statusCode(200)
                .body("page", equalTo(1))
                .body("limit", equalTo(20))
                .body("totalCount", greaterThanOrEqualTo(2))
                .body("items.size()", greaterThanOrEqualTo(2));
    }

    @Test
    void testPaginationWithPageSize() {
        createProject("Small Page 1", "bug-fix", "owner/repo#610");
        createProject("Small Page 2", "feature", "owner/repo#611");
        createProject("Small Page 3", "other", "owner/repo#612");

        given()
            .queryParam("page", 1)
            .queryParam("limit", 2)
            .when()
                .get(PROJECTS_PATH)
            .then()
                .statusCode(200)
                .body("page", equalTo(1))
                .body("limit", equalTo(2))
                .body("items.size()", lessThanOrEqualTo(2))
                .body("totalCount", greaterThanOrEqualTo(3));
    }

    @Test
    void testFilterByName() {
        createProject("Unique Alpha Project", "bug-fix", "owner/repo#620");
        createProject("Unique Beta Project", "feature", "owner/repo#621");

        given()
            .queryParam("filterName", "Alpha")
            .when()
                .get(PROJECTS_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", greaterThanOrEqualTo(1))
                .body("items.name", everyItem(containsStringIgnoringCase("Alpha")));
    }

    @Test
    void testFilterByIssueRef() {
        createProject("IssueRef Filter Test", "bug-fix", "filter-org/filter-repo#999");

        given()
            .queryParam("filterName", "filter-org/filter-repo")
            .when()
                .get(PROJECTS_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", greaterThanOrEqualTo(1))
                .body("items.ref", hasItem(containsString("filter-org/filter-repo")));
    }

    @Test
    void testFilterNoResults() {
        given()
            .queryParam("filterName", "zzz_nonexistent_project_name_zzz")
            .when()
                .get(PROJECTS_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", equalTo(0))
                .body("totalCount", equalTo(0));
    }

    @Test
    void testFilterWithPagination() {
        // Filters should affect totalCount (not just the current page)
        createProject("Paginated Filter A1", "bug-fix", "owner/repo#650");
        createProject("Paginated Filter A2", "bug-fix", "owner/repo#651");
        createProject("Paginated Filter B1", "feature", "owner/repo#652");

        given()
            .queryParam("filterName", "Paginated Filter A")
            .queryParam("page", 1)
            .queryParam("limit", 1)
            .when()
                .get(PROJECTS_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", equalTo(1))
                .body("totalCount", greaterThanOrEqualTo(2));
    }

    @Test
    void testActiveReflectsRunningOrWaitingRuns() {
        int id = createProject("Active Test", "other", "owner/repo#700");
        long runId = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = "active-test-def-" + id;
            def.content = "{}";
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.projectId = (long) id;
            run.definitionId = def.id;
            run.definitionVersion = 1;
            run.instanceState = "{}";
            run.status = "waiting";
            run.startedOn = Instant.now();
            run.persist();
            return run.id;
        });

        given()
            .when()
                .get(PROJECTS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("active", equalTo(true));
        given()
            .queryParam("filterRef", "owner/repo#700")
            .when()
                .get(PROJECTS_PATH)
            .then()
                .statusCode(200)
                .body("items[0].active", equalTo(true));
        given()
            .queryParam("filterActive", true)
            .queryParam("filterRef", "owner/repo#700")
            .when()
                .get(PROJECTS_PATH)
            .then()
                .statusCode(200)
                .body("totalCount", equalTo(1));
        given()
            .queryParam("filterActive", false)
            .queryParam("filterRef", "owner/repo#700")
            .when()
                .get(PROJECTS_PATH)
            .then()
                .statusCode(200)
                .body("totalCount", equalTo(0));
        // Projects with an active run cannot be deleted
        given()
            .when()
                .delete(PROJECTS_PATH + "/" + id)
            .then()
                .statusCode(409);

        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
            run.status = "completed";
            run.completedOn = Instant.now();
        });
        given()
            .when()
                .get(PROJECTS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("active", equalTo(false));
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private int createProject(String name, String type, String ref) {
        return given()
            .contentType(ContentType.JSON)
            .body(String.format("""
                {
                    "name": "%s",
                    "type": "%s",
                    "refSource": "github",
                    "ref": "%s",
                    "repository": "owner/repo"
                }
                """, name, type, ref))
            .when()
                .post(PROJECTS_PATH)
            .then()
                .statusCode(200)
                .extract().path("id");
    }

}
