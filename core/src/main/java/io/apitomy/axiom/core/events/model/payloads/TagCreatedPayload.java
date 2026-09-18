package io.apitomy.axiom.core.events.model.payloads;
/** Payload for {@code tag.created} events. */
public record TagCreatedPayload(String ref, String defaultBranch) implements EventPayload {
}
