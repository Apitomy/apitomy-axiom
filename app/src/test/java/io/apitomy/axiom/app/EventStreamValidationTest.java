package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.events.model.Actor;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.model.NormalizedIssue;
import io.apitomy.axiom.core.events.model.payloads.IssueClosedPayload;
import io.apitomy.axiom.events.core.EventStreamService;
import io.apitomy.axiom.events.core.PersistOutcome;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EventStreamValidationTest {

    @Inject
    EventStreamService service;

    static NormalizedEvent event(String eventSourceId, String sourceEventId, String createdAt) {
        Actor actor = new Actor("u1", "User One", null, null);
        NormalizedIssue issue = new NormalizedIssue("PROJ-1", "Title", null, "closed", "Closed", actor,
                List.of(), List.of(), null, "https://jira.example/browse/PROJ-1", createdAt, null, null);
        return new NormalizedEvent(UUID.randomUUID().toString(), sourceEventId, "jira", eventSourceId,
                EventType.ISSUE_CLOSED, "https://jira.example/browse/PROJ-1", "2026-10-21T14:54:06Z",
                actor, new IssueClosedPayload(issue), null);
    }

    static StreamEventEntity stored(String eventSourceId, String sourceEventId) {
        return QuarkusTransaction.requiringNew().call(() -> StreamEventEntity
                .<StreamEventEntity>find("eventSourceId = ?1 and sourceEventId = ?2", eventSourceId, sourceEventId)
                .firstResult());
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() ->
                StreamEventEntity.delete("eventSourceId in ?1", List.of("val-a", "val-b")));
    }

    @Test
    void validEventIsPersistedWithoutErrors() {
        assertEquals(PersistOutcome.PERSISTED, service.persistEvent(event("val-a", "P-1-1", "2026-10-21T14:53:07Z")));
        assertNull(stored("val-a", "P-1-1").validationErrors);
    }

    @Test
    void invalidEventIsPersistedWithErrors() {
        PersistOutcome outcome = service.persistEvent(event("val-a", "P-1-2", "2026-10-21T14:53:07.835+0000"));
        assertEquals(PersistOutcome.PERSISTED_INVALID, outcome);
        String errors = stored("val-a", "P-1-2").validationErrors;
        assertTrue(errors.startsWith("[") && errors.contains("$.payload.issue.createdAt"), errors);
    }

    @Test
    void sameSourceEventIdInTwoSourcesIsStoredTwice() {
        assertEquals(PersistOutcome.PERSISTED, service.persistEvent(event("val-a", "P-1-3", "2026-10-21T14:53:07Z")));
        assertEquals(PersistOutcome.PERSISTED, service.persistEvent(event("val-b", "P-1-3", "2026-10-21T14:53:07Z")));
    }

    @Test
    void sameSourceEventIdInOneSourceIsDuplicate() {
        assertEquals(PersistOutcome.PERSISTED, service.persistEvent(event("val-a", "P-1-4", "2026-10-21T14:53:07Z")));
        assertEquals(PersistOutcome.DUPLICATE, service.persistEvent(event("val-a", "P-1-4", "2026-10-21T14:53:07Z")));
    }

    @Test
    void existingIdsAreScopedToTheSource() {
        service.persistEvent(event("val-a", "P-1-5", "2026-10-21T14:53:07Z"));
        assertEquals(Set.of("P-1-5"), service.existingSourceEventIds("val-a", List.of("P-1-5", "P-1-6")));
        assertEquals(Set.of(), service.existingSourceEventIds("val-b", List.of("P-1-5")));
    }
}
