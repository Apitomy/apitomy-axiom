package io.apitomy.axiom.core.events.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.apitomy.axiom.core.events.model.payloads.EventPayload;

/**
 * The complete event envelope for a normalized event in the event stream.
 * Every event produced by an EventSourceConnection is wrapped in this envelope.
 *
 * @param id            system-generated unique event ID (UUID string)
 * @param sourceEventId original ID from the source system, used for deduplication
 * @param source        source system identifier: "github" or "jira"
 * @param connectionId  slug of the EventSourceConnection that produced this event
 * @param type          normalized event type
 * @param ref           full URL uniquely identifying the subject of the event
 * @param timestamp     when the event occurred in the source system (ISO-8601)
 * @param actor         who performed the action
 * @param payload       typed payload, schema determined by the event type
 * @param sourceData    raw source-specific data escape hatch (nullable)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedEvent(
        String id,
        String sourceEventId,
        String source,
        String connectionId,
        EventType type,
        String ref,
        String timestamp,
        Actor actor,
        EventPayload payload,
        JsonNode sourceData
) {
}
