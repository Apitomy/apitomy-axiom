package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Audits the result of a single poll cycle for a connection.
 */
@Entity
@Table(name = "connection_poll_log")
public class ConnectionPollLogEntity extends PanacheEntity {

    @Column(name = "connection_id", nullable = false, length = 63)
    public String connectionId;

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
