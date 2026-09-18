package io.apitomy.axiom.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * Tests for the Stream Events REST API endpoint.
 */
@QuarkusTest
class StreamEventsResourceTest {

    @Test
    void testListStreamEvents() {
        // No pollers running, so the stream should be empty.
        given()
            .when()
                .get("/api/v1/stream/events")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("items", is(empty()));
    }

    @Test
    void testGetStreamEventNotFound() {
        given()
            .when()
                .get("/api/v1/stream/events/" + UUID.randomUUID())
            .then()
                .statusCode(404);
    }
}
