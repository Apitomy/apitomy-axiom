package io.apitomy.axiom.app.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.NewMcpServer;
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.apitomy.axiom.core.entities.SecretEntity;
import io.apitomy.axiom.core.services.MissingSecretException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
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

    @Inject
    McpServerResolver resolver;

    @Inject
    McpConnectionTester tester;

    /** Time limit for a connection test when the server has no timeout (allows for npx/uvx downloads). */
    private static final Duration DEFAULT_TEST_TIMEOUT = Duration.ofSeconds(60);

    /** Upper bound for a connection test. */
    private static final Duration MAX_TEST_TIMEOUT = Duration.ofSeconds(120);

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
        validateDefinition(data);
        findByName(data.getName())
                .filter(other -> existingId == null || !other.id.equals(existingId))
                .ifPresent(other -> {
                    throw new McpServerConflictException(
                            "An MCP server named '" + data.getName() + "' already exists");
                });
    }

    /**
     * Validates a definition on its own (no name-conflict check).
     *
     * @param data the definition
     * @throws McpServerValidationException if the definition is invalid
     */
    public static void validateDefinition(NewMcpServer data) {
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
    }

    /**
     * Tests a saved server and, on success, stores the tools it reports.
     *
     * @param id the server id
     * @return the test result
     */
    public McpConnectionTester.Result test(long id) {
        Prepared prepared = QuarkusTransaction.requiringNew().call(() -> prepare(get(id)));
        McpConnectionTester.Result result = run(prepared);
        if (result.success()) {
            QuarkusTransaction.requiringNew().run(() -> {
                McpServerEntity entity = McpServerEntity.findById(id);
                if (entity != null) {
                    entity.discoveredTools = toJson(result.tools());
                    entity.toolsDiscoveredOn = Instant.now();
                }
            });
        }
        return result;
    }

    /**
     * Tests an unsaved definition. Nothing is stored.
     *
     * @param data the definition
     * @return the test result
     */
    public McpConnectionTester.Result test(NewMcpServer data) {
        validateDefinition(data);
        McpServerEntity transientEntity = new McpServerEntity();
        applyFields(transientEntity, data);
        Prepared prepared = QuarkusTransaction.requiringNew().call(() -> prepare(transientEntity));
        return run(prepared);
    }

    /**
     * Reads the tools stored by the last successful test.
     *
     * @param entity the server
     * @return the tools (empty if never tested)
     */
    public List<McpConnectionTester.ToolInfo> discoveredTools(McpServerEntity entity) {
        if (entity.discoveredTools == null || entity.discoveredTools.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(entity.discoveredTools,
                    new com.fasterxml.jackson.core.type.TypeReference<List<McpConnectionTester.ToolInfo>>() { });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** A resolved definition ready to test, or the reason it could not be resolved. */
    private record Prepared(McpServerDefinition definition, String error) {
    }

    private Prepared prepare(McpServerEntity entity) {
        boolean wasEnabled = entity.enabled;
        try {
            // Disabled servers can still be tested.
            entity.enabled = true;
            return resolver.toDefinition(entity)
                    .map(def -> new Prepared(def, null))
                    .orElseGet(() -> new Prepared(null, "The server configuration is invalid (see the server log)"));
        } catch (MissingSecretException e) {
            return new Prepared(null, e.getMessage());
        } finally {
            entity.enabled = wasEnabled;
        }
    }

    private McpConnectionTester.Result run(Prepared prepared) {
        if (prepared.definition() == null) {
            return new McpConnectionTester.Result(false, null, null, null, List.of(), prepared.error(), "", 0);
        }
        Integer timeoutMs = prepared.definition().timeoutMs();
        Duration timeout = timeoutMs != null
                ? Duration.ofMillis(Math.min(timeoutMs, MAX_TEST_TIMEOUT.toMillis()))
                : DEFAULT_TEST_TIMEOUT;
        return tester.test(prepared.definition(), timeout);
    }

    /**
     * Returns the explicit transport, or infers it from the populated fields.
     *
     * @param data the definition
     * @return {@code stdio}, {@code http} or {@code sse}
     */
    static String effectiveTransport(NewMcpServer data) {
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
