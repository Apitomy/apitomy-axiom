package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.events.core.ApiResult;
import io.apitomy.axiom.events.core.EventStreamService;
import io.apitomy.axiom.events.core.PersistOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JiraEventSourcePoller} checkpoint and pagination behaviour,
 * using a controlled in-memory Jira API stub and event stream.
 */
class JiraEventSourcePollerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SOURCE_ID = "jira-test";
    private static final String BASE_URL = "https://example.atlassian.net";
    private static final Instant SINCE = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant WINDOW_START = Instant.parse("2026-10-01T10:05:00Z");

    private StubApiClient api;
    private RecordingStream stream;
    private JiraEventSourcePoller poller;

    @BeforeEach
    void setUp() {
        api = new StubApiClient();
        stream = new RecordingStream();
        poller = new JiraEventSourcePoller();
        poller.apiClient = api;
        poller.normalizer = new ValidatingJiraNormalizer(MAPPER);
        poller.eventStreamService = stream;
    }

    @Test
    void canInstantiate() {
        assertNotNull(new JiraEventSourcePoller());
    }

    @Test
    void apiFailure_doesNotAdvanceCheckpoint() {
        api.pages.add(ApiResult.httpError(503, "POST", "url", null, "down"));

        JiraEventSourcePoller.WindowResult result = ingest();

        assertFalse(result.complete());
        assertNull(result.checkpoint());
        assertNotNull(result.errorMessage());
    }

    @Test
    void multiplePages_allIssuesIngested_checkpointIsWindowStart() {
        api.pages.add(page(issues(0, 100, "2026-10-01T10:01:00.000+0000"), "tok2"));
        api.pages.add(page(issues(100, 150, "2026-10-01T10:02:00.000+0000"), null));

        JiraEventSourcePoller.WindowResult result = ingest();

        assertTrue(result.complete());
        assertEquals(WINDOW_START, result.checkpoint());
        assertEquals(150, result.issuesProcessed());
        assertEquals(150, stream.ids.size());
        assertEquals(List.of("<first>", "tok2"), api.tokens);
    }

    @Test
    void laterPageFailure_doesNotAdvance_andRetryIngestsWithoutDuplicates() {
        api.pages.add(page(issues(0, 100, "2026-10-01T10:01:00.000+0000"), "tok2"));
        api.pages.add(ApiResult.httpError(500, "POST", "url", null, "boom"));

        JiraEventSourcePoller.WindowResult failed = ingest();
        assertFalse(failed.complete());
        assertNull(failed.checkpoint());

        api.pages.add(page(issues(0, 100, "2026-10-01T10:01:00.000+0000"), "tok2"));
        api.pages.add(page(issues(100, 150, "2026-10-01T10:02:00.000+0000"), null));
        JiraEventSourcePoller.WindowResult retried = ingest();

        assertTrue(retried.complete());
        assertEquals(150, stream.ids.size());
        assertEquals(50, retried.eventsIngested());
    }

    @Test
    void shutdownMidIngestion_doesNotAdvanceCheckpoint() {
        api.pages.add(page(issues(0, 10, "2026-10-01T10:01:00.000+0000"), null));
        stream.onPersist = () -> poller.onShutdown();

        JiraEventSourcePoller.WindowResult result = ingest();

        assertFalse(result.complete());
        assertNull(result.checkpoint());
        assertEquals(1, stream.ids.size());
    }

    @Test
    void persistFailure_doesNotAdvanceCheckpoint() {
        api.pages.add(page(issues(0, 3, "2026-10-01T10:01:00.000+0000"), null));
        stream.failOn = "P-1-created";

        JiraEventSourcePoller.WindowResult result = ingest();

        assertFalse(result.complete());
        assertNull(result.checkpoint());
    }

    @Test
    void pageCapHit_doesNotAdvanceCheckpoint() {
        poller.maxSearchPages = 2;
        api.pages.add(page(issues(0, 2, "2026-10-01T10:01:00.000+0000"), "tok2"));
        api.pages.add(page(issues(2, 4, "2026-10-01T10:02:00.000+0000"), "tok3"));
        api.pages.add(page(issues(4, 6, "2026-10-01T10:03:00.000+0000"), null));

        JiraEventSourcePoller.WindowResult result = ingest();

        assertFalse(result.complete());
        assertNull(result.checkpoint());
        assertTrue(result.errorMessage().contains("cap"));
        assertEquals(4, stream.ids.size());
    }

    @Test
    void defaultPageCap_isGenerous() {
        assertEquals(200, new JiraEventSourcePoller().maxSearchPages);
    }

    @Test
    void changelogNotFound_usesEmbeddedChangelogAndCompletes() {
        api.pages.add(page(List.of(truncatedIssue("P-9")), null));
        api.changelogs.put("P-9", ApiResult.httpError(404, "GET", "url", null, "gone"));

        JiraEventSourcePoller.WindowResult result = ingest();

        assertTrue(result.complete());
        assertEquals(WINDOW_START, result.checkpoint());
        assertEquals(Set.of("P-9-1"), stream.ids);
    }

    @Test
    void changelogForbidden_usesEmbeddedChangelogAndCompletes() {
        api.pages.add(page(List.of(truncatedIssue("P-9")), null));
        api.changelogs.put("P-9", ApiResult.httpError(403, "GET", "url", null, "no"));

        JiraEventSourcePoller.WindowResult result = ingest();

        assertTrue(result.complete());
        assertEquals(Set.of("P-9-1"), stream.ids);
    }

    @Test
    void changelogServerError_abortsWindow() {
        api.pages.add(page(List.of(truncatedIssue("P-9")), null));
        api.changelogs.put("P-9", ApiResult.httpError(503, "GET", "url", null, "down"));

        JiraEventSourcePoller.WindowResult result = ingest();

        assertFalse(result.complete());
        assertNull(result.checkpoint());
    }

    @Test
    void changelogRateLimited_abortsWindow() {
        api.pages.add(page(List.of(truncatedIssue("P-9")), null));
        api.changelogs.put("P-9", ApiResult.httpError(429, "GET", "url", null, "slow down"));

        JiraEventSourcePoller.WindowResult result = ingest();

        assertFalse(result.complete());
        assertNull(result.checkpoint());
    }

    @Test
    void changelogNetworkError_abortsWindow() {
        api.pages.add(page(List.of(truncatedIssue("P-9")), null));
        api.changelogs.put("P-9", ApiResult.exception(new java.io.IOException("reset"), "GET", "url", null));

        JiraEventSourcePoller.WindowResult result = ingest();

        assertFalse(result.complete());
        assertNull(result.checkpoint());
    }

    @Test
    void notLastPageWithoutToken_completesWindow() {
        ObjectNode data = MAPPER.createObjectNode();
        data.putArray("issues").add(issue("P-1", "2026-10-01T10:01:00.000+0000",
                "2026-10-01T10:01:00.000+0000"));
        data.put("isLast", false);
        api.pages.add(ApiResult.ok(data, "POST", "url", null, null));

        JiraEventSourcePoller.WindowResult result = ingest();

        assertTrue(result.complete());
        assertEquals(Set.of("P-1-created"), stream.ids);
    }

    @Test
    void changeExactlyAtCheckpoint_isIngested() {
        api.pages.add(page(issues(0, 1, "2026-10-01T10:00:00.000+0000"), null));

        ingest();

        assertEquals(Set.of("P-0-created"), stream.ids);
    }

    @Test
    void truncatedChangelog_fetchesFullChangelog() {
        ObjectNode issue = issue("P-9", "2026-09-01T00:00:00.000+0000", "2026-10-01T10:03:00.000+0000");
        ObjectNode changelog = issue.putObject("changelog");
        changelog.put("startAt", 0);
        changelog.put("maxResults", 1);
        changelog.put("total", 2);
        changelog.putArray("histories").add(history("1", "2026-09-02T00:00:00.000+0000"));
        ArrayNode all = MAPPER.createArrayNode();
        all.add(history("1", "2026-09-02T00:00:00.000+0000"));
        all.add(history("2", "2026-10-01T10:03:00.000+0000"));
        api.changelogs.put("P-9", ApiResult.ok(all, "GET", "url", null, null));
        api.pages.add(page(List.of(issue), null));

        JiraEventSourcePoller.WindowResult result = ingest();

        assertTrue(result.complete());
        assertEquals(Set.of("P-9-2"), stream.ids);
    }

    @Test
    void failedAttempt_isNotDueAgainBeforeInterval() {
        EventSourceEntity entity = new EventSourceEntity();
        entity.id = SOURCE_ID;
        entity.pollInterval = 60;
        entity.lastPolledAt = SINCE;
        Instant attempt = SINCE.plusSeconds(120);

        poller.recordAttempt(SOURCE_ID, attempt);

        assertFalse(poller.isDue(entity, attempt.plusSeconds(30)));
        assertTrue(poller.isDue(entity, attempt.plusSeconds(61)));
    }

    // -- helpers ----------------------------------------------------------------

    private JiraEventSourcePoller.WindowResult ingest() {
        return poller.ingestWindow(SOURCE_ID, BASE_URL, List.of("P"), SINCE, "a:b", WINDOW_START);
    }

    private static ApiResult page(List<JsonNode> issues, String nextPageToken) {
        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode arr = data.putArray("issues");
        issues.forEach(arr::add);
        if (nextPageToken != null) {
            data.put("nextPageToken", nextPageToken);
        } else {
            data.put("isLast", true);
        }
        return ApiResult.ok(data, "POST", "url", null, null);
    }

    private static List<JsonNode> issues(int from, int to, String timestamp) {
        List<JsonNode> list = new ArrayList<>();
        for (int i = from; i < to; i++) {
            list.add(issue("P-" + i, timestamp, timestamp));
        }
        return list;
    }

    private static ObjectNode issue(String key, String created, String updated) {
        ObjectNode issue = MAPPER.createObjectNode();
        issue.put("id", key);
        issue.put("key", key);
        ObjectNode fields = issue.putObject("fields");
        fields.put("summary", "Summary " + key);
        fields.put("created", created);
        fields.put("updated", updated);
        ObjectNode status = fields.putObject("status");
        status.put("name", "To Do");
        status.putObject("statusCategory").put("key", "new");
        fields.putObject("reporter").put("accountId", "r").put("displayName", "r");
        fields.putArray("labels");
        fields.putNull("assignee");
        return issue;
    }

    private static ObjectNode truncatedIssue(String key) {
        ObjectNode issue = issue(key, "2026-09-01T00:00:00.000+0000", "2026-10-01T10:03:00.000+0000");
        ObjectNode changelog = issue.putObject("changelog");
        changelog.put("startAt", 0);
        changelog.put("maxResults", 1);
        changelog.put("total", 2);
        changelog.putArray("histories").add(history("1", "2026-10-01T10:03:00.000+0000"));
        return issue;
    }

    private static ObjectNode history(String id, String created) {
        ObjectNode history = MAPPER.createObjectNode();
        history.put("id", id);
        history.put("created", created);
        history.putObject("author").put("accountId", "a").put("displayName", "a");
        ObjectNode item = history.putArray("items").addObject();
        item.put("field", "summary");
        item.put("fromString", "old");
        item.put("toString", "new " + id);
        return history;
    }

    /** Jira API stub serving queued search pages and per-issue changelogs. */
    static class StubApiClient extends JiraEventsApiClient {
        final Deque<ApiResult> pages = new ArrayDeque<>();
        final Map<String, ApiResult> changelogs = new HashMap<>();
        final List<String> tokens = new ArrayList<>();

        @Override
        public ApiResult fetchIssuesPage(String baseUrl, List<String> projects, Instant since,
                                         String credentials, String nextPageToken) {
            tokens.add(nextPageToken == null ? "<first>" : nextPageToken);
            return pages.removeFirst();
        }

        @Override
        public ApiResult fetchFullChangelog(String baseUrl, String issueKey, String credentials) {
            return changelogs.get(issueKey);
        }
    }

    /** In-memory event stream that dedupes by sourceEventId like the real service. */
    static class RecordingStream extends EventStreamService {
        final Set<String> ids = new LinkedHashSet<>();
        int attempts;
        String failOn;
        Runnable onPersist;

        @Override
        public PersistOutcome persistEvent(NormalizedEvent event) {
            attempts++;
            if (event.sourceEventId().equals(failOn)) {
                throw new IllegalStateException("db down");
            }
            boolean added = ids.add(event.sourceEventId());
            if (onPersist != null) {
                onPersist.run();
            }
            return added ? PersistOutcome.PERSISTED : PersistOutcome.DUPLICATE;
        }
    }
}
