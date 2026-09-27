package io.apitomy.axiom.core.events.model.payloads;
/** Payload for {@code branch.deleted} events. */
public record BranchDeletedPayload(String ref) implements EventPayload {
}
