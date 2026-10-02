package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One result of a routing outcome, e.g. a task created by a Manager decision or a workflow
 * run started by a create-workflow rule. An outcome can have any number of items. Items are
 * deleted with their outcome (ON DELETE CASCADE).
 */
@Entity
@Table(name = "routing_outcome_item")
public class RoutingOutcomeItemEntity extends PanacheEntity {

    /** A task was created (Manager create_task/script_action decision, or invoke-action). */
    public static final String TYPE_TASK = "task";
    /** A workflow run was started (create-workflow). */
    public static final String TYPE_WORKFLOW_RUN = "workflow-run";
    /** The Manager decided to ignore the event. */
    public static final String TYPE_IGNORED = "ignored";
    /** The Manager escalated the event (explicitly, or because of low confidence). */
    public static final String TYPE_ESCALATED = "escalated";
    /** A Manager decision of an unknown type (always failed). */
    public static final String TYPE_DECISION = "decision";

    @Column(name = "outcome_id", nullable = false)
    public Long outcomeId;

    @Column(name = "item_type", nullable = false, length = 32)
    public String itemType;

    @Column(nullable = false, length = 16)
    public String status;

    @Column(columnDefinition = "TEXT")
    public String summary;

    @Column(name = "error_message", columnDefinition = "TEXT")
    public String errorMessage;

    @Column(name = "project_id")
    public Long projectId;

    @Column(name = "task_id")
    public Long taskId;

    @Column(name = "workflow_run_id")
    public Long workflowRunId;

    @Column(name = "trace_node_id")
    public Long traceNodeId;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;
}
