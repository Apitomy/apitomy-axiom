package io.apitomy.axiom.core.filters;

/**
 * A single filter rule that matches either an event type or a payload field value.
 *
 * @param type    "event-type" or "payload"
 * @param pointer JSON Pointer (RFC 6901) path into the payload; required when type is "payload"
 * @param pattern glob pattern with * and ? wildcards
 */
public record EventSourceFilterRule(String type, String pointer, String pattern) {
}
