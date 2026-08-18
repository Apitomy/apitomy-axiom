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

    @Column(nullable = false)
    public String status;

    @Column(name = "issue_source", nullable = false)
    public String issueSource;

    @Column(name = "issue_ref", nullable = false, unique = true)
    public String issueRef;

    @Column(nullable = false)
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
}
