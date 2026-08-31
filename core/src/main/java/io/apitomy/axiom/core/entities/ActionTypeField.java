package io.apitomy.axiom.core.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * A single typed input or output field declared by an action type for use as a
 * Flow workflow action node. Persisted as a row in an element-collection table.
 */
@Embeddable
public class ActionTypeField {

    @Column(name = "name", nullable = false)
    public String name;

    /** One of: string, number, boolean, object. */
    @Column(name = "type", nullable = false)
    public String type;

    @Column(name = "required", nullable = false)
    public boolean required;

    @Column(name = "description", columnDefinition = "TEXT")
    public String description;

    /** Required by JPA. */
    public ActionTypeField() {
    }

    /**
     * Creates a fully-populated field.
     *
     * @param name        the field name
     * @param type        the declared type (string/number/boolean/object)
     * @param required    whether the field is required
     * @param description an optional human description
     */
    public ActionTypeField(String name, String type, boolean required, String description) {
        this.name = name;
        this.type = type;
        this.required = required;
        this.description = description;
    }
}
