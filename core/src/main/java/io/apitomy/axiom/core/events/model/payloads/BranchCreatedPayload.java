package io.apitomy.axiom.core.events.model.payloads;
/** Payload for {@code branch.created} events. */
public record BranchCreatedPayload(String ref, String defaultBranch) implements EventPayload {
}
