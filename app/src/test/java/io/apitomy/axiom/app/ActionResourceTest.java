package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for the Action Types REST API endpoints.
 */
@QuarkusTest
class ActionResourceTest {

    private static final String ACTION_TYPES_PATH = "/api/v1/action-types";

    @Test
    void testSeedDataLoaded() {
        given()
            .when()
                .get(ACTION_TYPES_PATH)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("items.size()", greaterThanOrEqualTo(2))
                .body("items.name", hasItems("auto-tag", "close-project"));
    }

    @Test
    void testGetSeedActionType() {
        int id = given()
            .when()
                .get(ACTION_TYPES_PATH)
            .then()
                .statusCode(200)
                .extract().path("items.find { it.name == 'auto-tag' }.id");

        given()
            .when()
                .get(ACTION_TYPES_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("name", equalTo("auto-tag"))
                .body("executionMode", equalTo("agent"))
                .body("userTriggerable", equalTo(false))
                .body("emitsEvent", equalTo(true));
    }

    @Test
    void testScriptActionType() {
        int id = given()
            .when()
                .get(ACTION_TYPES_PATH)
            .then()
                .statusCode(200)
                .extract().path("items.find { it.name == 'close-project' }.id");

        given()
            .when()
                .get(ACTION_TYPES_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("name", equalTo("close-project"))
                .body("executionMode", equalTo("script"))
                .body("userTriggerable", equalTo(true))
                .body("emitsEvent", equalTo(false));
    }

    @Test
    void testCreateCustomActionType() {
        int id = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "custom-action",
                    "description": "A custom action for testing",
                    "executionMode": "agent",
                    "promptTemplate": "Do the thing: {{input}}",
                    "userTriggerable": true,
                    "emitsEvent": false
                }
                """)
            .when()
                .post(ACTION_TYPES_PATH)
            .then()
                .statusCode(200)
                .body("name", equalTo("custom-action"))
                .body("executionMode", equalTo("agent"))
                .body("userTriggerable", equalTo(true))
                .body("emitsEvent", equalTo(false))
                .extract().path("id");

        given()
            .when()
                .get(ACTION_TYPES_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("name", equalTo("custom-action"));
    }

    @Test
    void testCreateIncompleteActionTypeRejected() {
        // An agent-mode action type without a prompt template is a validation error
        // and must be rejected with 422. The Create Action Type modal avoids this by
        // sending default templates for any required field the form omits.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "incomplete-action",
                    "executionMode": "agent"
                }
                """)
            .when()
                .post(ACTION_TYPES_PATH)
            .then()
                .statusCode(422)
                .body("errors.field", hasItem("promptTemplate"));
    }

    @Test
    void testUpdateIncompleteActionTypeRejected() {
        int id = createActionType("update-validation-action");

        // Updating an agent-mode action type without a prompt template is a
        // validation error and must be rejected with 422.
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "update-validation-action",
                    "executionMode": "agent"
                }
                """)
            .when()
                .put(ACTION_TYPES_PATH + "/" + id)
            .then()
                .statusCode(422)
                .body("errors.field", hasItem("promptTemplate"));
    }

    @Test
    void testUpdateActionType() {
        int id = createActionType("update-test-action");

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "updated-action",
                    "description": "Updated description",
                    "executionMode": "script",
                    "scriptTemplate": "echo updated",
                    "emitsEvent": true
                }
                """)
            .when()
                .put(ACTION_TYPES_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("name", equalTo("updated-action"))
                .body("description", equalTo("Updated description"))
                .body("executionMode", equalTo("script"))
                .body("emitsEvent", equalTo(true));
    }

    @Test
    void testDeleteActionType() {
        int id = createActionType("delete-test-action");

        given()
            .when()
                .delete(ACTION_TYPES_PATH + "/" + id)
            .then()
                .statusCode(204);

        given()
            .when()
                .get(ACTION_TYPES_PATH + "/" + id)
            .then()
                .statusCode(404);
    }

    @Test
    void testGetActionTypeNotFound() {
        given()
            .when()
                .get(ACTION_TYPES_PATH + "/999999")
            .then()
                .statusCode(404);
    }

    private int createActionType(String name) {
        return given()
            .contentType(ContentType.JSON)
            .body(String.format("""
                {
                    "name": "%s",
                    "executionMode": "agent",
                    "promptTemplate": "Do: {{input}}"
                }
                """, name))
            .when()
                .post(ACTION_TYPES_PATH)
            .then()
                .statusCode(200)
                .extract().path("id");
    }
}
