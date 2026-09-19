package io.apitomy.axiom.events.jira.v2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for {@link JiraConnectionPoller}.
 */
class JiraConnectionPollerTest {

    @Test
    void canInstantiate() {
        JiraConnectionPoller poller = new JiraConnectionPoller();
        assertNotNull(poller);
    }
}
