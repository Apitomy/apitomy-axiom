package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

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
                .body("status", equalTo("Created"))
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

        // Project must be Completed before it can be deleted
        updateStatus(id, "InProgress");
        updateStatus(id, "Completed");

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
    void testValidStatusTransition() {
        int id = createProject("Lifecycle Test", "bug-fix", "owner/repo#300");

        // Created → InProgress (valid)
        given()
            .contentType(ContentType.JSON)
            .body("""
                { "status": "InProgress" }
                """)
            .when()
                .put(PROJECTS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("status", equalTo("InProgress"));

        // InProgress → Idle (valid)
        given()
            .contentType(ContentType.JSON)
            .body("""
                { "status": "Idle" }
                """)
            .when()
                .put(PROJECTS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("status", equalTo("Idle"));

        // Idle → Completed (valid)
        given()
            .contentType(ContentType.JSON)
            .body("""
                { "status": "Completed" }
                """)
            .when()
                .put(PROJECTS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("status", equalTo("Completed"));
    }

    @Test
    void testInvalidStatusTransition() {
        int id = createProject("Invalid Lifecycle", "bug-fix", "owner/repo#301");

        // Created → Completed (invalid — must go through InProgress)
        given()
            .contentType(ContentType.JSON)
            .body("""
                { "status": "Completed" }
                """)
            .when()
                .put(PROJECTS_PATH + "/" + id)
            .then()
                .statusCode(409);
    }

    @Test
    void testReopenCompletedProject() {
        int id = createProject("Reopen Test", "bug-fix", "owner/repo#302");

        // Created → InProgress → Completed
        updateStatus(id, "InProgress");
        updateStatus(id, "Completed");

        // Completed → InProgress (valid re-open)
        given()
            .contentType(ContentType.JSON)
            .body("""
                { "status": "InProgress" }
                """)
            .when()
                .put(PROJECTS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("status", equalTo("InProgress"));
    }

    // ── Tasks ─────────────────────────────────────────────────────────

    @Test
    void testCreateAndListTasks() {
        int projectId = createProject("Task Test", "feature", "owner/repo#400");

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "actionType": "analyze",
                    "input": "Please analyze this issue"
                }
                """)
            .when()
                .post(PROJECTS_PATH + "/" + projectId + "/tasks")
            .then()
                .statusCode(200)
                .body("actionType", equalTo("analyze"))
                .body("createdBy", equalTo("user"))
                .body("status", equalTo("Pending"))
                .body("input", equalTo("Please analyze this issue"))
                .body("projectId", equalTo(projectId));

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

        int taskId = given()
            .contentType(ContentType.JSON)
            .body("""
                { "actionType": "implement" }
                """)
            .when()
                .post(PROJECTS_PATH + "/" + projectId + "/tasks")
            .then()
                .statusCode(200)
                .extract().path("id");

        given()
            .when()
                .get(PROJECTS_PATH + "/" + projectId + "/tasks/" + taskId)
            .then()
                .statusCode(200)
                .body("id", equalTo(taskId))
                .body("actionType", equalTo("implement"));
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
    void testFilterByStatus() {
        int id = createProject("Status Filter Test", "bug-fix", "owner/repo#630");
        updateStatus(id, "InProgress");

        given()
            .queryParam("filterStatus", "InProgress")
            .when()
                .get(PROJECTS_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", greaterThanOrEqualTo(1))
                .body("items.status", everyItem(equalTo("InProgress")));
    }

    @Test
    void testFilterByMultipleStatuses() {
        int id1 = createProject("Multi Status 1", "bug-fix", "owner/repo#640");
        updateStatus(id1, "InProgress");
        createProject("Multi Status 2", "feature", "owner/repo#641");
        // id2 stays in "Created" status

        given()
            .queryParam("filterStatus", "InProgress,Created")
            .when()
                .get(PROJECTS_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", greaterThanOrEqualTo(2))
                .body("items.status", everyItem(
                        anyOf(equalTo("InProgress"), equalTo("Created"))));
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

    private void updateStatus(int projectId, String status) {
        given()
            .contentType(ContentType.JSON)
            .body(String.format("""
                { "status": "%s" }
                """, status))
            .when()
                .put(PROJECTS_PATH + "/" + projectId)
            .then()
                .statusCode(200);
    }
}
