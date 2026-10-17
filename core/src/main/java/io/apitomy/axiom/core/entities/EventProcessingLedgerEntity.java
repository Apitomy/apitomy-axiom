package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks the processing state of an event against a specific subscription.
 * Each row represents one (event, subscription) pair and records whether the
 * event matched the subscription's filter and whether routing completed.
 *
 * <p>Statuses: {@link #PENDING} (an attempt is in progress), {@link #COMPLETED}
 * (routing succeeded), {@link #SKIPPED} (filter did not match), {@link #FAILED}
 * (the last attempt failed; retried at {@code nextAttemptAt}), {@link #EXHAUSTED}
 * (the last allowed attempt failed; not retried unless retried manually) and
 * {@link #FILTER_ERROR} (the subscription's filter could not be evaluated against the
 * event; no route ran, and it is re-evaluated only after a manual retry or a change of
 * the filter).</p>
 */
@Entity
@Table(name = "event_processing_ledger", indexes = @Index(name = "idx_epl_status_next",
        columnList = "status, next_attempt_at"))
public class EventProcessingLedgerEntity extends PanacheEntity {

    /** An attempt is in progress. */
    public static final String PENDING = "pending";
    /** Routing succeeded. */
    public static final String COMPLETED = "completed";
    /** The last attempt failed; retried at {@code nextAttemptAt}. */
    public static final String FAILED = "failed";
    /** The last allowed attempt failed; retried only manually. */
    public static final String EXHAUSTED = "exhausted";
    /** The subscription's filter did not match the event. */
    public static final String SKIPPED = "skipped";
    /** The subscription's filter could not be evaluated against the event (fail closed). */
    public static final String FILTER_ERROR = "filter_error";

    @Column(name = "event_id", nullable = false)
    public UUID eventId;

    @Column(name = "subscription_id", nullable = false)
    public Long subscriptionId;

    /**
     * Processing status: one of {@link #PENDING}, {@link #COMPLETED}, {@link #SKIPPED},
     * {@link #FAILED}, {@link #EXHAUSTED} or {@link #FILTER_ERROR}.
     */
    @Column(nullable = false, length = 32)
    public String status;

    /**
     * Error message when status is {@link #FAILED}, {@link #EXHAUSTED} or {@link #FILTER_ERROR}.
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "processed_on")
    public Instant processedOn;

    /**
     * Number of routing attempts made so far (the first try included).
     */
    @Column(name = "attempt_count", nullable = false)
    public int attemptCount;

    /**
     * When the last attempt started.
     */
    @Column(name = "last_attempt_at")
    public Instant lastAttemptAt;

    /**
     * When a {@code failed} entry is due for its next retry; null for every other status.
     */
    @Column(name = "next_attempt_at")
    public Instant nextAttemptAt;
}
