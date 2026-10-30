package io.apitomy.axiom.app.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.apitomy.axiom.core.services.EnvironmentResolver;
import io.apitomy.axiom.core.services.MissingSecretException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single place that turns user-defined MCP servers ({@link McpServerEntity}) into resolved
 * {@link McpServerDefinition}s. Used by both the task execution path and assistant sessions.
 */
@ApplicationScoped
public class McpServerResolver {

    private static final Logger LOG = Logger.getLogger(McpServerResolver.class);
    private static final String TOOL_PREFIX = "mcp__";

    private final ObjectMapper objectMapper;
    private final EnvironmentResolver environmentResolver;

    /**
     * CDI constructor.
     *
     * @param objectMapper the object mapper
     * @param environmentResolver resolves {@code ${secret:NAME}} references in env values and headers
     */
    @Inject
    public McpServerResolver(ObjectMapper objectMapper, EnvironmentResolver environmentResolver) {
        this.objectMapper = objectMapper;
        this.environmentResolver = environmentResolver;
    }

    /**
     * Resolves the named user-defined servers. Unknown or invalid servers are logged and omitted.
     *
     * @param names server names, in order
     * @return name to definition, preserving order
     */
    public Map<String, McpServerDefinition> resolveByName(Collection<String> names) {
        Map<String, McpServerDefinition> result = new LinkedHashMap<>();
        for (String name : names) {
            McpServerEntity entity = McpServerEntity.find("name", name).firstResult();
            if (entity == null) {
                LOG.warnf("MCP server not found: %s", name);
                continue;
            }
            toDefinition(entity).ifPresent(def -> result.put(name, def));
        }
        return result;
    }

    /**
     * Resolves the user-defined servers referenced by an allowed-tools list ({@code mcp__<name>__...}).
     * Servers are never included implicitly: a null or empty list selects none.
     *
     * @param allowedTools the allowed tools
     * @return name to definition, sorted by name
     */
    public Map<String, McpServerDefinition> resolveForAllowedTools(List<String> allowedTools) {
        List<McpServerEntity> all = McpServerEntity.listAll();
        Map<String, McpServerDefinition> result = new LinkedHashMap<>();
        selectByAllowedTools(all, allowedTools).stream()
                .sorted((a, b) -> a.name.compareTo(b.name))
                .forEach(e -> toDefinition(e).ifPresent(def -> result.put(e.name, def)));
        return result;
    }

    /**
     * Selects servers whose {@code mcp__<name>__} prefix appears in the allowed tools. A null or empty
     * list selects none.
     *
     * @param servers candidate servers
     * @param allowedTools the allowed tools
     * @return the selected servers
     */
    public static List<McpServerEntity> selectByAllowedTools(List<McpServerEntity> servers,
                                                             List<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return List.of();
        }
        return servers.stream()
                .filter(s -> allowedTools.stream().anyMatch(t -> t.startsWith(TOOL_PREFIX + s.name + "__")))
                .toList();
    }

    /**
     * Extracts the server name from a tool name of the form {@code mcp__<server>__<tool>}.
     *
     * @param toolName the tool name
     * @return the server name, if the tool name has that form
     */
    public static Optional<String> serverNameOf(String toolName) {
        if (toolName == null || !toolName.startsWith(TOOL_PREFIX)) {
            return Optional.empty();
        }
        int sep = toolName.indexOf("__", TOOL_PREFIX.length());
        if (sep <= TOOL_PREFIX.length()) {
            return Optional.empty();
        }
        return Optional.of(toolName.substring(TOOL_PREFIX.length(), sep));
    }

    /**
     * Converts an entity to a definition, resolving {@code ${secret:NAME}} references in env values and
     * headers. Returns empty (and logs a warning) if the entity is disabled, has no transport, or has
     * malformed args/env/headers.
     *
     * @param entity the entity
     * @return the definition, if valid
     * @throws MissingSecretException if a referenced secret does not exist or cannot be decrypted
     */
    public Optional<McpServerDefinition> toDefinition(McpServerEntity entity) {
        if (!entity.enabled) {
            LOG.debugf("MCP server '%s' is disabled; skipping", entity.name);
            return Optional.empty();
        }
        try {
            if (entity.serverUrl != null && !entity.serverUrl.isBlank()) {
                String type = "sse".equals(entity.transport) ? "sse" : "http";
                return Optional.of(McpServerDefinition.remote(type, entity.serverUrl.trim(),
                        resolveMap(entity, entity.headers), entity.timeoutMs));
            }
            if (entity.serverCommand == null || entity.serverCommand.isBlank()) {
                LOG.warnf("MCP server '%s' has neither a URL nor a command; skipping", entity.name);
                return Optional.empty();
            }
            List<String> args = entity.serverArgs == null || entity.serverArgs.isBlank()
                    ? List.of()
                    : objectMapper.readValue(entity.serverArgs, new TypeReference<List<String>>() { });
            return Optional.of(new McpServerDefinition(entity.serverCommand.trim(), args,
                    resolveMap(entity, entity.serverEnv), null, null, Map.of(), entity.timeoutMs));
        } catch (MissingSecretException e) {
            throw e;
        } catch (Exception e) {
            LOG.warnf("MCP server '%s' has malformed configuration; skipping: %s", entity.name, e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, String> resolveMap(McpServerEntity entity, String json) throws java.io.IOException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        // Validate the shape first so malformed JSON is reported as such.
        LinkedHashMap<String, String> raw = objectMapper.readValue(json,
                new TypeReference<LinkedHashMap<String, String>>() { });
        try {
            Map<String, String> resolved = environmentResolver.resolve(json);
            Map<String, String> ordered = new LinkedHashMap<>();
            raw.keySet().forEach(k -> ordered.put(k, resolved.get(k)));
            return ordered;
        } catch (MissingSecretException e) {
            throw new MissingSecretException("MCP server '" + entity.name + "': " + e.getMessage());
        }
    }
}
