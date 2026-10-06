package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunResumeEntity;
import io.apitomy.axiom.core.entities.WorkflowWaitEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link SubjectDeletionService}: deleting a subject must leave no row pointing at the
 * subject or at its deleted tasks and workflow runs.
 */
@QuarkusTest
class SubjectDeletionServiceTest {

    @Inject
    SubjectDeletionService subjectDeletionService;

    @Test
    void deletionKeepsTracesAndOutcomesButClearsSubjectReferences() {
        UUID traceId = UUID.randomUUID();
        long[] ids = new long[8];

        QuarkusTransaction.requiringNew().run(() -> {
            SubjectEntity subject = new SubjectEntity();
            subject.name = "deletion-test";
            subject.type = "test";
            subject.ref = "deletion-test-" + traceId;
            subject.createdOn = Instant.now();
            subject.updatedOn = Instant.now();
            subject.persist();
            ids[0] = subject.id;

            TaskEntity task = new TaskEntity();
            task.subjectId = subject.id;
            task.actionType = "test";
            task.createdBy = "test";
            task.status = "Completed";
            task.createdOn = Instant.now();
            task.persist();

            WorkflowRunEntity run = new WorkflowRunEntity();
            run.title = "Test run";
            run.subjectId = subject.id;
            run.definitionId = -1L;
            run.definitionVersion = 1;
            run.instanceState = "{}";
            run.status = "waiting";
            run.startedOn = Instant.now();
            run.persist();
            ids[1] = run.id;

            WorkflowWaitEntity wait = new WorkflowWaitEntity();
            wait.runId = run.id;
            wait.nodeId = "wait";
            wait.resumeAt = Instant.now();
            wait.createdOn = Instant.now();
            wait.persist();

            WorkflowEventSubscriptionEntity sub = new WorkflowEventSubscriptionEntity();
            sub.runId = run.id;
            sub.nodeId = "receive";
            sub.eventType = "pr.opened";
            sub.subjectId = subject.id;
            sub.createdOn = Instant.now();
            sub.persist();

            WorkflowRunResumeEntity resume = new WorkflowRunResumeEntity();
            resume.runId = run.id;
            resume.nodeId = "receive";
            resume.eventId = UUID.randomUUID();
            resume.resumedOn = Instant.now();
            resume.persist();

            TraceEntity trace = new TraceEntity();
            trace.traceId = traceId;
            trace.traceType = "workflow";
            trace.status = "completed";
            trace.summary = "deletion-test";
            trace.subjectId = subject.id;
            trace.startedOn = Instant.now();
            trace.persist();

            RoutingOutcomeEntity outcome = new RoutingOutcomeEntity();
            outcome.ledgerId = -1L;
            outcome.routingType = "workflow-dispatch";
            outcome.status = "succeeded";
            outcome.subjectId = subject.id;
            outcome.taskId = task.id;
            outcome.createdOn = Instant.now();
            outcome.persist();
            ids[2] = outcome.id;

            RoutingOutcomeItemEntity item = new RoutingOutcomeItemEntity();
            item.outcomeId = outcome.id;
            item.itemType = RoutingOutcomeItemEntity.TYPE_WORKFLOW_RUN;
            item.status = "succeeded";
            item.subjectId = subject.id;
            item.workflowRunId = run.id;
            item.createdOn = Instant.now();
            item.persist();
            ids[3] = item.id;

            RoutingOutcomeItemEntity taskItem = new RoutingOutcomeItemEntity();
            taskItem.outcomeId = outcome.id;
            taskItem.itemType = RoutingOutcomeItemEntity.TYPE_TASK;
            taskItem.status = "succeeded";
            taskItem.subjectId = subject.id;
            taskItem.taskId = task.id;
            taskItem.createdOn = Instant.now();
            taskItem.persist();
            ids[4] = taskItem.id;
        });

        QuarkusTransaction.requiringNew().run(() ->
                subjectDeletionService.deleteSubject(SubjectEntity.findById(ids[0])));

        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(SubjectEntity.findById(ids[0]));
            assertNull(WorkflowRunEntity.findById(ids[1]));
            assertEquals(0, WorkflowWaitEntity.count("runId", ids[1]));
            assertEquals(0, WorkflowEventSubscriptionEntity.count("runId", ids[1]));
            assertEquals(0, WorkflowRunResumeEntity.count("runId", ids[1]));

            TraceEntity trace = TraceEntity.findById(traceId);
            assertNotNull(trace, "trace history is kept");
            assertNull(trace.subjectId);

            RoutingOutcomeEntity outcome = RoutingOutcomeEntity.findById(ids[2]);
            assertNotNull(outcome, "event routing history is kept");
            assertNull(outcome.subjectId);
            assertNull(outcome.taskId);

            RoutingOutcomeItemEntity item = RoutingOutcomeItemEntity.findById(ids[3]);
            assertNull(item.subjectId);
            assertNull(item.workflowRunId);
            RoutingOutcomeItemEntity taskItem = RoutingOutcomeItemEntity.findById(ids[4]);
            assertNull(taskItem.subjectId);
            assertNull(taskItem.taskId);

            item.delete();
            taskItem.delete();
            outcome.delete();
            trace.delete();
        });
    }
}
