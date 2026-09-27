package io.apitomy.axiom.core.events.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.events.model.payloads.EventPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.PrMergedPayload;
import io.apitomy.axiom.core.events.model.payloads.PushPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventPayloadSerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Test
    void issueCreatedPayloadRoundTrips() throws Exception {
        NormalizedIssue issue = new NormalizedIssue(
                "42", "Fix login bug", "The login form crashes on submit",
                "open", null,
                new Actor("octocat", "Octocat", null, "https://github.com/octocat"),
                List.of(), List.of("bug"), null,
                "https://github.com/owner/repo/issues/42",
                "2026-01-15T10:00:00Z", "2026-01-15T10:00:00Z", null
        );
        IssueCreatedPayload original = new IssueCreatedPayload(issue);

        String json = mapper.writeValueAsString(original);

        // Verify the type discriminator is present
        assertTrue(json.contains("\"type\":\"issue.created\""),
                "JSON should contain type discriminator; got: " + json);

        // Deserialize via the sealed interface to exercise polymorphism
        EventPayload deserialized = mapper.readValue(json, EventPayload.class);
        assertInstanceOf(IssueCreatedPayload.class, deserialized);

        IssueCreatedPayload result = (IssueCreatedPayload) deserialized;
        assertEquals("42", result.issue().number());
        assertEquals("Fix login bug", result.issue().title());
        assertEquals("octocat", result.issue().author().login());
    }

    @Test
    void pushPayloadRoundTrips() throws Exception {
        Commit commit = new Commit(
                "abc123def456", "feat: add new endpoint",
                new Commit.CommitAuthor("Dev User", "dev@example.com"),
                "https://github.com/owner/repo/commit/abc123def456"
        );
        PushPayload original = new PushPayload(
                "refs/heads/main", "main",
                "000000", "abc123def456",
                false, List.of(commit)
        );

        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"type\":\"push\""),
                "JSON should contain type discriminator; got: " + json);

        EventPayload deserialized = mapper.readValue(json, EventPayload.class);
        assertInstanceOf(PushPayload.class, deserialized);

        PushPayload result = (PushPayload) deserialized;
        assertEquals("main", result.branch());
        assertEquals("abc123def456", result.afterSha());
        assertEquals(1, result.commits().size());
        assertEquals("abc123def456", result.commits().get(0).sha());
        assertEquals("Dev User", result.commits().get(0).author().name());
    }

    @Test
    void prMergedPayloadRoundTrips() throws Exception {
        NormalizedPullRequest pr = new NormalizedPullRequest(
                "99", "Add user profiles", "Implements user profile pages",
                "closed", null,
                new Actor("contributor", "Contributor", null, null),
                List.of(), List.of("enhancement"), null,
                "https://github.com/owner/repo/pull/99",
                "2026-02-01T09:00:00Z", "2026-02-05T14:30:00Z", "2026-02-05T14:30:00Z",
                "feature/profiles", "main", "def789",
                false, true, "2026-02-05T14:30:00Z",
                new Actor("maintainer", "Maintainer", null, null),
                150, 20, 5
        );
        PrMergedPayload original = new PrMergedPayload(pr);

        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"type\":\"pr.merged\""),
                "JSON should contain type discriminator; got: " + json);

        EventPayload deserialized = mapper.readValue(json, EventPayload.class);
        assertInstanceOf(PrMergedPayload.class, deserialized);

        PrMergedPayload result = (PrMergedPayload) deserialized;
        assertEquals("99", result.pullRequest().number());
        assertTrue(result.pullRequest().isMerged());
        assertEquals("maintainer", result.pullRequest().mergedBy().login());
    }

    @Test
    void deserializationFromRawJsonProducesCorrectType() throws Exception {
        String json = """
                {
                  "type": "issue.created",
                  "issue": {
                    "number": "7",
                    "title": "Raw JSON test",
                    "state": "open",
                    "author": { "login": "testuser" },
                    "assignees": [],
                    "labels": [],
                    "url": "https://github.com/owner/repo/issues/7",
                    "createdAt": "2026-03-01T00:00:00Z",
                    "updatedAt": "2026-03-01T00:00:00Z"
                  }
                }
                """;

        EventPayload payload = mapper.readValue(json, EventPayload.class);
        assertInstanceOf(IssueCreatedPayload.class, payload);

        IssueCreatedPayload result = (IssueCreatedPayload) payload;
        assertEquals("7", result.issue().number());
        assertEquals("Raw JSON test", result.issue().title());
        assertEquals("testuser", result.issue().author().login());
    }
}
