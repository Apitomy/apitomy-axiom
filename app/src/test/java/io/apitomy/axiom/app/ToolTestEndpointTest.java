package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Integration tests for the tool test endpoint ({@code POST /tools/{id}/test}).
 * Verifies that the endpoint respects a {@code scriptTemplate} override from
 * the request body and falls back to the saved definition when omitted.
 */
@QuarkusTest
class ToolTestEndpointTest {

    private static final String TOOLS_PATH = "/api/v1/tools";

    @Test
    void testToolUsesRequestScriptTemplate() {
        int toolId = given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "name": "test-override-tool",
                        "scriptTemplate": "echo saved"
                    }
                    """)
                .when()
                .post(TOOLS_PATH)
                .then()
                .statusCode(200)
                .extract().path("id");

        try {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                        {
                            "scriptTemplate": "echo override",
                            "parameters": {}
                        }
                        """)
                    .when()
                    .post(TOOLS_PATH + "/" + toolId + "/test")
                    .then()
                    .statusCode(200)
                    .body("success", is(true))
                    .body("output", containsString("override"))
                    .body("resolvedScript", equalTo("echo override"));
        } finally {
            given().when().delete(TOOLS_PATH + "/" + toolId).then().statusCode(204);
        }
    }

    @Test
    void testToolFallsBackToSavedScriptTemplate() {
        int toolId = given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "name": "test-fallback-tool",
                        "scriptTemplate": "echo saved"
                    }
                    """)
                .when()
                .post(TOOLS_PATH)
                .then()
                .statusCode(200)
                .extract().path("id");

        try {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                        {
                            "parameters": {}
                        }
                        """)
                    .when()
                    .post(TOOLS_PATH + "/" + toolId + "/test")
                    .then()
                    .statusCode(200)
                    .body("success", is(true))
                    .body("output", containsString("saved"))
                    .body("resolvedScript", equalTo("echo saved"));
        } finally {
            given().when().delete(TOOLS_PATH + "/" + toolId).then().statusCode(204);
        }
    }

    @Test
    void toolEnvironmentRoundTripsAndIsUsedByTestRuns() {
        int toolId = given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "name": "test-env-tool",
                        "scriptTemplate": "echo \\"own=$OWN extra=$EXTRA\\"",
                        "environment": {"OWN": "from-tool", "EXTRA": "saved"}
                    }
                    """)
                .when()
                .post(TOOLS_PATH)
                .then()
                .statusCode(200)
                .body("environment.OWN", equalTo("from-tool"))
                .extract().path("id");

        try {
            given().when().get(TOOLS_PATH + "/" + toolId).then()
                    .statusCode(200)
                    .body("environment.EXTRA", equalTo("saved"));

            given()
                    .contentType(ContentType.JSON)
                    .body("""
                        {
                            "parameters": {},
                            "environment": {"EXTRA": "from-request"}
                        }
                        """)
                    .when()
                    .post(TOOLS_PATH + "/" + toolId + "/test")
                    .then()
                    .statusCode(200)
                    .body("success", is(true))
                    .body("output", containsString("own=from-tool extra=from-request"));
        } finally {
            given().when().delete(TOOLS_PATH + "/" + toolId).then().statusCode(204);
        }
    }

    @Test
    void toolTestRunDoesNotInheritAxiomsEnvironment() {
        int toolId = given()
                .contentType(ContentType.JSON)
                .body("""
                    {
                        "name": "test-no-inherit-tool",
                        "scriptTemplate": "echo \\"server=${AXIOM_SERVER_ONLY_TEST_VAR:-none} path=${PATH:+set}\\""
                    }
                    """)
                .when()
                .post(TOOLS_PATH)
                .then()
                .statusCode(200)
                .extract().path("id");
        try {
            given()
                    .contentType(ContentType.JSON)
                    .body("{\"parameters\": {}}")
                    .when()
                    .post(TOOLS_PATH + "/" + toolId + "/test")
                    .then()
                    .statusCode(200)
                    .body("output", containsString("server=none path=set"));
        } finally {
            given().when().delete(TOOLS_PATH + "/" + toolId).then().statusCode(204);
        }
    }
}
