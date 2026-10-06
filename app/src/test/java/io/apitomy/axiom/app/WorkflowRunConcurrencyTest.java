package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.SubjectEntity;
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
 * Verifies the "one active run per (subject, workflow definition)" rule.
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
     * Two different definitions may run concurrently on the same subject.
     */
    @Test
    void differentDefinitionsRunConcurrentlyOnSameSubject() {
        long subjectId = createSubject("concurrent-different");
        long defA = createDefinition("Def A");
        long defB = createDefinition("Def B");

        WorkflowRunEntity runA = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defA));
        WorkflowRunEntity runB = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defB));

        assertEquals("waiting", runA.status);
        assertEquals("waiting", runB.status);
        assertNotEquals(runA.id, runB.id);
    }

    /**
     * A second active run of the same definition on the same subject is rejected.
     */
    @Test
    void sameDefinitionTwiceOnSameSubjectConflicts() {
        long subjectId = createSubject("concurrent-same");
        long def = createDefinition("Def Same");

        QuarkusTransaction.requiringNew().call(() -> workflowExecutionService.triggerWorkflow(subjectId, def));

        WorkflowRunConflictException ex = assertThrows(WorkflowRunConflictException.class, () ->
                QuarkusTransaction.requiringNew().call(() ->
                        workflowExecutionService.triggerWorkflow(subjectId, def)));
        assertEquals(409, ex.getResponse().getStatus());
    }

    /**
     * Once the earlier run is no longer active, the same definition may run again (sequential runs).
     */
    @Test
    void sameDefinitionMayRunAgainAfterPreviousRunEnds() {
        long subjectId = createSubject("sequential-same");
        long def = createDefinition("Def Sequential");

        WorkflowRunEntity first = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, def));
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowRunEntity run = WorkflowRunEntity.findById(first.id);
            run.status = "completed";
            run.completedOn = Instant.now();
        });

        WorkflowRunEntity second = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, def));
        assertEquals("waiting", second.status);
        assertTrue(second.id > first.id);
    }

    private long createSubject(String ref) {
        return QuarkusTransaction.requiringNew().call(() -> {
            SubjectEntity subject = new SubjectEntity();
            subject.name = ref;
            subject.type = "other";
            subject.ref = "test/" + ref;
            subject.createdOn = Instant.now();
            subject.updatedOn = Instant.now();
            subject.persist();
            return subject.id;
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
