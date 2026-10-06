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
 * Tests for the Subjects REST API endpoints, including tasks and threads.
 */
@QuarkusTest
class SubjectsResourceTest {

    private static final String SUBJECTS_PATH = "/api/v1/subjects";

    // ── Subjects CRUD ─────────────────────────────────────────────────

    @Test
    void testListSubjectsReturnsSearchResults() {
        given()
            .when()
                .get(SUBJECTS_PATH)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("items", is(notNullValue()))
                .body("totalCount", is(notNullValue()))
                .body("page", equalTo(1))
                .body("limit", equalTo(20));
    }

    @Test
    void testCreateAndGetSubject() {
        int id = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "Test Subject",
                    "body": "A test subject",
                    "type": "bug-fix",
                    "refSource": "github",
                    "ref": "owner/repo#1",
                    "repository": "owner/repo"
                }
                """)
            .when()
                .post(SUBJECTS_PATH)
            .then()
                .statusCode(200)
                .body("name", equalTo("Test Subject"))
                .body("body", equalTo("A test subject"))
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
                .get(SUBJECTS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("name", equalTo("Test Subject"))
                .body("id", equalTo(id));
    }

    @Test
    void testUpdateSubject() {
        int id = createSubject("Update Test", "feature", "owner/repo#100");

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "Updated Name",
                    "body": "Updated description"
                }
                """)
            .when()
                .put(SUBJECTS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("name", equalTo("Updated Name"))
                .body("body", equalTo("Updated description"))
                .body("type", equalTo("feature"));
    }

    @Test
    void testDeleteSubject() {
        int id = createSubject("Delete Test", "other", "owner/repo#200");

        given()
            .when()
                .delete(SUBJECTS_PATH + "/" + id)
            .then()
                .statusCode(204);

        given()
            .when()
                .get(SUBJECTS_PATH + "/" + id)
            .then()
                .statusCode(404);
    }

    @Test
    void testCreateSubjectMissingType() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "No Type Subject",
                    "ref": "owner/repo#missing-type"
                }
                """)
            .when()
                .post(SUBJECTS_PATH)
            .then()
                .statusCode(400);
    }

    @Test
    void testCreateSubjectMissingName() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "type": "bug-fix",
                    "ref": "owner/repo#missing-name"
                }
                """)
            .when()
                .post(SUBJECTS_PATH)
            .then()
                .statusCode(400);
    }

    @Test
    void testCreateSubjectMissingRef() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "No Ref Subject",
                    "type": "bug-fix"
                }
                """)
            .when()
                .post(SUBJECTS_PATH)
            .then()
                .statusCode(400);
    }

    @Test
    void testGetSubjectNotFound() {
        given()
            .when()
                .get(SUBJECTS_PATH + "/999999")
            .then()
                .statusCode(404);
    }

    @Test
    void testUpdateSubjectBody() {
        int id = createSubject("Body Test", "feature", "owner/repo#150");

        given()
            .contentType("text/markdown")
            .body("# Hello\n\nThis is **markdown** body content.")
            .when()
                .put(SUBJECTS_PATH + "/" + id + "/body")
            .then()
                .statusCode(204);

        given()
            .when()
                .get(SUBJECTS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("body", equalTo("# Hello\n\nThis is **markdown** body content."));
    }

    @Test
    void testUpdateSubjectBodyNotFound() {
        given()
            .contentType("text/markdown")
            .body("some content")
            .when()
                .put(SUBJECTS_PATH + "/999999/body")
            .then()
                .statusCode(404);
    }

    @Test
    void creatingTaskDirectlyOnSubjectIsNotAllowed() {
        int subjectId = createSubject("Direct Task", "feature", "owner/repo#399");

        given()
            .contentType(ContentType.JSON)
            .body("{\"actionType\": \"anything\"}")
            .when()
                .post(SUBJECTS_PATH + "/" + subjectId + "/tasks")
            .then()
                .statusCode(405);
    }

    @Test
    void testListTasks() {
        int subjectId = createSubject("Task Test", "feature", "owner/repo#400");
        seedTask(subjectId, "analyze");

        given()
            .when()
                .get(SUBJECTS_PATH + "/" + subjectId + "/tasks")
            .then()
                .statusCode(200)
                .body("$.size()", equalTo(1))
                .body("[0].actionType", equalTo("analyze"));
    }

    @Test
    void testGetTask() {
        int subjectId = createSubject("Get Task Test", "feature", "owner/repo#401");
        long taskId = seedTask(subjectId, "implement");

        given()
            .when()
                .get(SUBJECTS_PATH + "/" + subjectId + "/tasks/" + taskId)
            .then()
                .statusCode(200)
                .body("id", equalTo((int) taskId))
                .body("actionType", equalTo("implement"));
    }

    /**
     * Persists a pending task directly, standing in for a task created by a workflow action node.
     */
    private long seedTask(int subjectId, String actionType) {
        return QuarkusTransaction.requiringNew().call(() -> {
            TaskEntity task = new TaskEntity();
            task.subjectId = (long) subjectId;
            task.actionType = actionType;
            task.createdBy = "workflow";
            task.status = "Pending";
            task.createdOn = Instant.now();
            task.persist();
            return task.id;
        });
    }

    @Test
    void testTasksForNonexistentSubject() {
        given()
            .when()
                .get(SUBJECTS_PATH + "/999999/tasks")
            .then()
                .statusCode(404);
    }

    // ── Threads ───────────────────────────────────────────────────────

    @Test
    void testListThreadEntriesEmpty() {
        int subjectId = createSubject("Thread Test", "feature", "owner/repo#500");

        given()
            .when()
                .get(SUBJECTS_PATH + "/" + subjectId + "/thread")
            .then()
                .statusCode(200)
                .body("$.size()", equalTo(0));
    }

    @Test
    void testThreadEntriesForNonexistentSubject() {
        given()
            .when()
                .get(SUBJECTS_PATH + "/999999/thread")
            .then()
                .statusCode(404);
    }

    // ── Pagination and Filtering ───────────────────────────────────────

    @Test
    void testPaginationDefaults() {
        // Create a few subjects to have data
        createSubject("Page Test 1", "bug-fix", "owner/repo#600");
        createSubject("Page Test 2", "feature", "owner/repo#601");

        given()
            .when()
                .get(SUBJECTS_PATH)
            .then()
                .statusCode(200)
                .body("page", equalTo(1))
                .body("limit", equalTo(20))
                .body("totalCount", greaterThanOrEqualTo(2))
                .body("items.size()", greaterThanOrEqualTo(2));
    }

    @Test
    void testPaginationWithPageSize() {
        createSubject("Small Page 1", "bug-fix", "owner/repo#610");
        createSubject("Small Page 2", "feature", "owner/repo#611");
        createSubject("Small Page 3", "other", "owner/repo#612");

        given()
            .queryParam("page", 1)
            .queryParam("limit", 2)
            .when()
                .get(SUBJECTS_PATH)
            .then()
                .statusCode(200)
                .body("page", equalTo(1))
                .body("limit", equalTo(2))
                .body("items.size()", lessThanOrEqualTo(2))
                .body("totalCount", greaterThanOrEqualTo(3));
    }

    @Test
    void testFilterByName() {
        createSubject("Unique Alpha Subject", "bug-fix", "owner/repo#620");
        createSubject("Unique Beta Subject", "feature", "owner/repo#621");

        given()
            .queryParam("filterName", "Alpha")
            .when()
                .get(SUBJECTS_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", greaterThanOrEqualTo(1))
                .body("items.name", everyItem(containsStringIgnoringCase("Alpha")));
    }

    @Test
    void testFilterByIssueRef() {
        createSubject("IssueRef Filter Test", "bug-fix", "filter-org/filter-repo#999");

        given()
            .queryParam("filterName", "filter-org/filter-repo")
            .when()
                .get(SUBJECTS_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", greaterThanOrEqualTo(1))
                .body("items.ref", hasItem(containsString("filter-org/filter-repo")));
    }

    @Test
    void testFilterNoResults() {
        given()
            .queryParam("filterName", "zzz_nonexistent_subject_name_zzz")
            .when()
                .get(SUBJECTS_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", equalTo(0))
                .body("totalCount", equalTo(0));
    }

    @Test
    void testFilterWithPagination() {
        // Filters should affect totalCount (not just the current page)
        createSubject("Paginated Filter A1", "bug-fix", "owner/repo#650");
        createSubject("Paginated Filter A2", "bug-fix", "owner/repo#651");
        createSubject("Paginated Filter B1", "feature", "owner/repo#652");

        given()
            .queryParam("filterName", "Paginated Filter A")
            .queryParam("page", 1)
            .queryParam("limit", 1)
            .when()
                .get(SUBJECTS_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", equalTo(1))
                .body("totalCount", greaterThanOrEqualTo(2));
    }

    @Test
    void testActiveReflectsRunningOrWaitingRuns() {
        int id = createSubject("Active Test", "other", "owner/repo#700");
        long runId = QuarkusTransaction.requiringNew().call(() -> {
            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = "active-test-def-" + id;
            def.content = "{}";
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.subjectId = (long) id;
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
                .get(SUBJECTS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("active", equalTo(true));
        given()
            .queryParam("filterRef", "owner/repo#700")
            .when()
                .get(SUBJECTS_PATH)
            .then()
                .statusCode(200)
                .body("items[0].active", equalTo(true));
        given()
            .queryParam("filterActive", true)
            .queryParam("filterRef", "owner/repo#700")
            .when()
                .get(SUBJECTS_PATH)
            .then()
                .statusCode(200)
                .body("totalCount", equalTo(1));
        given()
            .queryParam("filterActive", false)
            .queryParam("filterRef", "owner/repo#700")
            .when()
                .get(SUBJECTS_PATH)
            .then()
                .statusCode(200)
                .body("totalCount", equalTo(0));
        // Subjects with an active run cannot be deleted
        given()
            .when()
                .delete(SUBJECTS_PATH + "/" + id)
            .then()
                .statusCode(409);

        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunEntity run = WorkflowRunEntity.findById(runId);
            run.status = "completed";
            run.completedOn = Instant.now();
        });
        given()
            .when()
                .get(SUBJECTS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("active", equalTo(false));
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private int createSubject(String name, String type, String ref) {
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
                .post(SUBJECTS_PATH)
            .then()
                .statusCode(200)
                .extract().path("id");
    }

}
