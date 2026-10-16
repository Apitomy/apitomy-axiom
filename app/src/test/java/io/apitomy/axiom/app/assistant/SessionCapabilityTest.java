package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies capability parsing and the capabilities declared by the built-in templates.
 */
class SessionCapabilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesKnownNamesAndIgnoresUnknown() throws IOException {
        JsonNode array = MAPPER.readTree("[\"config-items\", \"composition\", \"bogus\"]");
        Set<SessionCapability> parsed = SessionCapability.parse(array);
        assertEquals(EnumSet.of(SessionCapability.CONFIG_ITEMS, SessionCapability.COMPOSITION), parsed);
    }

    @Test
    void missingArrayParsesToEmptySet() {
        assertTrue(SessionCapability.parse(MAPPER.missingNode()).isEmpty());
    }

    @Test
    void jsonNamesRoundTrip() {
        for (SessionCapability capability : SessionCapability.values()) {
            assertEquals(Optional.of(capability), SessionCapability.fromJsonName(capability.jsonName()));
        }
        assertEquals("validation-feedback", SessionCapability.VALIDATION_FEEDBACK.jsonName());
    }

    @Test
    void configAssistantDeclaresItemsAndFeedback() throws IOException {
        assertEquals(EnumSet.of(SessionCapability.CONFIG_ITEMS, SessionCapability.VALIDATION_FEEDBACK),
                capabilitiesOf("axiom-config-assistant.json"));
    }

    @Test
    void otherBuiltInsDeclareNothing() throws IOException {
        assertTrue(capabilitiesOf("general-assistant.json").isEmpty());
        assertTrue(capabilitiesOf("subject-assistant.json").isEmpty());
    }

    private static Set<SessionCapability> capabilitiesOf(String file) throws IOException {
        try (InputStream is = SessionCapabilityTest.class.getResourceAsStream(
                "/templates/assistant-templates/" + file)) {
            return SessionCapability.parse(MAPPER.readTree(is).path("capabilities"));
        }
    }
}
