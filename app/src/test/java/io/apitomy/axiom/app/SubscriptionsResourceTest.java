package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for the Subscriptions REST API endpoints.
 */
@QuarkusTest
class SubscriptionsResourceTest {

    private static final String SUBSCRIPTIONS_PATH = "/api/v1/subscriptions";

    @Test
    void testCrudLifecycle() {
        // Create a subscription
        int id = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "test-subscription",
                    "description": "A test subscription",
                    "enabled": true,
                    "labels": ["ci", "deploy"]
                }
                """)
            .when()
                .post(SUBSCRIPTIONS_PATH)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("name", equalTo("test-subscription"))
                .body("description", equalTo("A test subscription"))
                .body("enabled", equalTo(true))
                .body("labels", hasItems("ci", "deploy"))
                .body("createdOn", notNullValue())
                .body("modifiedOn", notNullValue())
                .extract().path("id");

        // Get the created subscription
        given()
            .when()
                .get(SUBSCRIPTIONS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("name", equalTo("test-subscription"))
                .body("description", equalTo("A test subscription"))
                .body("enabled", equalTo(true));

        // List subscriptions
        given()
            .when()
                .get(SUBSCRIPTIONS_PATH)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("items.size()", greaterThanOrEqualTo(1))
                .body("items.name", hasItem("test-subscription"));

        // Update the subscription
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "updated-subscription",
                    "description": "Updated description",
                    "enabled": false,
                    "labels": ["production"]
                }
                """)
            .when()
                .put(SUBSCRIPTIONS_PATH + "/" + id)
            .then()
                .statusCode(200)
                .body("name", equalTo("updated-subscription"))
                .body("description", equalTo("Updated description"))
                .body("enabled", equalTo(false))
                .body("labels", hasItem("production"));

        // Delete the subscription
        given()
            .when()
                .delete(SUBSCRIPTIONS_PATH + "/" + id)
            .then()
                .statusCode(204);

        // Verify deletion
        given()
            .when()
                .get(SUBSCRIPTIONS_PATH + "/" + id)
            .then()
                .statusCode(404);
    }

    @Test
    void testGetSubscriptionNotFound() {
        given()
            .when()
                .get(SUBSCRIPTIONS_PATH + "/999999")
            .then()
                .statusCode(404);
    }

    @Test
    void testListWithFilterName() {
        // Create a subscription with a distinctive name
        createSubscription("filterable-sub");

        given()
            .queryParam("filterName", "filterable")
            .when()
                .get(SUBSCRIPTIONS_PATH)
            .then()
                .statusCode(200)
                .body("items.name", hasItem("filterable-sub"));
    }

    private int createSubscription(String name) {
        return given()
            .contentType(ContentType.JSON)
            .body(String.format("""
                {
                    "name": "%s",
                    "description": "Test subscription",
                    "enabled": true
                }
                """, name))
            .when()
                .post(SUBSCRIPTIONS_PATH)
            .then()
                .statusCode(200)
                .extract().path("id");
    }
}
