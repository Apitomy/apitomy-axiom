package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Single-row configuration for data retention periods. Each field
 * specifies the number of days to retain data before automatic cleanup.
 */
@Entity
@Table(name = "retention_config")
public class RetentionConfigEntity extends PanacheEntity {

    @Column(name = "closed_project_retention_days", nullable = false)
    public int closedProjectRetentionDays;

    @Column(name = "trace_retention_days", nullable = false)
    public int traceRetentionDays;

    @Column(name = "event_retention_days", nullable = false)
    public int eventRetentionDays;

    @Column(name = "event_source_log_retention_days", nullable = false)
    public int eventSourceLogRetentionDays;
}
