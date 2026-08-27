package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void testTriggerWithMissingRequiredInputReturns400() {
        long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            ProjectEntity project = new ProjectEntity();
            project.name = "Input Guard Project";
            project.type = "other";
            project.status = "new";
            project.ref = "test/input-guard";
            project.createdOn = Instant.now();
            project.updatedOn = Instant.now();
            project.persist();

            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = "Input Guard WF";
            def.content = LEGACY_CONTENT;
            def.currentVersion = 1;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();

            WorkflowDefinitionVersionEntity version =
                    new WorkflowDefinitionVersionEntity();
            version.definitionId = def.id;
            version.version = 1;
            version.content = LEGACY_CONTENT;
            version.createdOn = Instant.now();
            version.persist();

            return new long[] { project.id, def.id };
        });

        WebApplicationException ex = assertThrows(
                WebApplicationException.class,
                () -> service.triggerWorkflow(ids[0], ids[1]));
        assertEquals(400, ex.getResponse().getStatus());
    }
}
