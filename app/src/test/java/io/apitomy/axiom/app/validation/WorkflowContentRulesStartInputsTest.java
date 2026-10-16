package io.apitomy.axiom.app.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.flow.model.Workflow;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Start input publish rules: only standard inputs, with their types, and no defaults. */
class WorkflowContentRulesStartInputsTest {

    private static Optional<String> violation(String inputs) throws Exception {
        Workflow wf = new ObjectMapper().readValue("""
            {"id": "w", "name": "W",
             "nodes": [{"id": "s", "type": "start", "name": "Start", "config": {"inputs": %s}, "position": {"x": 0, "y": 0}},
                       {"id": "e", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 100}}],
             "edges": [{"id": "x", "source": "s", "target": "e", "priority": 0, "isDefault": false}]}
            """.formatted(inputs), Workflow.class);
        return WorkflowContentRules.startInputViolation(wf);
    }

    @Test
    void standardInputsAreValidAndEventMayBeRequired() throws Exception {
        assertTrue(violation("[{\"name\": \"subject\", \"type\": \"object\", \"required\": true},"
                + " {\"name\": \"event\", \"type\": \"object\", \"required\": true}]").isEmpty());
        assertTrue(violation("[{\"name\": \"event\", \"required\": false}]").isEmpty());
    }

    @Test
    void nonStandardNameIsRejected() throws Exception {
        assertEquals(Optional.of("Start node input 'ref' is not a standard input. A Start node may declare only: "
                + "subject, event."), violation("[{\"name\": \"ref\", \"type\": \"string\"}]"));
    }

    @Test
    void wrongTypeIsRejected() throws Exception {
        assertEquals(Optional.of("Start node input 'subject' must have type 'object', not 'string'."),
                violation("[{\"name\": \"subject\", \"type\": \"string\"}]"));
    }

    @Test
    void defaultValueIsRejected() throws Exception {
        assertEquals(Optional.of("Start node input 'event' cannot declare a defaultValue; Axiom provides "
                + "standard inputs itself."),
                violation("[{\"name\": \"event\", \"type\": \"object\", \"defaultValue\": {}}]"));
    }
}
