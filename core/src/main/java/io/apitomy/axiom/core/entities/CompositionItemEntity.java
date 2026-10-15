package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A draft item in a composition: a workflow (Flow document), an action type or a tool (pack item JSON).
 */
@Entity
@Table(name = "composition_item",
        uniqueConstraints = @UniqueConstraint(columnNames = {"composition_id", "item_type", "name"}))
public class CompositionItemEntity extends PanacheEntity {

    @Column(name = "composition_id", nullable = false)
    public Long compositionId;

    /** The {@code ConfigItemType} JSON name: workflow, action-type, tool or subscription. */
    @Column(name = "item_type", nullable = false)
    public String itemType;

    @Column(nullable = false)
    public String name;

    /** Database id of the published item this draft edits, or null for a new item. */
    @Column(name = "base_item_id")
    public Long baseItemId;

    /** Hash of the published item's JSON when it was loaded or last published. */
    @Column(name = "base_content_revision")
    public String baseContentRevision;

    @Column(columnDefinition = "TEXT", nullable = false)
    public String content;

    @Column(name = "content_revision", nullable = false)
    public String contentRevision;

    @Column(name = "last_author", nullable = false)
    public String lastAuthor;

    @Column(name = "last_turn_id")
    public String lastTurnId;

    @Column(name = "published_revision")
    public String publishedRevision;

    @Column(name = "published_on")
    public Instant publishedOn;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "updated_on", nullable = false)
    public Instant updatedOn;
}
