package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Records that a stream event resumed a {@link WorkflowRunEntity} parked at a receive-event
 * node. The {@link WorkflowEventSubscriptionEntity} is deleted when it is consumed, so this is
 * the lasting record of which event resumed the run, at which node. Deleted with the run
 * (ON DELETE CASCADE); {@code eventId} and {@code ledgerId} are not foreign keys because event
 * retention deletes events.
 */
@Entity
@Table(name = "workflow_run_resume")
public class WorkflowRunResumeEntity extends PanacheEntity {

    @Column(name = "run_id", nullable = false)
    public Long runId;

    @Column(name = "node_id", nullable = false)
    public String nodeId;

    @Column(name = "event_id", nullable = false)
    public UUID eventId;

    @Column(name = "ledger_id")
    public Long ledgerId;

    /** The receive-event trace node that the event completed; null if the run has no trace. */
    @Column(name = "trace_node_id")
    public Long traceNodeId;

    @Column(name = "resumed_on", nullable = false)
    public Instant resumedOn;
}
