package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.RetentionConfigEntity;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Periodically deletes inactive projects that have exceeded the configured
 * retention period. A project is inactive when it has no running or waiting
 * workflow run; it is eligible for deletion once its most recent run completed
 * before the retention cutoff. Runs once per hour.
 */
@ApplicationScoped
public class InactiveProjectCleanup {

    private static final Logger LOG = Logger.getLogger(InactiveProjectCleanup.class);

    private static final List<String> ACTIVE_STATUSES = List.of("running", "waiting");

    @Inject
    ProjectDeletionService projectDeletionService;

    private volatile boolean shuttingDown = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    /**
     * Finds and deletes inactive projects whose last run completed before the retention period.
     */
    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void cleanup() {
        if (shuttingDown) {
            return;
        }
        RetentionConfigEntity config = RetentionConfigEntity.<RetentionConfigEntity>findAll()
                .firstResult();
        if (config == null) {
            return;
        }

        Instant cutoff = Instant.now().minus(config.inactiveProjectRetentionDays, ChronoUnit.DAYS);
        List<ProjectEntity> staleProjects = ProjectEntity
                .find("id in (select r.projectId from WorkflowRunEntity r"
                        + " group by r.projectId having max(r.completedOn) < ?1)"
                        + " and id not in (select a.projectId from WorkflowRunEntity a"
                        + " where a.status in ?2)", cutoff, ACTIVE_STATUSES)
                .list();

        if (!staleProjects.isEmpty()) {
            for (ProjectEntity project : staleProjects) {
                LOG.infof("Cleaning up inactive project %d (%s)", project.id, project.name);
                projectDeletionService.deleteProject(project);
            }
            LOG.infof("Cleaned up %d inactive project(s) older than %d days",
                    staleProjects.size(), config.inactiveProjectRetentionDays);
        }
    }
}
