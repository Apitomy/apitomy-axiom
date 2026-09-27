package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An authenticated connection to an external system (GitHub or Jira) that
 * produces a normalized event stream. The id is a user-provided slug
 * constrained to lowercase letters, numbers, and dashes.
 */
@Entity
@Table(name = "event_source_connection")
public class EventSourceConnectionEntity extends PanacheEntityBase {

    /**
     * User-provided slug identifier (e.g., "github-com", "jira-prod").
     * Constrained to [a-z0-9-]+, max 63 characters.
     */
    @Id
    @Column(length = 63)
    public String id;

    @Column(nullable = false)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    /**
     * Source type: "github" or "jira".
     */
    @Column(name = "source_type", nullable = false, length = 32)
    public String sourceType;

    @Column(nullable = false)
    public boolean enabled = true;

    /**
     * Base URL for the API. GitHub: "https://api.github.com" (default) or
     * GHE instance URL. Jira: "https://myorg.atlassian.net".
     */
    @Column(name = "base_url", nullable = false, length = 1024)
    public String baseUrl;

    /**
     * Optional reference to a secret name for authentication.
     * Falls back to default provider secrets if null.
     */
    @Column(name = "secret_name")
    public String secretName;

    /**
     * Poll interval in seconds. If null, uses the system default.
     */
    @Column(name = "poll_interval")
    public Integer pollInterval;

    /**
     * Source-type-specific configuration stored as JSON.
     * GitHub: {"repositories": ["owner/repo1", "owner/repo2"]}
     * Jira: {"projects": ["PROJ1", "PROJ2"]}
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    public String configuration;

    /**
     * Timestamp of the last successful poll.
     */
    @Column(name = "last_polled_at")
    public Instant lastPolledAt;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "modified_on", nullable = false)
    public Instant modifiedOn;
}
