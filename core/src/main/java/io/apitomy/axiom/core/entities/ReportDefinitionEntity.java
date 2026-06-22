package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Defines a recurring report that the system generates on a schedule.
 * Each definition produces {@link ReportEntity} instances when executed.
 */
@Entity
@Table(name = "report_definition")
public class ReportDefinitionEntity extends PanacheEntity {

    @Column(nullable = false)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    /**
     * Schedule preset: "daily", "weekly", "monthly", or a cron expression.
     */
    @Column(nullable = false)
    public String schedule;

    /**
     * Time of day to run (e.g. "08:00"). Used with schedule presets.
     */
    @Column(name = "schedule_time")
    public String scheduleTime;

    /**
     * Day of week for weekly schedules (e.g. "monday", "tuesday").
     */
    @Column(name = "schedule_day_of_week")
    public String scheduleDayOfWeek;

    /**
     * Time window for the report: "since-last-run", "last-24h", "last-7d", "last-30d".
     */
    @Column(name = "time_window", nullable = false)
    public String timeWindow;

    /**
     * Prompt template with placeholders for the AI agent.
     * Supports: {{repositories}}, {{timeRangeStart}}, {{timeRangeEnd}}, {{timeWindow}}
     */
    @Column(name = "prompt_template", nullable = false, columnDefinition = "TEXT")
    public String promptTemplate;

    /**
     * Optional template for the report title. Supports placeholders:
     * {{name}}, {{date}}, {{time}}, {{datetime}}, {{timeWindow}}, {{schedule}}.
     * When set, the resolved template is used instead of extracting from markdown.
     */
    @Column(name = "title_template", columnDefinition = "TEXT")
    public String titleTemplate;

    /**
     * Comma-separated list of tools the AI agent is allowed to use.
     */
    @Column(name = "allowed_tools", columnDefinition = "TEXT")
    public String allowedTools;

    /**
     * Optional JSON object of environment variables for the subprocess.
     * Values can reference secrets using ${secret:NAME} syntax.
     */
    @Column(columnDefinition = "TEXT")
    public String environment;

    /**
     * Optional per-report timeout in seconds. Overrides the global default when set.
     */
    @Column(name = "timeout_seconds")
    public Integer timeoutSeconds;

    @Column(nullable = false)
    public boolean enabled;

    @Column(name = "next_run_at")
    public Instant nextRunAt;

    @Column(name = "last_run_at")
    public Instant lastRunAt;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "updated_on", nullable = false)
    public Instant updatedOn;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "report_definition_initial_label", joinColumns = @JoinColumn(name = "report_definition_id"))
    @Column(name = "label")
    public List<String> initialLabels = new ArrayList<>();
}
