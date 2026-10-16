package io.apitomy.axiom.app;

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

    @Inject
    WorkflowExecutionService workflowExecutionService;

    /**
     * Two different definitions may run concurrently on the same subject.
     */
    @Test
    void differentDefinitionsRunConcurrentlyOnSameSubject() {
        long subjectId = TestFixtures.createSubject("concurrent-different");
        long defA = TestFixtures.createWaitDefinition("Def A");
        long defB = TestFixtures.createWaitDefinition("Def B");

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
        long subjectId = TestFixtures.createSubject("concurrent-same");
        long def = TestFixtures.createWaitDefinition("Def Same");

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
        long subjectId = TestFixtures.createSubject("sequential-same");
        long def = TestFixtures.createWaitDefinition("Def Sequential");

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
}
