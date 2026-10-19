package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.entities.EventSourcePollLogEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the event operations endpoints used by dashboards: event source health and status, subscription
 * processing summary, processing failures and the event volume histogram.
 */
@QuarkusTest
class EventOperationsResourceTest {

    private static final Instant FAR_FUTURE = Instant.parse("2999-01-01T00:00:00Z");

    /** /event-sources/health reports every state, the error rule and the 24h event count. */
    @Test
    void eventSourceHealthStates() {
        Instant now = Instant.now();
        String never = createSource(true);
        String disabled = createSource(false);
        String baseline = createSource(true);
        addLog(baseline, EventSourcePollLogEntity.STATUS_SUCCESS, "Baseline set (first poll)", now);
        String healthy = createSource(true);
        addLog(healthy, EventSourcePollLogEntity.STATUS_ERROR, "old failure", now.minusSeconds(600));
        addLog(healthy, EventSourcePollLogEntity.STATUS_SUCCESS, "Polled 2 projects", now.minusSeconds(60));
        String failing = createSource(true);
        addLog(failing, EventSourcePollLogEntity.STATUS_SUCCESS, "Polled", now.minusSeconds(600));
        addLog(failing, EventSourcePollLogEntity.STATUS_ERROR, "first", now.minusSeconds(300));
        addLog(failing, EventSourcePollLogEntity.STATUS_ERROR, "second", now.minusSeconds(60));
        createEvent(healthy, now.minus(Duration.ofHours(1)));
        createEvent(healthy, now.minus(Duration.ofHours(2)));
        createEvent(healthy, now.minus(Duration.ofHours(30)));
        try {
            JsonPath body = given().when().get("/api/v1/event-sources/health")
                    .then().statusCode(200).extract().jsonPath();
            assertEquals("never-polled", state(body, never));
            assertEquals("disabled", state(body, disabled));
            assertEquals("baseline", state(body, baseline));
            assertEquals("healthy", state(body, healthy));
            assertEquals("failing", state(body, failing));

            Map<String, Object> h = source(body, healthy);
            assertNull(h.get("lastError"), "lastError only when after the last success");
            assertNotNull(h.get("lastErrorAt"));
            assertNotNull(h.get("lastSuccessAt"));
            assertNotNull(h.get("lastAttemptAt"));
            assertEquals("github", h.get("type"));
            assertEquals(2, ((Number) h.get("eventsLast24h")).intValue());
            assertEquals(0, ((Number) h.get("consecutiveFailures")).intValue());

            Map<String, Object> f = source(body, failing);
            assertEquals("second", f.get("lastError"));
            assertEquals(2, ((Number) f.get("consecutiveFailures")).intValue());
            assertEquals(0, ((Number) f.get("eventsLast24h")).intValue());
        } finally {
            disableSources(List.of(never, baseline, healthy, failing));
        }
    }

    /** #349: the per-source status reports lastError only when it is after the last success. */
    @Test
    void eventSourceStatusHidesRecoveredError() {
        Instant now = Instant.now();
        String recovered = createSource(false);
        addLog(recovered, EventSourcePollLogEntity.STATUS_ERROR, "old failure", now.minusSeconds(600));
        addLog(recovered, EventSourcePollLogEntity.STATUS_SUCCESS, "Polled", now.minusSeconds(60));
        createEvent(recovered, now);

        JsonPath body = given().when().get("/api/v1/event-sources/" + recovered + "/status")
                .then().statusCode(200).extract().jsonPath();
        assertNull(body.get("lastError"));
        assertNotNull(body.get("lastErrorAt"));
        assertNotNull(body.get("lastSuccessAt"));
        assertEquals(1, body.getInt("totalEventsProduced"));

        String failing = createSource(false);
        addLog(failing, EventSourcePollLogEntity.STATUS_SUCCESS, "Polled", now.minusSeconds(600));
        addLog(failing, EventSourcePollLogEntity.STATUS_ERROR, "boom", now.minusSeconds(60));
        JsonPath failingBody = given().when().get("/api/v1/event-sources/" + failing + "/status")
                .then().statusCode(200).extract().jsonPath();
        assertEquals("boom", failingBody.getString("lastError"));
    }

