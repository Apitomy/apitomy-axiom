package io.apitomy.axiom.app.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.NewMcpServer;
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
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

    /** stdio transport. */
    public static final String TRANSPORT_STDIO = "stdio";

    /** Streamable HTTP transport. */
    public static final String TRANSPORT_HTTP = "http";

    /** Server-sent events transport. */
    public static final String TRANSPORT_SSE = "sse";

    private static final Pattern SECRET_REF = Pattern.compile("\\$\\{secret:([^}]+)}");

    @Inject
    ObjectMapper objectMapper;

    @Inject
    McpServerReferences references;

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
        String oldName = entity.name;
        applyFields(entity, data);
        if (!oldName.equals(entity.name)) {
            references.rename(oldName, entity.name);
        }
        return entity;
    }

    /**
     * Lists the items that reference a server.
     *
     * @param id the server id
     * @return the references
     */
    public List<McpServerReferences.Usage> usages(long id) {
        return references.usagesOf(get(id).name);
    }

    /**
     * Deletes an MCP server that nothing references.
     *
     * @param id the server id
     * @throws McpServerInUseException if the server is referenced
     */
    @Transactional
    public void delete(long id) {
        McpServerEntity entity = get(id);
        List<McpServerReferences.Usage> usages = references.usagesOf(entity.name);
        if (!usages.isEmpty()) {
            throw new McpServerInUseException(entity.name, usages);
        }
        entity.delete();
    }

    /**
     * Returns non-blocking warnings for a server, e.g. references to secrets that do not exist.
     *
     * @param entity the server
     * @return warning messages (empty if none)
     */
    public List<String> warnings(McpServerEntity entity) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        collectSecretNames(entity.serverEnv, names);
        collectSecretNames(entity.headers, names);
        return names.stream()
                .filter(name -> SecretEntity.count("name", name) == 0)
                .map(name -> "Secret '" + name + "' does not exist")
                .toList();
    }

    private void collectSecretNames(String json, java.util.Set<String> names) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            java.util.Map<String, String> map = objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() { });
            map.values().stream().filter(java.util.Objects::nonNull).forEach(value -> {
                java.util.regex.Matcher m = SECRET_REF.matcher(value);
                while (m.find()) {
                    names.add(m.group(1));
                }
            });
        } catch (JsonProcessingException e) {
            // Malformed JSON is reported elsewhere; no secret warnings.
        }
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
        String transport = effectiveTransport(data);
        if (TRANSPORT_STDIO.equals(transport) && !hasCommand) {
            throw new McpServerValidationException("The stdio transport requires a server command");
        }
        if (!TRANSPORT_STDIO.equals(transport) && !hasUrl) {
            throw new McpServerValidationException("The " + transport + " transport requires a server URL");
        }
        if (hasUrl) {
            validateUrl(data.getServerUrl());
        }
        if (TRANSPORT_STDIO.equals(transport) && data.getHeaders() != null
                && !data.getHeaders().getAdditionalProperties().isEmpty()) {
            throw new McpServerValidationException("Headers are only supported for http and sse transports");
        }
        if (data.getTimeout() != null && data.getTimeout() <= 0) {
            throw new McpServerValidationException("Timeout must be a positive number of milliseconds");
        }
        findByName(data.getName())
                .filter(other -> existingId == null || !other.id.equals(existingId))
                .ifPresent(other -> {
                    throw new McpServerConflictException(
                            "An MCP server named '" + data.getName() + "' already exists");
                });
    }

    /**
     * Returns the explicit transport, or infers it from the populated fields.
     *
     * @param data the definition
     * @return {@code stdio}, {@code http} or {@code sse}
     */
    private static String effectiveTransport(NewMcpServer data) {
        if (data.getTransport() != null) {
            return data.getTransport().value();
        }
        return notBlank(data.getServerUrl()) ? TRANSPORT_HTTP : TRANSPORT_STDIO;
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
        entity.transport = effectiveTransport(data);
        entity.headers = data.getHeaders() == null || data.getHeaders().getAdditionalProperties().isEmpty()
                ? null : toJson(data.getHeaders());
        entity.timeoutMs = data.getTimeout();
        entity.enabled = data.getEnabled() == null || data.getEnabled();
        // Full-replace semantics: absent/empty args or env clear the stored value.
        entity.serverArgs = data.getServerArgs() == null || data.getServerArgs().isEmpty()
                ? null : toJson(data.getServerArgs());
        entity.serverEnv = data.getServerEnv() == null
                || data.getServerEnv().getAdditionalProperties().isEmpty()
                ? null : toJson(data.getServerEnv());
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
