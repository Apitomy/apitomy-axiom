package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for the Activity Log REST API endpoint, including pagination and filtering.
 */
@QuarkusTest
class ActivityResourceTest {

    private static final String ACTIVITY_PATH = "/api/v1/activity";

    private static final UUID EVENT_A = UUID.fromString("a1a1a1a1-0000-4000-8000-000000000100");
    private static final UUID EVENT_B = UUID.fromString("b2b2b2b2-0000-4000-8000-000000000200");
    private static final UUID EVENT_C = UUID.fromString("c3c3c3c3-0000-4000-8000-000000000300");

    @BeforeEach
    @Transactional
    void seedActivityEntries() {
        // Only seed once — check if our test entries already exist
        if (ActivityLogEntity.count("summary like 'FILTER-TEST%'") > 0) {
            return;
        }

        createEntry(1L, 10L, EVENT_A, "task-completed", "FILTER-TEST task alpha completed");
        createEntry(1L, 11L, EVENT_A, "task-failed", "FILTER-TEST task beta failed");
        createEntry(2L, 20L, EVENT_B, "task-completed", "FILTER-TEST task gamma completed");
        createEntry(null, null, EVENT_C, "event-ignored", "FILTER-TEST event ignored");
        createEntry(3L, 30L, null, "project-created", "FILTER-TEST project created");
    }

    private void createEntry(Long projectId, Long taskId, UUID eventId,
                              String entryType, String summary) {
        ActivityLogEntity entry = new ActivityLogEntity();
        entry.projectId = projectId;
        entry.taskId = taskId;
        entry.eventId = eventId;
        entry.entryType = entryType;
        entry.summary = summary;
        entry.createdOn = Instant.now();
        entry.persist();
    }

    // ── Pagination ───────────────────────────────────────────────────

    @Test
    void testPaginationDefaults() {
        given()
            .when()
                .get(ACTIVITY_PATH)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("page", equalTo(1))
                .body("limit", equalTo(20))
                .body("totalCount", greaterThanOrEqualTo(5))
                .body("items", is(notNullValue()));
    }

    @Test
    void testPaginationWithPageSize() {
        given()
            .queryParam("page", 1)
            .queryParam("limit", 2)
            .when()
                .get(ACTIVITY_PATH)
            .then()
                .statusCode(200)
                .body("page", equalTo(1))
                .body("limit", equalTo(2))
                .body("items.size()", lessThanOrEqualTo(2))
                .body("totalCount", greaterThanOrEqualTo(5));
    }

    // ── Filter by entry type ─────────────────────────────────────────

    @Test
    void testFilterByEntryType() {
        given()
            .queryParam("filterEntryType", "task-completed")
            .when()
                .get(ACTIVITY_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", greaterThanOrEqualTo(2))
                .body("items.entryType", everyItem(equalTo("task-completed")));
    }

    @Test
    void testFilterByMultipleEntryTypes() {
        given()
            .queryParam("filterEntryType", "task-completed,task-failed")
            .when()
                .get(ACTIVITY_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", greaterThanOrEqualTo(3))
                .body("items.entryType", everyItem(
                        anyOf(equalTo("task-completed"), equalTo("task-failed"))));
    }

    // ── Filter by summary ────────────────────────────────────────────

    @Test
    void testFilterBySummary() {
        given()
            .queryParam("filterSummary", "FILTER-TEST task alpha")
            .when()
                .get(ACTIVITY_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].summary", containsString("alpha"));
    }

    @Test
    void testFilterBySummaryCaseInsensitive() {
        given()
            .queryParam("filterSummary", "filter-test")
            .when()
                .get(ACTIVITY_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", greaterThanOrEqualTo(5));
    }

    // ── Filter by project ID ─────────────────────────────────────────

    @Test
    void testFilterByProjectId() {
        given()
            .queryParam("filterProjectId", 1)
            .when()
                .get(ACTIVITY_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", greaterThanOrEqualTo(2))
                .body("items.projectId", everyItem(equalTo(1)));
    }

    // ── Filter by event ID ───────────────────────────────────────────

    @Test
    void testFilterByEventId() {
        given()
            .queryParam("filterEventId", EVENT_B.toString())
            .when()
                .get(ACTIVITY_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].summary", containsString("gamma"))
                .body("items.eventId", everyItem(equalTo(EVENT_B.toString())));
    }

    @Test
    void testFilterByInvalidEventIdReturns400() {
        given()
            .queryParam("filterEventId", "200")
            .when()
                .get(ACTIVITY_PATH)
            .then()
                .statusCode(400);
    }

    // ── Combined filters ─────────────────────────────────────────────

    @Test
    void testCombinedFilters() {
        given()
            .queryParam("filterEntryType", "task-completed")
            .queryParam("filterProjectId", 1)
            .when()
                .get(ACTIVITY_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", greaterThanOrEqualTo(1))
                .body("items.entryType", everyItem(equalTo("task-completed")))
                .body("items.projectId", everyItem(equalTo(1)));
    }

    @Test
    void testFilterNoResults() {
        given()
            .queryParam("filterSummary", "zzz_nonexistent_summary_zzz")
            .when()
                .get(ACTIVITY_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", equalTo(0))
                .body("totalCount", equalTo(0));
    }

    @Test
    void testFilterWithPagination() {
        given()
            .queryParam("filterSummary", "FILTER-TEST")
            .queryParam("page", 1)
            .queryParam("limit", 2)
            .when()
                .get(ACTIVITY_PATH)
            .then()
                .statusCode(200)
                .body("items.size()", equalTo(2))
                .body("totalCount", greaterThanOrEqualTo(5));
    }
}
