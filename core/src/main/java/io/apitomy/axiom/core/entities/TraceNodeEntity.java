package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single span/step within a {@link TraceEntity} tree. Lightweight breadcrumb
 * that references a more detailed record elsewhere via {@code entityType} and
 * {@code entityId}.
 */
@Entity
@Table(name = "trace_node")
public class TraceNodeEntity extends PanacheEntity {

    @Column(name = "trace_id", nullable = false)
    public UUID traceId;

    @Column(name = "parent_node_id")
    public Long parentNodeId;

    @Column(name = "node_type", nullable = false)
    public String nodeType;

    @Column(nullable = false)
    public String status;

    @Column(nullable = false, length = 1024)
    public String summary;

    @Column(name = "started_on", nullable = false)
    public Instant startedOn;

    @Column(name = "completed_on")
    public Instant completedOn;

    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(name = "entity_type")
    public String entityType;

    @Column(name = "entity_id")
    public Long entityId;
}
