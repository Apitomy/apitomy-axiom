package io.apitomy.axiom.events.jira.v2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for {@link JiraEventSourcePoller}.
 */
class JiraEventSourcePollerTest {

    @Test
    void canInstantiate() {
        JiraEventSourcePoller poller = new JiraEventSourcePoller();
        assertNotNull(poller);
    }
}
