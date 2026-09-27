package io.apitomy.axiom.events.github.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.events.model.Actor;
import io.apitomy.axiom.core.events.model.Change;
import io.apitomy.axiom.core.events.model.Commit;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.core.events.model.NormalizedComment;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.model.NormalizedIssue;
import io.apitomy.axiom.core.events.model.NormalizedLabel;
import io.apitomy.axiom.core.events.model.NormalizedPullRequest;
import io.apitomy.axiom.core.events.model.NormalizedReview;
import io.apitomy.axiom.core.events.model.payloads.BranchCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.BranchDeletedPayload;
import io.apitomy.axiom.core.events.model.payloads.EventPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueAssignedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueClosedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCommentCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCommentDeletedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCommentUpdatedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueLabeledPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueReopenedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueUnassignedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueUnlabeledPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueUpdatedPayload;
import io.apitomy.axiom.core.events.model.payloads.PrClosedPayload;
import io.apitomy.axiom.core.events.model.payloads.PrCommentCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.PrCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.PrLabeledPayload;
import io.apitomy.axiom.core.events.model.payloads.PrMergedPayload;
import io.apitomy.axiom.core.events.model.payloads.PrReopenedPayload;
import io.apitomy.axiom.core.events.model.payloads.PrReviewRequestedPayload;
import io.apitomy.axiom.core.events.model.payloads.PrReviewSubmittedPayload;
import io.apitomy.axiom.core.events.model.payloads.PrSynchronizePayload;
import io.apitomy.axiom.core.events.model.payloads.PrUnlabeledPayload;
import io.apitomy.axiom.core.events.model.payloads.PushPayload;
import io.apitomy.axiom.core.events.model.payloads.ReleasePublishedPayload;
import io.apitomy.axiom.core.events.model.payloads.TagCreatedPayload;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Normalizes raw GitHub Repository Events API JSON into {@link NormalizedEvent} records
 * with typed payloads. This is the V2 normalizer designed for the event-sourcing redesign,
 * mapping events from the {@code GET /repos/{owner}/{repo}/events} endpoint.
 *
 * <p>Unlike the V1 normalizer (which worked with webhook payloads), this normalizer
 * operates on the Events API format where each event has a top-level {@code type} and
 * {@code payload} structure.</p>
 */
@ApplicationScoped
public class GitHubEventNormalizerV2 {

    private static final Logger LOG = Logger.getLogger(GitHubEventNormalizerV2.class);
    private static final String SOURCE = "github";

    @Inject
    ObjectMapper objectMapper;

    /**
     * Constructor for CDI.
     */
    public GitHubEventNormalizerV2() {
    }

