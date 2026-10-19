package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.agents.spi.Agent;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.apitomy.axiom.core.entities.SessionTemplateEntity;
import io.apitomy.axiom.core.entities.SessionTemplateOverrideEntity;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages AI Assistant session templates from two sources: immutable built-in
 * templates loaded from classpath resources, and user-defined templates stored
 * in the database.
 */
@ApplicationScoped
public class SessionTemplateService {

    private static final Logger LOG = Logger.getLogger(SessionTemplateService.class);
    private static final String TEMPLATES_RESOURCE_DIR = "templates/assistant-templates/";
    private static final String[] BUILT_IN_FILES = {
            "axiom-config-assistant.json",
            "general-assistant.json",
            "subject-assistant.json",
            "axiom-workbench.json",
    };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AgentRegistry agentRegistry;

    private final Map<String, SessionTemplate> builtInTemplates = new LinkedHashMap<>();

    /**
     * A session template definition (immutable value object).
     *
     * @param templateId unique identifier (slug for built-ins, UUID for user-defined)
     * @param name display name
     * @param description brief description
     * @param systemPrompt markdown passed via --append-system-prompt
     * @param welcomeMessage first chat message (nullable)
     * @param initialMessage message automatically sent to the AI on session start (nullable)
     * @param workingDirectory absolute path or null for auto-created
     * @param model AI model override, or null for default
     * @param engine interactive engine override, or null for global default
     * @param initScript optional init script content
     * @param initScriptType script type: "bash" or "node"
     * @param environment JSON object of environment variables (nullable)
     * @param mcpServers MCP server names to include
     * @param allowedTools tool patterns and @ToolsetName references for --allowedTools
     * @param builtIn true if loaded from classpath resources
     * @param capabilities behaviours this template enables (empty for user-defined templates)
     * @param overriddenFields names of fields overridden by the user (built-ins only)
     */
    public record SessionTemplate(
            String templateId,
            String name,
            String description,
            String systemPrompt,
            String welcomeMessage,
            String initialMessage,
            String workingDirectory,
            String model,
            String engine,
            String initScript,
            String initScriptType,
            String environment,
            List<String> mcpServers,
            List<String> allowedTools,
            boolean builtIn,
            Set<SessionCapability> capabilities,
            Set<String> overriddenFields) {
    }

    /**
     * User overrides for a built-in template. A null field means "use the built-in default".
     *
     * @param model AI model override, or null
     * @param engine interactive engine override, or null
     * @param environment JSON object string of environment variables, or null
     */
    public record TemplateOverrides(String model, String engine, String environment) {
    }

    /**
     * Thrown when a template identifier does not refer to any known template.
     */
    public static class TemplateNotFoundException extends RuntimeException {

        /**
         * Creates the exception.
         *
         * @param templateId the unknown template identifier
         */
        public TemplateNotFoundException(String templateId) {
            super("Template not found: " + templateId);
        }
    }

    /**
     * Loads built-in templates from classpath resources at application startup.
     *
     * @param event the startup event
     */
    void onStartup(@Observes StartupEvent event) {
        for (String fileName : BUILT_IN_FILES) {
            try (InputStream is = getClass().getClassLoader()
                    .getResourceAsStream(TEMPLATES_RESOURCE_DIR + fileName)) {
                if (is == null) {
                    LOG.warnf("Built-in template resource not found: %s", fileName);
                    continue;
                }
                JsonNode node = objectMapper.readTree(is);
                SessionTemplate template = parseTemplateJson(node, true);
                builtInTemplates.put(template.templateId(), template);
                LOG.infof("Loaded built-in template: %s", template.templateId());
            } catch (IOException e) {
                LOG.errorf(e, "Failed to load built-in template: %s", fileName);
            }
        }
    }

    /**
     * Returns all templates (built-in + user-defined), built-ins first. Built-in templates are returned
     * with user overrides applied.
     *
     * @return merged list of all templates
     */
    public List<SessionTemplate> listTemplates() {
        Map<String, SessionTemplateOverrideEntity> overrides =
                SessionTemplateOverrideEntity.<SessionTemplateOverrideEntity>listAll().stream()
                        .collect(Collectors.toMap(o -> o.templateId, o -> o));
        List<SessionTemplate> result = new ArrayList<>();
        for (SessionTemplate builtIn : builtInTemplates.values()) {
            result.add(applyOverrides(builtIn, overrides.get(builtIn.templateId())));
        }
        List<SessionTemplateEntity> entities = SessionTemplateEntity.listAll();
        for (SessionTemplateEntity entity : entities) {
            result.add(toTemplate(entity));
        }
        return result;
    }

