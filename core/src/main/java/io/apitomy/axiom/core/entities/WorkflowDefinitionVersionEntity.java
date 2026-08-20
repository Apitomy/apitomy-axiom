package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "workflow_definition_version",
       uniqueConstraints = @UniqueConstraint(columnNames = {"definition_id", "version"}))
public class WorkflowDefinitionVersionEntity extends PanacheEntity {

    @Column(name = "definition_id", nullable = false)
    public Long definitionId;

    @Column(nullable = false)
    public int version;

    @Column(columnDefinition = "TEXT", nullable = false)
    public String content;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;
}
