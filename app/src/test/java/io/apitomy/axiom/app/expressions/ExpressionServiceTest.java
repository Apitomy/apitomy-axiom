package io.apitomy.axiom.app.expressions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EL evaluation per scope, against literal contexts and stored stream events.
 */
@QuarkusTest
class ExpressionServiceTest {

    @Inject
    ExpressionService expressions;

    @Inject
    ObjectMapper mapper;

    private UUID eventId;
    private Long subjectId;

    @AfterEach
    void cleanUp() {
        if (eventId != null) {
            QuarkusTransaction.requiringNew().run(() -> StreamEventEntity.deleteById(eventId));
        }
        if (subjectId != null) {
            QuarkusTransaction.requiringNew().run(() -> SubjectEntity.deleteById(subjectId));
        }
    }

    private String storeEvent() {
        return storeEvent("https://github.com/o/r/issues/1");
    }

    private String storeEvent(String ref) {
        eventId = QuarkusTransaction.requiringNew().call(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = UUID.randomUUID();
            event.type = "issue.comment.created";
            event.source = "github";
            event.eventSourceId = "github-com";
            event.ref = ref;
            event.sourceEventId = "src-" + event.id;
            event.actor = "{\"login\":\"octo\"}";
            event.createdOn = Instant.now();
            event.timestamp = Instant.now();
            event.payload = "{\"comment\":{\"body\":\"Here are the logs\"}}";
            event.persist();
            return event.id;
        });
        return eventId.toString();
    }

    @Test
    void edgeConditionIsBooleanOverContext() throws Exception {
        ExpressionService.Result result = expressions.evaluate("context.needsInfo == true", "edge-condition",
                null, mapper.readTree("{\"needsInfo\":true}"));
        assertTrue(result.ok(), result.error());
        assertTrue(result.value().asBoolean());
    }

    @Test
    void humanTaskTitleResolvesAValue() throws Exception {
        ExpressionService.Result result = expressions.evaluate("'No reply on ' += context.ref", "human-task-title",
                null, mapper.readTree("{\"ref\":\"r-1\"}"));
        assertEquals("No reply on r-1", result.value().asText());
    }

    @Test
    void eventKeySeesOnlyTheEvent() {
        String id = storeEvent();
        ExpressionService.Result result = expressions.evaluate("event.ref", "correlation-event-key", id, null);
        assertEquals("https://github.com/o/r/issues/1", result.value().asText());
        ExpressionService.Result payload = expressions.evaluate("event.payload.comment.body", "receive-event-output", id, null);
        assertEquals("Here are the logs", payload.value().asText());
    }

    @Test
    void contextScopesSeeTheEventAsContextEvent() {
        String id = storeEvent();
        ExpressionService.Result result = expressions.evaluate("context.event.type", "action-input", id, null);
        assertEquals("issue.comment.created", result.value().asText());
    }

    @Test
    void contextEventMatchesWhatARunGetsIncludingItsId() {
        String id = storeEvent();
        assertEquals(id, expressions.evaluate("context.event.id", "action-input", id, null).value().asText());
        assertEquals("octo", expressions.evaluate("context.event.actor.login", "action-input", id, null)
                .value().asText());
    }

    @Test
    void contextSubjectIsTheExistingSubjectOfTheEvent() {
        String ref = "https://github.com/o/r/issues/" + UUID.randomUUID();
        String id = storeEvent(ref);
        subjectId = QuarkusTransaction.requiringNew().call(() -> {
            SubjectEntity s = new SubjectEntity();
            s.name = "Existing subject";
            s.type = "issue";
            s.ref = ref;
            s.refSource = "github";
            s.createdOn = Instant.now();
            s.updatedOn = Instant.now();
            s.persist();
            return s.id;
        });
        assertEquals(subjectId.longValue(), expressions.evaluate("context.subject.id", "action-input", id, null)
                .value().asLong());
        assertEquals("Existing subject", expressions.evaluate("context.subject.name", "human-task-title", id, null)
                .value().asText());
        // A run fills in a GitHub subject's missing repository from the event ref; evaluation shows the same.
        assertEquals("o/r", expressions.evaluate("context.subject.repository", "action-input", id, null)
                .value().asText());
    }

    @Test
    void contextSubjectIsPreviewedWhenTheEventHasNoSubjectYet() {
        String ref = "https://github.com/o/r/issues/" + UUID.randomUUID();
        String id = storeEvent(ref);
        assertEquals(ref, expressions.evaluate("context.subject.ref", "action-input", id, null).value().asText());
        assertEquals("o/r", expressions.evaluate("context.subject.repository", "action-input", id, null)
                .value().asText());
        assertEquals("issue", expressions.evaluate("context.subject.type", "action-input", id, null)
                .value().asText());
        assertTrue(expressions.evaluate("context.subject.id", "action-input", id, null).value().isNull());
        long created = QuarkusTransaction.requiringNew().call(() -> SubjectEntity.count("ref", ref));
        assertEquals(0, created, "evaluation must not create the subject");
    }

    @Test
    void suppliedContextWinsOverTheEventDerivedValues() throws Exception {
        String id = storeEvent();
        assertEquals("mine", expressions.evaluate("context.subject.ref", "action-input", id,
                mapper.readTree("{\"subject\": {\"ref\": \"mine\"}}")).value().asText());
    }

    @Test
    void subscriptionFilterSeesTheEventLikeSubscriptionMatching() {
        String id = storeEvent();
        ExpressionService.Result result = expressions.evaluate(
                "event.actor.login == 'octo' && event.ref.startsWith('https://github.com/o/')",
                "subscription-filter", id, null);
        assertTrue(result.ok(), result.error());
        assertTrue(result.value().asBoolean());
    }

    @Test
    void subscriptionFilterNeedsAnEventAndReportsErrors() {
        assertFalse(expressions.evaluate("event.type == 'x'", "subscription-filter", null, null).ok());
        String id = storeEvent();
        assertFalse(expressions.evaluate("event.(", "subscription-filter", id, null).ok());
    }

    @Test
    void reportsErrorsAsResults() {
        assertFalse(expressions.evaluate("event.ref", "receive-event-match", null, null).ok());
        assertFalse(expressions.evaluate("event.ref", "correlation-event-key", "not-a-uuid", null).ok());
        assertFalse(expressions.evaluate("context.(", "action-input", null, null).ok());
        assertThrows(IllegalArgumentException.class, () -> expressions.evaluate(" ", "action-input", null, null));
        assertThrows(IllegalArgumentException.class, () -> expressions.evaluate("1", "nope", null, null));
    }
}
