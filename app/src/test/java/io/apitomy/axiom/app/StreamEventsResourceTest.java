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
import static org.hamcrest.Matchers.notNullValue;
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
     * Every ledger entry except skipped and filter-error counts as a matched subscription, regardless of
     * routing completion, and the per-state breakdown is reported.
     */
    @Test
    void mixedEventCountsMatchesAndReportsEveryState() {
        String source = uniqueSource();
        UUID mixed = event(source, "{\"login\":\"match-mixed\"}");
        ledger(mixed, 9001L, EventProcessingLedgerEntity.COMPLETED);
        ledger(mixed, 9002L, EventProcessingLedgerEntity.PENDING);
        ledger(mixed, 9003L, EventProcessingLedgerEntity.FAILED);
        ledger(mixed, 9004L, EventProcessingLedgerEntity.EXHAUSTED);
        ledger(mixed, 9005L, EventProcessingLedgerEntity.SKIPPED);
        ledger(mixed, 9006L, EventProcessingLedgerEntity.FILTER_ERROR);
        try {
            given().queryParam("filterEventSourceId", source)
                    .get("/api/v1/stream/events").then().statusCode(200)
                    .body("items[0].matchedSubscriptions", is(4))
                    .body("items[0].processingStates.completed", is(1))
                    .body("items[0].processingStates.pending", is(1))
                    .body("items[0].processingStates.failed", is(1))
                    .body("items[0].processingStates.exhausted", is(1))
                    .body("items[0].processingStates.skipped", is(1))
                    .body("items[0].processingStates.filterError", is(1));
        } finally {
            cleanup(mixed);
        }
    }

    /** An event whose only ledger entry is skipped has no matches but reports its states. */
    @Test
    void skippedOnlyEventHasNoMatches() {
        String source = uniqueSource();
        UUID id = event(source, "{\"login\":\"match-skipped\"}");
        ledger(id, 9001L, EventProcessingLedgerEntity.SKIPPED);
        try {
            given().queryParam("filterEventSourceId", source)
                    .get("/api/v1/stream/events").then().statusCode(200)
                    .body("items[0].matchedSubscriptions", is(0))
                    .body("items[0].processingStates.skipped", is(1))
                    .body("items[0].processingStates.filterError", is(0));
        } finally {
            cleanup(id);
        }
    }

    /** An event whose only ledger entry is a filter error has no matches but reports the error. */
    @Test
    void filterErrorOnlyEventHasNoMatchesButReportsTheError() {
        String source = uniqueSource();
        UUID id = event(source, "{\"login\":\"match-filter-error\"}");
        ledger(id, 9001L, EventProcessingLedgerEntity.FILTER_ERROR);
        try {
            given().queryParam("filterEventSourceId", source)
                    .get("/api/v1/stream/events").then().statusCode(200)
                    .body("items[0].matchedSubscriptions", is(0))
                    .body("items[0].processingStates", notNullValue())
                    .body("items[0].processingStates.filterError", is(1))
                    .body("items[0].processingStates.completed", is(0));
        } finally {
            cleanup(id);
        }
    }

    /** An event without ledger entries has no matches and no processing states. */
    @Test
    void eventWithoutLedgerEntriesHasNoProcessingStates() {
        String source = uniqueSource();
        UUID id = event(source, "{\"login\":\"match-none\"}");
        try {
            given().queryParam("filterEventSourceId", source)
                    .get("/api/v1/stream/events").then().statusCode(200)
                    .body("items[0].matchedSubscriptions", is(0))
                    .body("items[0].processingStates", nullValue());
        } finally {
            cleanup(id);
        }
    }

    /** A completed subscription without routes is still a match. */
    @Test
    void completedOnlyEventIsAMatch() {
        String source = uniqueSource();
        UUID id = event(source, "{\"login\":\"match-completed\"}");
        ledger(id, 9006L, EventProcessingLedgerEntity.COMPLETED);
        try {
            given().queryParam("filterEventSourceId", source)
                    .get("/api/v1/stream/events").then().statusCode(200)
                    .body("items[0].matchedSubscriptions", is(1))
                    .body("items[0].processingStates.completed", is(1));
        } finally {
            cleanup(id);
        }
    }

    /** Page sizes above the maximum are clamped. */
    @Test
    void pageSizeIsClamped() {
        given().queryParam("limit", 100000)
                .get("/api/v1/stream/events").then().statusCode(200)
                .body("limit", is(500));
    }

    private static String uniqueSource() {
        return "conn-match-" + UUID.randomUUID();
    }

    private static void cleanup(UUID id) {
        QuarkusTransaction.requiringNew().run(() -> {
            EventProcessingLedgerEntity.delete("eventId", id);
            StreamEventEntity.delete("id", id);
        });
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
        return event("conn-actor", actorJson);
    }

    private static UUID event(String eventSourceId, String actorJson) {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity e = new StreamEventEntity();
            e.id = id;
            e.sourceEventId = "actor-" + id;
            e.source = "github";
            e.eventSourceId = eventSourceId;
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
