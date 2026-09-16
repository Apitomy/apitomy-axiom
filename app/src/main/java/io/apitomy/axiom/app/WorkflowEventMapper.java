package io.apitomy.axiom.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.EventEntity;

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
     * Maps an event entity to the curated event map.
     *
     * @param event        the event to map
     * @param objectMapper used to parse the raw JSON payload
     * @return a map with keys {@code type}, {@code source}, {@code payload}
     *         (always present; payload is an empty map when null/unparseable)
     *         and {@code issueRef}, {@code repository}, {@code receivedAt}
     *         (present only when non-null on the entity)
     */
    public static Map<String, Object> toEventMap(EventEntity event,
            ObjectMapper objectMapper) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", event.eventType);
        map.put("source", event.source);
        if (event.issueRef != null) {
            map.put("issueRef", event.issueRef);
        }
        if (event.repository != null) {
            map.put("repository", event.repository);
        }
        if (event.receivedAt != null) {
            map.put("receivedAt", event.receivedAt.toString());
        }
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
