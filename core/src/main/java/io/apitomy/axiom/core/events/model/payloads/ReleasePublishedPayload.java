package io.apitomy.axiom.core.events.model.payloads;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.apitomy.axiom.core.events.model.Actor;

/** Payload for {@code release.published} events. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReleasePublishedPayload(
        String tagName,
        String name,
        String body,
        Boolean isDraft,
        Boolean isPrerelease,
        Actor author,
        String url,
        String createdAt,
        String publishedAt
) implements EventPayload {
}
