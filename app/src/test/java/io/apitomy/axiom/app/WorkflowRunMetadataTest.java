package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import jakarta.ws.rs.WebApplicationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies run metadata (title, labels) is stored on the run.
 */
@QuarkusTest
class WorkflowRunMetadataTest {

    @Inject
    WorkflowExecutionService workflowExecutionService;

    /**
     * A run started without a title has none; nothing is generated from the definition and subject.
     */
    @Test
    void runWithoutTitleHasNoTitle() {
        long subjectId = TestFixtures.createSubject("meta-default");
        long defId = TestFixtures.createWaitDefinition("Meta Def");

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defId, RunRequest.EMPTY, null));

        assertNull(run.title);
    }

    /**
     * Supplied title and labels are persisted.
     */
    @Test
    void suppliedMetadataIsPersisted() {
        long subjectId = TestFixtures.createSubject("meta-supplied");
        long defId = TestFixtures.createWaitDefinition("Meta Def 2");
        RunRequest request = new RunRequest("Custom", Map.of("team", "core"));

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defId, request, null));

        WorkflowRunEntity loaded = QuarkusTransaction.requiringNew().call(() ->
                WorkflowRunEntity.findById(run.id));
        assertEquals("Custom", loaded.title);
        assertTrue(loaded.labels.contains("\"team\":\"core\""));
    }

    /**
     * A supplied title longer than 500 characters is rejected with 400.
     */
    @Test
    void overlongTitleIsRejected() {
        long subjectId = TestFixtures.createSubject("meta-long-title");
        long defId = TestFixtures.createWaitDefinition("Meta Def 3");
        RunRequest request = new RunRequest("x".repeat(501), null);

        WebApplicationException ex = assertThrows(WebApplicationException.class, () ->
                QuarkusTransaction.requiringNew().call(() ->
                        workflowExecutionService.triggerWorkflow(subjectId, defId, request, null)));
        assertEquals(400, ex.getResponse().getStatus());
    }
}
