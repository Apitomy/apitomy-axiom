package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A single execution (run) of a workflow definition against a subject.
 * Runs accumulate as history; at most one non-terminal run exists per (subject, definition).
 */
@Entity
@Table(name = "workflow_run")
public class WorkflowRunEntity extends PanacheEntity {

    @Column(name = "subject_id", nullable = false)
    public Long subjectId;

    @Column(name = "definition_id", nullable = false)
    public Long definitionId;

    @Column(name = "definition_version", nullable = false)
    public int definitionVersion;

    /** Optional run title, set only when the run was started with one; null otherwise. */
    @Column(length = 500)
    public String title;

    /** Free-form run labels; built-in ones use the {@code key:value} form, e.g. {@code trigger:manual}. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workflow_run_label", joinColumns = @JoinColumn(name = "workflow_run_id"))
    @Column(name = "label", nullable = false)
    public List<String> labels = new ArrayList<>();

    /** Workflow inputs supplied when the run was started, as a JSON object; null for none. */
    @Column(columnDefinition = "TEXT")
    public String inputs;

    @Column(name = "instance_state", columnDefinition = "TEXT", nullable = false)
    public String instanceState;

    @Column(length = 20, nullable = false)
    public String status;

    @Column(name = "current_node_id")
    public String currentNodeId;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    public String failureReason;

    /** Links this run to its execution trace; null if trace creation failed. */
    @Column(name = "trace_id")
    public UUID traceId;

    /**
     * The stream event whose create-workflow routing started this run; null for runs started
     * any other way. Not a foreign key: event retention deletes events.
     */
    @Column(name = "trigger_event_id")
    public UUID triggerEventId;

    /** The ledger entry (event × subscription) that started this run; null if not event-started. */
    @Column(name = "trigger_ledger_id")
    public Long triggerLedgerId;

    @Column(name = "started_on", nullable = false)
    public Instant startedOn;

    @Column(name = "completed_on")
    public Instant completedOn;
}
