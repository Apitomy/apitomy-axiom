package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for the System REST API endpoints.
 */
@QuarkusTest
class SystemResourceTest {

    @Test
    void testGetSystemHealth() {
        given()
            .when()
                .get("/api/v1/system/health")
            .then()
                .statusCode(200)
                .contentType("application/json")
                .body("status", equalTo("UP"))
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
                .body("engine", equalTo("opencode"));

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
    void testHealthStatusIsUp() {
        given()
            .when()
                .get("/api/v1/system/health")
            .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
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
                .body("scheduledJobRunRetentionDays", equalTo(0))
                .body("reportRetentionDays", equalTo(0))
                .body("workflowRunRetentionDays", equalTo(0))
                .body("activityLogRetentionDays", equalTo(0))
                .body("aiUsageRetentionDays", equalTo(0))
                .body("$", not(hasKey("eventSourceLogRetentionDays")));
    }

    @Test
    void testUpdateRetentionConfigRoundTripsAndKeepsOmittedSettings() {
        String original = given().when().get("/api/v1/system/retention")
                .then().statusCode(200).extract().asString();
        try {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {"closedProjectRetentionDays":90,"traceRetentionDays":30,
                             "eventRetentionDays":90,"scheduledJobRunRetentionDays":11,
                             "reportRetentionDays":12,"workflowRunRetentionDays":13,
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
                            {"closedProjectRetentionDays":90,"traceRetentionDays":30,
                             "eventRetentionDays":90}
                            """)
                .when()
                    .put("/api/v1/system/retention")
                .then()
                    .statusCode(200)
                    .body("scheduledJobRunRetentionDays", equalTo(11))
                    .body("aiUsageRetentionDays", equalTo(15));

            given()
                .when()
                    .get("/api/v1/system/retention")
                .then()
                    .statusCode(200)
                    .body("reportRetentionDays", equalTo(12))
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
                        {"closedProjectRetentionDays":90,"traceRetentionDays":30,
                         "eventRetentionDays":90,"reportRetentionDays":-1}
                        """)
            .when()
                .put("/api/v1/system/retention")
            .then()
                .statusCode(400);
    }

    @Test
    void testUpdateRetentionConfigRejectsCoreSettingsBelowOne() {
        for (String field : new String[] {"closedProjectRetentionDays", "traceRetentionDays",
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
                            {"closedProjectRetentionDays":77,"traceRetentionDays":22,
                             "eventRetentionDays":66}
                            """)
                .when()
                    .put("/api/v1/system/retention")
                .then()
                    .statusCode(200);

            given()
                    .contentType(ContentType.JSON)
                    .body("{\"reportRetentionDays\":3}")
                .when()
                    .put("/api/v1/system/retention")
                .then()
                    .statusCode(200)
                    .body("closedProjectRetentionDays", equalTo(77))
                    .body("traceRetentionDays", equalTo(22))
                    .body("eventRetentionDays", equalTo(66))
                    .body("reportRetentionDays", equalTo(3));
        } finally {
            given().contentType(ContentType.JSON).body(original)
                    .when().put("/api/v1/system/retention")
                    .then().statusCode(200);
        }
    }
}
