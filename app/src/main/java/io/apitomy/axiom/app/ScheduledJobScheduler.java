package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ScheduledJobEntity;
import io.apitomy.axiom.core.entities.ScheduledJobRunEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * Polls for scheduled jobs that are due and triggers execution.
 * Runs every 60 seconds, checking for jobs where nextRunAt has passed.
 */
@ApplicationScoped
public class ScheduledJobScheduler {

    private static final Logger LOG = Logger.getLogger(ScheduledJobScheduler.class);

    @Inject
    ScheduledJobQueueConsumer queueConsumer;

    @Inject
    Event<SseEvent> sseEvents;

    private volatile boolean shuttingDown = false;

    @PreDestroy
    void onShutdown() {
        shuttingDown = true;
    }

    /**
     * Checks for scheduled jobs that are due and triggers execution.
     * The transactional work (creating runs, advancing nextRunAt) is
     * done first, then run IDs are enqueued after the transaction commits.
     */
    @Scheduled(every = "${axiom.scheduled-jobs.poll-interval:60s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void checkDueJobs() {
        if (shuttingDown) {
            return;
        }
        List<Long> runIds = createPendingRuns();

        for (Long runId : runIds) {
            sseEvents.fire(SseEvent.scheduledJobRunUpdated(runId, "Pending"));
            queueConsumer.enqueue(runId);
        }
    }

    /**
     * Creates pending run entities for all due jobs and advances their schedules.
     *
     * @return the list of created run IDs
     */
    @Transactional
    List<Long> createPendingRuns() {
        List<ScheduledJobEntity> dueJobs = ScheduledJobEntity
                .<ScheduledJobEntity>list(
                        "enabled = true and nextRunAt <= ?1", Instant.now());

        if (dueJobs.isEmpty()) {
            return List.of();
        }

        LOG.infof("Found %d scheduled job(s) due for execution", dueJobs.size());

        List<Long> runIds = new java.util.ArrayList<>();
        for (ScheduledJobEntity job : dueJobs) {
            try {
                ScheduledJobRunEntity run = new ScheduledJobRunEntity();
                run.jobId = job.id;
                run.status = "Pending";
                run.trigger = "scheduled";
                run.createdOn = Instant.now();
                run.persist();

                job.lastRunAt = Instant.now();
                job.nextRunAt = computeNextRunAt(job);
                job.updatedOn = Instant.now();

                LOG.infof("Created pending run for job '%s' (run ID: %d, next run: %s)",
                        job.name, run.id, job.nextRunAt);

                runIds.add(run.id);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to create run for job '%s'", job.name);
            }
        }
        return runIds;
    }

    /**
     * Creates a pending run entity for a manual trigger and advances the schedule.
     *
     * @param job the scheduled job to run
     * @return the created run entity ID
     */
    @Transactional
    public Long createRunForManualTrigger(ScheduledJobEntity job) {
        ScheduledJobRunEntity run = new ScheduledJobRunEntity();
        run.jobId = job.id;
        run.status = "Pending";
        run.trigger = "manual";
        run.createdOn = Instant.now();
        run.persist();

        job.lastRunAt = Instant.now();
        job.nextRunAt = computeNextRunAt(job);
        job.updatedOn = Instant.now();

        LOG.infof("Triggered manual run for job '%s' (run ID: %d, next run: %s)",
                job.name, run.id, job.nextRunAt);

        return run.id;
    }

    /**
     * Computes the next run time based on the schedule preset and time of day.
     */
    private Instant computeNextRunAt(ScheduledJobEntity job) {
        LocalTime timeOfDay = parseTimeOfDay(job.scheduleTime);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime todayAtTime = now.toLocalDate().atTime(timeOfDay)
                .atZone(ZoneId.systemDefault());

        switch (job.schedule) {
            case "none" -> {
                return null;
            }
            case "daily" -> {
                if (todayAtTime.isAfter(now)) {
                    return todayAtTime.toInstant();
                }
                return todayAtTime.plusDays(1).toInstant();
            }
            case "weekly" -> {
                DayOfWeek targetDay = parseDayOfWeek(job.scheduleDayOfWeek);
                if (targetDay != null) {
                    ZonedDateTime nextOccurrence = todayAtTime.with(
                            TemporalAdjusters.nextOrSame(targetDay));
                    if (!nextOccurrence.isAfter(now)) {
                        nextOccurrence = nextOccurrence.plusWeeks(1);
                    }
                    return nextOccurrence.toInstant();
                }
                return todayAtTime.plusWeeks(1).toInstant();
            }
            case "monthly" -> {
                if (todayAtTime.isAfter(now)) {
                    return todayAtTime.toInstant();
                }
                return todayAtTime.plusMonths(1).toInstant();
            }
            case "hourly" -> {
                return now.plusHours(1).truncatedTo(ChronoUnit.HOURS).toInstant();
            }
            default -> {
                if (todayAtTime.isAfter(now)) {
                    return todayAtTime.toInstant();
                }
                return todayAtTime.plusDays(1).toInstant();
            }
        }
    }

    /**
     * Computes the initial next run time for a newly enabled job.
     * Called when a job is enabled or updated via the REST API.
     *
     * @param job the scheduled job entity
     * @return the next run time, or null if schedule is "none"
     */
    public Instant computeInitialNextRunAt(ScheduledJobEntity job) {
        return computeNextRunAt(job);
    }

    private DayOfWeek parseDayOfWeek(String dayOfWeek) {
        if (dayOfWeek == null || dayOfWeek.isBlank()) return null;
        try {
            return DayOfWeek.valueOf(dayOfWeek.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private LocalTime parseTimeOfDay(String scheduleTime) {
        if (scheduleTime != null && !scheduleTime.isBlank()) {
            try {
                return LocalTime.parse(scheduleTime);
            } catch (Exception e) {
                // Fall back to 8:00 AM
            }
        }
        return LocalTime.of(8, 0);
    }
}
