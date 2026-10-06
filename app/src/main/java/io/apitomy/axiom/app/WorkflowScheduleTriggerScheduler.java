package io.apitomy.axiom.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.SubjectEntity;
import io.apitomy.axiom.core.entities.WorkflowRunEntity;
import io.apitomy.axiom.core.entities.WorkflowScheduleTriggerEntity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Fires due {@link WorkflowScheduleTriggerEntity} rows: ensures the fixed subject exists and starts a run of the
 * trigger's workflow definition, skipping the firing if that subject already has an active run of the same
 * definition.
 *
 * <p>Each firing uses three short transactions so that a failed run start never loses the trigger's
 * bookkeeping: the first claims the firing (advances {@code nextRunAt}, records {@code lastRunAt}, creates the
 * subject and checks for an active run), the second starts the run, and the third records
 * {@code lastOutcome}.</p>
 */
@ApplicationScoped
public class WorkflowScheduleTriggerScheduler {

    private static final Logger LOG = Logger.getLogger(WorkflowScheduleTriggerScheduler.class);

    static final String STARTED = "started";
    static final String SKIPPED = "skipped";

    @Inject
    WorkflowExecutionService workflowExecutionService;

    @Inject
    ObjectMapper objectMapper;

    private volatile boolean shuttingDown = false;

    /** A claimed firing that should attempt to start a run. */
    private record Attempt(long subjectId, long definitionId, String inputs) {
    }

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    @Scheduled(every = "${axiom.workflow.schedule-poll-interval:30s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void checkDueTriggers() {
        if (shuttingDown) {
            return;
        }
        List<Long> dueIds = QuarkusTransaction.requiringNew().call(() -> findDueTriggerIds(Instant.now()));
        for (long triggerId : dueIds) {
            try {
                fire(triggerId, Instant.now());
            } catch (Exception e) {
                LOG.errorf(e, "Schedule trigger %d failed", triggerId);
            }
        }
    }

    List<Long> findDueTriggerIds(Instant now) {
        return WorkflowScheduleTriggerEntity
                .<WorkflowScheduleTriggerEntity>list("enabled = true and nextRunAt <= ?1", now)
                .stream().map(t -> t.id).toList();
    }

    /**
     * Fires one trigger if it is enabled and due. Always advances {@code nextRunAt} and records
     * {@code lastRunAt}/{@code lastOutcome}, even when the run cannot be started, so a failing trigger does not
     * fire in a tight loop.
     *
     * @param triggerId the trigger id
     * @param now       the firing time
     */
    void fire(long triggerId, Instant now) {
        Attempt attempt = QuarkusTransaction.requiringNew().call(() -> claim(triggerId, now));
        if (attempt == null) {
            return;
        }
        String outcome;
        try {
            QuarkusTransaction.requiringNew().run(() -> startRun(attempt));
            outcome = STARTED;
        } catch (WorkflowRunConflictException e) {
            outcome = SKIPPED;
            LOG.infof("Schedule trigger %d skipped: subject %d already has an active run of definition %d",
                    triggerId, attempt.subjectId(), attempt.definitionId());
        } catch (Exception e) {
            outcome = failedOutcome(e);
            LOG.warnf(e, "Schedule trigger %d failed to start a run", triggerId);
        }
        String finalOutcome = outcome;
        QuarkusTransaction.requiringNew().run(() -> {
            WorkflowScheduleTriggerEntity trigger = WorkflowScheduleTriggerEntity.findById(triggerId);
            if (trigger != null) {
                trigger.lastOutcome = finalOutcome;
            }
        });
    }

    /**
     * Claims a due firing: advances the schedule, ensures the subject exists and records a skip if the subject
     * already has an active run of the definition.
     *
     * @return the run attempt to make, or null if the trigger is not due or the firing was skipped
     */
    private Attempt claim(long triggerId, Instant now) {
        WorkflowScheduleTriggerEntity trigger = WorkflowScheduleTriggerEntity.findById(triggerId);
        if (trigger == null || !trigger.enabled || trigger.nextRunAt == null || trigger.nextRunAt.isAfter(now)) {
            return null;
        }
        trigger.lastRunAt = now;
        trigger.nextRunAt = CronSchedules.nextAfter(trigger.cron, now);
        SubjectEntity subject = SubjectEntity.findOrCreateByName(trigger.subjectName);
        long activeRuns = WorkflowRunEntity.count("subjectId = ?1 and definitionId = ?2 and status in ?3",
                subject.id, trigger.definitionId, List.of("running", "waiting"));
        if (activeRuns > 0) {
            trigger.lastOutcome = SKIPPED;
            LOG.infof("Schedule trigger %d skipped: subject %d already has an active run of definition %d",
                    triggerId, subject.id, trigger.definitionId);
            return null;
        }
        return new Attempt(subject.id, trigger.definitionId, trigger.inputs);
    }

    private void startRun(Attempt attempt) {
        Map<String, Object> inputs;
        try {
            inputs = attempt.inputs() == null ? null
                    : objectMapper.readValue(attempt.inputs(), new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("Invalid trigger inputs: " + e.getMessage(), e);
        }
        workflowExecutionService.triggerWorkflow(attempt.subjectId(), attempt.definitionId(),
                new RunRequest(null, Map.of("trigger", "schedule"), inputs, null), null);
    }

    /**
     * Builds a {@code "failed: <reason>"} outcome truncated to the column length.
     *
     * @param e the failure
     * @return the outcome text
     */
    static String failedOutcome(Exception e) {
        String outcome = "failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        int max = WorkflowScheduleTriggerEntity.LAST_OUTCOME_MAX_LENGTH;
        return outcome.length() > max ? outcome.substring(0, max) : outcome;
    }
}
