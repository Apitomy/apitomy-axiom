package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a discrete unit of work within a Project.
 */
@Entity
@Table(name = "task")
public class TaskEntity extends PanacheEntity {

    @Column(name = "project_id", nullable = false)
    public Long projectId;

    @Column(name = "action_type", nullable = false)
    public String actionType;

    @Column(name = "created_by", nullable = false)
    public String createdBy;

    @Column(name = "event_id")
    public Long eventId;

    @Column(name = "assigned_agent")
    public Long assignedAgent;

    @Column(nullable = false)
    public String status;

    @Column(columnDefinition = "TEXT")
    public String input;

    @Column(columnDefinition = "TEXT")
    public String output;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "completed_on")
    public Instant completedOn;


    @Column(name = "trace_id")
    public UUID traceId;

    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "workflow_instance_id")
    public Long workflowInstanceId;

    /**
     * Structured context for human tasks: title, description, reference links.
     * Stored as JSON. Null for non-human tasks.
     */
    @Column(name = "human_context", columnDefinition = "TEXT")
    public String humanContext;

    /**
     * Schema defining the form fields the human must fill in.
     * Stored as JSON. Null means a freeform text response is expected.
     */
    @Column(name = "output_schema", columnDefinition = "TEXT")
    public String outputSchema;

    @Column(name = "execution_log", columnDefinition = "TEXT")
    public String executionLog;
}
