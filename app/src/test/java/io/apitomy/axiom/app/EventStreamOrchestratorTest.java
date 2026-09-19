package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.EventSubscriptionEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the EventStreamOrchestrator.
 */
@QuarkusTest
class EventStreamOrchestratorTest {

    @Inject
    EventStreamOrchestrator orchestrator;

    @Inject
    ObjectMapper objectMapper;

    @AfterEach
    @Transactional
    void cleanup() {
        StreamEventEntity.deleteAll();
        EventSubscriptionEntity.deleteAll();
    }

    // ── buildEventMap ────────────────────────────────────────────────

    @Test
    void testBuildEventMapProducesExpectedKeys() throws Exception {
        StreamEventEntity event = new StreamEventEntity();
        event.id = UUID.randomUUID();
        event.sourceEventId = "gh-12345";
        event.source = "github";
        event.connectionId = "my-org-repo";
        event.type = "issue.created";
        event.ref = "https://github.com/my-org/repo/issues/42";
        event.timestamp = Instant.parse("2026-09-19T10:30:00Z");
        event.actor = "{\"login\":\"octocat\",\"url\":\"https://github.com/octocat\"}";
        event.payload = "{\"title\":\"Bug report\",\"state\":\"open\"}";
        event.createdOn = Instant.now();

        JsonNode payloadNode = objectMapper.readTree(event.payload);
        Map<String, Object> map = orchestrator.buildEventMap(event, payloadNode);

        // Verify all expected keys are present
        assertEquals("issue.created", map.get("type"));
        assertEquals("github", map.get("source"));
        assertEquals("my-org-repo", map.get("connectionId"));
        assertEquals("https://github.com/my-org/repo/issues/42", map.get("ref"));
        assertEquals("2026-09-19T10:30:00Z", map.get("timestamp"));

        // Actor should be parsed into a map
        assertInstanceOf(Map.class, map.get("actor"));
        @SuppressWarnings("unchecked")
        Map<String, Object> actor = (Map<String, Object>) map.get("actor");
        assertEquals("octocat", actor.get("login"));

        // Payload should be parsed into a map
        assertInstanceOf(Map.class, map.get("payload"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) map.get("payload");
        assertEquals("Bug report", payload.get("title"));
        assertEquals("open", payload.get("state"));
    }

    @Test
    void testBuildEventMapWithNullPayload() {
        StreamEventEntity event = new StreamEventEntity();
        event.id = UUID.randomUUID();
        event.sourceEventId = "gh-99";
        event.source = "github";
        event.connectionId = "conn-1";
        event.type = "pr.merged";
        event.ref = "https://github.com/org/repo/pull/5";
        event.timestamp = Instant.parse("2026-09-19T12:00:00Z");
        event.actor = null;
        event.payload = null;
        event.createdOn = Instant.now();

        Map<String, Object> map = orchestrator.buildEventMap(event, null);

        assertEquals("pr.merged", map.get("type"));
        assertEquals("github", map.get("source"));
        assertNotNull(map.get("payload"));
        assertEquals(Map.of(), map.get("payload"));
        // Actor should not be present when null
        assertFalse(map.containsKey("actor"));
    }

    // ── Smoke test: empty stream ─────────────────────────────────────

    @Test
    void testProcessNewEventsOnEmptyStream() {
        // Calling processNewEvents on an empty database should not throw.
        // First call initializes the cursor.
        orchestrator.processNewEvents();
        // Second call actually queries for new events (finds none).
        orchestrator.processNewEvents();
        // If we get here without exceptions, the smoke test passes.
    }

    // ── Smoke test: event with no subscriptions ──────────────────────

    @Test
    void testProcessNewEventsWithNoSubscriptions() {
        // Insert a stream event
        QuarkusTransaction.requiringNew().run(() -> {
            StreamEventEntity event = new StreamEventEntity();
            event.id = UUID.randomUUID();
            event.sourceEventId = "smoke-1";
            event.source = "github";
            event.connectionId = "test-conn";
            event.type = "issue.created";
            event.ref = "https://github.com/org/repo/issues/1";
            event.timestamp = Instant.now();
            event.actor = "{\"login\":\"tester\"}";
            event.payload = "{\"title\":\"Test\"}";
            event.createdOn = Instant.now();
            event.persist();
        });

        // Initialize cursor (first call) - this will set cursor to the event's time
        orchestrator.processNewEvents();
        // Second call processes events after cursor - nothing new, should not crash
        orchestrator.processNewEvents();
    }
}
