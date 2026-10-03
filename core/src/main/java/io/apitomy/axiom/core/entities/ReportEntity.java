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
import java.util.UUID;
import java.util.List;

/**
 * A single generated report instance produced from a {@link ReportDefinitionEntity}.
 * Contains the report content (markdown), metadata about the time range covered,
 * execution status, and AI cost.
 */
@Entity
@Table(name = "report")
public class ReportEntity extends PanacheEntity {

    @Column(name = "definition_id", nullable = false)
    public Long definitionId;

    @Column(nullable = false)
    public String status;

    public String title;

    /**
     * How the report was triggered: "scheduled" or "manual" (null for reports created before
     * this was recorded).
     */
    @Column(name = "report_trigger")
    public String trigger;

    /**
     * Who triggered the report: "scheduler" or "manual". Axiom has no authentication, so no
     * user identity is available for manual reports.
     */
    @Column(name = "triggered_by")
    public String triggeredBy;

    /**
     * Trace of the caller (typically an agent) that triggered a manual report, if the request
     * carried a valid caller trace.
     */
    @Column(name = "triggered_by_trace_id")
    public UUID triggeredByTraceId;

    @Column(columnDefinition = "TEXT")
    public String content;

    @Column(name = "time_range_start")
    public Instant timeRangeStart;

    @Column(name = "time_range_end")
    public Instant timeRangeEnd;

    @Column(name = "execution_log", columnDefinition = "TEXT")
    public String executionLog;

    @Column(name = "cost_usd")
    public Double costUsd;

    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "completed_on")
    public Instant completedOn;


    @Column(name = "trace_id")
    public UUID traceId;

    /**
     * The configuration version this ran with (#426); null when none was recorded.
     */
    @Column(name = "config_version_id")
    public Long configVersionId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "report_label", joinColumns = @JoinColumn(name = "report_id"))
    @Column(name = "label")
    public List<String> labels = new ArrayList<>();
}
