package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class SdkResourceTest {

    private static final String SDK_PATH = "/api/v1/sdk/functions";

    @Test
    void testListAllFunctions() {
        given()
            .when()
                .get(SDK_PATH)
            .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(20))
                .body("name", hasItems(
                    "axiom_close_project",
                    "axiom_fire_event",
                    "axiom_list_projects",
                    "axiom_get_project",
                    "axiom_create_task",
                    "axiom_list_agents"))
                .body("[0].name", notNullValue())
                .body("[0].description", notNullValue())
                .body("[0].parameters", notNullValue());
    }

    @Test
    void testListSdkCallOnlyFunctions() {
        given()
            .queryParam("sdkCallOnly", true)
            .when()
                .get(SDK_PATH)
            .then()
                .statusCode(200)
                .body("size()", greaterThan(0))
                .body("sdkCallSupported", everyItem(equalTo(true)))
                .body("name", hasItems(
                    "axiom_close_project",
                    "axiom_fire_event",
                    "axiom_create_project"));
    }

    @Test
    void testSdkCallOnlyExcludesNonSdkCallFunctions() {
        given()
            .queryParam("sdkCallOnly", true)
            .when()
                .get(SDK_PATH)
            .then()
                .statusCode(200)
                .body("name", not(hasItems(
                    "axiom_list_tools",
                    "axiom_list_report_definitions",
                    "axiom_get_activity_log")));
    }

    @Test
    void testFunctionHasTypedParameters() {
        given()
            .when()
                .get(SDK_PATH)
            .then()
                .statusCode(200)
                .body("find { it.name == 'axiom_close_project' }.parameters.size()",
                    equalTo(1))
                .body("find { it.name == 'axiom_close_project' }.parameters[0].name",
                    equalTo("projectId"))
                .body("find { it.name == 'axiom_close_project' }.parameters[0].type",
                    equalTo("number"))
                .body("find { it.name == 'axiom_close_project' }.parameters[0].required",
                    equalTo(true));
    }
}
