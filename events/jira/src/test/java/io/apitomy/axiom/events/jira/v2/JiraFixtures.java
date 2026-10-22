package io.apitomy.axiom.events.jira.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.events.model.NormalizedEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads sanitized Jira fixtures from {@code src/test/resources/fixtures} and validates normalizer output
 * against the event schemas.
 */
final class JiraFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private JiraFixtures() {
    }

    /**
     * Loads a fixture as a fresh, mutable JSON tree.
     *
     * @param name file name under {@code /fixtures/}
     * @return the parsed fixture
     */
    static JsonNode load(String name) {
        try (InputStream in = JiraFixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Fixture not found: " + name);
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Asserts that every event validates against its event-type schema, reusing
     * {@link ValidatingJiraNormalizer#REGISTRY}.
     *
     * @param events the normalizer output
     */
    static void assertSchemaValid(List<NormalizedEvent> events) {
        for (NormalizedEvent event : events) {
            List<String> errors = ValidatingJiraNormalizer.REGISTRY.validate(event);
            assertTrue(errors.isEmpty(), event.type().value() + " " + event.sourceEventId() + ": " + errors);
        }
    }
}
