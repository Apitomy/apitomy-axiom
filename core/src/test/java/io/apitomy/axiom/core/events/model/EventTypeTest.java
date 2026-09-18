package io.apitomy.axiom.core.events.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class EventTypeTest {

    @ParameterizedTest
    @EnumSource(EventType.class)
    void fromStringRoundTrips(EventType type) {
        assertEquals(type, EventType.fromString(type.value()));
    }

    @Test
    void fromStringThrowsForUnknown() {
        assertThrows(IllegalArgumentException.class, () -> EventType.fromString("unknown.event"));
    }

    @Test
    void spotCheckValues() {
        assertEquals("issue.created", EventType.ISSUE_CREATED.value());
        assertEquals("pr.merged", EventType.PR_MERGED.value());
        assertEquals("push", EventType.PUSH.value());
        assertEquals("release.published", EventType.RELEASE_PUBLISHED.value());
    }

    @Test
    void allTypesHaveDotNotationExceptPush() {
        for (EventType type : EventType.values()) {
            if (type == EventType.PUSH) {
                assertFalse(type.value().contains("."), "push should have no dot");
            } else {
                assertTrue(type.value().contains("."),
                        type.name() + " should use dot notation but was: " + type.value());
            }
        }
    }
}
