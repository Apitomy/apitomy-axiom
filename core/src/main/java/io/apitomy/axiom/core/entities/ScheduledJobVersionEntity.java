package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One distinct execution configuration of a {@link ScheduledJobEntity} (#426).
 * Each configuration is stored once per definition, keyed by the SHA-256 hash of its canonical
 * JSON snapshot; every scheduled job run points at the version it ran with. Snapshots never contain
 * secret values.
 */
@Entity
@Table(name = "scheduled_job_version")
public class ScheduledJobVersionEntity extends PanacheEntity {

    @Column(name = "job_id", nullable = false)
    public Long jobId;

    /**
     * Hex SHA-256 hash of {@link #configSnapshot}.
     */
    @Column(name = "config_hash", nullable = false, length = 64)
    public String configHash;

    /**
     * Canonical JSON object of the execution-relevant configuration fields.
     */
    @Column(name = "config_snapshot", nullable = false, columnDefinition = "TEXT")
    public String configSnapshot;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;
}
