package io.apitomy.axiom.core.events.model.payloads;

import io.apitomy.axiom.core.events.model.Commit;

import java.util.List;

/** Payload for {@code push} events. */
public record PushPayload(
        String ref,
        String branch,
        String beforeSha,
        String afterSha,
        Boolean forced,
        List<Commit> commits
) implements EventPayload {
}
