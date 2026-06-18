package io.apitomy.axiom.events.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PR event type determination logic in {@link GitHubPoller}.
 */
class GitHubPollerPrEventTypeTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GitHubPoller poller = new GitHubPoller();

    private final Instant since = Instant.parse("2025-06-01T00:00:00Z");
    private static final String AFTER = "2025-06-02T00:00:00Z";
    private static final String BEFORE = "2025-05-31T00:00:00Z";

    @Test
    void prCreatedAfterSince() {
        ObjectNode pr = mapper.createObjectNode();
        pr.put("state", "open");
        pr.put("created_at", AFTER);
        pr.put("updated_at", AFTER);
        assertEquals("pr-created", poller.determinePrEventType(pr, since));
    }

    @Test
    void prMerged() {
        ObjectNode pr = mapper.createObjectNode();
        pr.put("state", "closed");
        pr.put("created_at", BEFORE);
        pr.put("updated_at", AFTER);
        pr.put("closed_at", AFTER);
        pr.put("merged_at", AFTER);
        assertEquals("pr-merged", poller.determinePrEventType(pr, since));
    }

    @Test
    void prClosedNotMerged() {
        ObjectNode pr = mapper.createObjectNode();
        pr.put("state", "closed");
        pr.put("created_at", BEFORE);
        pr.put("updated_at", AFTER);
        pr.put("closed_at", AFTER);
        assertEquals("pr-closed", poller.determinePrEventType(pr, since));
    }

    @Test
    void prReopened() {
        ObjectNode pr = mapper.createObjectNode();
        pr.put("state", "open");
        pr.put("created_at", BEFORE);
        pr.put("updated_at", AFTER);
        pr.put("closed_at", BEFORE);
        assertEquals("pr-reopened", poller.determinePrEventType(pr, since));
    }

    @Test
    void prUpdated() {
        ObjectNode pr = mapper.createObjectNode();
        pr.put("state", "open");
        pr.put("created_at", BEFORE);
        pr.put("updated_at", AFTER);
        assertEquals("pr-updated", poller.determinePrEventType(pr, since));
    }

    @Test
    void prNotUpdatedSincePollReturnsNull() {
        ObjectNode pr = mapper.createObjectNode();
        pr.put("state", "open");
        pr.put("created_at", BEFORE);
        pr.put("updated_at", BEFORE);
        assertNull(poller.determinePrEventType(pr, since));
    }

    @Test
    void nullSinceReturnsNull() {
        ObjectNode pr = mapper.createObjectNode();
        pr.put("state", "open");
        pr.put("created_at", AFTER);
        pr.put("updated_at", AFTER);
        assertNull(poller.determinePrEventType(pr, null));
    }

    @Test
    void mergedTakesPriorityOverClosed() {
        ObjectNode pr = mapper.createObjectNode();
        pr.put("state", "closed");
        pr.put("created_at", BEFORE);
        pr.put("updated_at", AFTER);
        pr.put("closed_at", AFTER);
        pr.put("merged_at", AFTER);
        assertEquals("pr-merged", poller.determinePrEventType(pr, since));
    }

    @Test
    void createdTakesPriorityOverMerged() {
        ObjectNode pr = mapper.createObjectNode();
        pr.put("state", "closed");
        pr.put("created_at", AFTER);
        pr.put("updated_at", AFTER);
        pr.put("closed_at", AFTER);
        pr.put("merged_at", AFTER);
        assertEquals("pr-created", poller.determinePrEventType(pr, since));
    }
}
