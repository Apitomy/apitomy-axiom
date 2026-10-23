package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.model.payloads.IssueCommentDeletedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCommentUpdatedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueUnassignedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueUpdatedPayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the Jira ingestion spike defects, run against sanitized real fixtures. Every
 * normalizer output is validated against the event schemas.
 */
class JiraEventNormalizerV2FixtureTest {

    private static final Instant SINCE = Instant.parse("2026-10-01T00:00:00Z");
    private static final String SOURCE_ID = "jira-fixtures";
    private static final String BASE_URL = "https://example.atlassian.net";
    private static final Pattern ISO_UTC =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");
    private static final JiraStatusCategories CATEGORIES =
            JiraStatusCategories.fromStatuses(JiraFixtures.load("jira-statuses.json"));

    private final JiraEventNormalizerV2 normalizer = new JiraEventNormalizerV2(JiraFixtures.MAPPER);

    private List<NormalizedEvent> normalize(String fixture, JiraNormalizationContext context) {
        List<NormalizedEvent> events = normalizer.normalizeIssue(JiraFixtures.load(fixture), SINCE, SOURCE_ID,
                BASE_URL, context);
        JiraFixtures.assertSchemaValid(events);
        return events;
    }

    private List<NormalizedEvent> apicurio137() {
        return normalize("apicurio-137-issue.json", new JiraNormalizationContext(CATEGORIES, Map.of()));
    }

    private static JiraNormalizationContext iptContext(JiraStatusCategories categories) {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("Story Points", "customfield_10028");
        return new JiraNormalizationContext(categories, extra);
    }

    private static List<NormalizedEvent> ofType(List<NormalizedEvent> events, EventType type, String timestamp) {
        return events.stream().filter(e -> e.type() == type && e.timestamp().equals(timestamp)).toList();
    }

    private static NormalizedEvent single(List<NormalizedEvent> events, EventType type, String timestamp) {
        List<NormalizedEvent> matches = ofType(events, type, timestamp);
        assertEquals(1, matches.size(), type + " at " + timestamp + " in "
                + events.stream().map(e -> e.type().value() + "@" + e.timestamp()).toList());
        return matches.get(0);
    }

    private static JsonNode issue(NormalizedEvent event) {
        JsonNode payload = JiraFixtures.MAPPER.valueToTree(event.payload());
        return payload.path("issue");
    }

    private static String text(JsonNode node, String field) {
        return JiraEventNormalizerV2.textOrNull(node, field);
    }

