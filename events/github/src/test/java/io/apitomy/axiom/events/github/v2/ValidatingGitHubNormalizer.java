package io.apitomy.axiom.events.github.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.events.model.NormalizedEvent;
import io.apitomy.axiom.core.events.schema.EventSchemaRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Test normalizer that fails the test if any produced event does not match its JSON Schema. */
class ValidatingGitHubNormalizer extends GitHubEventNormalizerV2 {

    private static final EventSchemaRegistry REGISTRY = new EventSchemaRegistry(new ObjectMapper());

    ValidatingGitHubNormalizer(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public NormalizedEvent normalize(JsonNode event, String eventSourceId, String baseUrl, JsonNode fullPr) {
        NormalizedEvent result = super.normalize(event, eventSourceId, baseUrl, fullPr);
        if (result != null) {
            assertEquals(List.of(), REGISTRY.validate(result), "schema errors for " + result.type().value());
        }
        return result;
    }
}
