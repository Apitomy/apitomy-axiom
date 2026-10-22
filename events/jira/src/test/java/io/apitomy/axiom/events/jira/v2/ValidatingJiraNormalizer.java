package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.schema.EventSchemaRegistry;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Test normalizer that fails the test if any produced event does not match its JSON Schema. */
class ValidatingJiraNormalizer extends JiraEventNormalizerV2 {

    static final EventSchemaRegistry REGISTRY = new EventSchemaRegistry(new ObjectMapper());

    ValidatingJiraNormalizer(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public List<NormalizedEvent> normalizeIssue(JsonNode issue, Instant since, String eventSourceId,
                                                String baseUrl) {
        List<NormalizedEvent> events = super.normalizeIssue(issue, since, eventSourceId, baseUrl);
        for (NormalizedEvent event : events) {
            assertEquals(List.of(), REGISTRY.validate(event), "schema errors for " + event.sourceEventId());
        }
        return events;
    }
}
