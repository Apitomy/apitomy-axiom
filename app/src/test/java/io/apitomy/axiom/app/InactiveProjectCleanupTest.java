package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.apitomy.axiom.core.entities.WorkflowDefinitionEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.services.WorkspaceService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link InactiveProjectCleanup}: projects with no active run whose last run completed before the
 * retention cutoff are deleted (including their workspace); active or recently completed projects remain.
 */
@QuarkusTest
class InactiveProjectCleanupTest {

    @Inject
    InactiveProjectCleanup cleanup;

    @Inject
    WorkspaceService workspaceService;

    private Integer originalRetentionDays;

    @AfterEach
    void restoreRetention() {
        if (originalRetentionDays != null) {
            int days = originalRetentionDays;
            QuarkusTransaction.requiringNew().run(() -> {
                RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                        .firstResult();
                config.inactiveProjectRetentionDays = days;
            });
            originalRetentionDays = null;
        }
    }

    @Test
    void deletesOnlyProjectsInactiveBeyondRetention() {
        QuarkusTransaction.requiringNew().run(() -> {
            RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                    .firstResult();
            if (config == null) {
                config = new RetentionConfigEntity();
                config.inactiveProjectRetentionDays = 90;
                config.eventRetentionDays = 90;
                config.traceRetentionDays = 30;
                config.persist();
            }
            originalRetentionDays = config.inactiveProjectRetentionDays;
            config.inactiveProjectRetentionDays = 30;
        });

        long definitionId = createDefinition();
        long projectA = createProjectWithRun(definitionId, "completed", Instant.now().minus(40, ChronoUnit.DAYS));
        long projectB = createProjectWithRun(definitionId, "waiting", null);
        long projectC = createProjectWithRun(definitionId, "completed", Instant.now().minus(1, ChronoUnit.DAYS));
        Path workspaceA = workspacePath(projectA);
        Path workspaceB = workspacePath(projectB);
        Path workspaceC = workspacePath(projectC);
        assertTrue(Files.exists(workspaceA));

        QuarkusTransaction.requiringNew().run(() -> cleanup.cleanup());

        assertFalse(Files.exists(workspaceA));
        assertTrue(Files.exists(workspaceB));
        assertTrue(Files.exists(workspaceC));
        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(ProjectEntity.findById(projectA));
            assertNotNull(ProjectEntity.findById(projectB));
            assertNotNull(ProjectEntity.findById(projectC));
        });
    }

    private Path workspacePath(long projectId) {
        return QuarkusTransaction.requiringNew().call(() ->
                workspaceService.getWorkspacePath(ProjectEntity.findById(projectId)));
    }

    private long createDefinition() {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
            def.name = "cleanup-def-" + UUID.randomUUID();
            def.content = "{}";
            def.createdOn = Instant.now();
            def.updatedOn = Instant.now();
            def.persist();
            return def.id;
        });
    }

    private long createProjectWithRun(long definitionId, String status, Instant completedOn) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ProjectEntity project = new ProjectEntity();
            project.name = "cleanup-" + status;
            project.type = "other";
            project.ref = "test/cleanup-" + UUID.randomUUID();
            project.createdOn = Instant.now();
            project.updatedOn = Instant.now();
            project.persist();
            workspaceService.ensureWorkspace(project);

            WorkflowRunEntity run = new WorkflowRunEntity();
            run.projectId = project.id;
            run.definitionId = definitionId;
            run.definitionVersion = 1;
            run.instanceState = "{}";
            run.status = status;
            run.startedOn = completedOn != null ? completedOn.minus(1, ChronoUnit.HOURS) : Instant.now();
            run.completedOn = completedOn;
            run.persist();
            return project.id;
        });
    }
}
