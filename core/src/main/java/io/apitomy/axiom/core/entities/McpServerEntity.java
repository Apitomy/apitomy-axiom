package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Defines an external MCP server that provides tools to AI agents.
 * Supports stdio transport (command + args) or remote transports (HTTP / SSE URL).
 */
@Entity
@Table(name = "mcp_server")
public class McpServerEntity extends PanacheEntity {

    @Column(nullable = false, unique = true)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String description;

    /**
     * Command to launch the MCP server (stdio transport).
     */
    @Column(name = "server_command")
    public String serverCommand;

    /**
     * Arguments for the server command (JSON array).
     */
    @Column(name = "server_args", columnDefinition = "TEXT")
    public String serverArgs;

    /**
     * Environment variables for the server (JSON object).
     */
    @Column(name = "server_env", columnDefinition = "TEXT")
    public String serverEnv;

    /**
     * HTTP/SSE URL (alternative to stdio transport).
     */
    @Column(name = "server_url")
    public String serverUrl;

    /**
     * Transport type: {@code stdio}, {@code http} or {@code sse}.
     */
    @Column(name = "transport", nullable = false, length = 16)
    public String transport = "stdio";

    /**
     * HTTP headers for remote transports (JSON object).
     */
    @Column(name = "headers", columnDefinition = "TEXT")
    public String headers;

    /**
     * Connection/request timeout in milliseconds, or {@code null} for the engine default.
     */
    @Column(name = "timeout_ms")
    public Integer timeoutMs;

    /**
     * Whether the server is included at runtime.
     */
    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "modified_on", nullable = false)
    public Instant modifiedOn;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdOn == null) {
            createdOn = now;
        }
        modifiedOn = now;
    }

    @PreUpdate
    void onUpdate() {
        modifiedOn = Instant.now();
    }
}
