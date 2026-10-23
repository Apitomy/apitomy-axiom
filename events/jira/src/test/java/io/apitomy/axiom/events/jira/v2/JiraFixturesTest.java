package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guards the Jira test fixtures: they must load and must not contain real personal or tenant data.
 */
class JiraFixturesTest {

    private static final List<String> FIXTURES = List.of("apicurio-137-issue.json", "jira-statuses.json",
            "jira-fields.json", "ipt-1001-issue.json", "ipt-1001-comments.json");

    /** Fragments of real data seen in the spike evidence that must never appear in fixtures. */
    private static final List<String> FORBIDDEN = List.of("70121:", "redhat", "atl-paas", "@",
            "Wittmann", "rfv1:");

    @Test
    void fixturesLoadAndAreSanitized() {
        for (String name : FIXTURES) {
            JsonNode fixture = JiraFixtures.load(name);
            String text = fixture.toString();
            for (String forbidden : FORBIDDEN) {
                assertFalse(text.contains(forbidden), name + " contains '" + forbidden + "'");
            }
        }
    }

    @Test
    void apicurio137FixtureKeepsTheRealChangelogShape() {
        JsonNode histories = JiraFixtures.load("apicurio-137-issue.json").path("changelog").path("histories");
        assertEquals(12, histories.size());
        assertEquals("93654267", histories.get(0).path("id").asText());
        assertEquals("93654105", histories.get(11).path("id").asText());
    }
}