    /** /subscriptions/summary counts every status (filter_error included) inside the window. */
    @Test
    void subscriptionSummaryCountsStatuses() {
        String source = createSource(false);
        Instant now = Instant.now();
        long subId = createSubscription();
        long idleId = createSubscription();
        UUID event = createEvent(source, now);
        createLedger(event, subId, EventProcessingLedgerEntity.COMPLETED, null, now);
        createLedger(event, subId, EventProcessingLedgerEntity.COMPLETED, null, now);
        createLedger(event, subId, EventProcessingLedgerEntity.SKIPPED, null, now);
        createLedger(event, subId, EventProcessingLedgerEntity.EXHAUSTED, "gave up", now.minusSeconds(30));
        createLedger(event, subId, EventProcessingLedgerEntity.FILTER_ERROR, "bad filter", now);
        createLedger(event, subId, EventProcessingLedgerEntity.FAILED, "outside window",
                now.minus(Duration.ofDays(3)));

        JsonPath body = given().when().get("/api/v1/subscriptions/summary")
                .then().statusCode(200).extract().jsonPath();
        String sub = "find { it.subscriptionId == " + subId + " }";
        assertEquals(2, body.getInt(sub + ".counts.completed"));
        assertEquals(1, body.getInt(sub + ".counts.skipped"));
        assertEquals(1, body.getInt(sub + ".counts.exhausted"));
        assertEquals(1, body.getInt(sub + ".counts.filterError"));
        assertEquals(0, body.getInt(sub + ".counts.failed"));
        assertEquals(0, body.getInt(sub + ".counts.pending"));
        assertEquals("bad filter", body.getString(sub + ".lastError"));
        assertNotNull(body.getString(sub + ".lastErrorAt"));
        assertFalse(body.getBoolean(sub + ".enabled"));

        String idle = "find { it.subscriptionId == " + idleId + " }";
        assertEquals(0, body.getInt(idle + ".counts.completed"));
        assertNull(body.get(idle + ".lastError"));

        JsonPath wide = given().queryParam("since", now.minus(Duration.ofDays(4)).toString())
                .when().get("/api/v1/subscriptions/summary")
                .then().statusCode(200).extract().jsonPath();
        assertEquals(1, wide.getInt(sub + ".counts.failed"));

        // The literal /summary path does not break /subscriptions/{subscriptionId}.
        given().when().get("/api/v1/subscriptions/" + subId).then().statusCode(200);
        given().queryParam("since", "nope").when().get("/api/v1/subscriptions/summary").then().statusCode(400);
    }

    /** /stream/processing/failures lists failed, exhausted and filter_error entries newest first. */
    @Test
    void processingFailuresNewestFirst() {
        String source = createSource(false);
        Instant now = Instant.now();
        long subId = createSubscription();
        UUID event = createEvent(source, now);
        long older = createLedger(event, subId, EventProcessingLedgerEntity.EXHAUSTED, "exhausted",
                now.plusSeconds(3600));
        long newer = createLedger(event, subId, EventProcessingLedgerEntity.FILTER_ERROR, "bad filter",
                now.plusSeconds(7200));
        createLedger(event, subId, EventProcessingLedgerEntity.COMPLETED, null, now.plusSeconds(9000));

        JsonPath body = given().queryParam("limit", 2).when().get("/api/v1/stream/processing/failures")
                .then().statusCode(200).extract().jsonPath();
        assertEquals(List.of(newer, older), body.getList("ledgerId", Long.class));
        assertEquals("filter_error", body.getString("[0].status"));
        assertEquals("bad filter", body.getString("[0].errorMessage"));
        assertEquals(event.toString(), body.getString("[0].eventId"));
        assertEquals("issue.opened", body.getString("[0].eventType"));
        assertEquals(subId, body.getLong("[0].subscriptionId"));
        assertNotNull(body.getString("[0].subscriptionName"));
        assertEquals("exhausted", body.getString("[1].status"));

        JsonPath since = given().queryParam("since", now.plusSeconds(5000).toString())
                .when().get("/api/v1/stream/processing/failures")
                .then().statusCode(200).extract().jsonPath();
        assertEquals(List.of(newer), since.getList("ledgerId", Long.class));

        // Out-of-range limits are clamped (consistent with the workflow dashboard endpoints).
        int clamped = given().queryParam("limit", 1000).when().get("/api/v1/stream/processing/failures")
                .then().statusCode(200).extract().jsonPath().getList("ledgerId").size();
        assertTrue(clamped <= 200);
    }

    /** /stream/events/histogram zero-fills buckets and counts per bucket. */
    @Test
    void histogramZeroFillsBuckets() {
        String source = createSource(false);
        Instant now = Instant.now();
        Instant thisHour = now.truncatedTo(ChronoUnit.HOURS);
        createEvent(source, now);
        createEvent(source, thisHour.minus(Duration.ofHours(3)).plusSeconds(10));
        createEvent(source, thisHour.minus(Duration.ofHours(3)).plusSeconds(20));

        JsonPath body = given().queryParam("eventSourceId", source)
                .queryParam("since", thisHour.minus(Duration.ofHours(5)).toString())
                .when().get("/api/v1/stream/events/histogram")
                .then().statusCode(200).extract().jsonPath();
        assertEquals(List.of(0, 0, 2, 0, 0, 1), body.getList("count", Integer.class));
        assertEquals(thisHour.minus(Duration.ofHours(5)).toString(),
                Instant.parse(body.getString("[0].bucketStart")).toString());

        JsonPath dflt = given().queryParam("eventSourceId", source).when().get("/api/v1/stream/events/histogram")
                .then().statusCode(200).extract().jsonPath();
        assertEquals(25, dflt.getList("count").size(), "default is 24 hours of hourly buckets");

        JsonPath days = given().queryParam("eventSourceId", source).queryParam("bucket", "day")
                .queryParam("since", now.minus(Duration.ofDays(2)).toString())
                .when().get("/api/v1/stream/events/histogram")
                .then().statusCode(200).extract().jsonPath();
        assertEquals(3, days.getList("count").size());
        assertEquals(3, days.getList("count", Integer.class).stream().mapToInt(Integer::intValue).sum());
        assertTrue(days.getString("[0].bucketStart").contains("00:00:00"));
    }

