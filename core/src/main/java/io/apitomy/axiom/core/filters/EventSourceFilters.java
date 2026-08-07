package io.apitomy.axiom.core.filters;

import java.util.List;

/**
 * Include/exclude filter configuration for an event source.
 *
 * @param include rules for events to include (empty list means allow all)
 * @param exclude rules for events to exclude (applied after include)
 */
public record EventSourceFilters(List<EventSourceFilterRule> include,
                                  List<EventSourceFilterRule> exclude) {

    /**
     * Returns an empty filters instance that allows all events.
     */
    public static EventSourceFilters allowAll() {
        return new EventSourceFilters(List.of(), List.of());
    }
}
