package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Stores user overrides for a built-in AI Assistant session template. Only the
 * overridable fields are persisted; a null value means "use the built-in default".
 */
@Entity
@Table(name = "session_template_override")
public class SessionTemplateOverrideEntity extends PanacheEntity {

    /** The built-in template identifier these overrides apply to. */
    @Column(name = "template_id", nullable = false, unique = true)
    public String templateId;

    /** Optional AI model override. */
    @Column(name = "model")
    public String model;

    /** Optional interactive engine override. */
    @Column(name = "engine")
    public String engine;

    /** Optional JSON object of environment variables; replaces the built-in default. */
    @Column(columnDefinition = "TEXT")
    public String environment;

    /**
     * Finds the override row for a template.
     *
     * @param templateId the built-in template identifier
     * @return the override entity, or null if none exists
     */
    public static SessionTemplateOverrideEntity findByTemplateId(String templateId) {
        return find("templateId", templateId).firstResult();
    }
}
