package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.EventEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the curated event-map shape produced by {@link WorkflowEventMapper}
 * for engine matching and workflow-context merging.
 */
class WorkflowEventMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsAllFieldsIncludingParsedPayload() {
        EventEntity event = new EventEntity();
        event.eventType = "pr-merged";
        event.source = "github";
        event.issueRef = "acme/widget#42";
        event.repository = "acme/widget";
        event.receivedAt = Instant.parse("2026-09-16T12:00:00Z");
        event.payload = "{\"number\": 42, \"action\": \"closed\"}";

        Map<String, Object> map = WorkflowEventMapper.toEventMap(event, objectMapper);

        assertEquals("pr-merged", map.get("type"));
        assertEquals("github", map.get("source"));
        assertEquals("acme/widget#42", map.get("issueRef"));
        assertEquals("acme/widget", map.get("repository"));
        assertEquals("2026-09-16T12:00:00Z", map.get("receivedAt"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) map.get("payload");
        assertEquals(42, payload.get("number"));
        assertEquals("closed", payload.get("action"));
    }

    @Test
    void nullPayloadYieldsEmptyPayloadMap() {
        EventEntity event = new EventEntity();
        event.eventType = "issue-created";
        event.source = "github";
        event.payload = null;

        Map<String, Object> map = WorkflowEventMapper.toEventMap(event, objectMapper);

        assertEquals(Map.of(), map.get("payload"));
    }

    @Test
    void unparseablePayloadYieldsEmptyPayloadMap() {
        EventEntity event = new EventEntity();
        event.eventType = "issue-created";
        event.source = "github";
        event.payload = "not json at all {{{";

        Map<String, Object> map = WorkflowEventMapper.toEventMap(event, objectMapper);

        assertEquals(Map.of(), map.get("payload"));
    }

    @Test
    void nullOptionalFieldsAreOmitted() {
        EventEntity event = new EventEntity();
        event.eventType = "internal-note";
        event.source = "internal";
        // issueRef, repository, receivedAt left null

        Map<String, Object> map = WorkflowEventMapper.toEventMap(event, objectMapper);

        assertEquals("internal-note", map.get("type"));
        assertFalse(map.containsKey("issueRef"));
        assertFalse(map.containsKey("repository"));
        assertFalse(map.containsKey("receivedAt"));
        assertTrue(map.containsKey("payload"));
    }
}
