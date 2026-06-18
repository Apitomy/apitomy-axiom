package io.apitomy.axiom.events.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GitHubEventNormalizer}.
 */
class GitHubEventNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Issue events ──

    @Test
    void normalizeIssueOpened() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "opened");
        assertEquals("issue-created", GitHubEventNormalizer.normalizeEventType("issues", payload));
    }

    @Test
    void normalizeIssueEdited() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "edited");
        assertEquals("issue-updated", GitHubEventNormalizer.normalizeEventType("issues", payload));
    }

    @Test
    void normalizeIssueLabeled() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "labeled");
        assertEquals("issue-updated", GitHubEventNormalizer.normalizeEventType("issues", payload));
    }

    @Test
    void normalizeIssueClosed() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "closed");
        assertEquals("issue-closed", GitHubEventNormalizer.normalizeEventType("issues", payload));
    }

    @Test
    void normalizeIssueReopened() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "reopened");
        assertEquals("issue-reopened", GitHubEventNormalizer.normalizeEventType("issues", payload));
    }

    @Test
    void normalizeIssueCommentCreated() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "created");
        assertEquals("comment-added", GitHubEventNormalizer.normalizeEventType("issue_comment", payload));
    }

    @Test
    void normalizeIssueCommentEditedReturnsNull() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "edited");
        assertNull(GitHubEventNormalizer.normalizeEventType("issue_comment", payload));
    }

    @Test
    void normalizeUnsupportedEventReturnsNull() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "completed");
        assertNull(GitHubEventNormalizer.normalizeEventType("workflow_run", payload));
    }

    @Test
    void normalizeNullActionReturnsNull() {
        ObjectNode payload = mapper.createObjectNode();
        assertNull(GitHubEventNormalizer.normalizeEventType("issues", payload));
    }

    // ── Pull request events ──

    @Test
    void normalizePrOpened() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "opened");
        assertEquals("pr-created", GitHubEventNormalizer.normalizeEventType("pull_request", payload));
    }

    @Test
    void normalizePrEdited() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "edited");
        assertEquals("pr-updated", GitHubEventNormalizer.normalizeEventType("pull_request", payload));
    }

    @Test
    void normalizePrLabeled() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "labeled");
        assertEquals("pr-updated", GitHubEventNormalizer.normalizeEventType("pull_request", payload));
    }

    @Test
    void normalizePrSynchronize() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "synchronize");
        assertEquals("pr-updated", GitHubEventNormalizer.normalizeEventType("pull_request", payload));
    }

    @Test
    void normalizePrReviewRequested() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "review_requested");
        assertEquals("pr-updated", GitHubEventNormalizer.normalizeEventType("pull_request", payload));
    }

    @Test
    void normalizePrReadyForReview() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "ready_for_review");
        assertEquals("pr-updated", GitHubEventNormalizer.normalizeEventType("pull_request", payload));
    }

    @Test
    void normalizePrConvertedToDraft() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "converted_to_draft");
        assertEquals("pr-updated", GitHubEventNormalizer.normalizeEventType("pull_request", payload));
    }

    @Test
    void normalizePrClosedNotMerged() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "closed");
        payload.putObject("pull_request").put("merged", false);
        assertEquals("pr-closed", GitHubEventNormalizer.normalizeEventType("pull_request", payload));
    }

    @Test
    void normalizePrClosedAndMerged() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "closed");
        payload.putObject("pull_request").put("merged", true);
        assertEquals("pr-merged", GitHubEventNormalizer.normalizeEventType("pull_request", payload));
    }

    @Test
    void normalizePrReopened() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "reopened");
        assertEquals("pr-reopened", GitHubEventNormalizer.normalizeEventType("pull_request", payload));
    }

    @Test
    void normalizePrUnknownActionReturnsNull() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "auto_merge_enabled");
        assertNull(GitHubEventNormalizer.normalizeEventType("pull_request", payload));
    }

    @Test
    void normalizePrNullActionReturnsNull() {
        ObjectNode payload = mapper.createObjectNode();
        assertNull(GitHubEventNormalizer.normalizeEventType("pull_request", payload));
    }

    // ── Pull request review comment events ──

    @Test
    void normalizePrReviewCommentCreated() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "created");
        assertEquals("pr-review-comment",
                GitHubEventNormalizer.normalizeEventType("pull_request_review_comment", payload));
    }

    @Test
    void normalizePrReviewCommentEditedReturnsNull() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("action", "edited");
        assertNull(GitHubEventNormalizer.normalizeEventType("pull_request_review_comment", payload));
    }

    // ── extractIssueRef ──

    @Test
    void extractIssueRef() {
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("repository").put("full_name", "octocat/hello-world");
        payload.putObject("issue").put("number", 7);
        assertEquals("octocat/hello-world#7", GitHubEventNormalizer.extractIssueRef(payload));
    }

    @Test
    void extractIssueRefMissingIssue() {
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("repository").put("full_name", "octocat/hello-world");
        assertNull(GitHubEventNormalizer.extractIssueRef(payload));
    }

    // ── extractPrRef ──

    @Test
    void extractPrRef() {
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("repository").put("full_name", "octocat/hello-world");
        payload.putObject("pull_request").put("number", 42);
        assertEquals("octocat/hello-world#42", GitHubEventNormalizer.extractPrRef(payload));
    }

    @Test
    void extractPrRefFallsBackToIssueRef() {
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("repository").put("full_name", "octocat/hello-world");
        payload.putObject("issue").put("number", 7);
        assertEquals("octocat/hello-world#7", GitHubEventNormalizer.extractPrRef(payload));
    }

    @Test
    void extractPrRefMissingBothReturnsNull() {
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("repository").put("full_name", "octocat/hello-world");
        assertNull(GitHubEventNormalizer.extractPrRef(payload));
    }

    // ── extractRepository ──

    @Test
    void extractRepository() {
        ObjectNode payload = mapper.createObjectNode();
        payload.putObject("repository").put("full_name", "octocat/hello-world");
        assertEquals("octocat/hello-world", GitHubEventNormalizer.extractRepository(payload));
    }

    @Test
    void extractRepositoryMissingReturnsNull() {
        ObjectNode payload = mapper.createObjectNode();
        assertNull(GitHubEventNormalizer.extractRepository(payload));
    }
}
