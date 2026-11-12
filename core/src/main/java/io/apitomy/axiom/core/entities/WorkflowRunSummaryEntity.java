package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * The AI-generated summary of one workflow run, also used as the generation queue. The row shares the run's
 * id and is deleted by the database when the run is deleted.
 */
@Entity
@Table(name = "workflow_run_summary",
        indexes = @Index(name = "idx_workflow_run_summary_status", columnList = "status"))
public class WorkflowRunSummaryEntity extends PanacheEntityBase {

    public static final String PENDING = "pending";
    public static final String GENERATING = "generating";
    public static final String READY = "ready";
    public static final String FAILED = "failed";
    public static final String AUTO = "auto";
    public static final String MANUAL = "manual";

    @Id
    @Column(name = "run_id")
    public Long runId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", foreignKey = @jakarta.persistence.ForeignKey(name = "fk_workflow_run_summary_run"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    public WorkflowRunEntity run;

    @Column(length = 20, nullable = false)
    public String status;

    @Column(length = 200)
    public String headline;

    @Column(columnDefinition = "TEXT")
    public String body;

    @Column(columnDefinition = "TEXT")
    public String error;

    public String engine;

    public String model;

    @Column(name = "trigger_type", length = 10, nullable = false)
    public String triggerType;

    @Column(nullable = false)
    public int attempts;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "completed_on")
    public Instant completedOn;

    /**
     * Creates an unsaved pending summary for a run.
     *
     * @param run the run
     * @param triggerType {@link #AUTO} or {@link #MANUAL}
     * @return the new entity
     */
    public static WorkflowRunSummaryEntity newPending(WorkflowRunEntity run, String triggerType) {
        WorkflowRunSummaryEntity entity = new WorkflowRunSummaryEntity();
        entity.run = run;
        entity.status = PENDING;
        entity.triggerType = triggerType;
        entity.attempts = 0;
        entity.createdOn = Instant.now();
        return entity;
    }
}
