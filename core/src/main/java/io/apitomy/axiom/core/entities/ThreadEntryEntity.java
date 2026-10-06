package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A conversation thread entry within a Subject.
 */
@Entity
@Table(name = "thread_entry")
public class ThreadEntryEntity extends PanacheEntity {

    @Column(name = "subject_id", nullable = false)
    public Long subjectId;

    @Column(name = "author_type", nullable = false)
    public String authorType;

    @Column(name = "author_id")
    public String authorId;

    @Column(name = "entry_type", nullable = false)
    public String entryType;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String content;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;
}
