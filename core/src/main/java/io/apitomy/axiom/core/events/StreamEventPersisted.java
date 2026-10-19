package io.apitomy.axiom.core.events;

/**
 * CDI event fired in the transaction that persisted a new stream event. Observed after a
 * successful commit by {@link StreamEventSseNotifier}, which notifies SSE clients.
 *
 * @param eventId       the stream event UUID
 * @param eventType     the normalized event type
 * @param eventSourceId the event source id that produced the event
 */
public record StreamEventPersisted(String eventId, String eventType, String eventSourceId) {
}
