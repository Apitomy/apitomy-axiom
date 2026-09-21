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
 * A filtered view over the event stream. Each subscription defines filter
 * criteria and labels for routing matching events to destinations like
 * Manager triage, workflow dispatch, or future targets.
 */
@Entity
@Table(name = "event_subscription")
public class EventSubscriptionEntity extends PanacheEntity {

    @Column(nullable = false)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(nullable = false)
    public boolean enabled = true;

    /**
     * Jakarta EL filter expression that evaluates to boolean.
     * Available variables: event.type, event.source, event.connectionId,
     * event.ref, event.timestamp, event.actor.login, event.payload.*
     */
    @Column(columnDefinition = "TEXT")
    public String filters;

    /**
     * Routing rules stored as JSON. Each rule specifies a destination type
     * and optional configuration. Example:
     * [{"type":"manager"},{"type":"create-workflow","workflowDefinitionId":5}]
     */
    @Column(columnDefinition = "TEXT")
    public String routing;

    /**
     * Free-form labels for routing and categorization. Used to match
     * subscriptions to action types and to scope Manager evaluation.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "event_subscription_label",
            joinColumns = @JoinColumn(name = "event_subscription_id")
    )
    @Column(name = "label")
    public List<String> labels = new ArrayList<>();

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "modified_on", nullable = false)
    public Instant modifiedOn;
}
