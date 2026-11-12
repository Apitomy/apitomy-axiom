package io.apitomy.axiom.app.summary;

import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunSummaryEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies that deleting runs in bulk removes their summaries through the DB cascade. */
@QuarkusTest
class RunSummaryCascadeTest {

    @Test
    void bulkDeleteOfRunRemovesSummary() {
        Long runId = createRunWithSummary(-1L, -1L);
        QuarkusTransaction.requiringNew().run(() -> WorkflowRunEntity.delete("id in ?1", java.util.List.of(runId)));
        long remaining = QuarkusTransaction.requiringNew()
                .call(() -> WorkflowRunSummaryEntity.count("runId", runId));
        assertEquals(0L, remaining);
    }

    @Test
    void bulkDeleteBySubjectRemovesSummary() {
        Long runId = createRunWithSummary(-77L, -1L);
        QuarkusTransaction.requiringNew().run(() -> WorkflowRunEntity.delete("subjectId", -77L));
        long remaining = QuarkusTransaction.requiringNew()
                .call(() -> WorkflowRunSummaryEntity.count("runId", runId));
        assertEquals(0L, remaining);
    }

    @Test
    void bulkDeleteByDefinitionRemovesSummary() {
        Long runId = createRunWithSummary(-1L, -78L);
        QuarkusTransaction.requiringNew().run(() -> WorkflowRunEntity.delete("definitionId", -78L));
        long remaining = QuarkusTransaction.requiringNew()
                .call(() -> WorkflowRunSummaryEntity.count("runId", runId));
        assertEquals(0L, remaining);
    }

    private Long createRunWithSummary(Long subjectId, Long definitionId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.title = "Cascade";
            run.subjectId = subjectId;
            run.definitionId = definitionId;
            run.definitionVersion = 1;
            run.instanceState = "{}";
            run.status = "completed";
            run.startedOn = Instant.now();
            run.completedOn = Instant.now();
            run.persist();
            WorkflowRunSummaryEntity summary = WorkflowRunSummaryEntity.newPending(run, WorkflowRunSummaryEntity.AUTO);
            summary.persist();
            return run.id;
        });
    }
}
