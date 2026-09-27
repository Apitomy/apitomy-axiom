package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.model.payloads.IssueAssignedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueClosedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCommentCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueCreatedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueLabeledPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueReopenedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueUnassignedPayload;
import io.apitomy.axiom.core.events.model.payloads.IssueUpdatedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JiraEventNormalizerV2}. Uses plain JUnit 5 with
 * programmatically constructed fixture JSON.
 */
class JiraEventNormalizerV2Test {

    private static final String CONNECTION_ID = "test-jira-connection";
    private static final String BASE_URL = "https://myorg.atlassian.net";
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Cutoff time: events before this are ignored. */
    private static final Instant SINCE = Instant.parse("2026-09-18T00:00:00Z");

    private JiraEventNormalizerV2 normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new JiraEventNormalizerV2(mapper);
    }

    // -- Test 1: New issue produces ISSUE_CREATED ---------------------------------

    @Test
    void newIssue_producesIssueCreated() {
        ObjectNode issue = buildIssue("PROJ-101", "New bug", "open", "To Do",
                "2026-09-18T10:00:00.000+0000", "2026-09-18T10:00:00.000+0000");

        List<NormalizedEvent> events = normalizer.normalizeIssue(issue, SINCE, CONNECTION_ID, BASE_URL);

        assertEquals(1, events.size());
        NormalizedEvent event = events.get(0);
        assertEquals(EventType.ISSUE_CREATED, event.type());
        assertEquals("PROJ-101-created", event.sourceEventId());
        assertEquals("jira", event.source());
        assertEquals(CONNECTION_ID, event.connectionId());
        assertEquals("https://myorg.atlassian.net/browse/PROJ-101", event.ref());
        assertNotNull(event.id());
        assertNotNull(event.actor());
        assertEquals("reporter1", event.actor().displayName());

        assertInstanceOf(IssueCreatedPayload.class, event.payload());
        IssueCreatedPayload payload = (IssueCreatedPayload) event.payload();
        assertEquals("PROJ-101", payload.issue().number());
        assertEquals("New bug", payload.issue().title());
        assertEquals("open", payload.issue().state());
        assertEquals("To Do", payload.issue().stateDetail());
    }

    // -- Test 2: Status change to Done produces ISSUE_CLOSED ----------------------

    @Test
    void statusChangeToDone_producesIssueClosed() {
        ObjectNode issue = buildIssue("PROJ-102", "Fix login", "closed", "Done",
                "2026-09-17T08:00:00.000+0000", "2026-09-18T10:00:00.000+0000");
        addChangelog(issue,
                buildHistory("5001", "2026-09-18T10:00:00.000+0000", "user1", "User One",
                        buildItem("status", "In Progress", "Done")));

        List<NormalizedEvent> events = normalizer.normalizeIssue(issue, SINCE, CONNECTION_ID, BASE_URL);

        // Should NOT have ISSUE_CREATED (created before since), but should have ISSUE_CLOSED
        List<NormalizedEvent> closedEvents = events.stream()
                .filter(e -> e.type() == EventType.ISSUE_CLOSED)
                .toList();
        assertEquals(1, closedEvents.size());
        NormalizedEvent event = closedEvents.get(0);
        assertEquals("PROJ-102-5001", event.sourceEventId());
        assertInstanceOf(IssueClosedPayload.class, event.payload());
    }

    // -- Test 3: Status change from Done produces ISSUE_REOPENED ------------------

    @Test
    void statusChangeFromDone_producesIssueReopened() {
        ObjectNode issue = buildIssue("PROJ-103", "Regression", "open", "To Do",
                "2026-09-17T08:00:00.000+0000", "2026-09-18T10:00:00.000+0000");
        addChangelog(issue,
                buildHistory("5002", "2026-09-18T10:00:00.000+0000", "user2", "User Two",
                        buildItem("status", "Done", "To Do")));

        List<NormalizedEvent> events = normalizer.normalizeIssue(issue, SINCE, CONNECTION_ID, BASE_URL);

        List<NormalizedEvent> reopenedEvents = events.stream()
                .filter(e -> e.type() == EventType.ISSUE_REOPENED)
                .toList();
        assertEquals(1, reopenedEvents.size());
        NormalizedEvent event = reopenedEvents.get(0);
        assertEquals("PROJ-103-5002", event.sourceEventId());
        assertInstanceOf(IssueReopenedPayload.class, event.payload());
    }

    // -- Test 4: Summary change produces ISSUE_UPDATED with Change ----------------

    @Test
    void summaryChange_producesIssueUpdatedWithChange() {
        ObjectNode issue = buildIssue("PROJ-104", "Updated title", "open", "To Do",
                "2026-09-17T08:00:00.000+0000", "2026-09-18T10:00:00.000+0000");
        addChangelog(issue,
                buildHistory("5003", "2026-09-18T10:00:00.000+0000", "user1", "User One",
                        buildItem("summary", "Old title", "Updated title")));

        List<NormalizedEvent> events = normalizer.normalizeIssue(issue, SINCE, CONNECTION_ID, BASE_URL);

        List<NormalizedEvent> updatedEvents = events.stream()
                .filter(e -> e.type() == EventType.ISSUE_UPDATED)
                .toList();
        assertEquals(1, updatedEvents.size());
        NormalizedEvent event = updatedEvents.get(0);
        assertInstanceOf(IssueUpdatedPayload.class, event.payload());
        IssueUpdatedPayload payload = (IssueUpdatedPayload) event.payload();
        assertNotNull(payload.change());
        assertEquals("summary", payload.change().field());
        assertEquals("Old title", payload.change().from());
        assertEquals("Updated title", payload.change().to());
    }

    // -- Test 5: Assignee set produces ISSUE_ASSIGNED -----------------------------

    @Test
    void assigneeSet_producesIssueAssigned() {
        ObjectNode issue = buildIssue("PROJ-105", "Assign me", "open", "To Do",
                "2026-09-17T08:00:00.000+0000", "2026-09-18T10:00:00.000+0000");
        addChangelog(issue,
                buildHistory("5004", "2026-09-18T10:00:00.000+0000", "user1", "User One",
                        buildItem("assignee", null, "John Doe")));

        List<NormalizedEvent> events = normalizer.normalizeIssue(issue, SINCE, CONNECTION_ID, BASE_URL);

        List<NormalizedEvent> assignedEvents = events.stream()
                .filter(e -> e.type() == EventType.ISSUE_ASSIGNED)
                .toList();
        assertEquals(1, assignedEvents.size());
        NormalizedEvent event = assignedEvents.get(0);
        assertInstanceOf(IssueAssignedPayload.class, event.payload());
        IssueAssignedPayload payload = (IssueAssignedPayload) event.payload();
        assertEquals("John Doe", payload.assignee().displayName());
    }

    // -- Test 6: Assignee cleared produces ISSUE_UNASSIGNED -----------------------

    @Test
    void assigneeCleared_producesIssueUnassigned() {
        ObjectNode issue = buildIssue("PROJ-106", "Unassign me", "open", "To Do",
                "2026-09-17T08:00:00.000+0000", "2026-09-18T10:00:00.000+0000");
        addChangelog(issue,
                buildHistory("5005", "2026-09-18T10:00:00.000+0000", "user1", "User One",
                        buildItem("assignee", "John Doe", null)));

        List<NormalizedEvent> events = normalizer.normalizeIssue(issue, SINCE, CONNECTION_ID, BASE_URL);

        List<NormalizedEvent> unassignedEvents = events.stream()
                .filter(e -> e.type() == EventType.ISSUE_UNASSIGNED)
                .toList();
        assertEquals(1, unassignedEvents.size());
        NormalizedEvent event = unassignedEvents.get(0);
        assertInstanceOf(IssueUnassignedPayload.class, event.payload());
    }

    // -- Test 7: Label added produces ISSUE_LABELED with correct label ------------

    @Test
    void labelAdded_producesIssueLabeledWithCorrectLabel() {
        ObjectNode issue = buildIssue("PROJ-107", "Label me", "open", "To Do",
                "2026-09-17T08:00:00.000+0000", "2026-09-18T10:00:00.000+0000");
        addChangelog(issue,
                buildHistory("5006", "2026-09-18T10:00:00.000+0000", "user1", "User One",
                        buildItem("labels", "existing-label", "existing-label new-label")));

        List<NormalizedEvent> events = normalizer.normalizeIssue(issue, SINCE, CONNECTION_ID, BASE_URL);

        List<NormalizedEvent> labeledEvents = events.stream()
                .filter(e -> e.type() == EventType.ISSUE_LABELED)
                .toList();
        assertEquals(1, labeledEvents.size());
        NormalizedEvent event = labeledEvents.get(0);
        assertInstanceOf(IssueLabeledPayload.class, event.payload());
        IssueLabeledPayload payload = (IssueLabeledPayload) event.payload();
        assertEquals("new-label", payload.label().name());
    }

    // -- Test 8: New comment produces ISSUE_COMMENT_CREATED -----------------------

    @Test
    void newComment_producesIssueCommentCreated() {
        ObjectNode issue = buildIssue("PROJ-108", "Comment me", "open", "To Do",
                "2026-09-17T08:00:00.000+0000", "2026-09-18T10:00:00.000+0000");
        addComments(issue,
                buildComment("7001", "commenter1", "Commenter One",
                        "This is a comment",
                        "2026-09-18T09:00:00.000+0000",
                        "2026-09-18T09:00:00.000+0000"));

        List<NormalizedEvent> events = normalizer.normalizeIssue(issue, SINCE, CONNECTION_ID, BASE_URL);

        List<NormalizedEvent> commentEvents = events.stream()
                .filter(e -> e.type() == EventType.ISSUE_COMMENT_CREATED)
                .toList();
        assertEquals(1, commentEvents.size());
        NormalizedEvent event = commentEvents.get(0);
        assertEquals("PROJ-108-comment-7001", event.sourceEventId());
        assertInstanceOf(IssueCommentCreatedPayload.class, event.payload());
        IssueCommentCreatedPayload payload = (IssueCommentCreatedPayload) event.payload();
        assertEquals("7001", payload.comment().id());
        assertEquals("This is a comment", payload.comment().body());
        assertEquals("Commenter One", payload.comment().author().displayName());
    }

    // -- Test 9: Multiple changes in one history produce multiple events -----------

    @Test
    void multipleChangesInOneHistory_producesMultipleEvents() {
        ObjectNode issue = buildIssue("PROJ-109", "Multi change", "open", "In Progress",
                "2026-09-17T08:00:00.000+0000", "2026-09-18T10:00:00.000+0000");

        // One history entry with two items: summary change + assignee change
        ObjectNode history = mapper.createObjectNode();
        history.put("id", "5007");
        history.put("created", "2026-09-18T10:00:00.000+0000");
        ObjectNode author = mapper.createObjectNode();
        author.put("accountId", "user1");
        author.put("displayName", "User One");
        history.set("author", author);

        ArrayNode items = mapper.createArrayNode();
        items.add(buildItem("summary", "Old title", "New title"));
        items.add(buildItem("assignee", null, "Jane Smith"));
        history.set("items", items);

        addChangelog(issue, history);

        List<NormalizedEvent> events = normalizer.normalizeIssue(issue, SINCE, CONNECTION_ID, BASE_URL);

        // Should produce ISSUE_UPDATED (summary) + ISSUE_ASSIGNED (assignee)
        List<NormalizedEvent> updatedEvents = events.stream()
                .filter(e -> e.type() == EventType.ISSUE_UPDATED)
                .toList();
        List<NormalizedEvent> assignedEvents = events.stream()
                .filter(e -> e.type() == EventType.ISSUE_ASSIGNED)
                .toList();
        assertEquals(1, updatedEvents.size());
        assertEquals(1, assignedEvents.size());

        // sourceEventIds should have suffixes
        assertTrue(events.stream().anyMatch(e -> "PROJ-109-5007-0".equals(e.sourceEventId())));
        assertTrue(events.stream().anyMatch(e -> "PROJ-109-5007-1".equals(e.sourceEventId())));
    }

    // -- Test 10: Old changes are ignored -----------------------------------------

    @Test
    void oldChanges_areIgnored() {
        ObjectNode issue = buildIssue("PROJ-110", "Old stuff", "open", "To Do",
                "2026-09-17T08:00:00.000+0000", "2026-09-17T10:00:00.000+0000");
        // Changelog entry before "since"
        addChangelog(issue,
                buildHistory("5008", "2026-09-17T08:00:00.000+0000", "user1", "User One",
                        buildItem("status", "To Do", "In Progress")));
        // Comment before "since"
        addComments(issue,
                buildComment("7002", "commenter1", "Commenter One",
                        "Old comment",
                        "2026-09-17T07:00:00.000+0000",
                        "2026-09-17T07:00:00.000+0000"));

        List<NormalizedEvent> events = normalizer.normalizeIssue(issue, SINCE, CONNECTION_ID, BASE_URL);

        // Issue created is also before "since", so no events at all
        assertTrue(events.isEmpty(), "Expected no events for old changes");
    }

    // -- Test: sourceData is populated --------------------------------------------

    @Test
    void sourceData_containsJiraMetadata() {
        ObjectNode issue = buildIssue("PROJ-111", "Source data test", "open", "To Do",
                "2026-09-18T10:00:00.000+0000", "2026-09-18T10:00:00.000+0000");
        // Add project info to fields
        ObjectNode fields = (ObjectNode) issue.get("fields");
        ObjectNode project = mapper.createObjectNode();
        project.put("key", "PROJ");
        project.put("name", "Test Project");
        fields.set("project", project);
        ObjectNode issueType = mapper.createObjectNode();
        issueType.put("name", "Bug");
        fields.set("issuetype", issueType);

        List<NormalizedEvent> events = normalizer.normalizeIssue(issue, SINCE, CONNECTION_ID, BASE_URL);

        assertFalse(events.isEmpty());
        NormalizedEvent event = events.get(0);
        assertNotNull(event.sourceData());
        assertEquals("10001", event.sourceData().get("jiraId").asText());
        assertEquals("PROJ", event.sourceData().get("project").get("key").asText());
        assertEquals("Test Project", event.sourceData().get("project").get("name").asText());
        assertEquals("Bug", event.sourceData().get("issueType").get("name").asText());
    }

    // -- Test: ADF description is extracted ----------------------------------------

    @Test
    void adfDescription_isExtractedAsText() {
        ObjectNode issue = buildIssue("PROJ-112", "ADF test", "open", "To Do",
                "2026-09-18T10:00:00.000+0000", "2026-09-18T10:00:00.000+0000");
        // Replace description with ADF format
        ObjectNode fields = (ObjectNode) issue.get("fields");
        ObjectNode adf = mapper.createObjectNode();
        adf.put("type", "doc");
        adf.put("version", 1);
        ArrayNode content = mapper.createArrayNode();
        ObjectNode paragraph = mapper.createObjectNode();
        paragraph.put("type", "paragraph");
        ArrayNode paraContent = mapper.createArrayNode();
        ObjectNode textNode = mapper.createObjectNode();
        textNode.put("type", "text");
        textNode.put("text", "Hello world");
        paraContent.add(textNode);
        paragraph.set("content", paraContent);
        content.add(paragraph);
        adf.set("content", content);
        fields.set("description", adf);

        List<NormalizedEvent> events = normalizer.normalizeIssue(issue, SINCE, CONNECTION_ID, BASE_URL);

        assertFalse(events.isEmpty());
        IssueCreatedPayload payload = (IssueCreatedPayload) events.get(0).payload();
        assertEquals("Hello world", payload.issue().body());
    }

    // -- Test: Issue ref construction ---------------------------------------------

    @Test
    void issueRef_isConstructedCorrectly() {
        ObjectNode issue = buildIssue("PROJ-200", "Ref test", "open", "To Do",
                "2026-09-18T10:00:00.000+0000", "2026-09-18T10:00:00.000+0000");

        List<NormalizedEvent> events = normalizer.normalizeIssue(issue, SINCE, CONNECTION_ID, BASE_URL);

        assertEquals(1, events.size());
        assertEquals("https://myorg.atlassian.net/browse/PROJ-200", events.get(0).ref());
    }

    // -- Fixture builders ---------------------------------------------------------

    private ObjectNode buildIssue(String key, String summary, String state,
                                   String statusName, String created, String updated) {
        ObjectNode issue = mapper.createObjectNode();
        issue.put("id", "10001");
        issue.put("key", key);

        ObjectNode fields = issue.putObject("fields");
        fields.put("summary", summary);
        fields.put("description", "Description for " + summary);
        fields.put("created", created);
        fields.put("updated", updated);

        // Status with category
        ObjectNode status = fields.putObject("status");
        status.put("name", statusName);
        ObjectNode statusCategory = status.putObject("statusCategory");
        String categoryKey = "closed".equals(state) ? "done" : "indeterminate";
        statusCategory.put("key", categoryKey);
        statusCategory.put("name", "closed".equals(state) ? "Done" : "In Progress");

        // Reporter
        ObjectNode reporter = fields.putObject("reporter");
        reporter.put("accountId", "reporter1-id");
        reporter.put("displayName", "reporter1");

        // Labels
        fields.putArray("labels");

        // No assignee by default
        fields.putNull("assignee");

        // Empty changelog by default
        ObjectNode changelog = issue.putObject("changelog");
        changelog.putArray("histories");

        // Empty comments by default
        ObjectNode comment = fields.putObject("comment");
        comment.putArray("comments");

        return issue;
    }

    private ObjectNode buildHistory(String id, String created, String authorAccountId,
                                     String authorDisplayName, ObjectNode... items) {
        ObjectNode history = mapper.createObjectNode();
        history.put("id", id);
        history.put("created", created);

        ObjectNode author = mapper.createObjectNode();
        author.put("accountId", authorAccountId);
        author.put("displayName", authorDisplayName);
        history.set("author", author);

        ArrayNode itemsArray = mapper.createArrayNode();
        for (ObjectNode item : items) {
            itemsArray.add(item);
        }
        history.set("items", itemsArray);

        return history;
    }

    private ObjectNode buildItem(String field, String fromString, String toString) {
        ObjectNode item = mapper.createObjectNode();
        item.put("field", field);
        if (fromString != null) {
            item.put("fromString", fromString);
        } else {
            item.putNull("fromString");
        }
        if (toString != null) {
            item.put("toString", toString);
        } else {
            item.putNull("toString");
        }
        return item;
    }

    private ObjectNode buildComment(String id, String authorAccountId,
                                     String authorDisplayName, String body,
                                     String created, String updated) {
        ObjectNode comment = mapper.createObjectNode();
        comment.put("id", id);
        comment.put("body", body);
        comment.put("created", created);
        comment.put("updated", updated);

        ObjectNode author = mapper.createObjectNode();
        author.put("accountId", authorAccountId);
        author.put("displayName", authorDisplayName);
        comment.set("author", author);

        return comment;
    }

    private void addChangelog(ObjectNode issue, ObjectNode... histories) {
        ArrayNode historiesArray = (ArrayNode) issue.path("changelog").path("histories");
        for (ObjectNode history : histories) {
            historiesArray.add(history);
        }
    }

    private void addComments(ObjectNode issue, ObjectNode... comments) {
        ArrayNode commentsArray = (ArrayNode) issue.path("fields").path("comment").path("comments");
        for (ObjectNode comment : comments) {
            commentsArray.add(comment);
        }
    }
}
