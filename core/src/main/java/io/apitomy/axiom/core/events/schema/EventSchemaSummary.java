package io.apitomy.axiom.core.events.schema;

/**
 * Summary of one event type's schema.
 *
 * @param type        the event type, e.g. {@code issue.closed}
 * @param description human-readable description of the event type
 */
public record EventSchemaSummary(String type, String description) {
}
