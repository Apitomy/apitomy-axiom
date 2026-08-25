package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

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
}
