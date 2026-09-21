package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks the processing state of an event against a specific subscription.
 * Each row represents one (event, subscription) pair and records whether the
 * event matched the subscription's filter and whether routing completed.
 *
 * <p>Statuses: {@code pending} (matched, routing in progress), {@code completed}
 * (routing succeeded), {@code skipped} (filter did not match), {@code failed}
 * (routing threw an exception).</p>
 */
@Entity
@Table(name = "event_processing_ledger")
public class EventProcessingLedgerEntity extends PanacheEntity {

    @Column(name = "event_id", nullable = false)
    public UUID eventId;

    @Column(name = "subscription_id", nullable = false)
    public Long subscriptionId;

    /**
     * Processing status: "pending", "completed", "skipped", "failed".
     */
    @Column(nullable = false, length = 32)
    public String status;

    /**
     * Error message when status is "failed".
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "processed_on")
    public Instant processedOn;
}
