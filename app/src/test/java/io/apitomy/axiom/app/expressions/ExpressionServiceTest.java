package io.apitomy.axiom.app.expressions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.StreamEventEntity;
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

    @AfterEach
    void cleanUp() {
        if (eventId != null) {
            QuarkusTransaction.requiringNew().run(() -> StreamEventEntity.deleteById(eventId));
        }
    }

    private String storeEvent() {
        eventId = QuarkusTransaction.requiringNew().call(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = UUID.randomUUID();
            event.type = "issue.comment.created";
            event.source = "github";
            event.eventSourceId = "github-com";
            event.ref = "https://github.com/o/r/issues/1";
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
    void reportsErrorsAsResults() {
        assertFalse(expressions.evaluate("event.ref", "receive-event-match", null, null).ok());
        assertFalse(expressions.evaluate("event.ref", "correlation-event-key", "not-a-uuid", null).ok());
        assertFalse(expressions.evaluate("context.(", "action-input", null, null).ok());
        assertThrows(IllegalArgumentException.class, () -> expressions.evaluate(" ", "action-input", null, null));
        assertThrows(IllegalArgumentException.class, () -> expressions.evaluate("1", "nope", null, null));
    }
}
