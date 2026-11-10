package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Stores an encrypted secret (environment variable) that is injected
 * into agent subprocess environments at execution time.
 */
@Entity
@Table(name = "secret")
public class SecretEntity extends PanacheEntity {

    @Column(nullable = false, unique = true)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "encrypted_value", nullable = false, columnDefinition = "TEXT")
    public String encryptedValue;

    /** When the secret expires; null means it never expires. */
    @Column(name = "expires_on")
    public Instant expiresOn;

    /** Per-secret warning window in days; null uses {@code axiom.secrets.expiry-warning-days}. */
    @Column(name = "warn_days_before")
    public Integer warnDaysBefore;

    /** When the {@code secret.expiring} event was emitted for the current expiry; null if not yet. */
    @Column(name = "expiry_warning_sent_on")
    public Instant expiryWarningSentOn;

    /** When the {@code secret.expired} event was emitted for the current expiry; null if not yet. */
    @Column(name = "expired_event_sent_on")
    public Instant expiredEventSentOn;
}
