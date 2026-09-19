package io.apitomy.axiom.events.jira.v2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Basic unit test for {@link JiraEventsApiClient}.
 * Verifies the client can be instantiated.
 */
class JiraEventsApiClientTest {

    @Test
    void clientCanBeInstantiated() {
        JiraEventsApiClient client = new JiraEventsApiClient();
        assertNotNull(client);
    }
}
