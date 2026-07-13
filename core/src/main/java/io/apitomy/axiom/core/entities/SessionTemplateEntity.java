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
 * Stores a user-defined AI Assistant session template. Built-in templates
 * are loaded from classpath resources and are not stored in this table.
 */
@Entity
@Table(name = "session_template")
public class SessionTemplateEntity extends PanacheEntity {

    /** Unique template identifier (slug, e.g. "my-code-review-template"). */
    @Column(name = "template_id", nullable = false, unique = true)
    public String templateId;

    /** Display name shown in the template picker. */
    @Column(nullable = false)
    public String name;

    /** Brief description of what this template is for. */
    @Column(nullable = false, columnDefinition = "TEXT")
    public String description;

    /** Markdown content written to CLAUDE.md in the session working directory. */
    @Column(name = "system_prompt", nullable = false, columnDefinition = "TEXT")
    public String systemPrompt;

    /** First message shown in the chat UI, attributed to the assistant. */
    @Column(name = "welcome_message", columnDefinition = "TEXT")
    public String welcomeMessage;

    /** Optional absolute path to an existing directory for the session. */
    @Column(name = "working_directory", length = 1024)
    public String workingDirectory;

    /** Optional AI model override (e.g. "claude-sonnet-4-5-20250929"). */
    @Column(name = "model")
    public String model;

    /** Optional init script to run in the working directory on session creation. */
    @Column(name = "init_script", columnDefinition = "TEXT")
    public String initScript;

    /** Script type: "bash" or "node". */
    @Column(name = "init_script_type")
    public String initScriptType;

    /** Names of McpServerEntity records to include in the session's MCP config. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "session_template_mcp_server",
            joinColumns = @JoinColumn(name = "session_template_id"))
    @Column(name = "mcp_server_name")
    public List<String> mcpServers = new ArrayList<>();

    /** Tool patterns and @ToolsetName references for --allowedTools. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "session_template_allowed_tool",
            joinColumns = @JoinColumn(name = "session_template_id"))
    @Column(name = "tool_pattern")
    public List<String> allowedTools = new ArrayList<>();
}
