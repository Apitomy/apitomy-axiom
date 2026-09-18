package io.apitomy.axiom.core.events.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.events.model.payloads.IssueCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.PushPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NormalizedEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void issueCreatedEventRoundTrip() throws Exception {
        Actor author = new Actor("octocat", "The Octocat", "https://avatars.githubusercontent.com/u/583231", "https://github.com/octocat");
        NormalizedIssue issue = new NormalizedIssue(
                "42",
                "Found a bug",
                "Something is broken",
                "open",
                null,
                author,
                List.of(),
                List.of("bug", "urgent"),
                null,
                "https://github.com/owner/repo/issues/42",
                "2026-09-18T10:00:00Z",
                "2026-09-18T10:00:00Z",
                null
        );
        IssueCreatedPayload payload = new IssueCreatedPayload(issue);

        String eventId = UUID.randomUUID().toString();
        NormalizedEvent event = new NormalizedEvent(
                eventId,
                "gh-event-123456",
                "github",
                "github-com",
                EventType.ISSUE_CREATED,
                "https://github.com/owner/repo/issues/42",
                "2026-09-18T10:00:00Z",
                author,
                payload,
                null
        );

        // Serialize to JSON
        String json = mapper.writeValueAsString(event);

        // Verify key fields are present in JSON
        assertTrue(json.contains("\"id\":\"" + eventId + "\""), "JSON should contain id");
        assertTrue(json.contains("\"sourceEventId\":\"gh-event-123456\""), "JSON should contain sourceEventId");
        assertTrue(json.contains("\"source\":\"github\""), "JSON should contain source");
        assertTrue(json.contains("\"connectionId\":\"github-com\""), "JSON should contain connectionId");
        assertTrue(json.contains("\"issue.created\""), "JSON should contain type as issue.created");
        assertTrue(json.contains("\"ref\":\"https://github.com/owner/repo/issues/42\""), "JSON should contain ref URL");
        assertTrue(json.contains("\"timestamp\":\"2026-09-18T10:00:00Z\""), "JSON should contain timestamp");
        assertTrue(json.contains("\"actor\""), "JSON should contain actor");
        assertTrue(json.contains("\"Found a bug\""), "JSON should contain issue title in nested payload");

        // Deserialize back
        NormalizedEvent deserialized = mapper.readValue(json, NormalizedEvent.class);

        // Verify envelope fields
        assertEquals(eventId, deserialized.id());
        assertEquals("gh-event-123456", deserialized.sourceEventId());
        assertEquals("github", deserialized.source());
        assertEquals("github-com", deserialized.connectionId());
        assertEquals(EventType.ISSUE_CREATED, deserialized.type());
        assertEquals("https://github.com/owner/repo/issues/42", deserialized.ref());
        assertEquals("2026-09-18T10:00:00Z", deserialized.timestamp());
        assertEquals("octocat", deserialized.actor().login());
        assertEquals("The Octocat", deserialized.actor().displayName());

        // Verify payload type
        assertInstanceOf(IssueCreatedPayload.class, deserialized.payload());
        IssueCreatedPayload deserializedPayload = (IssueCreatedPayload) deserialized.payload();
        assertEquals("Found a bug", deserializedPayload.issue().title());
        assertEquals("42", deserializedPayload.issue().number());
        assertEquals("open", deserializedPayload.issue().state());
        assertEquals(List.of("bug", "urgent"), deserializedPayload.issue().labels());
    }

    @Test
    void sourceDataNullOmittedFromJson() throws Exception {
        NormalizedEvent event = new NormalizedEvent(
                UUID.randomUUID().toString(),
                "src-1",
                "github",
                "github-com",
                EventType.ISSUE_CLOSED,
                "https://github.com/owner/repo/issues/1",
                "2026-09-18T12:00:00Z",
                new Actor("user1", null, null, null),
                new IssueCreatedPayload(new NormalizedIssue(
                        "1", "Title", null, "closed", "completed",
                        new Actor("user1", null, null, null),
                        List.of(), List.of(), null,
                        "https://github.com/owner/repo/issues/1",
                        "2026-09-18T10:00:00Z", "2026-09-18T12:00:00Z", "2026-09-18T12:00:00Z"
                )),
                null
        );

        String json = mapper.writeValueAsString(event);
        assertFalse(json.contains("sourceData"), "sourceData should be omitted when null");

        NormalizedEvent deserialized = mapper.readValue(json, NormalizedEvent.class);
        assertNull(deserialized.sourceData());
    }

    @Test
    void sourceDataIncludedWhenPresent() throws Exception {
        ObjectNode sourceData = mapper.createObjectNode();
        sourceData.put("githubEventId", "99999");
        sourceData.put("githubEventType", "IssuesEvent");

        NormalizedEvent event = new NormalizedEvent(
                UUID.randomUUID().toString(),
                "src-2",
                "github",
                "github-com",
                EventType.ISSUE_CREATED,
                "https://github.com/owner/repo/issues/5",
                "2026-09-18T14:00:00Z",
                new Actor("dev", null, null, null),
                new IssueCreatedPayload(new NormalizedIssue(
                        "5", "With source data", null, "open", null,
                        new Actor("dev", null, null, null),
                        List.of(), List.of(), null,
                        "https://github.com/owner/repo/issues/5",
                        "2026-09-18T14:00:00Z", "2026-09-18T14:00:00Z", null
                )),
                sourceData
        );

        String json = mapper.writeValueAsString(event);
        assertTrue(json.contains("\"sourceData\""), "sourceData should be present when non-null");
        assertTrue(json.contains("\"githubEventId\":\"99999\""), "sourceData should contain the raw data");

        NormalizedEvent deserialized = mapper.readValue(json, NormalizedEvent.class);
        assertNotNull(deserialized.sourceData());
        assertEquals("99999", deserialized.sourceData().get("githubEventId").asText());
    }

    @Test
    void pushEventRoundTrip() throws Exception {
        Commit commit1 = new Commit(
                "abc123def456",
                "Fix the widget",
                new Commit.CommitAuthor("Alice", "alice@example.com"),
                "https://github.com/owner/repo/commit/abc123def456"
        );
        Commit commit2 = new Commit(
                "789xyz000111",
                "Update tests",
                new Commit.CommitAuthor("Bob", "bob@example.com"),
                "https://github.com/owner/repo/commit/789xyz000111"
        );

        PushPayload pushPayload = new PushPayload(
                "refs/heads/main",
                "main",
                "aaa111",
                "bbb222",
                false,
                List.of(commit1, commit2)
        );

        Actor pusher = new Actor("alice", "Alice Dev", null, "https://github.com/alice");
        String eventId = UUID.randomUUID().toString();
        NormalizedEvent event = new NormalizedEvent(
                eventId,
                "gh-push-789",
                "github",
                "github-com",
                EventType.PUSH,
                "https://github.com/owner/repo",
                "2026-09-18T15:30:00Z",
                pusher,
                pushPayload,
                null
        );

        // Serialize
        String json = mapper.writeValueAsString(event);
        assertTrue(json.contains("\"push\""), "JSON should contain type as push");
        assertTrue(json.contains("\"refs/heads/main\""), "JSON should contain ref in payload");
        assertTrue(json.contains("\"main\""), "JSON should contain branch");
        assertTrue(json.contains("\"abc123def456\""), "JSON should contain commit sha");
        assertTrue(json.contains("\"Fix the widget\""), "JSON should contain commit message");

        // Deserialize
        NormalizedEvent deserialized = mapper.readValue(json, NormalizedEvent.class);
        assertEquals(eventId, deserialized.id());
        assertEquals(EventType.PUSH, deserialized.type());
        assertEquals("github-com", deserialized.connectionId());
        assertEquals("https://github.com/owner/repo", deserialized.ref());

        assertInstanceOf(PushPayload.class, deserialized.payload());
        PushPayload deserializedPush = (PushPayload) deserialized.payload();
        assertEquals("refs/heads/main", deserializedPush.ref());
        assertEquals("main", deserializedPush.branch());
        assertEquals("aaa111", deserializedPush.beforeSha());
        assertEquals("bbb222", deserializedPush.afterSha());
        assertFalse(deserializedPush.forced());
        assertEquals(2, deserializedPush.commits().size());
        assertEquals("Fix the widget", deserializedPush.commits().get(0).message());
        assertEquals("Alice", deserializedPush.commits().get(0).author().name());
        assertEquals("bob@example.com", deserializedPush.commits().get(1).author().email());
    }
}
