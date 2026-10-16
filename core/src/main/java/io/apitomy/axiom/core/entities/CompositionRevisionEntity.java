package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One saved version of a composition item's content, with who made it.
 */
@Entity
@Table(name = "composition_revision")
public class CompositionRevisionEntity extends PanacheEntity {

    @Column(name = "item_id", nullable = false)
    public Long itemId;

    @Column(columnDefinition = "TEXT", nullable = false)
    public String content;

    @Column(name = "content_revision", nullable = false)
    public String contentRevision;

    /** {@code user} or {@code ai}. */
    @Column(nullable = false)
    public String author;

    @Column(name = "turn_id")
    public String turnId;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;
}
