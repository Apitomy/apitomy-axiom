package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import jakarta.ws.rs.WebApplicationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /**
     * A supplied title longer than 500 characters is rejected with 400.
     */
    @Test
    void overlongTitleIsRejected() {
        long subjectId = TestFixtures.createSubject("meta-long-title");
        long defId = TestFixtures.createWaitDefinition("Meta Def 3");
        RunRequest request = new RunRequest("x".repeat(501), null, null, null);

        WebApplicationException ex = assertThrows(WebApplicationException.class, () ->
                QuarkusTransaction.requiringNew().call(() ->
                        workflowExecutionService.triggerWorkflow(subjectId, defId, request, null)));
        assertEquals(400, ex.getResponse().getStatus());
    }

    /**
     * A default title longer than 500 characters is truncated to 500.
     */
    @Test
    void longDefaultTitleIsTruncated() {
        long subjectId = TestFixtures.createSubject("s".repeat(250));
        long defId = TestFixtures.createWaitDefinition("d".repeat(250));

        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defId, RunRequest.EMPTY, null));

        assertEquals(500, run.title.length());
    }
}
