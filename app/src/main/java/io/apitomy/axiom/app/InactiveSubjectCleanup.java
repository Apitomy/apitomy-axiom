package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.SubjectEntity;
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
 * Periodically deletes inactive subjects that have exceeded the configured
 * retention period. A subject is inactive when it has no running or waiting
 * workflow run; it is eligible for deletion once its most recent run completed
 * before the retention cutoff. Runs once per hour.
 */
@ApplicationScoped
public class InactiveSubjectCleanup {

    private static final Logger LOG = Logger.getLogger(InactiveSubjectCleanup.class);

    private static final List<String> ACTIVE_STATUSES = List.of("running", "waiting");

    @Inject
    SubjectDeletionService subjectDeletionService;

    private volatile boolean shuttingDown = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    /**
     * Finds and deletes inactive subjects whose last run completed before the retention period.
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

        Instant cutoff = Instant.now().minus(config.inactiveSubjectRetentionDays, ChronoUnit.DAYS);
        List<SubjectEntity> staleSubjects = SubjectEntity
                .find("id in (select r.subjectId from WorkflowRunEntity r"
                        + " group by r.subjectId having max(r.completedOn) < ?1)"
                        + " and id not in (select a.subjectId from WorkflowRunEntity a"
                        + " where a.status in ?2)", cutoff, ACTIVE_STATUSES)
                .list();

        if (!staleSubjects.isEmpty()) {
            for (SubjectEntity subject : staleSubjects) {
                LOG.infof("Cleaning up inactive subject %d (%s)", subject.id, subject.name);
                subjectDeletionService.deleteSubject(subject);
            }
            LOG.infof("Cleaned up %d inactive subject(s) older than %d days",
                    staleSubjects.size(), config.inactiveSubjectRetentionDays);
        }
    }
}
