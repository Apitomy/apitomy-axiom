package io.apitomy.axiom.events.github.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.events.core.ApiResult;
import io.apitomy.axiom.events.core.EventStreamService;
import io.apitomy.axiom.events.core.IngestTally;
import io.apitomy.axiom.events.core.PersistOutcome;
import io.apitomy.axiom.events.github.v2.GitHubEventsApiClient.EventsPollResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link GitHubEventSourcePoller}: events already in the event stream are skipped before any
 * work is done on them, so a restart does not re-process (or re-backfill) the whole events page.
 */
class GitHubEventSourcePollerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SOURCE_ID = "gh-test";
    private static final String REPO = "owner/repo";

    private StubApiClient api;
    private CountingNormalizer normalizer;
    private RecordingStream stream;
    private GitHubEventSourcePoller poller;

    @BeforeEach
    void setUp() {
        api = new StubApiClient();
        normalizer = new CountingNormalizer();
        stream = new RecordingStream();
        poller = newPoller();
    }

    /** A fresh poller instance stands in for an application restart; only the event stream survives. */
    private GitHubEventSourcePoller newPoller() {
        GitHubEventSourcePoller p = new GitHubEventSourcePoller();
        p.apiClient = api;
        p.normalizer = normalizer;
        p.eventStreamService = stream;
        return p;
    }

    @Test
    void newEventsAreIngested() {
        api.pages.add(EventsPollResult.ok(events(issueOpened("102"), issueOpened("101")), "\"e1\"", 60));

        assertEquals(2, poll());
        assertEquals(List.of("101", "102"), List.copyOf(stream.ids));
    }

    @Test
    void invalidEventsAreCountedForPollLog() {
        stream.invalidIds.add("101");
        api.pages.add(EventsPollResult.ok(events(issueOpened("102"), issueOpened("101")), "\"e1\"", 60));
        IngestTally tally = new IngestTally();

        int ingested = poller.pollRepository(SOURCE_ID, "owner", "repo", "token",
                "https://api.github.com", "https://github.com", new StringBuilder(), tally);

        assertEquals(2, ingested);
        assertEquals(1, tally.invalid());
        assertEquals("Polled 1 repositories (1 failed schema validation)",
                GitHubEventSourcePoller.successMessage(1, tally));
        assertEquals("Polled 1 repositories with errors (1 failed schema validation)",
                GitHubEventSourcePoller.errorMessage(1, tally));
    }

    @Test
    void pollLogMessagesHaveNoSuffixWhenAllValid() {
        IngestTally tally = new IngestTally();
        tally.record(PersistOutcome.PERSISTED);

        assertEquals("Polled 2 repositories", GitHubEventSourcePoller.successMessage(2, tally));
        assertEquals("Polled 2 repositories with errors", GitHubEventSourcePoller.errorMessage(2, tally));
    }

    @Test
    void afterRestart_storedEventsAreNotNormalizedOrBackfilled() {
        stream.ids.addAll(List.of("101", "102"));
        api.pages.add(EventsPollResult.ok(
                events(issueOpened("103"), prOpened("102", 7), issueOpened("101")), "\"e1\"", 60));

        int ingested = poll();

        assertEquals(1, ingested);
        assertEquals(1, normalizer.calls);
        assertEquals(0, api.prFetches);
        assertEquals(1, stream.lookups);
    }

    /** GitHub event IDs are not ordered by time across event types; skipping must not depend on order. */
    @Test
    void newEventWithSmallerIdThanStoredEventIsIngested() {
        stream.ids.add("24530593870");
        api.pages.add(EventsPollResult.ok(
                events(issueOpened("17602406515"), issueOpened("24530593870")), "\"e1\"", 60));

        assertEquals(1, poll());
        assertEquals(Set.of("24530593870", "17602406515"), stream.ids);
    }

    @Test
    void unsupportedPullRequestActionsAreNotBackfilled() {
        api.pages.add(EventsPollResult.ok(events(prAction("201", 7, "edited")), "\"e1\"", 60));

        poll();

        assertEquals(0, api.prFetches);
    }

    @Test
    void supportedPullRequestActionsAreBackfilled() {
        api.pages.add(EventsPollResult.ok(events(prOpened("201", 7)), "\"e1\"", 60));

        poll();

        assertEquals(1, api.prFetches);
    }

    @Test
    void persistFailure_isRetriedOnNextPoll_andEtagIsNotUpdated() {
        stream.failOn = "102";
        api.pages.add(EventsPollResult.ok(
                events(issueOpened("103"), issueOpened("102"), issueOpened("101")), "\"e1\"", 60));

        poll();

        assertEquals(Set.of("101"), stream.ids);

        stream.failOn = null;
        api.pages.add(EventsPollResult.ok(
                events(issueOpened("103"), issueOpened("102"), issueOpened("101")), "\"e1\"", 60));

        assertEquals(2, poll());
        assertEquals(Set.of("101", "102", "103"), stream.ids);
        // The ETag of the failed poll was not kept, so the retry was a full request.
        assertEquals(List.of("<none>", "<none>"), api.etagsSent);
    }

    @Test
    void etagIsSentOnSubsequentPolls() {
        api.pages.add(EventsPollResult.ok(events(issueOpened("101")), "\"e1\"", 60));
        api.pages.add(EventsPollResult.notModified("\"e1\"", 60));

        poll();
        poll();

        assertEquals(List.of("<none>", "\"e1\""), api.etagsSent);
    }

    // -- helpers ----------------------------------------------------------------

    private int poll() {
        return poller.pollRepository(SOURCE_ID, "owner", "repo", "token",
                "https://api.github.com", "https://github.com", new StringBuilder());
    }

    private static ArrayNode events(ObjectNode... events) {
        ArrayNode array = MAPPER.createArrayNode();
        for (ObjectNode event : events) {
            array.add(event);
        }
        return array;
    }

    private static ObjectNode baseEvent(String type, String id) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("id", id);
        event.put("type", type);
        event.putObject("actor").put("login", "octocat").put("display_login", "octocat");
        event.putObject("repo").put("name", REPO);
        event.put("created_at", "2026-10-20T10:00:00Z");
        return event;
    }

    private static ObjectNode issueOpened(String id) {
        ObjectNode event = baseEvent("IssuesEvent", id);
        ObjectNode payload = event.putObject("payload");
        payload.put("action", "opened");
        ObjectNode issue = payload.putObject("issue");
        issue.put("number", 1);
        issue.put("title", "Issue");
        issue.put("state", "open");
        issue.putObject("user").put("login", "reporter");
        issue.putArray("assignees");
        issue.putArray("labels");
        return event;
    }

    private static ObjectNode prOpened(String id, int number) {
        return prAction(id, number, "opened");
    }

    private static ObjectNode prAction(String id, int number, String action) {
        ObjectNode event = baseEvent("PullRequestEvent", id);
        ObjectNode payload = event.putObject("payload");
        payload.put("action", action);
        payload.putObject("pull_request").put("number", number);
        return event;
    }

    /** Events API stub serving queued poll results and recording the ETags sent. */
    static class StubApiClient extends GitHubEventsApiClient {
        final Deque<EventsPollResult> pages = new ArrayDeque<>();
        final List<String> etagsSent = new ArrayList<>();
        int prFetches;

        @Override
        public EventsPollResult fetchRepositoryEvents(String baseUrl, String owner, String repo,
                                                      String token, String etag) {
            etagsSent.add(etag == null ? "<none>" : etag);
            return pages.removeFirst();
        }

        @Override
        public ApiResult fetchPullRequest(String baseUrl, String owner, String repo, int number, String token) {
            prFetches++;
            return ApiResult.httpError(404, "GET", "url", null, "not found");
        }
    }

    /** Real normalizer that counts how often it is invoked. */
    static class CountingNormalizer extends GitHubEventNormalizerV2 {
        int calls;

        CountingNormalizer() {
            super(MAPPER);
        }

        @Override
        public NormalizedEvent normalize(JsonNode event, String eventSourceId, String baseUrl, JsonNode fullPr) {
            calls++;
            return super.normalize(event, eventSourceId, baseUrl, fullPr);
        }
    }

    /** In-memory event stream that dedupes by sourceEventId like the real service. */
    static class RecordingStream extends EventStreamService {
        final Set<String> ids = new LinkedHashSet<>();
        final Set<String> invalidIds = new HashSet<>();
        String failOn;
        int lookups;

        @Override
        public Set<String> existingSourceEventIds(String eventSourceId, Collection<String> sourceEventIds) {
            lookups++;
            Set<String> found = new HashSet<>(sourceEventIds);
            found.retainAll(ids);
            return found;
        }

        @Override
        public PersistOutcome persistEvent(NormalizedEvent event) {
            if (event.sourceEventId().equals(failOn)) {
                throw new IllegalStateException("db down");
            }
            if (!ids.add(event.sourceEventId())) {
                return PersistOutcome.DUPLICATE;
            }
            return invalidIds.contains(event.sourceEventId())
                    ? PersistOutcome.PERSISTED_INVALID : PersistOutcome.PERSISTED;
        }
    }
}
