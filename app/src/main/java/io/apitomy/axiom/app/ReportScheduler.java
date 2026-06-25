package io.apitomy.axiom.app;

import io.apitomy.axiom.core.entities.ReportDefinitionEntity;
import io.apitomy.axiom.core.entities.ReportEntity;
import io.apitomy.axiom.core.events.SseEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * Polls for report definitions that are due and triggers report generation.
 * Runs every 60 seconds, checking for definitions where nextRunAt has passed.
 */
@ApplicationScoped
public class ReportScheduler {

    private static final Logger LOG = Logger.getLogger(ReportScheduler.class);

    @Inject
    ReportQueueConsumer reportQueuePoller;

    @Inject
    Event<SseEvent> sseEvents;

    /**
     * Checks for report definitions that are due and triggers generation.
     * The transactional work (creating reports, advancing nextRunAt) is
     * done first, then report IDs are enqueued after the transaction commits.
     */
    @Scheduled(every = "${axiom.reports.poll-interval:60s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void checkDueReports() {
        // Step 1: create reports and advance schedules (transactional)
        List<Long> reportIds = createPendingReports();

        // Step 2: enqueue for execution (after transaction has committed)
        for (Long reportId : reportIds) {
            sseEvents.fire(SseEvent.reportUpdated(reportId, "Pending"));
            reportQueuePoller.enqueue(reportId);
        }
    }

    /**
     * Creates pending report entities for all due definitions and advances
     * their schedules. Returns the list of created report IDs.
     */
    @Transactional
    List<Long> createPendingReports() {
        List<ReportDefinitionEntity> dueReports = ReportDefinitionEntity
                .<ReportDefinitionEntity>list(
                        "enabled = true and nextRunAt <= ?1", Instant.now());

        if (dueReports.isEmpty()) {
            return List.of();
        }

        LOG.infof("Found %d report(s) due for generation", dueReports.size());

        List<Long> reportIds = new java.util.ArrayList<>();
        for (ReportDefinitionEntity definition : dueReports) {
            try {
                ReportEntity report = new ReportEntity();
                report.definitionId = definition.id;
                report.status = "Pending";
                report.title = definition.name;
                report.createdOn = Instant.now();
                report.labels.addAll(definition.initialLabels);
                report.persist();

                definition.lastRunAt = Instant.now();
                definition.nextRunAt = computeNextRunAt(definition);
                definition.updatedOn = Instant.now();

                LOG.infof("Created pending report '%s' (report ID: %d, next run: %s)",
                        definition.name, report.id, definition.nextRunAt);

                reportIds.add(report.id);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to create report for '%s'", definition.name);
            }
        }
        return reportIds;
    }

    /**
     * Creates a pending report entity and advances the definition's next run time.
     *
     * @param definition the report definition
     * @return the created report entity ID
     */
    @Transactional
    public Long createReportAndScheduleNext(ReportDefinitionEntity definition) {
        // Create the report entity
        ReportEntity report = new ReportEntity();
        report.definitionId = definition.id;
        report.status = "Pending";
        report.title = definition.name;
        report.createdOn = Instant.now();
        report.labels.addAll(definition.initialLabels);
        report.persist();

        // Update the definition's scheduling
        definition.lastRunAt = Instant.now();
        definition.nextRunAt = computeNextRunAt(definition);
        definition.updatedOn = Instant.now();

        LOG.infof("Triggered report '%s' (report ID: %d, next run: %s)",
                definition.name, report.id, definition.nextRunAt);

        return report.id;
    }

    /**
     * Computes the next run time based on the schedule preset and time of day.
     */
    /**
     * Computes the next run time based on the schedule preset and time of day.
     * If today's scheduled time hasn't passed yet, returns today's time.
     * Otherwise returns the next occurrence (tomorrow, next week, etc.).
     */
    private Instant computeNextRunAt(ReportDefinitionEntity definition) {
        LocalTime timeOfDay = parseTimeOfDay(definition.scheduleTime);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime todayAtTime = now.toLocalDate().atTime(timeOfDay)
                .atZone(ZoneId.systemDefault());

        switch (definition.schedule) {
            case "none" -> {
                return null;
            }
            case "daily" -> {
                // If today's time hasn't passed, schedule for today; otherwise tomorrow
                if (todayAtTime.isAfter(now)) {
                    return todayAtTime.toInstant();
                }
                return todayAtTime.plusDays(1).toInstant();
            }
            case "weekly" -> {
                DayOfWeek targetDay = parseDayOfWeek(definition.scheduleDayOfWeek);
                if (targetDay != null) {
                    ZonedDateTime nextOccurrence = todayAtTime.with(TemporalAdjusters.next(targetDay));
                    return nextOccurrence.toInstant();
                }
                return todayAtTime.plusWeeks(1).toInstant();
            }
            case "monthly" -> {
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
     * Computes the initial next run time for a newly enabled definition.
     * Called when a definition is enabled or updated via the REST API.
     *
     * @param definition the report definition
     * @return the next run time
     */
    public Instant computeInitialNextRunAt(ReportDefinitionEntity definition) {
        return computeNextRunAt(definition);
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
