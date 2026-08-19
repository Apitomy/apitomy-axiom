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
 * Represents a user-created custom dashboard with configurable widgets.
 */
@Entity
@Table(name = "dashboard")
public class DashboardEntity extends PanacheEntity {

    @Column(nullable = false)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    @Column(name = "is_default")
    public boolean isDefault;

    @Column(name = "tabs", columnDefinition = "TEXT")
    public String tabs;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "updated_on", nullable = false)
    public Instant updatedOn;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "dashboard_label", joinColumns = @JoinColumn(name = "dashboard_id"))
    @Column(name = "label")
    public List<String> labels = new ArrayList<>();
}
