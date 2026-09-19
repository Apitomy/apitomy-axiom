package io.apitomy.axiom.events.github.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.model.payloads.BranchCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueClosedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCommentCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueLabeledPayload;
import io.apitomy.axiom.core.events.model.payloads.PrClosedPayload;
import io.apitomy.axiom.core.events.model.payloads.PrCommentCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.PrMergedPayload;
import io.apitomy.axiom.core.events.model.payloads.PushPayload;
import io.apitomy.axiom.core.events.model.payloads.ReleasePublishedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GitHubEventNormalizerV2}. Uses plain JUnit 5 with
 * programmatically constructed fixture JSON.
 */
class GitHubEventNormalizerV2Test {

    private static final String CONNECTION_ID = "test-connection";
    private static final String BASE_URL = "https://github.com";
    private static final ObjectMapper mapper = new ObjectMapper();

    private GitHubEventNormalizerV2 normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new GitHubEventNormalizerV2(mapper);
    }

    // ── Test 1: IssuesEvent/opened ──────────────────────────────────────────

    @Test
    void issuesEvent_opened_producesIssueCreated() {
        ObjectNode event = buildEvent("IssuesEvent", "12345", "owner/repo");
        ObjectNode payload = (ObjectNode) event.get("payload");
        payload.put("action", "opened");
        payload.set("issue", buildIssue(42, "Bug report", "open"));

        NormalizedEvent result = normalizer.normalize(event, CONNECTION_ID, BASE_URL, null);

        assertNotNull(result);
        assertNotNull(result.id()); // UUID generated
        assertEquals("12345", result.sourceEventId());
        assertEquals("github", result.source());
        assertEquals(CONNECTION_ID, result.connectionId());
        assertEquals(EventType.ISSUE_CREATED, result.type());
        assertEquals("https://github.com/owner/repo/issues/42", result.ref());
        assertEquals("2026-09-18T10:00:00Z", result.timestamp());
        assertNotNull(result.actor());
        assertEquals("octocat", result.actor().login());

        assertInstanceOf(IssueCreatedPayload.class, result.payload());
        IssueCreatedPayload issuePayload = (IssueCreatedPayload) result.payload();
        assertEquals("42", issuePayload.issue().number());
        assertEquals("Bug report", issuePayload.issue().title());
        assertEquals("open", issuePayload.issue().state());
    }

    // ── Test 2: IssuesEvent/labeled ─────────────────────────────────────────

    @Test
    void issuesEvent_labeled_producesIssueLabeled() {
        ObjectNode event = buildEvent("IssuesEvent", "12346", "owner/repo");
        ObjectNode payload = (ObjectNode) event.get("payload");
        payload.put("action", "labeled");
        payload.set("issue", buildIssue(42, "Bug report", "open"));

        ObjectNode label = mapper.createObjectNode();
        label.put("name", "bug");
        label.put("color", "d73a4a");
        label.put("description", "Something isn't working");
        payload.set("label", label);

        NormalizedEvent result = normalizer.normalize(event, CONNECTION_ID, BASE_URL, null);

        assertNotNull(result);
        assertEquals(EventType.ISSUE_LABELED, result.type());

        assertInstanceOf(IssueLabeledPayload.class, result.payload());
        IssueLabeledPayload labeledPayload = (IssueLabeledPayload) result.payload();
        assertEquals("bug", labeledPayload.label().name());
        assertEquals("d73a4a", labeledPayload.label().color());
        assertEquals("Something isn't working", labeledPayload.label().description());
    }

    // ── Test 3: IssueCommentEvent/created on issue ──────────────────────────

    @Test
    void issueCommentEvent_createdOnIssue_producesIssueCommentCreated() {
        ObjectNode event = buildEvent("IssueCommentEvent", "12347", "owner/repo");
        ObjectNode payload = (ObjectNode) event.get("payload");
        payload.put("action", "created");
        payload.set("issue", buildIssue(10, "Feature request", "open"));
        payload.set("comment", buildComment(99001, "Looks good to me!"));

        NormalizedEvent result = normalizer.normalize(event, CONNECTION_ID, BASE_URL, null);

        assertNotNull(result);
        assertEquals(EventType.ISSUE_COMMENT_CREATED, result.type());
        assertEquals("https://github.com/owner/repo/issues/10", result.ref());

        assertInstanceOf(IssueCommentCreatedPayload.class, result.payload());
        IssueCommentCreatedPayload commentPayload = (IssueCommentCreatedPayload) result.payload();
        assertEquals("10", commentPayload.issue().number());
        assertEquals("99001", commentPayload.comment().id());
        assertEquals("Looks good to me!", commentPayload.comment().body());
    }

    // ── Test 4: IssueCommentEvent/created on PR ─────────────────────────────

    @Test
    void issueCommentEvent_createdOnPr_producesPrCommentCreated() {
        ObjectNode event = buildEvent("IssueCommentEvent", "12348", "owner/repo");
        ObjectNode payload = (ObjectNode) event.get("payload");
        payload.put("action", "created");

        // Issue with a pull_request key → this is a PR comment
        ObjectNode issue = buildIssue(55, "Add new feature", "open");
        ObjectNode prRef = mapper.createObjectNode();
        prRef.put("url", "https://api.github.com/repos/owner/repo/pulls/55");
        issue.set("pull_request", prRef);
        payload.set("issue", issue);
        payload.set("comment", buildComment(99002, "Please fix the tests"));

        NormalizedEvent result = normalizer.normalize(event, CONNECTION_ID, BASE_URL, null);

        assertNotNull(result);
        assertEquals(EventType.PR_COMMENT_CREATED, result.type());
        assertEquals("https://github.com/owner/repo/pull/55", result.ref());

        assertInstanceOf(PrCommentCreatedPayload.class, result.payload());
        PrCommentCreatedPayload prCommentPayload = (PrCommentCreatedPayload) result.payload();
        assertEquals("55", prCommentPayload.pullRequest().number());
        assertEquals("99002", prCommentPayload.comment().id());
    }

    // ── Test 5: PullRequestEvent/closed merged=true → PR_MERGED ─────────────

    @Test
    void pullRequestEvent_closedMerged_producesPrMerged() {
        ObjectNode event = buildEvent("PullRequestEvent", "12349", "owner/repo");
        ObjectNode payload = (ObjectNode) event.get("payload");
        payload.put("action", "closed");
        payload.set("pull_request", buildPullRequest(77, "Great PR", "closed", true));

        // fullPr with merged=true
        ObjectNode fullPr = buildPullRequest(77, "Great PR", "closed", true);
        fullPr.put("additions", 50);
        fullPr.put("deletions", 10);
        fullPr.put("changed_files", 3);

        NormalizedEvent result = normalizer.normalize(event, CONNECTION_ID, BASE_URL, fullPr);

        assertNotNull(result);
        assertEquals(EventType.PR_MERGED, result.type());
        assertEquals("https://github.com/owner/repo/pull/77", result.ref());

        assertInstanceOf(PrMergedPayload.class, result.payload());
        PrMergedPayload mergedPayload = (PrMergedPayload) result.payload();
        assertEquals("77", mergedPayload.pullRequest().number());
        assertTrue(mergedPayload.pullRequest().isMerged());
        assertEquals(50, mergedPayload.pullRequest().additions());
        assertEquals(10, mergedPayload.pullRequest().deletions());
        assertEquals(3, mergedPayload.pullRequest().changedFiles());
    }

    // ── Test 6: PullRequestEvent/closed merged=false → PR_CLOSED ────────────

    @Test
    void pullRequestEvent_closedNotMerged_producesPrClosed() {
        ObjectNode event = buildEvent("PullRequestEvent", "12350", "owner/repo");
        ObjectNode payload = (ObjectNode) event.get("payload");
        payload.put("action", "closed");
        payload.set("pull_request", buildPullRequest(78, "WIP PR", "closed", false));

        ObjectNode fullPr = buildPullRequest(78, "WIP PR", "closed", false);

        NormalizedEvent result = normalizer.normalize(event, CONNECTION_ID, BASE_URL, fullPr);

        assertNotNull(result);
        assertEquals(EventType.PR_CLOSED, result.type());

        assertInstanceOf(PrClosedPayload.class, result.payload());
        PrClosedPayload closedPayload = (PrClosedPayload) result.payload();
        assertFalse(closedPayload.pullRequest().isMerged());
    }

    // ── Test 7: PushEvent ───────────────────────────────────────────────────

    @Test
    void pushEvent_producesPush() {
        ObjectNode event = buildEvent("PushEvent", "12351", "owner/repo");
        ObjectNode payload = (ObjectNode) event.get("payload");
        payload.put("ref", "refs/heads/main");
        payload.put("before", "aaa111");
        payload.put("head", "bbb222");
        payload.put("forced", false);

        ArrayNode commits = mapper.createArrayNode();
        ObjectNode commit1 = mapper.createObjectNode();
        commit1.put("sha", "ccc333");
        commit1.put("message", "fix: resolve bug");
        ObjectNode commitAuthor = mapper.createObjectNode();
        commitAuthor.put("name", "Octocat");
        commitAuthor.put("email", "octocat@github.com");
        commit1.set("author", commitAuthor);
        commits.add(commit1);
        payload.set("commits", commits);

        NormalizedEvent result = normalizer.normalize(event, CONNECTION_ID, BASE_URL, null);

        assertNotNull(result);
        assertEquals(EventType.PUSH, result.type());
        assertEquals("https://github.com/owner/repo", result.ref());

        assertInstanceOf(PushPayload.class, result.payload());
        PushPayload pushPayload = (PushPayload) result.payload();
        assertEquals("refs/heads/main", pushPayload.ref());
        assertEquals("main", pushPayload.branch());
        assertEquals("aaa111", pushPayload.beforeSha());
        assertEquals("bbb222", pushPayload.afterSha());
        assertFalse(pushPayload.forced());
        assertEquals(1, pushPayload.commits().size());
        assertEquals("ccc333", pushPayload.commits().get(0).sha());
        assertEquals("fix: resolve bug", pushPayload.commits().get(0).message());
        assertEquals("Octocat", pushPayload.commits().get(0).author().name());
        assertEquals("https://github.com/owner/repo/commit/ccc333", pushPayload.commits().get(0).url());
    }

    // ── Test 8: CreateEvent/branch ──────────────────────────────────────────

    @Test
    void createEvent_branch_producesBranchCreated() {
        ObjectNode event = buildEvent("CreateEvent", "12352", "owner/repo");
        ObjectNode payload = (ObjectNode) event.get("payload");
        payload.put("ref_type", "branch");
        payload.put("ref", "feature/new-thing");
        payload.put("master_branch", "main");

        NormalizedEvent result = normalizer.normalize(event, CONNECTION_ID, BASE_URL, null);

        assertNotNull(result);
        assertEquals(EventType.BRANCH_CREATED, result.type());
        assertEquals("https://github.com/owner/repo/tree/feature/new-thing", result.ref());

        assertInstanceOf(BranchCreatedPayload.class, result.payload());
        BranchCreatedPayload branchPayload = (BranchCreatedPayload) result.payload();
        assertEquals("feature/new-thing", branchPayload.ref());
        assertEquals("main", branchPayload.defaultBranch());
    }

    // ── Test 9: ReleaseEvent/published ──────────────────────────────────────

    @Test
    void releaseEvent_published_producesReleasePublished() {
        ObjectNode event = buildEvent("ReleaseEvent", "12353", "owner/repo");
        ObjectNode payload = (ObjectNode) event.get("payload");
        payload.put("action", "published");

        ObjectNode release = mapper.createObjectNode();
        release.put("tag_name", "v1.0.0");
        release.put("name", "Release 1.0.0");
        release.put("body", "First stable release");
        release.put("draft", false);
        release.put("prerelease", false);
        release.put("html_url", "https://github.com/owner/repo/releases/tag/v1.0.0");
        release.put("created_at", "2026-09-17T12:00:00Z");
        release.put("published_at", "2026-09-18T10:00:00Z");

        ObjectNode releaseAuthor = mapper.createObjectNode();
        releaseAuthor.put("login", "releaser");
        release.set("author", releaseAuthor);

        payload.set("release", release);

        NormalizedEvent result = normalizer.normalize(event, CONNECTION_ID, BASE_URL, null);

        assertNotNull(result);
        assertEquals(EventType.RELEASE_PUBLISHED, result.type());
        assertEquals("https://github.com/owner/repo/releases/tag/v1.0.0", result.ref());

        assertInstanceOf(ReleasePublishedPayload.class, result.payload());
        ReleasePublishedPayload releasePayload = (ReleasePublishedPayload) result.payload();
        assertEquals("v1.0.0", releasePayload.tagName());
        assertEquals("Release 1.0.0", releasePayload.name());
        assertEquals("First stable release", releasePayload.body());
        assertFalse(releasePayload.isDraft());
        assertFalse(releasePayload.isPrerelease());
        assertEquals("releaser", releasePayload.author().login());
    }

    // ── Test 10: Unknown event type → null ──────────────────────────────────

    @Test
    void unknownEventType_returnsNull() {
        ObjectNode event = buildEvent("WatchEvent", "12354", "owner/repo");

        NormalizedEvent result = normalizer.normalize(event, CONNECTION_ID, BASE_URL, null);

        assertNull(result);
    }

    // ── Test 11: IssuesEvent/closed ─────────────────────────────────────────

    @Test
    void issuesEvent_closed_producesIssueClosed() {
        ObjectNode event = buildEvent("IssuesEvent", "12355", "owner/repo");
        ObjectNode payload = (ObjectNode) event.get("payload");
        payload.put("action", "closed");
        ObjectNode issue = buildIssue(42, "Bug report", "closed");
        issue.put("closed_at", "2026-09-18T11:00:00Z");
        issue.put("state_reason", "completed");
        payload.set("issue", issue);

        NormalizedEvent result = normalizer.normalize(event, CONNECTION_ID, BASE_URL, null);

        assertNotNull(result);
        assertEquals(EventType.ISSUE_CLOSED, result.type());

        assertInstanceOf(IssueClosedPayload.class, result.payload());
        IssueClosedPayload closedPayload = (IssueClosedPayload) result.payload();
        assertEquals("closed", closedPayload.issue().state());
        assertEquals("completed", closedPayload.issue().stateDetail());
        assertEquals("2026-09-18T11:00:00Z", closedPayload.issue().closedAt());
    }

    // ── Test 12: sourceData is populated ────────────────────────────────────

    @Test
    void sourceData_containsGitHubEventMetadata() {
        ObjectNode event = buildEvent("IssuesEvent", "99999", "owner/repo");
        ObjectNode payload = (ObjectNode) event.get("payload");
        payload.put("action", "opened");
        payload.set("issue", buildIssue(1, "Test", "open"));

        NormalizedEvent result = normalizer.normalize(event, CONNECTION_ID, BASE_URL, null);

        assertNotNull(result);
        assertNotNull(result.sourceData());
        assertEquals("99999", result.sourceData().get("githubEventId").asText());
        assertEquals("IssuesEvent", result.sourceData().get("githubEventType").asText());
        assertEquals("opened", result.sourceData().get("githubAction").asText());
        assertNotNull(result.sourceData().get("rawPayload"));
    }

    // ── Fixture builders ────────────────────────────────────────────────────

    private ObjectNode buildEvent(String type, String id, String repoName) {
        ObjectNode event = mapper.createObjectNode();
        event.put("id", id);
        event.put("type", type);

        ObjectNode actor = mapper.createObjectNode();
        actor.put("login", "octocat");
        actor.put("display_login", "octocat");
        actor.put("avatar_url", "https://avatars.githubusercontent.com/u/1?v=4");
        event.set("actor", actor);

        ObjectNode repo = mapper.createObjectNode();
        repo.put("name", repoName);
        event.set("repo", repo);

        event.set("payload", mapper.createObjectNode());
        event.put("created_at", "2026-09-18T10:00:00Z");

        return event;
    }

    private ObjectNode buildIssue(int number, String title, String state) {
        ObjectNode issue = mapper.createObjectNode();
        issue.put("number", number);
        issue.put("title", title);
        issue.put("body", "Issue body for " + title);
        issue.put("state", state);
        issue.put("html_url", "https://github.com/owner/repo/issues/" + number);
        issue.put("created_at", "2026-09-17T08:00:00Z");
        issue.put("updated_at", "2026-09-18T10:00:00Z");

        ObjectNode user = mapper.createObjectNode();
        user.put("login", "reporter");
        user.put("html_url", "https://github.com/reporter");
        issue.set("user", user);

        issue.set("assignees", mapper.createArrayNode());
        issue.set("labels", mapper.createArrayNode());

        return issue;
    }

    private ObjectNode buildPullRequest(int number, String title, String state, boolean merged) {
        ObjectNode pr = mapper.createObjectNode();
        pr.put("number", number);
        pr.put("title", title);
        pr.put("body", "PR body for " + title);
        pr.put("state", state);
        pr.put("html_url", "https://github.com/owner/repo/pull/" + number);
        pr.put("created_at", "2026-09-17T08:00:00Z");
        pr.put("updated_at", "2026-09-18T10:00:00Z");
        pr.put("merged", merged);
        pr.put("draft", false);

        if (merged) {
            pr.put("merged_at", "2026-09-18T10:00:00Z");
        }

        ObjectNode head = mapper.createObjectNode();
        head.put("ref", "feature/branch");
        head.put("sha", "abc123");
        pr.set("head", head);

        ObjectNode base = mapper.createObjectNode();
        base.put("ref", "main");
        pr.set("base", base);

        ObjectNode user = mapper.createObjectNode();
        user.put("login", "author");
        user.put("html_url", "https://github.com/author");
        pr.set("user", user);

        pr.set("assignees", mapper.createArrayNode());
        pr.set("labels", mapper.createArrayNode());

        return pr;
    }

    private ObjectNode buildComment(long id, String body) {
        ObjectNode comment = mapper.createObjectNode();
        comment.put("id", id);
        comment.put("body", body);
        comment.put("html_url", "https://github.com/owner/repo/issues/1#issuecomment-" + id);
        comment.put("created_at", "2026-09-18T10:30:00Z");
        comment.put("updated_at", "2026-09-18T10:30:00Z");

        ObjectNode user = mapper.createObjectNode();
        user.put("login", "commenter");
        user.put("html_url", "https://github.com/commenter");
        comment.set("user", user);

        return comment;
    }
}
