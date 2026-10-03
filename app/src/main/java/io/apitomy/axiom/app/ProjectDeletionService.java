package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
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
 * Handles the full cascade deletion of a project and all its associated data.
 */
@ApplicationScoped
public class ProjectDeletionService {

    @Inject
    WorkspaceService workspaceService;

    /**
     * Deletes a project and all associated data: thread entries, AI usage records,
     * activity log entries, tasks, workflow runs (with their waits and event
     * subscriptions and resume records), and the workspace directory.
     *
     * <p>Traces and event routing outcomes are event history, not project data: they are kept
     * and their references to the project, its tasks and its workflow runs are cleared, so no
     * row points at a deleted project, task or run. Trace node {@code entity_id} values are
     * free-form display references and are left as they are.
     *
     * @param project the project to delete (must already be in Completed status)
     */
    @Transactional(TxType.REQUIRES_NEW)
    public void deleteProject(ProjectEntity project) {
        long projectId = project.id;
        String tasksOfProject = "in (select t.id from TaskEntity t where t.projectId = ?1)";
        String runsOfProject = "in (select r.id from WorkflowRunEntity r where r.projectId = ?1)";
        RoutingOutcomeItemEntity.update("taskId = null where taskId " + tasksOfProject, projectId);
        RoutingOutcomeItemEntity.update(
                "workflowRunId = null where workflowRunId " + runsOfProject, projectId);
        RoutingOutcomeItemEntity.update("projectId = null where projectId = ?1", projectId);
        RoutingOutcomeEntity.update("taskId = null where taskId " + tasksOfProject, projectId);
        RoutingOutcomeEntity.update("projectId = null where projectId = ?1", projectId);
        TraceEntity.update("projectId = null where projectId = ?1", projectId);
        ThreadEntryEntity.delete("projectId", projectId);
        AiUsageEntity.delete("projectId", projectId);
        ActivityLogEntity.delete("projectId", projectId);
        TaskEntity.delete("projectId", projectId);
        WorkflowWaitEntity.delete(
                "runId in (select r.id from WorkflowRunEntity r where r.projectId = ?1)", projectId);
        WorkflowEventSubscriptionEntity.delete(
                "runId in (select r.id from WorkflowRunEntity r where r.projectId = ?1)", projectId);
        WorkflowRunResumeEntity.delete(
                "runId in (select r.id from WorkflowRunEntity r where r.projectId = ?1)", projectId);
        WorkflowRunEntity.delete("projectId", projectId);
        workspaceService.deleteWorkspace(project);
        project.delete();
    }
}