    /** The histogram rejects invalid buckets and more than 400 buckets. */
    @Test
    void histogramRejectsTooManyBuckets() {
        Instant now = Instant.now();
        given().queryParam("since", now.minus(Duration.ofHours(399)).toString())
                .when().get("/api/v1/stream/events/histogram").then().statusCode(200);
        given().queryParam("since", now.minus(Duration.ofHours(400)).toString())
                .when().get("/api/v1/stream/events/histogram").then().statusCode(400);
        given().queryParam("since", now.minus(Duration.ofDays(30)).toString()).queryParam("bucket", "day")
                .when().get("/api/v1/stream/events/histogram").then().statusCode(200);
        given().queryParam("bucket", "week").when().get("/api/v1/stream/events/histogram").then().statusCode(400);
    }

    private static String state(JsonPath body, String id) {
        return (String) source(body, id).get("state");
    }

    private static Map<String, Object> source(JsonPath body, String id) {
        return body.getMap("find { it.id == '" + id + "' }");
    }

    private static String createSource(boolean enabled) {
        String id = "ops-" + UUID.randomUUID().toString().substring(0, 8);
        QuarkusTransaction.requiringNew().run(() -> {
            EventSourceEntity source = new EventSourceEntity();
            source.id = id;
            source.name = id;
            source.sourceType = "github";
            source.enabled = enabled;
            source.baseUrl = "https://api.github.com";
            source.configuration = "{}";
            source.pollInterval = 86400;
            source.createdOn = Instant.now();
            source.modifiedOn = Instant.now();
            source.persist();
        });
        return id;
    }

    private static void disableSources(List<String> ids) {
        QuarkusTransaction.requiringNew().run(() -> ids.forEach(id -> {
            EventSourceEntity source = EventSourceEntity.findById(id);
            source.enabled = false;
        }));
    }

    private static void addLog(String sourceId, String status, String message, Instant createdOn) {
        QuarkusTransaction.requiringNew().run(() -> {
            EventSourcePollLogEntity log = new EventSourcePollLogEntity();
            log.eventSourceId = sourceId;
            log.status = status;
            log.message = message;
            log.createdOn = createdOn;
            log.persist();
        });
    }

    private static UUID createEvent(String sourceId, Instant createdOn) {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = id;
            event.sourceEventId = "ops-" + id;
            event.source = "github";
            event.eventSourceId = sourceId;
            event.type = "issue.opened";
            event.ref = "https://github.com/test-org/ops/issues/" + id;
            event.timestamp = createdOn;
            event.actor = "{\"login\":\"testuser\"}";
            event.payload = "{}";
            event.createdOn = createdOn;
            event.persist();
        });
        return id;
    }

    /** Creates a disabled subscription so the orchestrator never routes the test events. */
    private static long createSubscription() {
        return QuarkusTransaction.requiringNew().call(() -> {
            EventSubscriptionEntity sub = new EventSubscriptionEntity();
            sub.name = "ops-" + UUID.randomUUID();
            sub.enabled = false;
            sub.routing = "[]";
            sub.processEventsFrom = FAR_FUTURE;
            sub.createdOn = Instant.now();
            sub.modifiedOn = Instant.now();
            sub.persist();
            return sub.id;
        });
    }

    /** Creates a ledger entry whose timestamps are all {@code at}; failed entries are not due for retry. */
    private static long createLedger(UUID eventId, long subscriptionId, String status, String error, Instant at) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EventProcessingLedgerEntity entry = new EventProcessingLedgerEntity();
            entry.eventId = eventId;
            entry.subscriptionId = subscriptionId;
            entry.status = status;
            entry.errorMessage = error;
            entry.attemptCount = 1;
            entry.createdOn = at;
            entry.lastAttemptAt = at;
            entry.processedOn = at;
            entry.nextAttemptAt = EventProcessingLedgerEntity.FAILED.equals(status) ? FAR_FUTURE : null;
            entry.persist();
            return entry.id;
        });
    }
}
