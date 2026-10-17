package io.apitomy.axiom.events.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.events.jira.v2.JiraEventNormalizerV2;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that {@link EventStreamService#persistEvent} reports serialization failures as
 * errors rather than as duplicates, so pollers do not advance their checkpoint past them.
 * Lives in the Jira module because events-core has no test dependencies.
 */
class EventStreamServicePersistTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void serializationFailure_throwsInsteadOfReportingDuplicate() {
        EventStreamService service = new EventStreamService();
        service.objectMapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("boom") { };
            }
        };

        assertThrows(EventPersistenceException.class, () -> service.persistEvent(sampleEvent()));
    }

    private static NormalizedEvent sampleEvent() {
        ObjectNode issue = MAPPER.createObjectNode();
        issue.put("id", "P-1");
        issue.put("key", "P-1");
        ObjectNode fields = issue.putObject("fields");
        fields.put("summary", "s");
        fields.put("created", "2026-10-01T10:01:00.000+0000");
        fields.put("updated", "2026-10-01T10:01:00.000+0000");
        ObjectNode status = fields.putObject("status");
        status.put("name", "To Do");
        status.putObject("statusCategory").put("key", "new");
        fields.putObject("reporter").put("accountId", "r").put("displayName", "r");
        fields.putArray("labels");
        fields.putNull("assignee");
        List<NormalizedEvent> events = new JiraEventNormalizerV2(MAPPER).normalizeIssue(
                issue, Instant.parse("2026-10-01T10:00:00Z"), "src", "https://x");
        assertFalse(events.isEmpty());
        return events.get(0);
    }
}
