package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.ws.rs.WebApplicationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
     * The run context always exposes the subject's name and repository so nodes can map them explicitly.
     */
    @Test
    void contextContainsSubjectNameAndRepository() {
        long subjectId = QuarkusTransaction.requiringNew().call(() -> {
            SubjectEntity s = new SubjectEntity();
            s.name = "ctx-subject-" + UUID.randomUUID();
            s.type = "other";
            s.ref = "https://github.com/o/r/issues/1";
            s.repository = "o/r";
            s.createdOn = Instant.now();
            s.updatedOn = Instant.now();
            s.persist();
            return s.id;
        });
        long defId = TestFixtures.createWaitDefinition("Ctx Def " + UUID.randomUUID());
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defId, RunRequest.EMPTY, null));
        String state = QuarkusTransaction.requiringNew().call(() ->
                ((WorkflowRunEntity) WorkflowRunEntity.findById(run.id)).instanceState);
        assertTrue(state.contains("\"repository\":\"o/r\""), state);
        assertTrue(state.contains("\"subjectName\":\"ctx-subject-"), state);
    }

    /**
     * Supplied run inputs cannot override protected subject keys.
     */
    @Test
    void suppliedInputsCannotOverrideRepositoryOrSubjectName() {
        long subjectId = TestFixtures.createSubject("ctx-protected-" + UUID.randomUUID());
        long defId = TestFixtures.createWaitDefinition("Ctx Prot " + UUID.randomUUID());
        RunRequest request = new RunRequest(null, null,
                Map.of("repository", "evil/repo", "subjectName", "evil"), null);
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defId, request, null));
        String state = QuarkusTransaction.requiringNew().call(() ->
                ((WorkflowRunEntity) WorkflowRunEntity.findById(run.id)).instanceState);
        assertFalse(state.contains("evil"), state);
    }

    /**
     * Extra context supplied by the trigger cannot override the subject's repository.
     */
    @Test
    void extraContextCannotOverrideRepository() {
        long subjectId = QuarkusTransaction.requiringNew().call(() -> {
            SubjectEntity s = new SubjectEntity();
            s.name = "ctx-extra-" + UUID.randomUUID();
            s.type = "other";
            s.ref = "test/ctx-extra";
            s.repository = "real/repo";
            s.createdOn = Instant.now();
            s.updatedOn = Instant.now();
            s.persist();
            return s.id;
        });
        long defId = TestFixtures.createWaitDefinition("Ctx Extra " + UUID.randomUUID());
        Map<String, Object> extra = Map.of("repository", "evil/repo");
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defId, extra, null));
        String state = QuarkusTransaction.requiringNew().call(() ->
                ((WorkflowRunEntity) WorkflowRunEntity.findById(run.id)).instanceState);
        assertTrue(state.contains("\"repository\":\"real/repo\""), state);
        assertFalse(state.contains("evil/repo"), state);
    }

    /**
     * A subject without a repository yields an empty-string repository in the run context.
     */
    @Test
    void nullRepositoryYieldsEmptyString() {
        long subjectId = TestFixtures.createSubject("ctx-null-repo-" + UUID.randomUUID());
        long defId = TestFixtures.createWaitDefinition("Ctx Null " + UUID.randomUUID());
        WorkflowRunEntity run = QuarkusTransaction.requiringNew().call(() ->
                workflowExecutionService.triggerWorkflow(subjectId, defId, RunRequest.EMPTY, null));
        String state = QuarkusTransaction.requiringNew().call(() ->
                ((WorkflowRunEntity) WorkflowRunEntity.findById(run.id)).instanceState);
        assertTrue(state.contains("\"repository\":\"\""), state);
    }
}
