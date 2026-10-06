package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SdkResourceTest {

    private static final String SDK_PATH = "/api/v1/sdk/functions";

    @Inject
    SdkCallService sdkCallService;

    @Test
    void testListAllFunctions() {
        given()
            .when()
                .get(SDK_PATH)
            .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(15))
                .body("name", hasItems(
                    "axiom_close_project",
                    "axiom_fire_event",
                    "axiom_list_projects",
                    "axiom_get_project",
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

    /**
     * Reports were removed: their SDK functions are no longer listed, and calling one is
     * handled like any other unknown function. The SDK resource only lists functions, so the
     * call is made through {@link SdkCallService}, which backs workflow {@code sdk:} calls.
     */
    @Test
    void removedReportFunctionsAreUnknown() {
        given()
            .when()
                .get(SDK_PATH)
            .then()
                .statusCode(200)
                .body("name", not(hasItems("axiom_add_report_label")))
                .body("name", not(hasItems("axiom_remove_report_label")))
                .body("name", not(hasItems("axiom_list_report_definitions")))
                .body("name", not(hasItems("axiom_list_reports")));

        SdkCallService.SdkCallResult result = sdkCallService.execute("axiom_list_reports", Map.of());
        assertFalse(result.success());
        assertTrue(result.output().startsWith("Unknown SDK function"), result.output());
    }

    /**
     * Action types run only from workflow action nodes, so the SDK no longer offers task creation.
     */
    @Test
    void createTaskFunctionIsUnknown() {
        given()
            .when()
                .get(SDK_PATH)
            .then()
                .statusCode(200)
                .body("name", not(hasItems("axiom_create_task")));

        SdkCallService.SdkCallResult result = sdkCallService.execute("axiom_create_task", Map.of());
        assertFalse(result.success());
        assertTrue(result.output().startsWith("Unknown SDK function"), result.output());
    }
}
