package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Single-row configuration for data retention periods. Each field
 * specifies the number of days to retain data before automatic cleanup.
 *
 * <p>For the history settings added in V72 (job runs, reports, workflow runs, activity log and
 * AI usage), {@code 0} means "keep forever"; it is the default so that upgrading never deletes
 * data the user did not ask to delete.
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

    /** Days to keep finished scheduled job runs; {@code 0} keeps them forever. */
    @Column(name = "scheduled_job_run_retention_days", nullable = false)
    public int scheduledJobRunRetentionDays;

    /** Days to keep finished reports; {@code 0} keeps them forever. */
    @Column(name = "report_retention_days", nullable = false)
    public int reportRetentionDays;

    /** Days to keep finished workflow runs; {@code 0} keeps them forever. */
    @Column(name = "workflow_run_retention_days", nullable = false)
    public int workflowRunRetentionDays;

    /** Days to keep activity log entries; {@code 0} keeps them forever. */
    @Column(name = "activity_log_retention_days", nullable = false)
    public int activityLogRetentionDays;

    /** Days to keep AI usage records; {@code 0} keeps them forever. */
    @Column(name = "ai_usage_retention_days", nullable = false)
    public int aiUsageRetentionDays;

}
