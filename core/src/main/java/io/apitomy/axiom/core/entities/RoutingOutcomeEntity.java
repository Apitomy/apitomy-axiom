package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Records what a single routing rule produced when processing an event
 * against a subscription. Linked to the processing ledger entry.
 */
@Entity
@Table(name = "routing_outcome")
public class RoutingOutcomeEntity extends PanacheEntity {

    @Column(name = "ledger_id", nullable = false)
    public Long ledgerId;

    @Column(name = "routing_type", nullable = false, length = 32)
    public String routingType;

    @Column(nullable = false, length = 16)
    public String status;

    @Column(columnDefinition = "TEXT")
    public String summary;

    @Column(name = "project_id")
    public Long projectId;

    @Column(name = "task_id")
    public Long taskId;

    @Column(name = "trace_id")
    public UUID traceId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;
}
