package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.SubjectEntity;
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
 * Tests for {@link InactiveSubjectCleanup}: subjects with no active run whose last run completed before the
 * retention cutoff are deleted (including their workspace); active or recently completed subjects remain.
 */
@QuarkusTest
class InactiveSubjectCleanupTest {

    @Inject
    InactiveSubjectCleanup cleanup;

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
                config.inactiveSubjectRetentionDays = days;
            });
            originalRetentionDays = null;
        }
    }

    @Test
    void deletesOnlySubjectsInactiveBeyondRetention() {
        QuarkusTransaction.requiringNew().run(() -> {
            RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                    .firstResult();
            if (config == null) {
                config = new RetentionConfigEntity();
                config.inactiveSubjectRetentionDays = 90;
                config.eventRetentionDays = 90;
                config.traceRetentionDays = 30;
                config.persist();
            }
            originalRetentionDays = config.inactiveSubjectRetentionDays;
            config.inactiveSubjectRetentionDays = 30;
        });

        long definitionId = createDefinition();
        long subjectA = createSubjectWithRun(definitionId, "completed", Instant.now().minus(40, ChronoUnit.DAYS));
        long subjectB = createSubjectWithRun(definitionId, "waiting", null);
        long subjectC = createSubjectWithRun(definitionId, "completed", Instant.now().minus(1, ChronoUnit.DAYS));
        Path workspaceA = workspacePath(subjectA);
        Path workspaceB = workspacePath(subjectB);
        Path workspaceC = workspacePath(subjectC);
        assertTrue(Files.exists(workspaceA));

        QuarkusTransaction.requiringNew().run(() -> cleanup.cleanup());

        assertFalse(Files.exists(workspaceA));
        assertTrue(Files.exists(workspaceB));
        assertTrue(Files.exists(workspaceC));
        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(SubjectEntity.findById(subjectA));
            assertNotNull(SubjectEntity.findById(subjectB));
            assertNotNull(SubjectEntity.findById(subjectC));
        });
    }

    private Path workspacePath(long subjectId) {
        return QuarkusTransaction.requiringNew().call(() ->
                workspaceService.getWorkspacePath(SubjectEntity.findById(subjectId)));
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

    private long createSubjectWithRun(long definitionId, String status, Instant completedOn) {
        return QuarkusTransaction.requiringNew().call(() -> {
            SubjectEntity subject = new SubjectEntity();
            subject.name = "cleanup-" + status;
            subject.type = "other";
            subject.ref = "test/cleanup-" + UUID.randomUUID();
            subject.createdOn = Instant.now();
            subject.updatedOn = Instant.now();
            subject.persist();
            workspaceService.ensureWorkspace(subject);

            WorkflowRunEntity run = new WorkflowRunEntity();
            run.title = "Test run";
            run.subjectId = subject.id;
            run.definitionId = definitionId;
            run.definitionVersion = 1;
            run.instanceState = "{}";
            run.status = status;
            run.startedOn = completedOn != null ? completedOn.minus(1, ChronoUnit.HOURS) : Instant.now();
            run.completedOn = completedOn;
            run.persist();
            return subject.id;
        });
    }
}
