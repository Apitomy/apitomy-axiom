package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A cron schedule that starts runs of a workflow definition against a fixed subject.
 */
@Entity
@Table(name = "workflow_schedule_trigger")
public class WorkflowScheduleTriggerEntity extends PanacheEntity {

    /** Maximum length of {@link #lastOutcome}. */
    public static final int LAST_OUTCOME_MAX_LENGTH = 1000;

    @Column(name = "definition_id", nullable = false)
    public Long definitionId;

    /** Unix (5-field) cron expression, evaluated in UTC. */
    @Column(nullable = false, length = 100)
    public String cron;

    /** Name of the fixed subject every run attaches to; created on first firing. */
    @Column(name = "subject_name", nullable = false, length = 255)
    public String subjectName;

    /** Static workflow inputs as a JSON object; null for none. */
    @Column(columnDefinition = "TEXT")
    public String inputs;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "next_run_at")
    public Instant nextRunAt;

    @Column(name = "last_run_at")
    public Instant lastRunAt;

    /** Outcome of the last firing: "started", "skipped" or "failed: &lt;reason&gt;". */
    @Column(name = "last_outcome", length = LAST_OUTCOME_MAX_LENGTH)
    public String lastOutcome;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "updated_on", nullable = false)
    public Instant updatedOn;
}
