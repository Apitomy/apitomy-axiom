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
 * Defines a scheduled job that runs on a configurable CRON-style schedule.
 * Each definition produces {@link ScheduledJobRunEntity} instances when executed.
 * Jobs are global (not project-scoped) and support both AI agent and script execution modes.
 */
@Entity
@Table(name = "scheduled_job")
public class ScheduledJobEntity extends PanacheEntity {

    @Column(nullable = false, unique = true)
    public String name;

    public String slug;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(nullable = false)
    public boolean enabled;

    /**
     * Schedule preset: "hourly", "daily", "weekly", "monthly", or "none".
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

    @Column(name = "next_run_at")
    public Instant nextRunAt;

    @Column(name = "last_run_at")
    public Instant lastRunAt;

    /**
     * Execution mode: "agent" for AI agent execution, "script" for bash script execution.
     */
    @Column(name = "execution_mode", nullable = false)
    public String executionMode;

    /**
     * Prompt template for agent mode. Supports placeholders: {{jobName}}, {{apiBaseUrl}}.
     */
    @Column(name = "prompt_template", columnDefinition = "TEXT")
    public String promptTemplate;

    /**
     * Bash script template for script mode.
     * Supports placeholders: {{jobName}}, {{jobId}}, {{runId}}, {{apiBaseUrl}}.
     */
    @Column(name = "script_template", columnDefinition = "TEXT")
    public String scriptTemplate;

    /**
     * Optional AI model override for agent mode (e.g. "claude-sonnet-4-6").
     */
    public String model;

    /**
     * Optional AI engine override for agent mode (e.g. "claude-code", "opencode").
     */
    public String engine;

    /**
     * Comma-separated list of tools the AI agent is allowed to use.
     * Supports @ToolsetName references for toolset expansion.
     */
    @Column(name = "allowed_tools", columnDefinition = "TEXT")
    public String allowedTools;

    /**
     * Optional maximum number of agent steps/turns.
     */
    @Column(name = "max_steps")
    public Integer maxSteps;

    /**
     * Optional maximum budget in USD for AI execution.
     */
    @Column(name = "max_budget_usd")
    public Double maxBudgetUsd;

    /**
     * Optional JSON object of environment variables for the subprocess.
     * Values can reference secrets using ${secret:NAME} syntax.
     */
    @Column(columnDefinition = "TEXT")
    public String environment;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "updated_on", nullable = false)
    public Instant updatedOn;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "scheduled_job_label", joinColumns = @JoinColumn(name = "scheduled_job_id"))
    @Column(name = "label")
    public List<String> labels = new ArrayList<>();
}
