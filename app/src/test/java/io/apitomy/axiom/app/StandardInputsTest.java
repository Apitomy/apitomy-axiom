package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.flow.model.Workflow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the standard workflow inputs registry. */
class StandardInputsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Workflow workflow(String startInputsJson) throws Exception {
        return MAPPER.readValue("""
            {"id": "w", "name": "W",
             "nodes": [
               {"id": "s", "type": "start", "name": "Start", "config": {"inputs": %s}, "position": {"x": 0, "y": 0}},
               {"id": "e", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 100}}],
             "edges": [{"id": "x", "source": "s", "target": "e", "priority": 0, "isDefault": false}]}
            """.formatted(startInputsJson), Workflow.class);
    }

    private static SubjectEntity subject() {
        SubjectEntity s = new SubjectEntity();
        s.id = 7L;
        s.name = "PR 12";
        s.type = "pull-request";
        s.ref = "https://github.com/o/r/pull/12";
        s.refSource = "github";
        s.repository = " ";
        return s;
    }

    @Test
    void registryListsSubjectAndEvent() {
        assertEquals(List.of("subject", "event"), StandardInputs.ALL.stream().map(StandardInputs.StandardInput::name).toList());
        assertEquals("object", StandardInputs.find("subject").orElseThrow().type());
        assertTrue(StandardInputs.find("ref").isEmpty());
    }

    @Test
    void subjectValueHasExactlyTheSixFieldsWithBlankAsNull() {
        Map<String, Object> value = StandardInputs.subjectValue(subject());
        assertEquals(List.of("id", "name", "type", "ref", "refSource", "repository"), List.copyOf(value.keySet()));
        assertEquals(7L, value.get("id"));
        assertEquals("PR 12", value.get("name"));
        assertNull(value.get("repository"));
    }

    @Test
    void eventValueIsTheEnvelope() {
        StreamEventEntity e = new StreamEventEntity();
        e.id = UUID.randomUUID();
        e.type = "issue.created";
        e.source = "github";
        e.eventSourceId = "gh";
        e.ref = "r";
        e.timestamp = Instant.parse("2026-10-15T00:00:00Z");
        e.payload = "{\"a\": 1}";
        ObjectNode node = StandardInputs.eventValue(e, MAPPER);
        assertEquals("issue.created", node.path("type").asText());
        assertEquals(1, node.path("payload").path("a").asInt());
        assertFalse(node.has("actor"));
    }

    @Test
    void resolvesOnlyDeclaredInputs() throws Exception {
        StandardInputs.Resolution r = StandardInputs.resolve(
                workflow("[{\"name\": \"subject\", \"type\": \"object\", \"required\": true}]"), subject(),
                MAPPER.readTree("{\"type\": \"t\"}"));
        assertEquals(List.of("subject"), List.copyOf(r.context().keySet()));
        assertTrue(r.missingRequired().isEmpty());
    }

    @Test
    void optionalUnavailableEventIsAbsentAndRequiredIsReported() throws Exception {
        StandardInputs.Resolution optional = StandardInputs.resolve(
                workflow("[{\"name\": \"event\", \"type\": \"object\", \"required\": false}]"), subject(), null);
        assertFalse(optional.context().containsKey("event"));
        assertTrue(optional.missingRequired().isEmpty());

        StandardInputs.Resolution required = StandardInputs.resolve(
                workflow("[{\"name\": \"event\", \"type\": \"object\", \"required\": true}]"), subject(), null);
        assertEquals(List.of("event"), required.missingRequired());
    }

    @Test
    void noDeclaredInputsGivesEmptyContext() throws Exception {
        JsonNode event = MAPPER.readTree("{}");
        assertTrue(StandardInputs.resolve(workflow("[]"), subject(), event).context().isEmpty());
    }
}
