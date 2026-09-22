package io.apitomy.axiom.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.StreamEventEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds the curated event map used for receive-event node matching and for
 * merging into workflow context. The map's {@code type} key is what the Flow
 * engine's {@code matchesEvent} compares against a node's configured
 * {@code eventType}; the whole map is available to {@code match} EL
 * expressions and, on match, becomes the node's {@code event} output.
 */
public final class WorkflowEventMapper {

    private WorkflowEventMapper() {
    }

    /**
     * Maps a stream event entity to the curated event map for workflow matching.
     * The map structure is compatible with the Flow engine's EL evaluation.
     *
     * @param event        the stream event entity
     * @param objectMapper used to parse JSON fields
     * @return a map with keys: type, source, connectionId, ref, timestamp,
     *         actor, payload
     */
    public static Map<String, Object> toEventMap(StreamEventEntity event,
            ObjectMapper objectMapper) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", event.type);
        map.put("source", event.source);
        map.put("connectionId", event.connectionId);
        map.put("ref", event.ref);
        map.put("timestamp", event.timestamp.toString());

        // Parse actor JSON
        if (event.actor != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> actor = objectMapper.readValue(event.actor, Map.class);
                map.put("actor", actor);
            } catch (JsonProcessingException e) {
                map.put("actor", Map.of());
            }
        }

        // Parse payload JSON
        map.put("payload", parsePayload(event.payload, objectMapper));
        return map;
    }

    private static Map<String, Object> parsePayload(String payload,
            ObjectMapper objectMapper) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(payload, Map.class);
            return parsed;
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
