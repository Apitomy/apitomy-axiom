package io.apitomy.axiom.events.jira.v2;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link JiraStatusCategories}.
 */
class JiraStatusCategoriesTest {

    private final JiraStatusCategories categories =
            JiraStatusCategories.fromStatuses(JiraFixtures.load("jira-statuses.json"));

    @Test
    void categoryIsResolvedById() {
        assertEquals("new", categories.categoryKey("10143", "Refinement"));
        assertEquals("indeterminate", categories.categoryKey("3", "In Progress"));
        assertEquals("done", categories.categoryKey("6", "Closed"));
    }

    @Test
    void customDoneStatusNameIsDoneByCategory() {
        assertTrue(categories.isDone("10500", "Shipped"));
    }

    @Test
    void unknownIdFallsBackToKnownName() {
        assertEquals("done", categories.categoryKey("99999", "shipped"));
    }

    @Test
    void emptyLookupFallsBackToDoneNames() {
        JiraStatusCategories empty = JiraStatusCategories.empty();
        assertTrue(empty.isEmpty());
        assertTrue(empty.isDone(null, "Resolved"));
        assertTrue(empty.isDone("6", "CLOSED"));
        assertTrue(empty.isDone(null, "Done"));
        assertFalse(empty.isDone("10500", "Shipped"));
        assertNull(empty.categoryKey(null, "In Progress"));
        assertNull(empty.categoryKey(null, null));
    }

    @Test
    void contextExposesExtraFieldsCaseInsensitively() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("Story Points", "customfield_10028");
        extra.put("Team", null);
        JiraNormalizationContext context = new JiraNormalizationContext(categories, extra);
        assertTrue(context.isExtraField("story points"));
        assertEquals("Story Points", context.extraFieldName("STORY POINTS"));
        assertFalse(context.isExtraField("priority"));
        assertEquals(List.of("customfield_10028"), context.extraFieldIds());
        assertTrue(JiraNormalizationContext.defaults().extraFields().isEmpty());
    }
}
