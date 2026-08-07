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
 * A configured event source that Axiom monitors for new events.
 * Each event source watches a single resource (one GitHub repository
 * or one Jira project) and generates events via polling or webhooks.
 */
@Entity
@Table(name = "event_source")
public class EventSourceEntity extends PanacheEntity {

    @Column(nullable = false)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "source_type", nullable = false)
    public String sourceType;

    @Column(nullable = false)
    public boolean enabled;

    @Column(name = "poll_interval")
    public Integer pollInterval;

    @Column(name = "last_polled_at")
    public Instant lastPolledAt;

    /**
     * Optional reference to a secret name (from the Secrets store) to use
     * for authentication. If null, falls back to the default provider secret
     * (e.g. GH_TOKEN for GitHub, JIRA_API_TOKEN for Jira).
     */
    @Column(name = "secret_name")
    public String secretName;

    /**
     * Source-type-specific configuration stored as JSON.
     * GitHub: {"owner":"...","name":"...","url":"..."}
     * Jira: {"project":"...","baseUrl":"..."}
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    public String configuration;

    @Column(columnDefinition = "TEXT")
    public String filters;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "event_source_label", joinColumns = @JoinColumn(name = "event_source_id"))
    @Column(name = "label")
    public List<String> labels = new ArrayList<>();
}
