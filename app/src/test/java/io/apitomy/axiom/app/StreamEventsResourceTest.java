package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
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

    @Test
    void filtersByActorLoginExactlyIgnoringCase() {
        UUID a = event("{\"login\":\"jsenko\",\"displayName\":\"J Senko\"}");
        UUID b = event("{\"login\":\"jsenkox\"}");
        UUID c = event("{\"login\":\"eric_w\"}");
        try {
            given().queryParam("filterActor", "JSenko").get("/api/v1/stream/events").then().statusCode(200)
                    .body("items.id", contains(a.toString()));
            given().queryParam("filterActor", "eric_w").get("/api/v1/stream/events").then().statusCode(200)
                    .body("items.id", contains(c.toString()));
            // "_" is a literal, not a LIKE wildcard.
            given().queryParam("filterActor", "ericxw").get("/api/v1/stream/events").then().statusCode(200)
                    .body("items", is(empty()));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> StreamEventEntity.delete("id in ?1", java.util.List.of(a, b, c)));
        }
    }

    private static UUID event(String actorJson) {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity e = new StreamEventEntity();
            e.id = id;
            e.sourceEventId = "actor-" + id;
            e.source = "github";
            e.eventSourceId = "conn-actor";
            e.type = "issue.created";
            e.ref = "https://github.com/o/r/issues/1";
            e.timestamp = Instant.now();
            e.actor = actorJson;
            e.payload = "{}";
            e.createdOn = Instant.now();
            e.persist();
        });
        return id;
    }
}
