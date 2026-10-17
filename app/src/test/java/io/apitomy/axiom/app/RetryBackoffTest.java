package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.EventProcessingLedgerEntity;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.filters.SubscriptionFilterResult;
import io.apitomy.axiom.core.filters.SubscriptionFilterEvaluator;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies limited retries with exponential backoff and the attempt history of event
 * processing (#422). Time is controlled by moving {@code next_attempt_at} into the past
 * ({@link #makeDue()}) rather than by sleeping; the test profile uses the default delays
 * (30s initial, 1h max) and max attempts (3).
 */
@QuarkusTest
class RetryBackoffTest {

    @InjectMock
    AgentPool agentPool;

    @InjectMock
    WorkflowEventDispatcher workflowEventDispatcher;

    @InjectMock
    SubscriptionFilterEvaluator filterEvaluator;

    @Inject
    EventStreamOrchestrator orchestrator;

    private final List<UUID> eventIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Mockito.when(agentPool.tryLease(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(Optional.empty());
        Mockito.when(filterEvaluator.evaluateFilter(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(SubscriptionFilterResult.match());
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            RoutingOutcomeItemEntity.deleteAll();
            RoutingOutcomeEntity.deleteAll();
            EventProcessingLedgerEntity.deleteAll();
            for (UUID id : eventIds) {
                TaskEntity.delete("eventId", id);
            }
            StreamEventEntity.deleteAll();
            EventSubscriptionEntity.deleteAll();
        });
        eventIds.clear();
    }

    @Test
    void oneTickAttemptsANewEntryOnlyOnce() {
        failRouting();
        UUID eventId = createEventAndSubscription();
        Instant before = Instant.now();

        orchestrator.processNewEvents();

        assertEquals(1, evaluationCalls(), "First pass and retry pass must not both run the entry");
        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("failed", entry.status);
        assertEquals(1, entry.attemptCount);
        assertNotNull(entry.lastAttemptAt);
        assertNotNull(entry.nextAttemptAt);
        assertBetween(entry.nextAttemptAt, before.plus(Duration.ofSeconds(30)),
                Instant.now().plus(Duration.ofSeconds(30)));
    }

    @Test
    void entryIsNotRetriedBeforeNextAttemptAt() {
        failRouting();
        UUID eventId = createEventAndSubscription();
        orchestrator.processNewEvents();
        orchestrator.processNewEvents();
        orchestrator.processNewEvents();
        assertEquals(1, evaluationCalls(), "Not due yet: no retry");

        makeDue();
        Instant before = Instant.now();
        orchestrator.processNewEvents();

        assertEquals(2, evaluationCalls());
        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("failed", entry.status);
        assertEquals(2, entry.attemptCount);
        assertTrue(!entry.lastAttemptAt.isBefore(before), "lastAttemptAt is updated");
        assertBetween(entry.nextAttemptAt, before.plus(Duration.ofSeconds(60)),
                Instant.now().plus(Duration.ofSeconds(60)));
    }

    @Test
    void backoffDoublesAndIsCappedByMaxDelay() {
        assertEquals(Duration.ofSeconds(30), orchestrator.backoffDelay(1));
        assertEquals(Duration.ofSeconds(60), orchestrator.backoffDelay(2));
        assertEquals(Duration.ofSeconds(120), orchestrator.backoffDelay(3));
        assertEquals(Duration.ofHours(1), orchestrator.backoffDelay(8));
        assertEquals(Duration.ofHours(1), orchestrator.backoffDelay(500));
    }

    @Test
    void entryIsExhaustedAtTheCap() {
        failRouting();
        UUID eventId = createEventAndSubscription();

        for (int i = 0; i < 6; i++) {
            orchestrator.processNewEvents();
            makeDue();
        }

        assertEquals(3, evaluationCalls(), "Default axiom.stream-pipeline.max-attempts is 3");
        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("exhausted", entry.status);
        assertEquals(3, entry.attemptCount);
        assertNull(entry.nextAttemptAt, "An exhausted entry has no next attempt");
        assertTrue(entry.errorMessage.contains("giving up after 3 attempts"), entry.errorMessage);
        assertEquals(1, entry.errorMessage.split("giving up", -1).length - 1,
                "The giving-up suffix is appended once");
    }

    @Test
    void unexpectedExceptionBeforeTheLedgerEntryCreatesAFailedEntryAndIsCapped() {
        // evaluateFilter reports evaluation errors as results and building the event map
        // tolerates bad JSON, so no real input reaches this path: the mock throws to simulate
        // an unexpected failure (a bug, a runtime error) before the ledger entry exists.
        Mockito.when(filterEvaluator.evaluateFilter(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("filter blew up"));
        UUID eventId = createEventAndSubscription();

        orchestrator.processNewEvents();

        EventProcessingLedgerEntity entry = ledger(eventId);
        assertNotNull(entry, "A pre-ledger failure must create a ledger entry");
        assertEquals("failed", entry.status);
        assertEquals(1, entry.attemptCount);
        assertNotNull(entry.nextAttemptAt);
        assertTrue(entry.errorMessage.contains("filter blew up"), entry.errorMessage);

        for (int i = 0; i < 6; i++) {
            makeDue();
            orchestrator.processNewEvents();
        }

        entry = ledger(eventId);
        assertEquals("exhausted", entry.status);
        assertEquals(3, entry.attemptCount);
        List<RoutingOutcomeEntity> outcomes = outcomes();
        assertEquals(3, outcomes.size(), "One processing outcome per attempt");
        for (int i = 0; i < 3; i++) {
            assertEquals("processing", outcomes.get(i).routingType);
            assertEquals("failed", outcomes.get(i).status);
            assertEquals(Integer.valueOf(i + 1), outcomes.get(i).attemptNumber);
        }
        assertEquals(0, evaluationCalls(), "The filter never matched, so nothing was routed");
    }

    @Test
    void retryReEvaluatesTheFilterAfterAPreLedgerFailure() {
        Mockito.when(filterEvaluator.evaluateFilter(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("filter blew up"))
                .thenReturn(SubscriptionFilterResult.noMatch());
        UUID eventId = createEventAndSubscription();
        orchestrator.processNewEvents();
        makeDue();
        orchestrator.processNewEvents();

        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("skipped", entry.status, "A filter that no longer matches skips the entry");
        assertNull(entry.nextAttemptAt);
        assertEquals(0, evaluationCalls());
    }

    @Test
    void attemptNumbersAreRecordedOnOutcomesAndExposedByTheApi() {
        failRouting();
        UUID eventId = createEventAndSubscription();
        orchestrator.processNewEvents();
        makeDue();
        succeedRouting();
        orchestrator.processNewEvents();

        List<RoutingOutcomeEntity> outcomes = outcomes();
        assertEquals(2, outcomes.size());
        assertEquals("failed", outcomes.get(0).status);
        assertEquals(Integer.valueOf(1), outcomes.get(0).attemptNumber);
        assertEquals("completed", outcomes.get(1).status);
        assertEquals(Integer.valueOf(2), outcomes.get(1).attemptNumber);
        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("completed", entry.status);
        assertEquals(2, entry.attemptCount);
        assertNull(entry.nextAttemptAt);

        given()
            .when()
                .get("/api/v1/stream/events/" + eventId + "/processing")
            .then()
                .statusCode(200)
                .body("items[0].attemptCount", is(2))
                .body("items[0].maxAttempts", is(3))
                .body("items[0].lastAttemptAt", notNullValue())
                .body("items[0].nextAttemptAt", nullValue())
                .body("items[0].outcomes.attemptNumber", contains(1, 2))
                .body("items[0].outcomes.status", contains("failed", "completed"))
                .body("items[0].outcomes[0].errorMessage", notNullValue());
    }

    @Test
    void failedEntryExposesItsNextAttemptInTheApi() {
        failRouting();
        UUID eventId = createEventAndSubscription();
        orchestrator.processNewEvents();

        given()
            .when()
                .get("/api/v1/stream/events/" + eventId + "/processing")
            .then()
                .statusCode(200)
                .body("items[0].status", is("failed"))
                .body("items[0].attemptCount", is(1))
                .body("items[0].nextAttemptAt", notNullValue())
                .body("items[0].outcomes.attemptNumber", contains(1));
    }

    @Test
    void manualRetryAllowsOneMoreAttemptOfAnExhaustedEntry() {
        failRouting();
        UUID eventId = createEventAndSubscription();
        for (int i = 0; i < 4; i++) {
            orchestrator.processNewEvents();
            makeDue();
        }
        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("exhausted", entry.status);

        given()
            .when()
                .post("/api/v1/stream/events/" + eventId + "/processing/" + entry.id + "/retry")
            .then()
                .statusCode(200)
                .body("status", is("failed"))
                .body("attemptCount", is(3))
                .body("nextAttemptAt", notNullValue());

        orchestrator.processNewEvents();
        orchestrator.processNewEvents();
        makeDue();
        orchestrator.processNewEvents();

        assertEquals(4, evaluationCalls(), "A manual retry grants exactly one more attempt");
        entry = ledger(eventId);
        assertEquals("exhausted", entry.status);
        assertEquals(4, entry.attemptCount);
        assertTrue(entry.errorMessage.contains("giving up after 4 attempts"), entry.errorMessage);
        assertEquals(Integer.valueOf(4), outcomes().get(3).attemptNumber);
    }

    @Test
    void manualRetryCanSucceed() {
        failRouting();
        UUID eventId = createEventAndSubscription();
        for (int i = 0; i < 4; i++) {
            orchestrator.processNewEvents();
            makeDue();
        }
        Long ledgerId = ledger(eventId).id;
        given().when()
                .post("/api/v1/stream/events/" + eventId + "/processing/" + ledgerId + "/retry")
                .then().statusCode(200);
        succeedRouting();

        orchestrator.processNewEvents();

        assertEquals("completed", ledger(eventId).status);
    }

    @Test
    void manualRetryRejectsEntriesThatAreNotFailedOrExhausted() {
        succeedRouting();
        UUID eventId = createEventAndSubscription();
        orchestrator.processNewEvents();
        Long ledgerId = ledger(eventId).id;

        given().when()
                .post("/api/v1/stream/events/" + eventId + "/processing/" + ledgerId + "/retry")
                .then().statusCode(409);
        given().when()
                .post("/api/v1/stream/events/" + UUID.randomUUID() + "/processing/" + ledgerId + "/retry")
                .then().statusCode(404);
        given().when()
                .post("/api/v1/stream/events/" + eventId + "/processing/987654321/retry")
                .then().statusCode(404);
    }

    @Test
    void startupRecoveryMakesRecoveredEntriesDue() throws Exception {
        UUID eventId = createEventAndSubscription();
        Long subId = QuarkusTransaction.requiringNew().call(() ->
                EventSubscriptionEntity.<EventSubscriptionEntity>findAll().firstResult().id);
        QuarkusTransaction.requiringNew().run(() -> {
            EventProcessingLedgerEntity entry = new EventProcessingLedgerEntity();
            entry.eventId = eventId;
            entry.subscriptionId = subId;
            entry.status = "pending";
            entry.attemptCount = 1;
            entry.lastAttemptAt = Instant.now().minusSeconds(300);
            entry.createdOn = Instant.now().minusSeconds(300);
            entry.persist();
        });
        succeedRouting();
        Object bean = ClientProxy.unwrap(orchestrator);
        Field field = EventStreamOrchestrator.class.getDeclaredField("startupRecoveryDone");
        field.setAccessible(true);
        field.set(bean, false);

        orchestrator.processNewEvents();

        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("completed", entry.status, "A recovered entry is due immediately");
        assertEquals(2, entry.attemptCount);
    }


    @Test
    void manualRetryRejectsAPendingEntry() {
        UUID eventId = createEventAndSubscription();
        Long ledgerId = insertLedger(eventId, "pending", 1);

        given().when()
                .post("/api/v1/stream/events/" + eventId + "/processing/" + ledgerId + "/retry")
                .then().statusCode(409);
        assertEquals("pending", ledger(eventId).status);
    }

    @Test
    void beginAttemptSkipsAnEntryThatIsNoLongerFailed() {
        UUID eventId = createEventAndSubscription();
        Long ledgerId = insertLedger(eventId, "pending", 1);

        assertNull(orchestrator.beginAttempt(ledgerId), "Conditional update changes no row");
        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("pending", entry.status);
        assertEquals(1, entry.attemptCount);
    }

    @Test
    void manualRetryDoesNotOverwriteAnAttemptStartedConcurrently() throws Exception {
        UUID eventId = createEventAndSubscription();
        Long ledgerId = insertLedger(eventId, "failed", 1);

        // A tick starts an attempt (status pending, attempt 2) in a transaction that is still
        // open while the manual retry runs.
        Object[] retryResult = new Object[1];
        runWhileRowIsLocked(ledgerId,
                "status = 'pending', attemptCount = attemptCount + 1, nextAttemptAt = null",
                () -> {
                    try {
                        retryResult[0] = orchestrator.retryNow(ledgerId);
                    } catch (RuntimeException e) {
                        retryResult[0] = e;
                    }
                });

        assertTrue(retryResult[0] instanceof IllegalStateException,
                "The manual retry must be rejected, got " + retryResult[0]);
        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("pending", entry.status, "The running attempt must not be made due again");
        assertEquals(2, entry.attemptCount, "The attempt count must not be rolled back");
        assertNull(entry.nextAttemptAt);
    }

    @Test
    void beginAttemptDoesNotOverwriteAConcurrentStatusChange() throws Exception {
        UUID eventId = createEventAndSubscription();
        Long ledgerId = insertLedger(eventId, "failed", 1);

        // Another node starts the attempt first, in a transaction still open while this
        // node's retry pass picks up the same entry.
        Integer[] attempt = new Integer[1];
        runWhileRowIsLocked(ledgerId,
                "status = 'pending', attemptCount = attemptCount + 1, nextAttemptAt = null",
                () -> attempt[0] = orchestrator.beginAttempt(ledgerId));

        assertNull(attempt[0], "Only one node may start the attempt");
        assertEquals(2, ledger(eventId).attemptCount, "The attempt is counted once");
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private Long insertLedger(UUID eventId, String status, int attempts) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EventProcessingLedgerEntity entry = new EventProcessingLedgerEntity();
            entry.eventId = eventId;
            entry.subscriptionId = EventSubscriptionEntity.<EventSubscriptionEntity>findAll()
                    .firstResult().id;
            entry.status = status;
            entry.attemptCount = attempts;
            entry.lastAttemptAt = Instant.now().minusSeconds(60);
            entry.nextAttemptAt = "failed".equals(status) ? Instant.now().minusSeconds(1) : null;
            entry.createdOn = Instant.now().minusSeconds(60);
            entry.persist();
            return entry.id;
        });
    }

    @Test
    void filterEvaluationErrorIsRecordedAsFilterErrorNotSkipped() {
        brokenFilter();
        UUID eventId = createEventAndSubscription();

        orchestrator.processNewEvents();

        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("filter_error", entry.status);
        assertTrue(entry.errorMessage.contains("bad property"), entry.errorMessage);
        assertEquals(1, entry.attemptCount);
        assertNull(entry.nextAttemptAt, "Filter errors are not retried automatically");
        assertEquals(0, evaluationCalls(), "Fail closed: no routes run after a filter error");

        given()
            .when()
                .get("/api/v1/stream/events/" + eventId + "/processing")
            .then()
                .statusCode(200)
                .body("items[0].status", is("filter_error"))
                .body("items[0].errorMessage", notNullValue());
    }

    @Test
    void filterErrorIsNotRetriedAutomatically() {
        brokenFilter();
        UUID eventId = createEventAndSubscription();
        orchestrator.processNewEvents();
        makeDue();
        orchestrator.processNewEvents();
        orchestrator.processNewEvents();

        Mockito.verify(filterEvaluator, Mockito.times(1))
                .evaluateFilter(ArgumentMatchers.any(), ArgumentMatchers.any());
        assertEquals("filter_error", ledger(eventId).status);
    }

    @Test
    void manualRetryReEvaluatesAFilterErrorAndRecovers() {
        brokenFilter();
        UUID eventId = createEventAndSubscription();
        orchestrator.processNewEvents();
        Long ledgerId = ledger(eventId).id;

        Mockito.when(filterEvaluator.evaluateFilter(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(SubscriptionFilterResult.match());
        succeedRouting();
        given().when()
                .post("/api/v1/stream/events/" + eventId + "/processing/" + ledgerId + "/retry")
                .then().statusCode(200);
        orchestrator.processNewEvents();

        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("completed", entry.status);
        assertNull(entry.errorMessage);
        assertEquals(1, evaluationCalls());
    }

    @Test
    void manualRetryOfAStillBrokenFilterRecordsFilterErrorAgain() {
        brokenFilter();
        UUID eventId = createEventAndSubscription();
        orchestrator.processNewEvents();
        Long ledgerId = ledger(eventId).id;

        given().when()
                .post("/api/v1/stream/events/" + eventId + "/processing/" + ledgerId + "/retry")
                .then().statusCode(200);
        orchestrator.processNewEvents();

        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("filter_error", entry.status);
        assertNull(entry.nextAttemptAt);
        assertEquals(0, evaluationCalls());
    }

    @Test
    void updatingTheSubscriptionMakesFilterErrorsDueAgain() {
        brokenFilter();
        UUID eventId = createEventAndSubscription();
        orchestrator.processNewEvents();
        Long subId = QuarkusTransaction.requiringNew().call(() ->
                EventSubscriptionEntity.<EventSubscriptionEntity>findAll().firstResult().id);

        Mockito.when(filterEvaluator.evaluateFilter(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(SubscriptionFilterResult.noMatch());
        Mockito.when(filterEvaluator.isValid(ArgumentMatchers.any())).thenReturn(true);
        given().contentType("application/json")
                .body("{\"name\":\"fixed\",\"enabled\":true,\"filterExpression\":\"event.type == 'x'\"}")
                .when().put("/api/v1/subscriptions/" + subId)
                .then().statusCode(200);
        orchestrator.processNewEvents();

        assertEquals("skipped", ledger(eventId).status, "The fixed filter is re-evaluated");
    }

    @Test
    void filterFixedWhileATickEvaluatesTheOldFilterIsReEvaluated() {
        UUID eventId = createEventAndSubscription();
        Long subId = subscriptionId();
        Mockito.when(filterEvaluator.isValid(ArgumentMatchers.any())).thenReturn(true);
        // The tick evaluates the old (broken) filter; while it does, the user fixes the filter.
        // retryFilterErrors runs and commits before the tick records the filter error.
        Mockito.when(filterEvaluator.evaluateFilter(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    putSubscription(subId, "event.type == 'fixed'", true);
                    return SubscriptionFilterResult.error("bad property");
                });

        orchestrator.processNewEvents();

        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("failed", entry.status, "A filter error from a stale filter is re-evaluated");
        assertNotNull(entry.nextAttemptAt);

        // doReturn: when(...) would invoke the answer that updates the subscription
        Mockito.doReturn(SubscriptionFilterResult.noMatch()).when(filterEvaluator)
                .evaluateFilter(ArgumentMatchers.any(), ArgumentMatchers.any());
        orchestrator.processNewEvents();
        assertEquals("skipped", ledger(eventId).status);
    }

    @Test
    void updatingTheSubscriptionWithoutChangingTheFilterDoesNotRequeueFilterErrors() {
        brokenFilter();
        UUID eventId = createEventAndSubscription();
        orchestrator.processNewEvents();
        Mockito.when(filterEvaluator.isValid(ArgumentMatchers.any())).thenReturn(true);

        // createEventAndSubscription leaves the filter unset
        putSubscription(subscriptionId(), null, true);

        assertEquals("filter_error", ledger(eventId).status);
    }

    @Test
    void updatingADisabledSubscriptionDoesNotRequeueFilterErrors() {
        brokenFilter();
        UUID eventId = createEventAndSubscription();
        orchestrator.processNewEvents();
        Mockito.when(filterEvaluator.isValid(ArgumentMatchers.any())).thenReturn(true);

        putSubscription(subscriptionId(), "event.type == 'x'", false);

        assertEquals("filter_error", ledger(eventId).status);
    }

    @Test
    void reEvaluatingFilterErrorsAfterAFilterChangeDoesNotBurnAttempts() {
        brokenFilter();
        UUID eventId = createEventAndSubscription();
        orchestrator.processNewEvents();
        Mockito.when(filterEvaluator.isValid(ArgumentMatchers.any())).thenReturn(true);
        for (int i = 0; i < 5; i++) {
            putSubscription(subscriptionId(), "event.type == 'x" + i + "'", true);
            orchestrator.processNewEvents();
        }

        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("filter_error", entry.status, "Never exhausted by re-evaluations");
        assertEquals(1, entry.attemptCount);
    }

    @Test
    void manualRetriesOfAFilterErrorDoNotBurnAttempts() {
        brokenFilter();
        UUID eventId = createEventAndSubscription();
        orchestrator.processNewEvents();
        Long ledgerId = ledger(eventId).id;
        for (int i = 0; i < 5; i++) {
            given().when()
                    .post("/api/v1/stream/events/" + eventId + "/processing/" + ledgerId + "/retry")
                    .then().statusCode(200);
            orchestrator.processNewEvents();
        }

        EventProcessingLedgerEntity entry = ledger(eventId);
        assertEquals("filter_error", entry.status);
        assertEquals(1, entry.attemptCount);
    }

    private static Long subscriptionId() {
        return QuarkusTransaction.requiringNew().call(() ->
                EventSubscriptionEntity.<EventSubscriptionEntity>findAll().firstResult().id);
    }

    private static void putSubscription(Long subId, String filter, boolean enabled) {
        String filterJson = filter != null ? ",\"filterExpression\":\"" + filter + "\"" : "";
        given().contentType("application/json")
                .body("{\"name\":\"retry-sub\",\"enabled\":" + enabled
                        + filterJson + "}")
                .when().put("/api/v1/subscriptions/" + subId)
                .then().statusCode(200);
    }

    private void brokenFilter() {
        Mockito.when(filterEvaluator.evaluateFilter(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(SubscriptionFilterResult.error("bad property"));
    }

    /**
     * Applies {@code change} to the ledger row in a transaction on another thread, runs
     * {@code action} on a third thread while that transaction still holds the row, then
     * commits the change and waits for the action.
     */
    private static void runWhileRowIsLocked(Long ledgerId, String change, Runnable action)
            throws Exception {
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> QuarkusTransaction.requiringNew().run(() -> {
            EventProcessingLedgerEntity.update(change + " where id = ?1", ledgerId);
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        holder.start();
        locked.await();
        Thread actor = new Thread(action);
        actor.start();
        Thread.sleep(300);
        release.countDown();
        holder.join(10000);
        actor.join(10000);
    }

    private void failRouting() {
        Mockito.doThrow(new IllegalStateException("still broken")).when(workflowEventDispatcher)
                .dispatchStreamEvent(ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any());
    }

    private void succeedRouting() {
        // doReturn: when(...) would invoke a method currently stubbed to throw
        Mockito.doReturn(List.of()).when(workflowEventDispatcher).dispatchStreamEvent(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    /** Moves the next attempt of every waiting entry into the past. */
    private static void makeDue() {
        QuarkusTransaction.requiringNew().run(() -> EventProcessingLedgerEntity.update(
                "nextAttemptAt = ?1 where nextAttemptAt is not null",
                Instant.now().minusSeconds(1)));
    }

    private long evaluationCalls() {
        return Mockito.mockingDetails(workflowEventDispatcher).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("dispatchStreamEvent"))
                .count();
    }

    private static void assertBetween(Instant actual, Instant low, Instant high) {
        assertTrue(!actual.isBefore(low.minusMillis(5)) && !actual.isAfter(high.plusMillis(5)),
                actual + " not in [" + low + ", " + high + "]");
    }

    private UUID createEventAndSubscription() {
        UUID eventId = UUID.randomUUID();
        eventIds.add(eventId);
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = eventId;
            event.sourceEventId = "retry-" + eventId;
            event.source = "github";
            event.eventSourceId = "conn-retry";
            event.type = "issue.created";
            event.ref = "https://github.com/test-org/retry/issues/" + eventId;
            event.timestamp = Instant.now();
            event.actor = "{\"login\":\"testuser\"}";
            event.payload = "{\"issue\":{\"title\":\"Test\"}}";
            event.createdOn = Instant.now();
            event.persist();

            EventSubscriptionEntity sub = new EventSubscriptionEntity();
            sub.name = "retry-" + eventId;
            sub.enabled = true;
            sub.routing = "[{\"type\":\"workflow-dispatch\"}]";
            sub.createdOn = Instant.now();
            sub.modifiedOn = Instant.now();
            sub.persist();
        });
        return eventId;
    }

    private List<RoutingOutcomeEntity> outcomes() {
        return QuarkusTransaction.requiringNew().call(() ->
                RoutingOutcomeEntity.<RoutingOutcomeEntity>list("order by id"));
    }

    private EventProcessingLedgerEntity ledger(UUID eventId) {
        return QuarkusTransaction.requiringNew().call(() ->
                EventProcessingLedgerEntity.<EventProcessingLedgerEntity>find("eventId", eventId)
                        .firstResult());
    }
}
