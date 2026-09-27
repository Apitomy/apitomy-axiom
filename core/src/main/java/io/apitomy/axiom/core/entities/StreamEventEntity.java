package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single normalized event in the event stream. Every event produced by
 * an {@link EventSourceConnectionEntity} is stored here with a typed,
 * schema-validated payload.
 */
@Entity
@Table(name = "stream_event")
public class StreamEventEntity extends PanacheEntityBase {

    @Id
    public UUID id;

    /**
     * Original ID from the source system, used for deduplication.
     * GitHub: Events API id. Jira: synthesized from issue key + changelog entry ID.
     */
    @Column(name = "source_event_id", nullable = false)
    public String sourceEventId;

    /**
     * Source system identifier: "github" or "jira".
     */
    @Column(nullable = false, length = 32)
    public String source;

    /**
     * Slug of the EventSourceConnection that produced this event.
     */
    @Column(name = "connection_id", nullable = false, length = 63)
    public String connectionId;

    /**
     * Normalized event type (e.g., "issue.created", "pr.merged").
     */
    @Column(nullable = false, length = 64)
    public String type;

    /**
     * Full URL uniquely identifying the subject of the event.
     * GitHub: https://github.com/owner/repo/issues/123
     * Jira: https://myorg.atlassian.net/browse/PROJ-123
     */
    @Column(nullable = false, length = 2048)
    public String ref;

    /**
     * When the event occurred in the source system.
     */
    @Column(name = "timestamp", nullable = false)
    public Instant timestamp;

    /**
     * The actor who performed the action, stored as JSON.
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    public String actor;

    /**
     * Typed event payload, stored as JSON. Schema determined by the event type.
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    public String payload;

    /**
     * Raw source-specific data escape hatch, stored as JSON. Nullable.
     */
    @Column(name = "source_data", columnDefinition = "TEXT")
    public String sourceData;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;
}
