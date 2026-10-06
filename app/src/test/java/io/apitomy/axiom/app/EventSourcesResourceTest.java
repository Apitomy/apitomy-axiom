package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for the Event Sources REST API endpoints.
 */
@QuarkusTest
class EventSourcesResourceTest {

    private static final String EVENT_SOURCES_PATH = "/api/v1/event-sources";

    @Test
    void testCreateAndGetEventSource() {
        String slug = "test-github-conn";

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "id": "%s",
                    "name": "Test GitHub Event Source",
                    "description": "A test event source",
                    "sourceType": "github",
                    "enabled": true,
                    "baseUrl": "https://api.github.com",
                    "secretName": "GH_TOKEN",
                    "pollInterval": 300,
                    "configuration": { "repositories": ["owner/repo1"] }
                }
                """.formatted(slug))
            .when()
                .post(EVENT_SOURCES_PATH)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("id", equalTo(slug))
                .body("name", equalTo("Test GitHub Event Source"))
                .body("description", equalTo("A test event source"))
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
                .get(EVENT_SOURCES_PATH + "/" + slug)
            .then()
                .statusCode(200)
                .body("id", equalTo(slug))
                .body("name", equalTo("Test GitHub Event Source"));
    }

    @Test
    void testListEventSources() {
        createEventSource("list-conn-1");
        createEventSource("list-conn-2");

        given()
            .when()
                .get(EVENT_SOURCES_PATH)
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("items.size()", greaterThanOrEqualTo(2))
                .body("totalCount", greaterThanOrEqualTo(2))
                .body("page", equalTo(1));
    }

    @Test
    void testUpdateEventSource() {
        String slug = "update-conn";
        createEventSource(slug);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "id": "%s",
                    "name": "Updated Event Source",
                    "description": "Updated description",
                    "sourceType": "jira",
                    "enabled": false,
                    "baseUrl": "https://myorg.atlassian.net",
                    "pollInterval": 600,
                    "configuration": { "subjects": ["PROJ1"] }
                }
                """.formatted(slug))
            .when()
                .put(EVENT_SOURCES_PATH + "/" + slug)
            .then()
                .statusCode(200)
                .body("name", equalTo("Updated Event Source"))
                .body("description", equalTo("Updated description"))
                .body("sourceType", equalTo("jira"))
                .body("enabled", equalTo(false))
                .body("baseUrl", equalTo("https://myorg.atlassian.net"));
    }

    @Test
    void testDeleteEventSource() {
        String slug = "delete-conn";
        createEventSource(slug);

        given()
            .when()
                .delete(EVENT_SOURCES_PATH + "/" + slug)
            .then()
                .statusCode(204);

        given()
            .when()
                .get(EVENT_SOURCES_PATH + "/" + slug)
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
                    "name": "Bad Event Source",
                    "sourceType": "github",
                    "baseUrl": "https://api.github.com",
                    "configuration": {}
                }
                """)
            .when()
                .post(EVENT_SOURCES_PATH)
            .then()
                .statusCode(400);
    }

    @Test
    void testGetEventSourceNotFound() {
        given()
            .when()
                .get(EVENT_SOURCES_PATH + "/nonexistent-slug")
            .then()
                .statusCode(404);
    }

    @Test
    void testGetEventSourceStatus() {
        String slug = "status-conn";
        createEventSource(slug);

        given()
            .when()
                .get(EVENT_SOURCES_PATH + "/" + slug + "/status")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("eventSourceId", equalTo(slug))
                .body("totalEventsProduced", equalTo(0));
    }

    /**
     * The old Connections endpoint was renamed to Event Sources and must no longer exist.
     */
    @Test
    void oldConnectionsPathIsGone() {
        given().when().get("/api/v1/connections").then().statusCode(404);
    }

    /**
     * The Event Sources list endpoint responds.
     */
    @Test
    void eventSourcesPathResponds() {
        given().when().get(EVENT_SOURCES_PATH).then().statusCode(200);
    }

    private void createEventSource(String slug) {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "id": "%s",
                    "name": "Event Source %s",
                    "sourceType": "github",
                    "enabled": true,
                    "baseUrl": "https://api.github.com",
                    "configuration": { "repositories": ["owner/repo"] }
                }
                """.formatted(slug, slug))
            .when()
                .post(EVENT_SOURCES_PATH)
            .then()
                .statusCode(200);
    }
}
