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
 * Periodically deletes closed projects that have exceeded the configured
 * retention period. Runs once per hour.
 */
@ApplicationScoped
public class ClosedProjectCleanup {

    private static final Logger LOG = Logger.getLogger(ClosedProjectCleanup.class);

    @Inject
    ProjectDeletionService projectDeletionService;

    private volatile boolean shuttingDown = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    /**
     * Finds and deletes closed projects older than the retention period.
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

        Instant cutoff = Instant.now().minus(config.closedProjectRetentionDays, ChronoUnit.DAYS);
        List<ProjectEntity> staleProjects = ProjectEntity
                .find("status = 'Completed' and updatedOn < ?1", cutoff)
                .list();

        if (!staleProjects.isEmpty()) {
            for (ProjectEntity project : staleProjects) {
                LOG.infof("Cleaning up closed project %d (%s)", project.id, project.name);
                projectDeletionService.deleteProject(project);
            }
            LOG.infof("Cleaned up %d closed project(s) older than %d days",
                    staleProjects.size(), config.closedProjectRetentionDays);
        }
    }
}
