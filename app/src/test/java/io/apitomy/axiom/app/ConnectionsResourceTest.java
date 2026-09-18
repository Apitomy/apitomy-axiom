package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for the Connections REST API endpoints.
 */
@QuarkusTest
class ConnectionsResourceTest {

    private static final String CONNECTIONS_PATH = "/api/v1/connections";

    @Test
    void testCreateAndGetConnection() {
        String slug = "test-github-conn";

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "id": "%s",
                    "name": "Test GitHub Connection",
                    "description": "A test connection",
                    "sourceType": "github",
                    "enabled": true,
                    "baseUrl": "https://api.github.com",
                    "secretName": "GH_TOKEN",
                    "pollInterval": 300,
                    "configuration": { "repositories": ["owner/repo1"] }
                }
                """.formatted(slug))
            .when()
                .post(CONNECTIONS_PATH)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("id", equalTo(slug))
                .body("name", equalTo("Test GitHub Connection"))
                .body("description", equalTo("A test connection"))
                .body("sourceType", equalTo("github"))
                .body("enabled", equalTo(true))
                .body("baseUrl", equalTo("https://api.github.com"))
                .body("secretName", equalTo("GH_TOKEN"))
                .body("pollInterval", equalTo(300))
                .body("configuration.repositories", hasItem("owner/repo1"))
                .body("createdOn", notNullValue())
                .body("modifiedOn", notNullValue());

        given()
            .when()
                .get(CONNECTIONS_PATH + "/" + slug)
            .then()
                .statusCode(200)
                .body("id", equalTo(slug))
                .body("name", equalTo("Test GitHub Connection"));
    }

    @Test
    void testListConnections() {
        createConnection("list-conn-1");
        createConnection("list-conn-2");

        given()
            .when()
                .get(CONNECTIONS_PATH)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("items.size()", greaterThanOrEqualTo(2))
                .body("total", greaterThanOrEqualTo(2))
                .body("page", equalTo(1));
    }

    @Test
    void testUpdateConnection() {
        String slug = "update-conn";
        createConnection(slug);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "id": "%s",
                    "name": "Updated Connection",
                    "description": "Updated description",
                    "sourceType": "jira",
                    "enabled": false,
                    "baseUrl": "https://myorg.atlassian.net",
                    "pollInterval": 600,
                    "configuration": { "projects": ["PROJ1"] }
                }
                """.formatted(slug))
            .when()
                .put(CONNECTIONS_PATH + "/" + slug)
            .then()
                .statusCode(200)
                .body("name", equalTo("Updated Connection"))
                .body("description", equalTo("Updated description"))
                .body("sourceType", equalTo("jira"))
                .body("enabled", equalTo(false))
                .body("baseUrl", equalTo("https://myorg.atlassian.net"));
    }

    @Test
    void testDeleteConnection() {
        String slug = "delete-conn";
        createConnection(slug);

        given()
            .when()
                .delete(CONNECTIONS_PATH + "/" + slug)
            .then()
                .statusCode(204);

        given()
            .when()
                .get(CONNECTIONS_PATH + "/" + slug)
            .then()
                .statusCode(404);
    }

    @Test
    void testCreateWithInvalidSlug() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "id": "INVALID_SLUG!",
                    "name": "Bad Connection",
                    "sourceType": "github",
                    "baseUrl": "https://api.github.com",
                    "configuration": {}
                }
                """)
            .when()
                .post(CONNECTIONS_PATH)
            .then()
                .statusCode(400);
    }

    @Test
    void testGetConnectionNotFound() {
        given()
            .when()
                .get(CONNECTIONS_PATH + "/nonexistent-slug")
            .then()
                .statusCode(404);
    }

    @Test
    void testGetConnectionStatus() {
        String slug = "status-conn";
        createConnection(slug);

        given()
            .when()
                .get(CONNECTIONS_PATH + "/" + slug + "/status")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("connectionId", equalTo(slug))
                .body("totalEventsProduced", equalTo(0));
    }

    private void createConnection(String slug) {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "id": "%s",
                    "name": "Connection %s",
                    "sourceType": "github",
                    "enabled": true,
                    "baseUrl": "https://api.github.com",
                    "configuration": { "repositories": ["owner/repo"] }
                }
                """.formatted(slug, slug))
            .when()
                .post(CONNECTIONS_PATH)
            .then()
                .statusCode(200);
    }
}
