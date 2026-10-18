package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for the System REST API endpoints.
 */
@QuarkusTest
class SystemResourceTest {

    /**
     * Reports were removed; the endpoint must no longer exist.
     */
    @Test
    void reportsEndpointIsGone() {
        given().when().get("/api/v1/reports").then().statusCode(404);
    }

    @Test
    void scheduledJobsEndpointIsGone() {
        given().when().get("/api/v1/scheduled-jobs").then().statusCode(404);
    }

    @Test
    void testGetSystemHealth() {
        given()
            .when()
                .get("/api/v1/system/health")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("status", notNullValue())
                .body("checks.find { it.name == 'database' }.status", equalTo("UP"))
                .body("version", notNullValue())
                .body("timestamp", notNullValue());
    }

    @Test
    void testGetSystemConfig() {
        given()
            .when()
                .get("/api/v1/system/config")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("version", notNullValue())
                .body("features", notNullValue());
    }

    @Test
    void testSystemConfigIncludesModelSourcePerEngine() {
        given()
            .when()
                .get("/api/v1/system/config")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("engines.size()", greaterThan(0))
                .body("engines.modelSource", everyItem(notNullValue()));
    }

    @Test
    void testUpdateSystemConfigDefaultEngine() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"defaultEngine":"opencode"}
                        """)
            .when()
                .put("/api/v1/system/config")
            .then()
                .statusCode(200)
                .body("defaultEngine", equalTo("opencode"));

        given()
            .when()
                .get("/api/v1/system/config")
            .then()
                .statusCode(200)
                .body("defaultEngine", equalTo("opencode"))
                .body("$", not(hasKey("engine")));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"defaultEngine":"claude-code"}
                        """)
            .when()
                .put("/api/v1/system/config")
            .then()
                .statusCode(200)
                .body("defaultEngine", equalTo("claude-code"));
    }

    @Test
    void testHealthStatusIsWorstCheck() {
        // Overall status is the worst check; the test environment may legitimately be DEGRADED
        // (e.g. engine CLIs missing), so assert consistency rather than a fixed value.
        io.restassured.path.json.JsonPath body = given()
            .when()
                .get("/api/v1/system/health")
            .then()
                .statusCode(200)
                .extract().jsonPath();
        java.util.List<String> statuses = body.getList("checks.status");
        String expected = statuses.contains("DOWN") ? "DOWN"
                : statuses.contains("DEGRADED") ? "DEGRADED" : "UP";
        org.junit.jupiter.api.Assertions.assertEquals(expected, body.getString("status"));
    }

    @Test
    void testConfigVersionMatchesHealthVersion() {
        String healthVersion = given()
            .when()
                .get("/api/v1/system/health")
            .then()
                .statusCode(200)
                .extract()
                .path("version");

        given()
            .when()
                .get("/api/v1/system/config")
            .then()
                .statusCode(200)
                .body("version", equalTo(healthVersion));
    }

    @Test
    void testExportPackMissingNameReturnsBadRequest() {
        given()
                .contentType(ContentType.JSON)
                .body("{}")
            .when()
                .post("/api/v1/system/packs/export")
            .then()
                .statusCode(400);
    }

    @Test
    void testRetentionConfigIncludesHistorySettings() {
        given()
            .when()
                .get("/api/v1/system/retention")
            .then()
                .statusCode(200)
                .body("traceRetentionDays", notNullValue())
                .body("workflowRunRetentionDays", equalTo(0))
                .body("activityLogRetentionDays", equalTo(0))
                .body("aiUsageRetentionDays", equalTo(0))
                .body("$", not(hasKey("eventSourceLogRetentionDays")))
                .body("$", not(hasKey("reportRetentionDays")))
                .body("$", not(hasKey("scheduledJobRunRetentionDays")));
    }

    @Test
    void testUpdateRetentionConfigRoundTripsAndKeepsOmittedSettings() {
        String original = given().when().get("/api/v1/system/retention")
                .then().statusCode(200).extract().asString();
        try {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {"inactiveSubjectRetentionDays":90,"traceRetentionDays":30,
                             "eventRetentionDays":90,"workflowRunRetentionDays":13,
                             "activityLogRetentionDays":14,"aiUsageRetentionDays":15}
                            """)
                .when()
                    .put("/api/v1/system/retention")
                .then()
                    .statusCode(200)
                    .body("workflowRunRetentionDays", equalTo(13));

            // A client that does not know the new settings must not reset them.
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {"inactiveSubjectRetentionDays":90,"traceRetentionDays":30,
                             "eventRetentionDays":90}
                            """)
                .when()
                    .put("/api/v1/system/retention")
                .then()
                    .statusCode(200)
                    .body("workflowRunRetentionDays", equalTo(13))
                    .body("aiUsageRetentionDays", equalTo(15));

            given()
                .when()
                    .get("/api/v1/system/retention")
                .then()
                    .statusCode(200)
                    .body("workflowRunRetentionDays", equalTo(13))
                    .body("activityLogRetentionDays", equalTo(14));
        } finally {
            given().contentType(ContentType.JSON).body(original)
                    .when().put("/api/v1/system/retention")
                    .then().statusCode(200);
        }
    }

    @Test
    void testUpdateRetentionConfigRejectsNegativeDays() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"inactiveSubjectRetentionDays":90,"traceRetentionDays":30,
                         "eventRetentionDays":90,"workflowRunRetentionDays":-1}
                        """)
            .when()
                .put("/api/v1/system/retention")
            .then()
                .statusCode(400);
    }

    @Test
    void testUpdateRetentionConfigRejectsCoreSettingsBelowOne() {
        for (String field : new String[] {"inactiveSubjectRetentionDays", "traceRetentionDays",
                "eventRetentionDays"}) {
            for (int value : new int[] {0, -5}) {
                given()
                        .contentType(ContentType.JSON)
                        .body("{\"" + field + "\":" + value + "}")
                    .when()
                        .put("/api/v1/system/retention")
                    .then()
                        .statusCode(400);
            }
        }
    }

    @Test
    void testUpdateRetentionConfigKeepsOmittedCoreSettings() {
        String original = given().when().get("/api/v1/system/retention")
                .then().statusCode(200).extract().asString();
        try {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {"inactiveSubjectRetentionDays":77,"traceRetentionDays":22,
                             "eventRetentionDays":66}
                            """)
                .when()
                    .put("/api/v1/system/retention")
                .then()
                    .statusCode(200);

            given()
                    .contentType(ContentType.JSON)
                    .body("{\"workflowRunRetentionDays\":3}")
                .when()
                    .put("/api/v1/system/retention")
                .then()
                    .statusCode(200)
                    .body("inactiveSubjectRetentionDays", equalTo(77))
                    .body("traceRetentionDays", equalTo(22))
                    .body("eventRetentionDays", equalTo(66))
                    .body("workflowRunRetentionDays", equalTo(3));
        } finally {
            given().contentType(ContentType.JSON).body(original)
                    .when().put("/api/v1/system/retention")
                    .then().statusCode(200);
        }
    }

    /** Importing a pack with an invalid item returns 400 with the problems keyed by item. */
    @Test
    void importInvalidPackReturnsProblems() {
        String name = "bad-wf-" + UUID.randomUUID().toString().substring(0, 8);
        given()
                .contentType(ContentType.JSON)
                .body("{\"workflowDefinitions\": [{\"name\": \"" + name + "\"}]}")
            .when()
                .post("/api/v1/system/packs/import")
            .then()
                .statusCode(400)
                .body("message", equalTo("Configuration pack has invalid items"))
                .body("problems.'workflow/" + name + "'", not(empty()))
                .body("problems.'workflow/" + name + "'[0].path", equalTo("content"));
    }
}
