package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionVersionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the "one active run per (project, workflow definition)" rule.
 */
@QuarkusTest
class WorkflowRunConcurrencyTest {

    private static final String WAIT_CONTENT = """
        {
            "id": "wait-wf", "name": "Wait WF",
            "nodes": [
                {"id": "s1", "type": "start", "name": "Start", "config": {}, "position": {"x": 0, "y": 0}},
                {"id": "w1", "type": "wait", "name": "Hold", "config": {"duration": "PT1H"},
                 "position": {"x": 0, "y": 100}},
                {"id": "e1", "type": "end", "name": "End", "config": {}, "position": {"x": 0, "y": 200}}
            ],
            "edges": [
                {"id": "x1", "source": "s1", "target": "w1", "priority": 0, "isDefault": true},
                {"id": "x2", "source": "w1", "target": "e1", "priority": 0, "isDefault": true}
            ]
        }
        """;

    @Inject
    WorkflowExecutionService workflowExecutionService;

    /**
     * Two different definitions may run concurrently on the same project.
     */
    @Test
    void differentDefinitionsRunConcurrentlyOnSameProject() {
        long projectId = createProject("concurrent-different");
        long defA = createDefinition("Def A");
        long defB = createDefinition("Def B");

        WorkflowRunEntity runA = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(projectId, defA));
        WorkflowRunEntity runB = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(projectId, defB));

        assertEquals("waiting", runA.status);
        assertEquals("waiting", runB.status);
        assertNotEquals(runA.id, runB.id);
    }

    /**
     * A second active run of the same definition on the same project is rejected.
     */
    @Test
    void sameDefinitionTwiceOnSameProjectConflicts() {
        long projectId = createProject("concurrent-same");
        long def = createDefinition("Def Same");

        QuarkusTransaction.requiringNew().call(() -> workflowExecutionService.triggerWorkflow(projectId, def));

        WorkflowRunConflictException ex = assertThrows(WorkflowRunConflictException.class, () ->
                QuarkusTransaction.requiringNew().call(() ->
                        workflowExecutionService.triggerWorkflow(projectId, def)));
        assertEquals(409, ex.getResponse().getStatus());
    }

    /**
     * Once the earlier run is no longer active, the same definition may run again (sequential runs).
     */
    @Test
    void sameDefinitionMayRunAgainAfterPreviousRunEnds() {
        long projectId = createProject("sequential-same");
        long def = createDefinition("Def Sequential");

        WorkflowRunEntity first = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(projectId, def));
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunEntity run = WorkflowRunEntity.findById(first.id);
            run.status = "completed";
            run.completedOn = Instant.now();
        });

        WorkflowRunEntity second = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(projectId, def));
        assertEquals("waiting", second.status);
        assertTrue(second.id > first.id);
    }

    private long createProject(String ref) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ProjectEntity project = new ProjectEntity();
            project.name = ref;
            project.type = "other";
            project.ref = "test/" + ref;
            project.createdOn = Instant.now();
            project.updatedOn = Instant.now();
            project.persist();
            return project.id;
        });
    }

    private long createDefinition(String name) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = name;
            def.content = WAIT_CONTENT;
            def.currentVersion = 1;
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();
            WorkflowDefinitionVersionEntity version = new WorkflowDefinitionVersionEntity();
            version.definitionId = def.id;
            version.version = 1;
            version.content = WAIT_CONTENT;
            version.createdOn = Instant.now();
            version.persist();
            return def.id;
        });
    }
}
