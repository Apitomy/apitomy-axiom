package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the curated event-map shape produced by {@link WorkflowEventMapper}
 * for engine matching and workflow-context merging.
 */
class WorkflowEventMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsAllFieldsIncludingParsedPayload() {
        StreamEventEntity event = new StreamEventEntity();
        event.type = "pr-merged";
        event.source = "github";
        event.connectionId = "conn-1";
        event.ref = "acme/widget#42";
        event.timestamp = Instant.parse("2026-09-16T12:00:00Z");
        event.payload = "{\"number\": 42, \"action\": \"closed\"}";

        Map<String, Object> map = WorkflowEventMapper.toEventMap(event, objectMapper);

        assertEquals("pr-merged", map.get("type"));
        assertEquals("github", map.get("source"));
        assertEquals("conn-1", map.get("connectionId"));
        assertEquals("acme/widget#42", map.get("ref"));
        assertEquals("2026-09-16T12:00:00Z", map.get("timestamp"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) map.get("payload");
        assertEquals(42, payload.get("number"));
        assertEquals("closed", payload.get("action"));
    }

    @Test
    void nullPayloadYieldsEmptyPayloadMap() {
        StreamEventEntity event = new StreamEventEntity();
        event.type = "issue-created";
        event.source = "github";
        event.connectionId = "conn-1";
        event.ref = "test";
        event.timestamp = Instant.now();
        event.payload = null;

        Map<String, Object> map = WorkflowEventMapper.toEventMap(event, objectMapper);

        assertEquals(Map.of(), map.get("payload"));
    }

    @Test
    void unparseablePayloadYieldsEmptyPayloadMap() {
        StreamEventEntity event = new StreamEventEntity();
        event.type = "issue-created";
        event.source = "github";
        event.connectionId = "conn-1";
        event.ref = "test";
        event.timestamp = Instant.now();
        event.payload = "not json at all {{{";

        Map<String, Object> map = WorkflowEventMapper.toEventMap(event, objectMapper);

        assertEquals(Map.of(), map.get("payload"));
    }

    @Test
    void allExpectedKeysArePresent() {
        StreamEventEntity event = new StreamEventEntity();
        event.type = "internal-note";
        event.source = "internal";
        event.connectionId = "conn-1";
        event.ref = "ref-1";
        event.timestamp = Instant.now();

        Map<String, Object> map = WorkflowEventMapper.toEventMap(event, objectMapper);

        assertEquals("internal-note", map.get("type"));
        assertEquals("internal", map.get("source"));
        assertTrue(map.containsKey("connectionId"));
        assertTrue(map.containsKey("ref"));
        assertTrue(map.containsKey("timestamp"));
        assertTrue(map.containsKey("payload"));
    }
}
