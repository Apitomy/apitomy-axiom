package io.apitomy.axiom.events.jira.v2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link JiraFieldCatalog}.
 */
class JiraFieldCatalogTest {

    private final JiraFieldCatalog catalog = JiraFieldCatalog.fromFields(JiraFixtures.load("jira-fields.json"));

    @Test
    void namesResolveCaseInsensitively() {
        assertEquals("customfield_10028", catalog.idFor("Story Points"));
        assertEquals("customfield_10028", catalog.idFor("story points"));
    }

    @Test
    void unknownNamesResolveToNull() {
        assertNull(catalog.idFor("No Such Field"));
        assertNull(catalog.idFor(null));
    }
}