    /**
     * Returns a template by its template ID, checking built-ins first. Built-in templates are returned
     * with user overrides applied.
     *
     * @param templateId the template identifier
     * @return the template, or null if not found
     */
    public SessionTemplate getTemplate(String templateId) {
        SessionTemplate builtIn = builtInTemplates.get(templateId);
        if (builtIn != null) {
            return applyOverrides(builtIn, SessionTemplateOverrideEntity.findByTemplateId(templateId));
        }
        SessionTemplateEntity entity = SessionTemplateEntity
                .find("templateId", templateId).firstResult();
        return entity != null ? toTemplate(entity) : null;
    }

    /**
     * Returns whether the given template ID refers to a built-in template.
     *
     * @param templateId the template identifier
     * @return true if this is a built-in template
     */
    public boolean isBuiltIn(String templateId) {
        return builtInTemplates.containsKey(templateId);
    }

    /**
     * Creates a new user-defined template.
     *
     * @param template the template data (templateId is generated if not provided)
     * @return the persisted template
     * @throws IllegalArgumentException if the templateId collides with a built-in template
     */
    @Transactional
    public SessionTemplate createTemplate(SessionTemplate template) {
        SessionTemplateEntity entity = new SessionTemplateEntity();
        entity.templateId = template.templateId() != null && !template.templateId().isBlank()
                ? template.templateId()
                : UUID.randomUUID().toString();
        if (isBuiltIn(entity.templateId)) {
            throw new IllegalArgumentException(
                    "Template ID conflicts with a built-in template: " + entity.templateId);
        }
        entity.name = template.name();
        entity.description = template.description();
        entity.systemPrompt = template.systemPrompt();
        entity.welcomeMessage = template.welcomeMessage();
        entity.initialMessage = template.initialMessage();
        entity.workingDirectory = template.workingDirectory();
        entity.model = template.model();
        entity.engine = template.engine();
        entity.initScript = template.initScript();
        entity.initScriptType = template.initScriptType();
        entity.environment = template.environment();
        entity.mcpServers = new ArrayList<>(template.mcpServers() != null
                ? template.mcpServers() : List.of());
        entity.allowedTools = new ArrayList<>(template.allowedTools() != null
                ? template.allowedTools() : List.of());
        entity.persist();
        return toTemplate(entity);
    }

    /**
     * Updates a user-defined template. Throws if the template is built-in.
     *
     * @param templateId the template identifier
     * @param template the updated template data
     * @return the updated template
     * @throws IllegalArgumentException if the template is not found
     * @throws IllegalStateException if the template is built-in
     */
    @Transactional
    public SessionTemplate updateTemplate(String templateId, SessionTemplate template) {
        if (isBuiltIn(templateId)) {
            throw new IllegalStateException("Cannot modify built-in template: " + templateId);
        }
        SessionTemplateEntity entity = SessionTemplateEntity
                .find("templateId", templateId).firstResult();
        if (entity == null) {
            throw new IllegalArgumentException("Template not found: " + templateId);
        }
        entity.name = template.name();
        entity.description = template.description();
        entity.systemPrompt = template.systemPrompt();
        entity.welcomeMessage = template.welcomeMessage();
        entity.initialMessage = template.initialMessage();
        entity.workingDirectory = template.workingDirectory();
        entity.model = template.model();
        entity.engine = template.engine();
        entity.initScript = template.initScript();
        entity.initScriptType = template.initScriptType();
        entity.environment = template.environment();
        entity.mcpServers = new ArrayList<>(template.mcpServers() != null
                ? template.mcpServers() : List.of());
        entity.allowedTools = new ArrayList<>(template.allowedTools() != null
                ? template.allowedTools() : List.of());
        entity.persist();
        return toTemplate(entity);
    }

    /**
     * Deletes a user-defined template. Throws if the template is built-in.
     *
     * @param templateId the template identifier
     * @throws IllegalStateException if the template is built-in
     */
    @Transactional
    public void deleteTemplate(String templateId) {
        if (isBuiltIn(templateId)) {
            throw new IllegalStateException("Cannot delete built-in template: " + templateId);
        }
        SessionTemplateEntity entity = SessionTemplateEntity
                .find("templateId", templateId).firstResult();
        if (entity != null) {
            entity.delete();
        }
    }

    /**
     * Returns the stored overrides for a built-in template.
     *
     * @param templateId the built-in template identifier
     * @return the overrides; all fields null when none are stored
     * @throws TemplateNotFoundException if the template does not exist
     * @throws IllegalArgumentException if the template is not built-in
     */
    public TemplateOverrides getOverrides(String templateId) {
        requireBuiltIn(templateId);
        SessionTemplateOverrideEntity entity = SessionTemplateOverrideEntity.findByTemplateId(templateId);
        if (entity == null) {
            return new TemplateOverrides(null, null, null);
        }
        return new TemplateOverrides(entity.model, entity.engine, entity.environment);
    }

