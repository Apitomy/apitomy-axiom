package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Stores mutable system configuration values that can be updated from the UI.
 * This is a single-row table.
 */
@Entity
@Table(name = "system_config")
public class SystemConfigEntity extends PanacheEntity {

    @Column(name = "default_engine", nullable = false)
    public String defaultEngine;
}
