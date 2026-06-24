package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Root of a complete execution trace — connects every step in a pipeline run
 * or report generation into a directed graph of {@link TraceNodeEntity} spans.
 */
@Entity
@Table(name = "trace")
public class TraceEntity extends PanacheEntityBase {

    @Id
    @Column(name = "trace_id", nullable = false)
    public UUID traceId;

    @Column(name = "trace_type", nullable = false)
    public String traceType;

    @Column(nullable = false)
    public String status;

    @Column(nullable = false, length = 1024)
    public String summary;

    @Column(name = "event_id")
    public Long eventId;

    @Column(name = "project_id")
    public Long projectId;

    @Column(name = "report_id")
    public Long reportId;

    @Column(name = "started_on", nullable = false)
    public Instant startedOn;

    @Column(name = "completed_on")
    public Instant completedOn;
}
