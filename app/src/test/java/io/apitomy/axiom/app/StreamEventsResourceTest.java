package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

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

    /**
     * Every non-skipped ledger entry counts as a matched subscription, regardless of routing completion,
     * and the per-state breakdown is reported for each listed event.
     */
    @Test
    void countsEveryMatchedSubscriptionAndReportsProcessingStates() {
        UUID mixed = event("{\"login\":\"match-mixed\"}");
        UUID skippedOnly = event("{\"login\":\"match-skipped\"}");
        UUID none = event("{\"login\":\"match-none\"}");
        UUID completedOnly = event("{\"login\":\"match-completed\"}");
        ledger(mixed, 9001L, "completed");
        ledger(mixed, 9002L, "pending");
        ledger(mixed, 9003L, "failed");
        ledger(mixed, 9004L, "exhausted");
        ledger(mixed, 9005L, "skipped");
        ledger(skippedOnly, 9001L, "skipped");
        // A completed subscription without routes is still a match.
        ledger(completedOnly, 9006L, "completed");
        try {
            String base = "items.find { it.id == '%s' }.";
            given().queryParam("filterEventSourceId", "conn-actor").queryParam("limit", 100)
                    .get("/api/v1/stream/events").then().statusCode(200)
                    .body(String.format(base, mixed) + "matchedSubscriptions", is(4))
                    .body(String.format(base, mixed) + "processingStates.completed", is(1))
                    .body(String.format(base, mixed) + "processingStates.pending", is(1))
                    .body(String.format(base, mixed) + "processingStates.failed", is(1))
                    .body(String.format(base, mixed) + "processingStates.exhausted", is(1))
                    .body(String.format(base, mixed) + "processingStates.skipped", is(1))
                    .body(String.format(base, skippedOnly) + "matchedSubscriptions", is(0))
                    .body(String.format(base, skippedOnly) + "processingStates.skipped", is(1))
                    .body(String.format(base, none) + "matchedSubscriptions", is(0))
                    .body(String.format(base, none) + "processingStates", nullValue())
                    .body(String.format(base, completedOnly) + "matchedSubscriptions", is(1))
                    .body(String.format(base, completedOnly) + "processingStates.completed", is(1));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                List<UUID> ids = List.of(mixed, skippedOnly, none, completedOnly);
                EventProcessingLedgerEntity.delete("eventId in ?1", ids);
                StreamEventEntity.delete("id in ?1", ids);
            });
        }
    }

    private static void ledger(UUID eventId, long subscriptionId, String status) {
        QuarkusTransaction.requiringNew().run(() -> {
            EventProcessingLedgerEntity e = new EventProcessingLedgerEntity();
            e.eventId = eventId;
            e.subscriptionId = subscriptionId;
            e.status = status;
            e.createdOn = Instant.now();
            e.persist();
        });
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
