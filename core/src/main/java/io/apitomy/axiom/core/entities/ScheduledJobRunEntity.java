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

    /**
     * Who triggered the run: "scheduler" or "manual". Axiom has no authentication, so no user
     * identity is available for manual runs.
     */
    @Column(name = "triggered_by")
    public String triggeredBy;

    /**
     * Trace of the caller (typically an agent) that triggered a manual run, if the request
     * carried a valid caller trace.
     */
    @Column(name = "triggered_by_trace_id")
    public UUID triggeredByTraceId;

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
