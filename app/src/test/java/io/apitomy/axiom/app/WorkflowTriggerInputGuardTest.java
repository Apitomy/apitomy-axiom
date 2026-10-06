package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for run-input handling at workflow start: required custom inputs, supplied inputs joining the
 * initial context, and protection of the built-in inputs.
 */
@QuarkusTest
class WorkflowTriggerInputGuardTest {

    @Inject
    WorkflowExecutionService service;

    /**
     * A structurally valid start→end workflow whose Start node requires a
     * non-canonical input Axiom never provides. Simulates legacy/hand-edited
     * content that bypassed publish-time validation.
     */
    private static final String LEGACY_CONTENT = """
        {
            "id": "legacy-wf",
            "name": "Legacy WF",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start",
                 "config": {"inputs": [
                     {"name": "issueNumber", "type": "string",
                      "required": true}
                 ]},
                 "position": {"x": 100, "y": 100}},
                {"id": "e1", "type": "end", "name": "End",
                 "config": {}, "position": {"x": 100, "y": 300}}
            ],
            "edges": [
                {"id": "edge1", "source": "s1", "target": "e1",
                 "priority": 0, "isDefault": true}
            ]
        }
        """;

    /** A Start node declaring a required custom input {@code environment}. */
    private static final String CUSTOM_INPUT_CONTENT = """
        {
            "id": "custom-input-wf",
            "name": "Custom Input WF",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start",
                 "config": {"inputs": [
                     {"name": "subjectId", "type": "number", "required": true},
                     {"name": "event", "type": "object", "required": false},
                     {"name": "environment", "type": "string", "required": true}
                 ]},
                 "position": {"x": 100, "y": 100}},
                {"id": "e1", "type": "end", "name": "End",
                 "config": {}, "position": {"x": 100, "y": 300}}
            ],
            "edges": [
                {"id": "edge1", "source": "s1", "target": "e1",
                 "priority": 0, "isDefault": true}
            ]
        }
        """;

    @Test
    void testTriggerWithMissingRequiredInputReturns400() {
        long[] ids = createSubjectAndDefinition(LEGACY_CONTENT);

        WebApplicationException ex = assertThrows(
                WebApplicationException.class,
                () -> service.triggerWorkflow(ids[0], ids[1]));
        assertEquals(400, ex.getResponse().getStatus());
    }

    @Test
    void testSuppliedCustomInputJoinsWorkflowContext() {
        long[] ids = createSubjectAndDefinition(CUSTOM_INPUT_CONTENT);

        WorkflowRunEntity run = service.triggerWorkflow(ids[0], ids[1],
                new RunRequest(null, null, Map.of("environment", "prod"), null), null);

        String state = QuarkusTransaction.requiringNew().call(
                () -> WorkflowRunEntity.<WorkflowRunEntity>findById(run.id).instanceState);
        assertTrue(state.contains("\"environment\":\"prod\""), state);
    }

    @Test
    void testMissingRequiredCustomInputFailsStart() {
        long[] ids = createSubjectAndDefinition(CUSTOM_INPUT_CONTENT);

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> service.triggerWorkflow(ids[0], ids[1], RunRequest.EMPTY, null));
        assertEquals(400, ex.getResponse().getStatus());
        assertTrue(String.valueOf(ex.getResponse().getEntity()).contains("environment"));
    }

    @Test
    void testSuppliedInputsCannotOverrideRoutedEvent() {
        long[] ids = createSubjectAndDefinition(CUSTOM_INPUT_CONTENT);

        WorkflowRunEntity run = service.triggerWorkflow(ids[0], ids[1],
                new RunRequest(null, null,
                        Map.of("environment", "prod", "event", "forged-event", "ref", "forged-ref"),
                        Map.of("event", Map.of("type", "routed-event"))), null);

        String state = QuarkusTransaction.requiringNew().call(
                () -> WorkflowRunEntity.<WorkflowRunEntity>findById(run.id).instanceState);
        assertTrue(state.contains("routed-event"), state);
        assertFalse(state.contains("forged-event"), state);
        assertFalse(state.contains("forged-ref"), state);
    }

    private long[] createSubjectAndDefinition(String content) {
        return QuarkusTransaction.requiringNew().call(() -> {
            SubjectEntity subject = new SubjectEntity();
            subject.name = "Input Guard Subject";
            subject.type = "other";
            subject.ref = "test/input-guard/" + java.util.UUID.randomUUID();
            subject.createdOn = Instant.now();
            subject.updatedOn = Instant.now();
            subject.persist();

            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = "Input Guard WF " + java.util.UUID.randomUUID();
            def.content = content;
            def.currentVersion = 1;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();

            WorkflowDefinitionVersionEntity version =
                    new WorkflowDefinitionVersionEntity();
            version.definitionId = def.id;
            version.version = 1;
            version.content = content;
            version.createdOn = Instant.now();
            version.persist();

            return new long[] { subject.id, def.id };
        });
    }
}
