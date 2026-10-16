package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeEntity;
import io.apitomy.axiom.core.entities.RoutingOutcomeItemEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
import io.apitomy.axiom.core.entities.TraceEntity;
import io.apitomy.axiom.core.entities.WorkflowEventSubscriptionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowRunResumeEntity;
import io.apitomy.axiom.core.entities.WorkflowWaitEntity;
import io.apitomy.axiom.core.services.WorkspaceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

/**
 * Handles the full cascade deletion of a subject and all its associated data.
 */
@ApplicationScoped
public class SubjectDeletionService {

    @Inject
    WorkspaceService workspaceService;

    /**
     * Deletes a subject and all associated data: thread entries, AI usage records,
     * activity log entries, tasks, workflow runs (with their waits and event
     * subscriptions and resume records), and the workspace directory.
     *
     * <p>Traces and event routing outcomes are event history, not subject data: they are kept
     * and their references to the subject, its tasks and its workflow runs are cleared, so no
     * row points at a deleted subject, task or run. Trace node {@code entity_id} values are
     * free-form display references and are left as they are.
     *
     * @param subject the subject to delete
     */
    @Transactional(TxType.REQUIRES_NEW)
    public void deleteSubject(SubjectEntity subject) {
        long subjectId = subject.id;
        String tasksOfSubject = "in (select t.id from TaskEntity t where t.subjectId = ?1)";
        String runsOfSubject = "in (select r.id from WorkflowRunEntity r where r.subjectId = ?1)";
        RoutingOutcomeItemEntity.update("taskId = null where taskId " + tasksOfSubject, subjectId);
        RoutingOutcomeItemEntity.update(
                "workflowRunId = null where workflowRunId " + runsOfSubject, subjectId);
        RoutingOutcomeItemEntity.update("subjectId = null where subjectId = ?1", subjectId);
        RoutingOutcomeEntity.update("taskId = null where taskId " + tasksOfSubject, subjectId);
        RoutingOutcomeEntity.update("subjectId = null where subjectId = ?1", subjectId);
        TraceEntity.update("subjectId = null where subjectId = ?1", subjectId);
        ThreadEntryEntity.delete("subjectId", subjectId);
        AiUsageEntity.delete("subjectId", subjectId);
        ActivityLogEntity.delete("subjectId", subjectId);
        TaskEntity.delete("subjectId", subjectId);
        WorkflowWaitEntity.delete(
                "runId in (select r.id from WorkflowRunEntity r where r.subjectId = ?1)", subjectId);
        WorkflowEventSubscriptionEntity.delete(
                "runId in (select r.id from WorkflowRunEntity r where r.subjectId = ?1)", subjectId);
        WorkflowRunResumeEntity.delete(
                "runId in (select r.id from WorkflowRunEntity r where r.subjectId = ?1)", subjectId);
        WorkflowRunEntity.delete("subjectId", subjectId);
        workspaceService.deleteWorkspace(subject);
        subject.delete();
    }
}
