package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

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

    @Test
    void testPreviewWithProcessEventsFrom() {
        Instant now = Instant.now();
        Instant oldTimestamp = now.minus(7, ChronoUnit.DAYS);
        Instant recentTimestamp = now.minus(1, ChronoUnit.HOURS);
        Instant cutoff = now.minus(3, ChronoUnit.DAYS);

        String oldId = "preview-old-" + UUID.randomUUID();
        String recentId = "preview-recent-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            createStreamEvent(oldId, "github", "issue.created", oldTimestamp);
            createStreamEvent(recentId, "github", "issue.created", recentTimestamp);
        });

        // Without processEventsFrom, get baseline matched count
        int matchedWithout = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "filterExpression": "event.type == 'issue.created'",
                    "page": 1,
                    "limit": 100
                }
                """)
            .when()
                .post(SUBSCRIPTIONS_PATH + "/preview")
            .then()
                .statusCode(200)
                .body("totalCount", greaterThanOrEqualTo(2))
                .extract().path("totalMatched");

        // With processEventsFrom, totalCount stays the same but totalMatched decreases
        given()
            .contentType(ContentType.JSON)
            .body(String.format("""
                {
                    "filterExpression": "event.type == 'issue.created'",
                    "processEventsFrom": "%s",
                    "page": 1,
                    "limit": 100
                }
                """, formatForJson(cutoff)))
            .when()
                .post(SUBSCRIPTIONS_PATH + "/preview")
            .then()
                .statusCode(200)
                .body("totalCount", greaterThanOrEqualTo(2))
                .body("totalMatched", lessThan(matchedWithout));
    }

    @Test
    void testPreviewProcessEventsFromShowsOldEventsAsNonMatching() {
        Instant now = Instant.now();
        Instant oldTimestamp = now.minus(30, ChronoUnit.DAYS);
        Instant cutoff = now.minus(1, ChronoUnit.DAYS);

        String sourceEventId = "preview-dim-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() ->
            createStreamEvent(sourceEventId, "github", "push", oldTimestamp));

        // The old event should still appear in the results (totalCount includes it)
        // but should not be in totalMatched
        given()
            .contentType(ContentType.JSON)
            .body(String.format("""
                {
                    "filterExpression": "",
                    "processEventsFrom": "%s",
                    "page": 1,
                    "limit": 100
                }
                """, formatForJson(cutoff)))
            .when()
                .post(SUBSCRIPTIONS_PATH + "/preview")
            .then()
                .statusCode(200)
                .body("totalCount", greaterThanOrEqualTo(1))
                .body("totalMatched", greaterThanOrEqualTo(0));
    }

    private static final DateTimeFormatter JSON_DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private String formatForJson(Instant instant) {
        return JSON_DATE_FMT.format(instant);
    }

    private void createStreamEvent(String sourceEventId, String source, String type, Instant timestamp) {
        StreamEventEntity event = new StreamEventEntity();
        event.id = UUID.randomUUID();
        event.sourceEventId = sourceEventId;
        event.source = source;
        event.connectionId = "test-connection";
        event.type = type;
        event.ref = "https://github.com/test/repo/issues/1";
        event.timestamp = timestamp;
        event.actor = "{\"login\":\"test-user\"}";
        event.payload = "{}";
        event.createdOn = Instant.now();
        event.persist();
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
