package io.apitomy.axiom.app.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.NewMcpServer;
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Manages external MCP server definitions: CRUD plus validation.
 *
 * <p>All writes to {@link McpServerEntity} should go through this service so that validation rules
 * are applied consistently (REST, configuration packs, assistants).</p>
 */
@ApplicationScoped
public class McpServerService {

    /** Maximum length of an MCP server name. */
    public static final int MAX_NAME_LENGTH = 64;

    /**
     * Allowed name characters. The name is used in {@code mcp__<name>__<tool>} prefixes, so it must
     * start and end with an alphanumeric character and must not contain {@code __}.
     */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9_-]*[A-Za-z0-9])?$");

    @Inject
    ObjectMapper objectMapper;

    /**
     * Lists all MCP servers sorted by name.
     *
     * @return the servers
     */
    public List<McpServerEntity> list() {
        return McpServerEntity.listAll(Sort.ascending("name"));
    }

    /**
     * Gets an MCP server by id.
     *
     * @param id the server id
     * @return the server
     * @throws McpServerNotFoundException if no server exists with the id
     */
    public McpServerEntity get(long id) {
        McpServerEntity entity = McpServerEntity.findById(id);
        if (entity == null) {
            throw new McpServerNotFoundException(id);
        }
        return entity;
    }

    /**
     * Finds an MCP server by name.
     *
     * @param name the server name
     * @return the server, if present
     */
    public Optional<McpServerEntity> findByName(String name) {
        return McpServerEntity.find("name", name).firstResultOptional();
    }

    /**
     * Validates and creates a new MCP server.
     *
     * @param data the server definition
     * @return the persisted entity
     */
    @Transactional
    public McpServerEntity create(NewMcpServer data) {
        validate(data, null);
        McpServerEntity entity = new McpServerEntity();
        applyFields(entity, data);
        entity.persist();
        return entity;
    }

    /**
     * Validates and updates an existing MCP server.
     *
     * @param id the server id
     * @param data the new server definition
     * @return the updated entity
     */
    @Transactional
    public McpServerEntity update(long id, NewMcpServer data) {
        McpServerEntity entity = get(id);
        validate(data, id);
        applyFields(entity, data);
        return entity;
    }

    /**
     * Deletes an MCP server.
     *
     * @param id the server id
     */
    @Transactional
    public void delete(long id) {
        get(id).delete();
    }

    /**
     * Validates an MCP server name.
     *
     * @param name the name
     * @throws McpServerValidationException if the name is invalid
     */
    public static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new McpServerValidationException("MCP server name is required");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new McpServerValidationException(
                    "MCP server name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (!NAME_PATTERN.matcher(name).matches() || name.contains("__")) {
            throw new McpServerValidationException("Invalid MCP server name '" + name
                    + "': use letters, digits, '-' and '_', start and end with a letter or digit, "
                    + "and do not use '__'");
        }
    }

    /**
     * Validates a server definition.
     *
     * @param data the definition
     * @param existingId the id of the server being updated, or {@code null} on create
     */
    private void validate(NewMcpServer data, Long existingId) {
        validateName(data.getName());
        boolean hasCommand = notBlank(data.getServerCommand());
        boolean hasUrl = notBlank(data.getServerUrl());
        if (hasCommand == hasUrl) {
            throw new McpServerValidationException(
                    "Specify either a server command (stdio) or a server URL (HTTP), but not both");
        }
        if (hasUrl) {
            validateUrl(data.getServerUrl());
        }
        findByName(data.getName())
                .filter(other -> existingId == null || !other.id.equals(existingId))
                .ifPresent(other -> {
                    throw new McpServerConflictException(
                            "An MCP server named '" + data.getName() + "' already exists");
                });
    }

    private static void validateUrl(String url) {
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new McpServerValidationException(
                        "Server URL must be an absolute http:// or https:// URL: " + url);
            }
        } catch (URISyntaxException e) {
            throw new McpServerValidationException("Server URL is not a valid URL: " + url);
        }
    }

    private void applyFields(McpServerEntity entity, NewMcpServer data) {
        entity.name = data.getName();
        entity.description = data.getDescription();
        entity.serverCommand = trimToNull(data.getServerCommand());
        entity.serverUrl = trimToNull(data.getServerUrl());
        if (data.getServerArgs() != null) {
            entity.serverArgs = toJson(data.getServerArgs());
        }
        if (data.getServerEnv() != null) {
            entity.serverEnv = toJson(data.getServerEnv());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize MCP server field", e);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimToNull(String s) {
        return notBlank(s) ? s.trim() : null;
    }
}