    /**
     * Constructor for testing (direct injection).
     */
    public GitHubEventNormalizerV2(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Normalizes a single GitHub Events API event into a {@link NormalizedEvent}.
     *
     * @param event        a single event JSON object from the Events API array
     * @param connectionId the slug of the EventSourceConnection that produced this event
     * @param baseUrl      the HTML base URL (e.g., {@code https://github.com}), NOT the API base
     * @param fullPr       the backfilled full PR JSON from the Pulls API (null for non-PR events)
     * @return the normalized event, or null if the event type/action is not recognized
     */
    public NormalizedEvent normalize(JsonNode event, String connectionId, String baseUrl, JsonNode fullPr) {
        String githubEventType = textOrNull(event, "type");
        JsonNode payload = event.path("payload");
        String action = textOrNull(payload, "action");
        String githubEventId = textOrNull(event, "id");
        String repoFullName = textOrNull(event.path("repo"), "name");
        String timestamp = textOrNull(event, "created_at");

        if (githubEventType == null || repoFullName == null) {
            return null;
        }

        EventType eventType;
        EventPayload eventPayload;
        String ref;

        try {
            switch (githubEventType) {
                case "IssuesEvent" -> {
                    var result = normalizeIssuesEvent(action, payload, baseUrl, repoFullName);
                    if (result == null) return null;
                    eventType = result.type;
                    eventPayload = result.payload;
                    ref = result.ref;
                }
                case "IssueCommentEvent" -> {
                    var result = normalizeIssueCommentEvent(action, payload, baseUrl, repoFullName);
                    if (result == null) return null;
                    eventType = result.type;
                    eventPayload = result.payload;
                    ref = result.ref;
                }
                case "PullRequestEvent" -> {
                    var result = normalizePullRequestEvent(action, payload, baseUrl, repoFullName, fullPr);
                    if (result == null) return null;
                    eventType = result.type;
                    eventPayload = result.payload;
                    ref = result.ref;
                }
                case "PullRequestReviewEvent" -> {
                    var result = normalizePrReviewEvent(action, payload, baseUrl, repoFullName, fullPr);
                    if (result == null) return null;
                    eventType = result.type;
                    eventPayload = result.payload;
                    ref = result.ref;
                }
                case "PushEvent" -> {
                    var result = normalizePushEvent(payload, baseUrl, repoFullName);
                    if (result == null) return null;
                    eventType = result.type;
                    eventPayload = result.payload;
                    ref = result.ref;
                }
                case "CreateEvent" -> {
                    var result = normalizeCreateEvent(payload, baseUrl, repoFullName);
                    if (result == null) return null;
                    eventType = result.type;
                    eventPayload = result.payload;
                    ref = result.ref;
                }
                case "DeleteEvent" -> {
                    var result = normalizeDeleteEvent(payload, baseUrl, repoFullName);
                    if (result == null) return null;
                    eventType = result.type;
                    eventPayload = result.payload;
                    ref = result.ref;
                }
                case "ReleaseEvent" -> {
                    var result = normalizeReleaseEvent(action, payload, baseUrl, repoFullName);
                    if (result == null) return null;
                    eventType = result.type;
                    eventPayload = result.payload;
                    ref = result.ref;
                }
                default -> {
                    LOG.debugf("Unrecognized GitHub event type: %s", githubEventType);
                    return null;
                }
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to normalize GitHub event %s (type=%s, action=%s)",
                    githubEventId, githubEventType, action);
            return null;
        }

        Actor actor = mapActor(event.path("actor"));
        JsonNode sourceData = buildSourceData(githubEventId, githubEventType, action, payload);

        return new NormalizedEvent(
                UUID.randomUUID().toString(),
                githubEventId,
                SOURCE,
                connectionId,
                eventType,
                ref,
                timestamp,
                actor,
                eventPayload,
                sourceData
        );
    }

    // ── Event type normalizers ──────────────────────────────────────────────

    private NormalizationResult normalizeIssuesEvent(String action, JsonNode payload,
                                                      String baseUrl, String repoFullName) {
        if (action == null) return null;
        JsonNode issueNode = payload.path("issue");
        NormalizedIssue issue = mapIssue(issueNode);
        String ref = buildIssueRef(baseUrl, repoFullName, issueNode.path("number").asInt());

        return switch (action) {
            case "opened" -> new NormalizationResult(EventType.ISSUE_CREATED,
                    new IssueCreatedPayload(issue), ref);
            case "edited" -> new NormalizationResult(EventType.ISSUE_UPDATED,
                    new IssueUpdatedPayload(issue, mapChange(payload.path("changes"))), ref);
            case "closed" -> new NormalizationResult(EventType.ISSUE_CLOSED,
                    new IssueClosedPayload(issue), ref);
            case "reopened" -> new NormalizationResult(EventType.ISSUE_REOPENED,
                    new IssueReopenedPayload(issue), ref);
            case "assigned" -> new NormalizationResult(EventType.ISSUE_ASSIGNED,
                    new IssueAssignedPayload(issue, mapActor(payload.path("assignee"))), ref);
            case "unassigned" -> new NormalizationResult(EventType.ISSUE_UNASSIGNED,
                    new IssueUnassignedPayload(issue, mapActor(payload.path("assignee"))), ref);
            case "labeled" -> new NormalizationResult(EventType.ISSUE_LABELED,
                    new IssueLabeledPayload(issue, mapLabel(payload.path("label"))), ref);
            case "unlabeled" -> new NormalizationResult(EventType.ISSUE_UNLABELED,
                    new IssueUnlabeledPayload(issue, mapLabel(payload.path("label"))), ref);
            default -> {
                LOG.debugf("Unrecognized IssuesEvent action: %s", action);
                yield null;
            }
        };
    }

    private NormalizationResult normalizeIssueCommentEvent(String action, JsonNode payload,
                                                            String baseUrl, String repoFullName) {
        if (action == null) return null;
        JsonNode issueNode = payload.path("issue");
        NormalizedComment comment = mapComment(payload.path("comment"));

        boolean isPr = issueNode.has("pull_request");
        int number = issueNode.path("number").asInt();

        if (isPr && "created".equals(action)) {
            // Issue comment on a PR → PR_COMMENT_CREATED
            // Build a minimal NormalizedPullRequest from the issue data
            NormalizedPullRequest pr = mapPullRequestFromIssue(issueNode);
            String ref = buildPrRef(baseUrl, repoFullName, number);
            return new NormalizationResult(EventType.PR_COMMENT_CREATED,
                    new PrCommentCreatedPayload(pr, comment), ref);
        }

        NormalizedIssue issue = mapIssue(issueNode);
        String ref = isPr
                ? buildPrRef(baseUrl, repoFullName, number)
                : buildIssueRef(baseUrl, repoFullName, number);

        return switch (action) {
            case "created" -> new NormalizationResult(EventType.ISSUE_COMMENT_CREATED,
                    new IssueCommentCreatedPayload(issue, comment), ref);
            case "edited" -> new NormalizationResult(EventType.ISSUE_COMMENT_UPDATED,
                    new IssueCommentUpdatedPayload(issue, comment), ref);
            case "deleted" -> new NormalizationResult(EventType.ISSUE_COMMENT_DELETED,
                    new IssueCommentDeletedPayload(issue, comment), ref);
            default -> {
                LOG.debugf("Unrecognized IssueCommentEvent action: %s", action);
                yield null;
            }
        };
    }

    private NormalizationResult normalizePullRequestEvent(String action, JsonNode payload,
                                                           String baseUrl, String repoFullName,
                                                           JsonNode fullPr) {
        if (action == null) return null;
        JsonNode prNode = fullPr != null ? fullPr : payload.path("pull_request");
        NormalizedPullRequest pr = mapPullRequest(prNode);
        int number = prNode.path("number").asInt();
        String ref = buildPrRef(baseUrl, repoFullName, number);

        return switch (action) {
            case "opened" -> new NormalizationResult(EventType.PR_CREATED,
                    new PrCreatedPayload(pr), ref);
            case "closed" -> {
                boolean merged = prNode.path("merged").asBoolean(false);
                if (merged) {
                    yield new NormalizationResult(EventType.PR_MERGED,
                            new PrMergedPayload(pr), ref);
                } else {
                    yield new NormalizationResult(EventType.PR_CLOSED,
                            new PrClosedPayload(pr), ref);
                }
            }
            case "reopened" -> new NormalizationResult(EventType.PR_REOPENED,
                    new PrReopenedPayload(pr), ref);
            case "review_requested" -> new NormalizationResult(EventType.PR_REVIEW_REQUESTED,
                    new PrReviewRequestedPayload(pr, mapActor(payload.path("requested_reviewer"))), ref);
            case "labeled" -> new NormalizationResult(EventType.PR_LABELED,
                    new PrLabeledPayload(pr, mapLabel(payload.path("label"))), ref);
            case "unlabeled" -> new NormalizationResult(EventType.PR_UNLABELED,
                    new PrUnlabeledPayload(pr, mapLabel(payload.path("label"))), ref);
            case "synchronize" -> new NormalizationResult(EventType.PR_SYNCHRONIZE,
                    new PrSynchronizePayload(pr,
                            textOrNull(payload, "before"),
                            textOrNull(payload, "after")), ref);
            default -> {
                LOG.debugf("Unrecognized PullRequestEvent action: %s", action);
                yield null;
            }
        };
    }

    private NormalizationResult normalizePrReviewEvent(String action, JsonNode payload,
                                                        String baseUrl, String repoFullName,
                                                        JsonNode fullPr) {
        if (!"created".equals(action)) {
            return null;
        }
        JsonNode prNode = fullPr != null ? fullPr : payload.path("pull_request");
        NormalizedPullRequest pr = mapPullRequest(prNode);
        NormalizedReview review = mapReview(payload.path("review"));
        int number = prNode.path("number").asInt();
        String ref = buildPrRef(baseUrl, repoFullName, number);
        return new NormalizationResult(EventType.PR_REVIEW_SUBMITTED,
                new PrReviewSubmittedPayload(pr, review), ref);
    }

    private NormalizationResult normalizePushEvent(JsonNode payload, String baseUrl,
                                                    String repoFullName) {
        String gitRef = textOrNull(payload, "ref");
        String branch = gitRef != null && gitRef.startsWith("refs/heads/")
                ? gitRef.substring("refs/heads/".length())
                : gitRef;
        String beforeSha = textOrNull(payload, "before");
        String afterSha = textOrNull(payload, "head");
        Boolean forced = payload.has("forced") ? payload.get("forced").asBoolean() : null;

        List<Commit> commits = mapCommits(payload.path("commits"), baseUrl, repoFullName);
        String ref = baseUrl + "/" + repoFullName;

        return new NormalizationResult(EventType.PUSH,
                new PushPayload(gitRef, branch, beforeSha, afterSha, forced, commits), ref);
    }

    private NormalizationResult normalizeCreateEvent(JsonNode payload, String baseUrl,
                                                      String repoFullName) {
        String refType = textOrNull(payload, "ref_type");
        String refName = textOrNull(payload, "ref");
        String defaultBranch = textOrNull(payload, "master_branch");

        if ("branch".equals(refType)) {
            String ref = baseUrl + "/" + repoFullName + "/tree/" + refName;
            return new NormalizationResult(EventType.BRANCH_CREATED,
                    new BranchCreatedPayload(refName, defaultBranch), ref);
        } else if ("tag".equals(refType)) {
            String ref = baseUrl + "/" + repoFullName + "/releases/tag/" + refName;
            return new NormalizationResult(EventType.TAG_CREATED,
                    new TagCreatedPayload(refName, defaultBranch), ref);
        }
        return null;
    }

    private NormalizationResult normalizeDeleteEvent(JsonNode payload, String baseUrl,
                                                      String repoFullName) {
        String refType = textOrNull(payload, "ref_type");
        String refName = textOrNull(payload, "ref");

        if ("branch".equals(refType)) {
            String ref = baseUrl + "/" + repoFullName + "/tree/" + refName;
            return new NormalizationResult(EventType.BRANCH_DELETED,
                    new BranchDeletedPayload(refName), ref);
        }
        return null;
    }

    private NormalizationResult normalizeReleaseEvent(String action, JsonNode payload,
                                                       String baseUrl, String repoFullName) {
        if (!"published".equals(action)) {
            return null;
        }
        JsonNode release = payload.path("release");
        String tagName = textOrNull(release, "tag_name");
        String ref = baseUrl + "/" + repoFullName + "/releases/tag/" + tagName;

        return new NormalizationResult(EventType.RELEASE_PUBLISHED,
                new ReleasePublishedPayload(
                        tagName,
                        textOrNull(release, "name"),
                        textOrNull(release, "body"),
                        release.has("draft") ? release.get("draft").asBoolean() : null,
                        release.has("prerelease") ? release.get("prerelease").asBoolean() : null,
                        mapActor(release.path("author")),
                        textOrNull(release, "html_url"),
                        textOrNull(release, "created_at"),
                        textOrNull(release, "published_at")
                ), ref);
    }

    // ── Mapping helpers ─────────────────────────────────────────────────────

    /**
     * Maps a GitHub user/actor JSON node to an {@link Actor}.
     */
    Actor mapActor(JsonNode user) {
        if (user == null || user.isMissingNode() || user.isNull()) {
            return null;
        }
        String login = textOrNull(user, "login");
        String displayName = textOrNull(user, "display_login");
        if (displayName == null) {
            displayName = login;
        }
        String avatarUrl = textOrNull(user, "avatar_url");
        String url = textOrNull(user, "html_url");
        return new Actor(login, displayName, avatarUrl, url);
    }

    /**
     * Maps a GitHub issue JSON node to a {@link NormalizedIssue}.
     */
    NormalizedIssue mapIssue(JsonNode issue) {
        if (issue == null || issue.isMissingNode()) {
            return null;
        }
        return new NormalizedIssue(
                String.valueOf(issue.path("number").asInt()),
                textOrNull(issue, "title"),
                textOrNull(issue, "body"),
                textOrNull(issue, "state"),
                textOrNull(issue, "state_reason"),
                mapActor(issue.path("user")),
                mapActorList(issue.path("assignees")),
                mapLabelNames(issue.path("labels")),
                textOrNull(issue.path("milestone"), "title"),
                textOrNull(issue, "html_url"),
                textOrNull(issue, "created_at"),
                textOrNull(issue, "updated_at"),
                textOrNull(issue, "closed_at")
        );
    }

    /**
     * Maps a GitHub pull request JSON node to a {@link NormalizedPullRequest}.
     */
    NormalizedPullRequest mapPullRequest(JsonNode pr) {
        if (pr == null || pr.isMissingNode()) {
            return null;
        }
        return new NormalizedPullRequest(
                String.valueOf(pr.path("number").asInt()),
                textOrNull(pr, "title"),
                textOrNull(pr, "body"),
                textOrNull(pr, "state"),
                null, // stateDetail not directly available in GitHub PR JSON
                mapActor(pr.path("user")),
                mapActorList(pr.path("assignees")),
                mapLabelNames(pr.path("labels")),
                textOrNull(pr.path("milestone"), "title"),
                textOrNull(pr, "html_url"),
                textOrNull(pr, "created_at"),
                textOrNull(pr, "updated_at"),
                textOrNull(pr, "closed_at"),
                textOrNull(pr.path("head"), "ref"),
                textOrNull(pr.path("base"), "ref"),
                textOrNull(pr.path("head"), "sha"),
                pr.has("draft") ? pr.get("draft").asBoolean() : null,
                pr.has("merged") ? pr.get("merged").asBoolean() : null,
                textOrNull(pr, "merged_at"),
                mapActor(pr.path("merged_by")),
                pr.has("additions") && !pr.get("additions").isNull()
                        ? pr.get("additions").asInt() : null,
                pr.has("deletions") && !pr.get("deletions").isNull()
                        ? pr.get("deletions").asInt() : null,
                pr.has("changed_files") && !pr.get("changed_files").isNull()
                        ? pr.get("changed_files").asInt() : null
        );
    }

    /**
     * Maps a GitHub issue node (that has a pull_request key) into a minimal
     * {@link NormalizedPullRequest}. Used for IssueCommentEvent on PRs where
     * only issue-level data is available.
     */
    private NormalizedPullRequest mapPullRequestFromIssue(JsonNode issueNode) {
        return new NormalizedPullRequest(
                String.valueOf(issueNode.path("number").asInt()),
                textOrNull(issueNode, "title"),
                textOrNull(issueNode, "body"),
                textOrNull(issueNode, "state"),
                null,
                mapActor(issueNode.path("user")),
                mapActorList(issueNode.path("assignees")),
                mapLabelNames(issueNode.path("labels")),
                textOrNull(issueNode.path("milestone"), "title"),
                textOrNull(issueNode, "html_url"),
                textOrNull(issueNode, "created_at"),
                textOrNull(issueNode, "updated_at"),
                textOrNull(issueNode, "closed_at"),
                null, null, null, // head/base branch, headSha not available from issue
                null, null, null, null, // draft, merged, mergedAt, mergedBy
                null, null, null  // additions, deletions, changedFiles
        );
    }

    /**
     * Maps a GitHub comment JSON node to a {@link NormalizedComment}.
     */
    NormalizedComment mapComment(JsonNode comment) {
        if (comment == null || comment.isMissingNode()) {
            return null;
        }
        return new NormalizedComment(
                String.valueOf(comment.path("id").asLong()),
                textOrNull(comment, "body"),
                mapActor(comment.path("user")),
                textOrNull(comment, "html_url"),
                textOrNull(comment, "created_at"),
                textOrNull(comment, "updated_at")
        );
    }

    /**
     * Maps a GitHub review JSON node to a {@link NormalizedReview}.
     */
    NormalizedReview mapReview(JsonNode review) {
        if (review == null || review.isMissingNode()) {
            return null;
        }
        return new NormalizedReview(
                String.valueOf(review.path("id").asLong()),
                textOrNull(review, "state"),
                textOrNull(review, "body"),
                mapActor(review.path("user")),
                textOrNull(review, "html_url"),
                textOrNull(review, "submitted_at")
        );
    }

    /**
     * Maps a GitHub label JSON node to a {@link NormalizedLabel}.
     */
    NormalizedLabel mapLabel(JsonNode label) {
        if (label == null || label.isMissingNode()) {
            return null;
        }
        return new NormalizedLabel(
                textOrNull(label, "name"),
                textOrNull(label, "color"),
                textOrNull(label, "description")
        );
    }

    /**
     * Maps a GitHub commits array to a list of {@link Commit} records.
     */
    List<Commit> mapCommits(JsonNode commitsArray, String baseUrl, String repoFullName) {
        List<Commit> commits = new ArrayList<>();
        if (commitsArray == null || !commitsArray.isArray()) {
            return commits;
        }
        for (JsonNode c : commitsArray) {
            String sha = textOrNull(c, "sha");
            String url = sha != null ? baseUrl + "/" + repoFullName + "/commit/" + sha : null;
            commits.add(new Commit(
                    sha,
                    textOrNull(c, "message"),
                    new Commit.CommitAuthor(
                            textOrNull(c.path("author"), "name"),
                            textOrNull(c.path("author"), "email")
                    ),
                    url
            ));
        }
        return commits;
    }

    // ── Ref URL builders ────────────────────────────────────────────────────

    private String buildIssueRef(String baseUrl, String repoFullName, int number) {
        return baseUrl + "/" + repoFullName + "/issues/" + number;
    }

    private String buildPrRef(String baseUrl, String repoFullName, int number) {
        return baseUrl + "/" + repoFullName + "/pull/" + number;
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private Change mapChange(JsonNode changes) {
        if (changes == null || changes.isMissingNode()) {
            return null;
        }
        // GitHub changes object has field names as keys, each with {from: "old value"}
        var fields = changes.fieldNames();
        if (fields.hasNext()) {
            String field = fields.next();
            String from = textOrNull(changes.path(field), "from");
            return new Change(field, from, null, null);
        }
        return null;
    }

    private List<Actor> mapActorList(JsonNode array) {
        List<Actor> actors = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                Actor a = mapActor(node);
                if (a != null) {
                    actors.add(a);
                }
            }
        }
        return actors;
    }

    private List<String> mapLabelNames(JsonNode array) {
        List<String> names = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                String name = textOrNull(node, "name");
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private JsonNode buildSourceData(String githubEventId, String githubEventType,
                                      String action, JsonNode rawPayload) {
        ObjectNode source = objectMapper.createObjectNode();
        source.put("githubEventId", githubEventId);
        source.put("githubEventType", githubEventType);
        if (action != null) {
            source.put("githubAction", action);
        }
        source.set("rawPayload", rawPayload);
        return source;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    /**
     * Internal result holder for event normalization.
     */
    private record NormalizationResult(EventType type, EventPayload payload, String ref) {
    }
}
