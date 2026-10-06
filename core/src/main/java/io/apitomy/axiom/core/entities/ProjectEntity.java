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

/**
 * Represents a tracked unit of work tied to a single external issue.
 */
@Entity
@Table(name = "project")
public class ProjectEntity extends PanacheEntity {

    @Column(nullable = false)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String body;

    @Column(nullable = false)
    public String type;

    @Column(name = "ref_source")
    public String refSource;

    @Column(name = "project_ref", nullable = false, unique = true)
    public String ref;

    public String repository;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "updated_on", nullable = false)
    public Instant updatedOn;

    @Column(columnDefinition = "TEXT")
    public String metadata;

    @Column(name = "disk_usage_bytes")
    public Long diskUsageBytes;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "project_label", joinColumns = @JoinColumn(name = "project_id"))
    @Column(name = "label")
    public List<String> labels = new ArrayList<>();

    /**
     * Whether the project has any active (running or waiting) workflow run.
     *
     * @param projectId the project id
     * @return true if at least one run is running or waiting
     */
    public static boolean isActive(long projectId) {
        return WorkflowRunEntity.count("projectId = ?1 and status in ?2",
                projectId, java.util.List.of("running", "waiting")) > 0;
    }

    /**
     * Returns the ids of all projects that have at least one active (running or waiting) workflow run.
     *
     * @return the set of active project ids
     */
    public static java.util.Set<Long> findActiveProjectIds() {
        return new java.util.HashSet<>(WorkflowRunEntity.find(
                "select distinct r.projectId from WorkflowRunEntity r where r.status in ?1",
                java.util.List.of("running", "waiting")).project(Long.class).list());
    }
}