    /**
     * Replaces the overrides for a built-in template. Blank values are treated as null; when
     * every field is null the stored overrides are removed.
     *
     * @param templateId the built-in template identifier
     * @param overrides the new overrides
     * @return the built-in template with overrides applied
     * @throws TemplateNotFoundException if the template does not exist
     * @throws IllegalArgumentException if the template is not built-in, or the engine is not a
     *         registered interactive engine
     */
    @Transactional
    public SessionTemplate setOverrides(String templateId, TemplateOverrides overrides) {
        requireBuiltIn(templateId);
        String model = blankToNull(overrides.model());
        String engine = blankToNull(overrides.engine());
        validateInteractiveEngine(engine);
        String environment = normalizeEnvironment(overrides.environment());
        SessionTemplateOverrideEntity entity = SessionTemplateOverrideEntity.findByTemplateId(templateId);
        if (model == null && engine == null && environment == null) {
            if (entity != null) {
                entity.delete();
            }
            return builtInTemplates.get(templateId);
        }
        if (entity == null) {
            entity = new SessionTemplateOverrideEntity();
            entity.templateId = templateId;
        }
        entity.model = model;
        entity.engine = engine;
        entity.environment = environment;
        entity.persist();
        return applyOverrides(builtInTemplates.get(templateId), entity);
    }

    /**
     * Removes all overrides for a built-in template, restoring its defaults.
     *
     * @param templateId the built-in template identifier
     * @throws TemplateNotFoundException if the template does not exist
     * @throws IllegalArgumentException if the template is not built-in
     */
    @Transactional
    public void clearOverrides(String templateId) {
        requireBuiltIn(templateId);
        SessionTemplateOverrideEntity.delete("templateId", templateId);
    }

    private void validateInteractiveEngine(String engine) {
        if (engine == null) {
            return;
        }
        boolean known = agentRegistry.getAllAgents().stream()
                .filter(Agent::supportsInteractiveSessions)
                .anyMatch(agent -> engine.equals(agent.getType()));
        if (!known) {
            throw new IllegalArgumentException("Unknown interactive engine type: " + engine);
        }
    }

    private void requireBuiltIn(String templateId) {
        if (isBuiltIn(templateId)) {
            return;
        }
        if (SessionTemplateEntity.find("templateId", templateId).firstResult() != null) {
            throw new IllegalArgumentException(
                    "Overrides are only supported for built-in templates: " + templateId);
        }
        throw new TemplateNotFoundException(templateId);
    }

    private SessionTemplate applyOverrides(SessionTemplate base, SessionTemplateOverrideEntity o) {
        if (o == null) {
            return base;
        }
        Set<String> overridden = new LinkedHashSet<>();
        String model = base.model();
        String engine = base.engine();
        String environment = base.environment();
        if (o.model != null) {
            model = o.model;
            overridden.add("model");
        }
        if (o.engine != null) {
            engine = o.engine;
            overridden.add("engine");
        }
        if (o.environment != null) {
            environment = o.environment;
            overridden.add("environment");
        }
        return new SessionTemplate(base.templateId(), base.name(), base.description(), base.systemPrompt(),
                base.welcomeMessage(), base.initialMessage(), base.workingDirectory(), model, engine,
                base.initScript(), base.initScriptType(), environment, base.mcpServers(),
                base.allowedTools(), base.builtIn(), base.capabilities(), Set.copyOf(overridden));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeEnvironment(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isObject() && node.size() > 0 ? node.toString() : null;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid environment JSON", e);
        }
    }

    private SessionTemplate toTemplate(SessionTemplateEntity entity) {
        return new SessionTemplate(
                entity.templateId,
                entity.name,
                entity.description,
                entity.systemPrompt,
                entity.welcomeMessage,
                entity.initialMessage,
                entity.workingDirectory,
                entity.model,
                entity.engine,
                entity.initScript,
                entity.initScriptType,
                entity.environment,
                List.copyOf(entity.mcpServers),
                List.copyOf(entity.allowedTools),
                false,
                Set.of(),
                Set.of());
    }

    private SessionTemplate parseTemplateJson(JsonNode node, boolean builtIn) {
        JsonNode envNode = node.path("environment");
        String environment = envNode.isObject() ? envNode.toString() : null;
        return new SessionTemplate(
                node.path("templateId").asText(),
                node.path("name").asText(),
                node.path("description").asText(""),
                node.path("systemPrompt").asText(""),
                node.path("welcomeMessage").asText(null),
                node.path("initialMessage").asText(null),
                node.path("workingDirectory").asText(null),
                node.path("model").asText(null),
                node.path("engine").asText(null),
                node.path("initScript").asText(null),
                node.path("initScriptType").asText(null),
                environment,
                jsonArrayToList(node.path("mcpServers")),
                jsonArrayToList(node.path("allowedTools")),
                builtIn,
                SessionCapability.parse(node.path("capabilities")),
                Set.of());
    }

    private List<String> jsonArrayToList(JsonNode arrayNode) {
        List<String> result = new ArrayList<>();
        if (arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                result.add(item.asText());
            }
        }
        return result;
    }
}
