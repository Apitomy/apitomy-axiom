package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Snapshot-correctness tests for {@link JiraIssueHistory}, using the sanitized APICURIO-137 changelog and the
 * IPT-modelled fixture.
 */
class JiraIssueHistoryTest {

    private static final JiraStatusCategories CATEGORIES =
            JiraStatusCategories.fromStatuses(JiraFixtures.load("jira-statuses.json"));

    private static JiraIssueHistory apicurio137() {
        return JiraIssueHistory.rebuild(JiraFixtures.load("apicurio-137-issue.json"),
                new JiraNormalizationContext(CATEGORIES, Map.of()));
    }

    private static JiraNormalizationContext iptContext() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("Story Points", "customfield_10028");
        return new JiraNormalizationContext(CATEGORIES, extra);
    }

    private static void assertState(JiraIssueState s, String statusName, String category, String summary,
                                     String description, List<String> labels, String priority,
                                     String resolution, String closedAt, String assigneeId) {
        assertEquals(statusName, s.statusName, "statusName");
        assertEquals(category, s.statusCategoryKey, "statusCategoryKey");
        assertEquals(summary, s.summary, "summary");
        assertEquals(description, s.description, "description");
        assertEquals(labels, s.labels, "labels");
        assertEquals(priority, s.priority, "priority");
        assertEquals(resolution, s.resolution, "resolution");
        assertEquals(closedAt, s.closedAt, "closedAt");
        assertEquals(assigneeId, s.assigneeAccountId, "assignee");
    }

    @Test
    void creationSnapshot_isTheStateBeforeEveryChange() {
        JiraIssueState s = apicurio137().created();
        assertState(s, "New", "new", "Spike issue title", "Initial description.", List.of("axiom-spike"),
                "Undefined", null, null, "acct-reporter-1");
        assertEquals("10142", s.statusId);
        assertEquals("Reporter One", s.assigneeDisplayName);
        assertEquals("2026-10-21T14:53:07.835Z", s.updatedAt);
    }

    @Test
    void snapshotAfterEachHistory_matchesApicurio137GroundTruth() {
        JiraIssueHistory h = apicurio137();
        String t = "Spike issue title";
        String r = "Spike issue title (renamed)";
        String d0 = "Initial description.";
        String d1 = "Updated description.";
        List<String> l0 = List.of("axiom-spike");
        List<String> l1 = List.of("axiom-spike", "spike-a", "spike-b");
        List<String> l2 = List.of("axiom-spike", "spike-b");
        String a = "acct-reporter-1";
        String closed = "2026-10-21T14:54:06.982Z";

        assertState(h.after("93654105"), "New", "new", t, d0, l0, "Undefined", null, null, a);
        assertState(h.after("93654123"), "New", "new", r, d0, l0, "Undefined", null, null, a);
        assertState(h.after("93654138"), "New", "new", r, d1, l0, "Undefined", null, null, a);
        assertState(h.after("93654147"), "New", "new", r, d1, l1, "Undefined", null, null, a);
        assertState(h.after("93654152"), "New", "new", r, d1, l2, "Undefined", null, null, a);
        assertState(h.after("93654157"), "New", "new", r, d1, l2, "Major", null, null, a);
        assertState(h.after("93654169"), "Refinement", "new", r, d1, l2, "Major", null, null, a);
        assertState(h.after("93654174"), "In Progress", "indeterminate", r, d1, l2, "Major", null, null, a);
        assertState(h.after("93654235"), "In Progress", "indeterminate", r, d1, l2, "Major", null, null, a);
        assertState(h.after("93654249"), "Closed", "done", r, d1, l2, "Major", "Done", closed, a);
        assertState(h.after("93654254"), "Closed", "done", r, d1, l2, "Major", "Done", closed, null);
        assertState(h.after("93654267"), "New", "new", r, d1, l2, "Major", null, null, null);

        assertEquals("2026-10-21T14:54:06.982Z", h.after("93654249").updatedAt);
        assertEquals("2026-10-21T14:54:17.424Z", h.after("93654267").updatedAt);
        assertEquals("6", h.after("93654249").statusId);
    }

    @Test
    void unknownHistoryId_returnsCurrentState() {
        JiraIssueHistory h = apicurio137();
        assertSame(h.current(), h.after("does-not-exist"));
        assertSame(h.after("93654267"), h.current());
    }

    @Test
    void asOf_returnsSnapshotInEffectAtTheInstant() {
        JiraIssueHistory h = apicurio137();
        assertEquals("In Progress", h.asOf(Instant.parse("2026-10-21T14:53:45.185Z")).statusName);
        assertSame(h.created(), h.asOf(Instant.parse("2026-10-21T14:53:08Z")));
        assertSame(h.after("93654267"), h.asOf(Instant.parse("2030-01-01T00:00:00Z")));
        assertSame(h.after("93654169"), h.asOf(Instant.parse("2026-10-21T14:53:38.380Z")));
    }

    @Test
    void fixVersionsComponentsDueDateAndExtraFields_areRebuilt() {
        JiraIssueHistory h = JiraIssueHistory.rebuild(JiraFixtures.load("ipt-1001-issue.json"), iptContext());

        JiraIssueState created = h.created();
        assertEquals("In Progress", created.statusName);
        assertEquals("indeterminate", created.statusCategoryKey);
        assertEquals(List.of(), created.fixVersions);
        assertEquals(List.of(), created.components);
        assertNull(created.dueDate);
        assertNull(created.extraFields.get("Story Points"));
        assertNull(created.resolution);
        assertNull(created.closedAt);

        assertEquals(List.of("1.2.0"), h.after("2001").fixVersions);
        assertEquals(List.of("core"), h.after("2001").components);
        assertNull(h.after("2001").dueDate);
        assertEquals("2026-10-30", h.after("2002").dueDate);
        assertNull(h.after("2002").extraFields.get("Story Points"));
        assertEquals("5", h.after("2003").extraFields.get("Story Points"));
        assertEquals("Shipped", h.after("2004").statusName);
        assertEquals("done", h.after("2004").statusCategoryKey);
        assertEquals("2026-10-20T09:20:00Z", h.after("2004").closedAt);
    }

    @Test
    void fieldsWithoutHistory_keepCurrentValues() {
        ObjectNode issue = (ObjectNode) JiraFixtures.load("ipt-1001-issue.json");
        issue.remove("changelog");

        JiraIssueState created = JiraIssueHistory.rebuild(issue, iptContext()).created();

        assertEquals(List.of("core"), created.components);
        assertEquals("Done", created.resolution);
        assertEquals("2026-10-20T09:20:00Z", created.closedAt);
        assertEquals("5", created.extraFields.get("Story Points"));
        assertEquals("2026-10-20T08:00:00Z", created.updatedAt);
    }

    @Test
    void displayValue_rendersCommonFieldShapes() {
        ObjectNode values = JiraFixtures.MAPPER.createObjectNode();
        values.put("text", "abc");
        values.put("integral", 5.0);
        values.put("decimal", 2.5);
        values.putNull("none");
        values.putObject("option").put("value", "Blue");
        ArrayNode users = values.putArray("users");
        users.addObject().put("displayName", "Dev");
        users.addObject().put("displayName", "Ops");

        assertEquals("abc", JiraIssueHistory.displayValue(values.path("text")));
        assertEquals("5", JiraIssueHistory.displayValue(values.path("integral")));
        assertEquals("2.5", JiraIssueHistory.displayValue(values.path("decimal")));
        assertNull(JiraIssueHistory.displayValue(values.path("none")));
        assertNull(JiraIssueHistory.displayValue(values.path("missing")));
        assertEquals("Blue", JiraIssueHistory.displayValue(values.path("option")));
        assertEquals("Dev, Ops", JiraIssueHistory.displayValue(values.path("users")));
    }
}
