package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines a custom script tool that can be provided to AI agents via MCP.
 * The tool is a bash script template with {{param}} placeholders that Axiom
 * wraps into an MCP server automatically.
 */
@Entity
@Table(name = "tool_definition")
public class ToolDefinitionEntity extends PanacheEntity {

    @Column(nullable = false, unique = true)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    /**
     * JSON array of parameter definitions.
     * Each element: {name, type, description, required}
     */
    @Column(columnDefinition = "TEXT")
    public String parameters;

    /**
     * Bash script template with {{param}} placeholders.
     * Supports multi-line scripts.
     */
    @Column(name = "script_template", columnDefinition = "TEXT")
    public String scriptTemplate;

    /**
     * Optional JSON object of environment variables for the tool's script. Values may reference secrets with
     * {@code ${secret:NAME}}. A tool never inherits the environment of the agent that calls it.
     */
    @Column(columnDefinition = "TEXT")
    public String environment;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "tool_label", joinColumns = @JoinColumn(name = "tool_id"))
    @Column(name = "label")
    public List<String> labels = new ArrayList<>();
}
