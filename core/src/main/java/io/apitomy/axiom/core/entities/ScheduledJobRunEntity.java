package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Records a single execution of a {@link ScheduledJobEntity}.
 * Tracks status, output, errors, cost, and timing information.
 */
@Entity
@Table(name = "scheduled_job_run")
public class ScheduledJobRunEntity extends PanacheEntity {

    @Column(name = "job_id", nullable = false)
    public Long jobId;

    /**
     * Run status: "Pending", "Running", "Completed", or "Failed".
     */
    @Column(nullable = false)
    public String status;

    /**
     * How the run was triggered: "scheduled" or "manual".
     */
    @Column(name = "run_trigger", nullable = false)
    public String trigger;

    @Column(name = "started_at")
    public Instant startedAt;

    @Column(name = "completed_at")
    public Instant completedAt;

    @Column(columnDefinition = "TEXT")
    public String output;

    @Column(columnDefinition = "TEXT")
    public String error;

    @Column(name = "execution_log", columnDefinition = "TEXT")
    public String executionLog;

    @Column(name = "cost_usd")
    public Double costUsd;

    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(name = "trace_id")
    public UUID traceId;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;
}
