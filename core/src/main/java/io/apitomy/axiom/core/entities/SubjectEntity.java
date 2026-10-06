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
@Table(name = "subject")
public class SubjectEntity extends PanacheEntity {

    @Column(nullable = false)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String body;

    @Column(nullable = false)
    public String type;

    @Column(name = "ref_source")
    public String refSource;

    @Column(name = "subject_ref", nullable = false, unique = true)
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
    @CollectionTable(name = "subject_label", joinColumns = @JoinColumn(name = "subject_id"))
    @Column(name = "label")
    public List<String> labels = new ArrayList<>();

    /**
     * Whether the subject has any active (running or waiting) workflow run.
     *
     * @param subjectId the subject id
     * @return true if at least one run is running or waiting
     */
    public static boolean isActive(long subjectId) {
        return WorkflowRunEntity.count("subjectId = ?1 and status in ?2",
                subjectId, java.util.List.of("running", "waiting")) > 0;
    }

    /**
     * Returns the ids of all subjects that have at least one active (running or waiting) workflow run.
     *
     * @return the set of active subject ids
     */
    public static java.util.Set<Long> findActiveSubjectIds() {
        return new java.util.HashSet<>(WorkflowRunEntity.find(
                "select distinct r.subjectId from WorkflowRunEntity r where r.status in ?1",
                java.util.List.of("running", "waiting")).project(Long.class).list());
    }

    /**
     * Finds the schedule-owned subject with the given name, creating it if it does not exist. Schedule
     * subjects have type and ref source {@code "schedule"} and ref {@code "schedule:<name>"}.
     *
     * @param name the subject name
     * @return the existing or newly persisted subject
     */
    public static SubjectEntity findOrCreateByName(String name) {
        String ref = "schedule:" + name;
        SubjectEntity existing = find("ref", ref).firstResult();
        if (existing != null) {
            return existing;
        }
        SubjectEntity subject = new SubjectEntity();
        subject.name = name;
        subject.type = "schedule";
        subject.refSource = "schedule";
        subject.ref = ref;
        Instant now = Instant.now();
        subject.createdOn = now;
        subject.updatedOn = now;
        subject.persist();
        return subject;
    }
}