    private static List<String> labels(JsonNode issue) {
        return JiraFixtures.MAPPER.convertValue(issue.path("labels"),
                JiraFixtures.MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    @Test
    void everyEvent_carriesTheIssueStateAsOfThatEvent() {
        List<NormalizedEvent> events = apicurio137();

        JsonNode created = issue(single(events, EventType.ISSUE_CREATED, "2026-10-21T14:53:07.835Z"));
        assertEquals("Spike issue title", text(created, "title"));
        assertEquals("Initial description.", text(created, "body"));
        assertEquals("New", text(created, "stateDetail"));
        assertEquals("open", text(created, "state"));
        assertEquals(List.of("axiom-spike"), labels(created));
        assertEquals("acct-reporter-1", text(created.path("assignees").get(0), "login"));
        assertEquals("2026-10-21T14:53:07.835Z", text(created, "updatedAt"));

        JsonNode renamed = issue(single(events, EventType.ISSUE_UPDATED, "2026-10-21T14:53:18.191Z"));
        assertEquals("Spike issue title (renamed)", text(renamed, "title"));
        assertEquals("Initial description.", text(renamed, "body"));

        JsonNode described = issue(single(events, EventType.ISSUE_UPDATED, "2026-10-21T14:53:21.586Z"));
        assertEquals("Updated description.", text(described, "body"));

        List<NormalizedEvent> labeled = ofType(events, EventType.ISSUE_LABELED, "2026-10-21T14:53:25.001Z");
        assertEquals(2, labeled.size());
        assertEquals(List.of("axiom-spike", "spike-a", "spike-b"), labels(issue(labeled.get(0))));

        JsonNode unlabeled = issue(single(events, EventType.ISSUE_UNLABELED, "2026-10-21T14:53:28.375Z"));
        assertEquals(List.of("axiom-spike", "spike-b"), labels(unlabeled));

        JsonNode refinement = issue(single(events, EventType.ISSUE_UPDATED, "2026-10-21T14:53:38.380Z"));
        assertEquals("Refinement", text(refinement, "stateDetail"));
        assertEquals("open", text(refinement, "state"));

        JsonNode inProgress = issue(single(events, EventType.ISSUE_UPDATED, "2026-10-21T14:53:41.795Z"));
        assertEquals("In Progress", text(inProgress, "stateDetail"));

        JsonNode commented = issue(single(events, EventType.ISSUE_COMMENT_CREATED, "2026-10-21T14:53:45.185Z"));
        assertEquals("In Progress", text(commented, "stateDetail"));

        JsonNode reopened = issue(single(events, EventType.ISSUE_REOPENED, "2026-10-21T14:54:17.424Z"));
        assertEquals("open", text(reopened, "state"));
        assertEquals("New", text(reopened, "stateDetail"));
        assertNull(text(reopened, "closedAt"));
        assertEquals(0, reopened.path("assignees").size());
    }

    @Test
    void closedEvent_hasClosedSnapshotWithClosedAt() {
        List<NormalizedEvent> events = apicurio137();

        JsonNode closed = issue(single(events, EventType.ISSUE_CLOSED, "2026-10-21T14:54:06.982Z"));
        assertEquals("closed", text(closed, "state"));
        assertEquals("Closed", text(closed, "stateDetail"));
        assertEquals("2026-10-21T14:54:06.982Z", text(closed, "closedAt"));

        JsonNode unassigned = issue(single(events, EventType.ISSUE_UNASSIGNED, "2026-10-21T14:54:10.349Z"));
        assertEquals("closed", text(unassigned, "state"));
        assertEquals("2026-10-21T14:54:06.982Z", text(unassigned, "closedAt"));

        for (NormalizedEvent event : events) {
            JsonNode snapshot = issue(event);
            if ("closed".equals(text(snapshot, "state"))) {
                assertTrue(text(snapshot, "closedAt") != null, event.timestamp() + " closed without closedAt");
            }
        }
    }

    @Test
    void statusChangeBetweenOpenCategories_isAnUpdateNotAClose() {
        List<NormalizedEvent> events = apicurio137();

        NormalizedEvent refinement = single(events, EventType.ISSUE_UPDATED, "2026-10-21T14:53:38.380Z");
        JsonNode change = JiraFixtures.MAPPER.valueToTree(refinement.payload()).path("change");
        assertEquals("status", text(change, "field"));
        assertEquals("New", text(change, "from"));
        assertEquals("Refinement", text(change, "to"));
    }

    @Test
    void unassignedEvent_hasPreviousAssigneeLoginAndName() {
        NormalizedEvent event = single(apicurio137(), EventType.ISSUE_UNASSIGNED, "2026-10-21T14:54:10.349Z");
        IssueUnassignedPayload payload = (IssueUnassignedPayload) event.payload();
        assertEquals("acct-reporter-1", payload.assignee().login());
        assertEquals("Reporter One", payload.assignee().displayName());
    }

    @Test
    void allTimestamps_areIsoUtc() {
        for (NormalizedEvent event : apicurio137()) {
            assertTrue(ISO_UTC.matcher(event.timestamp()).matches(), event.timestamp());
            JsonNode payload = JiraFixtures.MAPPER.valueToTree(event.payload());
            for (String field : List.of("createdAt", "updatedAt", "closedAt")) {
                String value = text(payload.path("issue"), field);
                assertTrue(value == null || ISO_UTC.matcher(value).matches(), field + "=" + value);
                String commentValue = text(payload.path("comment"), field);
                assertTrue(commentValue == null || ISO_UTC.matcher(commentValue).matches(),
                        "comment." + field + "=" + commentValue);
            }
        }
    }

    @Test
    void sourceData_hasProjectStatusAndSnapshotValues() {
        List<NormalizedEvent> events = apicurio137();

        JsonNode created = single(events, EventType.ISSUE_CREATED, "2026-10-21T14:53:07.835Z").sourceData();
        assertEquals("7358053", text(created, "jiraId"));
        assertEquals("APICURIO", text(created.path("project"), "key"));
        assertEquals("Apicurio Project", text(created.path("project"), "name"));
        assertEquals("Task", text(created.path("issueType"), "name"));
        assertEquals("New", text(created.path("status"), "name"));
        assertEquals("new", text(created.path("status"), "categoryKey"));
        assertEquals("Undefined", text(created.path("priority"), "name"));
        assertFalse(created.has("resolution"));
        assertFalse(created.has("extraFields"));

        JsonNode closed = single(events, EventType.ISSUE_CLOSED, "2026-10-21T14:54:06.982Z").sourceData();
        assertEquals("Closed", text(closed.path("status"), "name"));
        assertEquals("done", text(closed.path("status"), "categoryKey"));
        assertEquals("Major", text(closed.path("priority"), "name"));
        assertEquals("Done", text(closed.path("resolution"), "name"));
    }

    @Test
    void customDoneStatusName_isClosedByCategory() {
        List<NormalizedEvent> events = normalize("ipt-1001-issue.json", iptContext(CATEGORIES));

        JsonNode closed = issue(single(events, EventType.ISSUE_CLOSED, "2026-10-20T09:20:00Z"));
        assertEquals("closed", text(closed, "state"));
        assertEquals("Shipped", text(closed, "stateDetail"));
        assertEquals("2026-10-20T09:20:00Z", text(closed, "closedAt"));
    }

    @Test
    void customDoneStatusName_withoutStatusLookup_fallsBackToNameMatching() {
        List<NormalizedEvent> events = normalize("ipt-1001-issue.json",
                iptContext(JiraStatusCategories.empty()));

        assertTrue(ofType(events, EventType.ISSUE_CLOSED, "2026-10-20T09:20:00Z").isEmpty());
        NormalizedEvent update = events.stream()
                .filter(e -> e.type() == EventType.ISSUE_UPDATED && e.timestamp().equals("2026-10-20T09:20:00Z"))
                .filter(e -> "status".equals(text(JiraFixtures.MAPPER.valueToTree(e.payload()).path("change"),
                        "field")))
                .findFirst()
                .orElseThrow();
        assertEquals("Shipped", text(issue(update), "stateDetail"));
    }

    @Test
    void extraFields_sourceDataHoldsValueAsOfEachEvent() {
        List<NormalizedEvent> events = normalize("ipt-1001-issue.json", iptContext(CATEGORIES));

        JsonNode created = single(events, EventType.ISSUE_CREATED, "2026-10-20T08:00:00Z").sourceData();
        assertTrue(created.path("extraFields").has("Story Points"));
        assertTrue(created.path("extraFields").path("Story Points").isNull());
        assertFalse(created.has("dueDate"));
        assertFalse(created.has("fixVersions"));

        JsonNode closed = single(events, EventType.ISSUE_CLOSED, "2026-10-20T09:20:00Z").sourceData();
        assertEquals("5", text(closed.path("extraFields"), "Story Points"));
        assertEquals("2026-10-30", text(closed, "dueDate"));
        assertEquals("1.2.0", text(closed.path("fixVersions").get(0), "name"));
        assertEquals("core", text(closed.path("components").get(0), "name"));
        assertEquals("IPT", text(closed.path("project"), "key"));
    }

    private static NormalizedEvent byId(List<NormalizedEvent> events, String sourceEventId) {
        return events.stream().filter(e -> sourceEventId.equals(e.sourceEventId())).findFirst()
                .orElseThrow(() -> new AssertionError(sourceEventId + " not in "
                        + events.stream().map(NormalizedEvent::sourceEventId).toList()));
    }

    private static void assertChange(NormalizedEvent event, String field, String from, String to) {
        assertEquals(EventType.ISSUE_UPDATED, event.type(), event.sourceEventId());
        IssueUpdatedPayload payload = (IssueUpdatedPayload) event.payload();
        assertEquals(field, payload.change().field());
        assertEquals(from, payload.change().from());
        assertEquals(to, payload.change().to());
    }

    @Test
    void apicurio137_producesTheCompleteEventSet() {
        Set<String> ids = apicurio137().stream().map(NormalizedEvent::sourceEventId).collect(Collectors.toSet());
        assertEquals(Set.of(
                "APICURIO-137-created",
                "APICURIO-137-93654123",
                "APICURIO-137-93654138",
                "APICURIO-137-93654147-0",
                "APICURIO-137-93654147-1",
                "APICURIO-137-93654152",
                "APICURIO-137-93654157",
                "APICURIO-137-93654169",
                "APICURIO-137-93654174",
                "APICURIO-137-93654235",
                "APICURIO-137-93654249-0",
                "APICURIO-137-93654249-1",
                "APICURIO-137-93654254",
                "APICURIO-137-93654267-0",
                "APICURIO-137-93654267-1",
                "APICURIO-137-comment-19040900",
                "APICURIO-137-comment-19040900-updated-1792594436781"), ids);
    }

    @Test
    void commentDeletion_emitsIssueCommentDeleted() {
        NormalizedEvent event = byId(apicurio137(), "APICURIO-137-93654235");
        assertEquals(EventType.ISSUE_COMMENT_DELETED, event.type());
        IssueCommentDeletedPayload payload = (IssueCommentDeletedPayload) event.payload();
        assertNull(payload.comment().id());
        assertNull(payload.comment().author());
        assertEquals("Deleted comment text", payload.comment().body());
        assertEquals("acct-reporter-1", event.actor().login());
        assertEquals("In Progress", payload.issue().stateDetail());
    }

    @Test
    void priorityAndResolutionChanges_emitIssueUpdated() {
        List<NormalizedEvent> events = apicurio137();
        assertChange(byId(events, "APICURIO-137-93654157"), "priority", "Undefined", "Major");
        assertChange(byId(events, "APICURIO-137-93654249-0"), "resolution", null, "Done");
        assertEquals(EventType.ISSUE_CLOSED, byId(events, "APICURIO-137-93654249-1").type());
        assertChange(byId(events, "APICURIO-137-93654267-0"), "resolution", "Done", null);
        assertEquals(EventType.ISSUE_REOPENED, byId(events, "APICURIO-137-93654267-1").type());
        assertEquals("Major", byId(events, "APICURIO-137-93654157").sourceData().path("priority")
                .path("name").asText());
    }

    @Test
    void fixVersionComponentDueDateAndExtraFields_emitIssueUpdated() {
        List<NormalizedEvent> events = normalize("ipt-1001-issue.json", iptContext(CATEGORIES));
        assertChange(byId(events, "IPT-1001-2001-0"), "Fix Version", null, "1.2.0");
        assertChange(byId(events, "IPT-1001-2001-1"), "Component", null, "core");
        assertChange(byId(events, "IPT-1001-2002"), "duedate", null, "2026-10-30 00:00:00.0");
        assertChange(byId(events, "IPT-1001-2003"), "Story Points", null, "5");
        assertEquals("5", byId(events, "IPT-1001-2003").sourceData().path("extraFields")
                .path("Story Points").asText());
        assertChange(byId(events, "IPT-1001-2004-0"), "resolution", null, "Done");
        assertEquals(EventType.ISSUE_CLOSED, byId(events, "IPT-1001-2004-1").type());
    }

    @Test
    void unconfiguredCustomField_emitsNothing() {
        List<NormalizedEvent> events = normalize("ipt-1001-issue.json",
                new JiraNormalizationContext(CATEGORIES, Map.of()));
        assertTrue(events.stream().noneMatch(e -> e.sourceEventId().startsWith("IPT-1001-2003")));
        assertFalse(byId(events, "IPT-1001-created").sourceData().has("extraFields"));
    }

    @Test
    void extraFields_matchChangelogFieldCaseInsensitively() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("story points", "customfield_10028");
        List<NormalizedEvent> events = normalize("ipt-1001-issue.json",
                new JiraNormalizationContext(CATEGORIES, extra));
        assertChange(byId(events, "IPT-1001-2003"), "Story Points", null, "5");
        assertEquals("5", byId(events, "IPT-1001-2003").sourceData().path("extraFields")
                .path("story points").asText());
    }

    @Test
    void repeatedCommentEdits_produceDistinctIds() {
        List<NormalizedEvent> first = apicurio137();
        ObjectNode issue = (ObjectNode) JiraFixtures.load("apicurio-137-issue.json");
        ObjectNode comment = (ObjectNode) issue.path("fields").path("comment").path("comments").get(0);
        comment.put("updated", "2026-10-21T15:10:00.000+0000");
        List<NormalizedEvent> second = normalizer.normalizeIssue(issue, SINCE, SOURCE_ID, BASE_URL,
                new JiraNormalizationContext(CATEGORIES, Map.of()));
        JiraFixtures.assertSchemaValid(second);

        NormalizedEvent firstEdit = byId(first, "APICURIO-137-comment-19040900-updated-1792594436781");
        String secondId = "APICURIO-137-comment-19040900-updated-"
                + Instant.parse("2026-10-21T15:10:00Z").toEpochMilli();
        NormalizedEvent secondEdit = byId(second, secondId);
        assertEquals(EventType.ISSUE_COMMENT_UPDATED, firstEdit.type());
        assertEquals("2026-10-21T15:10:00Z", secondEdit.timestamp());
        assertEquals("In Progress", ((IssueCommentUpdatedPayload) firstEdit.payload()).issue().stateDetail());
        assertEquals("New", ((IssueCommentUpdatedPayload) secondEdit.payload()).issue().stateDetail(),
                "an edit after the reopen sees the reopened state");
    }
}
