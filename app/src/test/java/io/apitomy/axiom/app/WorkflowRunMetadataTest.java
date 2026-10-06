package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies run metadata (title, labels, inputs) is stored on the run.
 */
@QuarkusTest
class WorkflowRunMetadataTest {

    @Inject
    WorkflowExecutionService workflowExecutionService;

    /**
     * When no title is supplied the default "&lt;definition&gt; — &lt;subject&gt;" title is used.
     */
    @Test
    void defaultTitleCombinesDefinitionAndSubject() {
        long subjectId = TestFixtures.createSubject("meta-default");
        long defId = TestFixtures.createWaitDefinition("Meta Def");

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defId, RunRequest.EMPTY, null));

        assertEquals("Meta Def — meta-default", run.title);
    }

    /**
     * Supplied title, labels and inputs are persisted.
     */
    @Test
    void suppliedMetadataIsPersisted() {
        long subjectId = TestFixtures.createSubject("meta-supplied");
        long defId = TestFixtures.createWaitDefinition("Meta Def 2");
        RunRequest request = new RunRequest("Custom", Map.of("team", "core"), Map.of(), null);

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defId, request, null));

        WorkflowRunEntity loaded = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunEntity.findById(run.id));
        assertEquals("Custom", loaded.title);
        assertTrue(loaded.labels.contains("\"team\":\"core\""));
    }
}
