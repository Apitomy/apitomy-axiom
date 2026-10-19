package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Audits the result of a single poll cycle for an event source.
 */
@Entity
@Table(name = "event_source_poll_log")
public class EventSourcePollLogEntity extends PanacheEntity {

    /** Status of a successful poll. */
    public static final String STATUS_SUCCESS = "success";

    /** Status of a failed poll. */
    public static final String STATUS_ERROR = "error";

    /** Message prefix of a successful poll that only established the baseline (first poll). */
    public static final String BASELINE_MESSAGE_PREFIX = "Baseline";

    @Column(name = "event_source_id", nullable = false, length = 63)
    public String eventSourceId;

    /** "success" or "error" */
    @Column(nullable = false, length = 16)
    public String status;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String message;

    @Column(columnDefinition = "TEXT")
    public String detail;

    @Column(name = "events_ingested")
    public Integer eventsIngested;

    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;
}
