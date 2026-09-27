package io.apitomy.axiom.core.events.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A label attached to an issue or pull request.
 *
 * @param name        label name
 * @param color       hex color code (nullable, GitHub only)
 * @param description label description (nullable, GitHub only)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedLabel(
        String name,
        String color,
        String description
) {
}
