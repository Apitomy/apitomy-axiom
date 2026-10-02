package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One distinct execution configuration of a {@link ReportDefinitionEntity} (#426).
 * Each configuration is stored once per definition, keyed by the SHA-256 hash of its canonical
 * JSON snapshot; every report points at the version it ran with. Snapshots never contain
 * secret values.
 */
@Entity
@Table(name = "report_definition_version", uniqueConstraints = @UniqueConstraint(
        name = "uq_rdv_def_hash", columnNames = {"definition_id", "config_hash"}))
public class ReportDefinitionVersionEntity extends PanacheEntity {

    @Column(name = "definition_id", nullable = false)
    public Long definitionId;

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
