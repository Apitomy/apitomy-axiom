package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.core.entities.ThreadEntryEntity;
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
     * activity log entries, tasks, and the workspace directory. Nullifies the
     * projectId on any linked events rather than deleting them.
     *
     * @param project the project to delete (must already be in Completed status)
     */
    @Transactional(TxType.REQUIRES_NEW)
    public void deleteProject(ProjectEntity project) {
        long projectId = project.id;
        ThreadEntryEntity.delete("projectId", projectId);
        AiUsageEntity.delete("projectId", projectId);
        ActivityLogEntity.delete("projectId", projectId);
        EventEntity.update("projectId = null where projectId = ?1", projectId);
        TaskEntity.delete("projectId", projectId);
        workspaceService.deleteWorkspace(project);
        project.delete();
    }
}
