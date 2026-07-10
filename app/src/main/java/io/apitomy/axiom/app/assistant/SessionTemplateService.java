package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.SessionTemplateEntity;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    };

    @Inject
    ObjectMapper objectMapper;

    private final Map<String, SessionTemplate> builtInTemplates = new LinkedHashMap<>();

    /**
     * A session template definition (immutable value object).
     *
     * @param templateId unique identifier (slug for built-ins, UUID for user-defined)
     * @param name display name
     * @param description brief description
     * @param systemPrompt markdown written to CLAUDE.md
     * @param welcomeMessage first chat message (nullable)
     * @param workingDirectory absolute path or null for auto-created
     * @param mcpServers MCP server names to include
     * @param allowedTools tool patterns and @ToolsetName references for --allowedTools
     * @param builtIn true if loaded from classpath resources
     */
    public record SessionTemplate(
            String templateId,
            String name,
            String description,
            String systemPrompt,
            String welcomeMessage,
            String workingDirectory,
            List<String> mcpServers,
            List<String> allowedTools,
            boolean builtIn) {
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
     * Returns all templates (built-in + user-defined), built-ins first.
     *
     * @return merged list of all templates
     */
    public List<SessionTemplate> listTemplates() {
        List<SessionTemplate> result = new ArrayList<>(builtInTemplates.values());
        List<SessionTemplateEntity> entities = SessionTemplateEntity.listAll();
        for (SessionTemplateEntity entity : entities) {
            result.add(toTemplate(entity));
        }
        return result;
    }

    /**
     * Returns a template by its template ID, checking built-ins first.
     *
     * @param templateId the template identifier
     * @return the template, or null if not found
     */
    public SessionTemplate getTemplate(String templateId) {
        SessionTemplate builtIn = builtInTemplates.get(templateId);
        if (builtIn != null) {
            return builtIn;
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
     */
    @Transactional
    public SessionTemplate createTemplate(SessionTemplate template) {
        SessionTemplateEntity entity = new SessionTemplateEntity();
        entity.templateId = template.templateId() != null && !template.templateId().isBlank()
                ? template.templateId()
                : UUID.randomUUID().toString();
        entity.name = template.name();
        entity.description = template.description();
        entity.systemPrompt = template.systemPrompt();
        entity.welcomeMessage = template.welcomeMessage();
        entity.workingDirectory = template.workingDirectory();
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
        entity.workingDirectory = template.workingDirectory();
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

    private SessionTemplate toTemplate(SessionTemplateEntity entity) {
        return new SessionTemplate(
                entity.templateId,
                entity.name,
                entity.description,
                entity.systemPrompt,
                entity.welcomeMessage,
                entity.workingDirectory,
                List.copyOf(entity.mcpServers),
                List.copyOf(entity.allowedTools),
                false);
    }

    private SessionTemplate parseTemplateJson(JsonNode node, boolean builtIn) {
        return new SessionTemplate(
                node.path("templateId").asText(),
                node.path("name").asText(),
                node.path("description").asText(""),
                node.path("systemPrompt").asText(""),
                node.path("welcomeMessage").asText(null),
                node.path("workingDirectory").asText(null),
                jsonArrayToList(node.path("mcpServers")),
                jsonArrayToList(node.path("allowedTools")),
                builtIn);
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
