package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.api.beans.ImportResult;
import io.apitomy.axiom.api.beans.PackExportRequest;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.apitomy.axiom.core.entities.ReportDefinitionEntity;
import io.apitomy.axiom.core.entities.ScheduledJobEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.entities.SessionTemplateEntity;
import io.apitomy.axiom.core.entities.ToolsetEntity;
import io.apitomy.axiom.app.assistant.SessionTemplateService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for exporting and importing configuration packs.
 * Packs bundle related configuration items (action types, tools, toolsets,
 * MCP servers, report definitions) into a portable JSON format.
 */
@ApplicationScoped
public class ImportExportService {

    private static final Logger LOG = Logger.getLogger(ImportExportService.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SessionTemplateService sessionTemplateService;

    /**
     * Exports a configuration pack containing the selected items.
     *
     * @param request the items to include in the pack
     * @return the pack as a JSON node
     */
    public JsonNode exportPack(PackExportRequest request) {
        ObjectNode pack = objectMapper.createObjectNode();

        ObjectNode metadata = pack.putObject("metadata");
        metadata.put("name", request.getName());
        if (request.getDescription() != null) {
            metadata.put("description", request.getDescription());
        }
        metadata.put("version", "2.0");
        metadata.put("exportedAt", Instant.now().toString());

        if (request.getToolIds() != null && !request.getToolIds().isEmpty()) {
            ArrayNode arr = pack.putArray("tools");
            for (Number id : request.getToolIds()) {
                ToolDefinitionEntity entity = ToolDefinitionEntity.findById(id.longValue());
                if (entity != null) arr.add(serializeTool(entity));
            }
        }

        if (request.getToolsetIds() != null && !request.getToolsetIds().isEmpty()) {
            ArrayNode arr = pack.putArray("toolsets");
            for (Number id : request.getToolsetIds()) {
                ToolsetEntity entity = ToolsetEntity.findById(id.longValue());
                if (entity != null) arr.add(serializeToolset(entity));
            }
        }

        if (request.getMcpServerIds() != null && !request.getMcpServerIds().isEmpty()) {
            ArrayNode arr = pack.putArray("mcpServers");
            for (Number id : request.getMcpServerIds()) {
                McpServerEntity entity = McpServerEntity.findById(id.longValue());
                if (entity != null) arr.add(serializeMcpServer(entity));
            }
        }

        if (request.getActionTypeIds() != null && !request.getActionTypeIds().isEmpty()) {
            ArrayNode arr = pack.putArray("actionTypes");
            for (Number id : request.getActionTypeIds()) {
                ActionTypeEntity entity = ActionTypeEntity.findById(id.longValue());
                if (entity != null) arr.add(serializeActionType(entity));
            }
        }

        if (request.getReportDefinitionIds() != null && !request.getReportDefinitionIds().isEmpty()) {
            ArrayNode arr = pack.putArray("reportDefinitions");
            for (Number id : request.getReportDefinitionIds()) {
                ReportDefinitionEntity entity = ReportDefinitionEntity.findById(id.longValue());
                if (entity != null) arr.add(serializeReportDefinition(entity));
            }
        }

        if (request.getSessionTemplateIds() != null && !request.getSessionTemplateIds().isEmpty()) {
            ArrayNode arr = pack.putArray("sessionTemplates");
            for (String templateId : request.getSessionTemplateIds()) {
                SessionTemplateEntity entity = SessionTemplateEntity.find("templateId", templateId).firstResult();
                if (entity != null) arr.add(serializeSessionTemplate(entity));
            }
        }

        if (request.getScheduledJobIds() != null && !request.getScheduledJobIds().isEmpty()) {
            ArrayNode arr = pack.putArray("scheduledJobs");
            for (Number id : request.getScheduledJobIds()) {
                ScheduledJobEntity entity = ScheduledJobEntity.findById(id.longValue());
                if (entity != null) arr.add(serializeScheduledJob(entity));
            }
        }

        LOG.infof("Exported configuration pack '%s'", request.getName());
        return pack;
    }

    /**
     * Imports a configuration pack. Checks for name conflicts first and
     * fails fast if any are detected.
     *
     * @param pack the pack JSON
     * @return summary of what was imported
     */
    @Transactional
    public ImportResult importPack(JsonNode pack) {
        List<String> conflicts = new ArrayList<>();

        checkConflicts(pack, "tools", "name", "tool", conflicts);
        checkConflicts(pack, "toolsets", "name", "toolset", conflicts);
        checkConflicts(pack, "mcpServers", "name", "mcpServer", conflicts);
        checkConflicts(pack, "actionTypes", "name", "actionType", conflicts);
        checkConflicts(pack, "reportDefinitions", "name", "reportDefinition", conflicts);
        checkConflicts(pack, "sessionTemplates", "templateId", "sessionTemplate", conflicts);
        checkConflicts(pack, "scheduledJobs", "name", "scheduledJob", conflicts);

        if (!conflicts.isEmpty()) {
            ObjectNode error = objectMapper.createObjectNode();
            ArrayNode arr = error.putArray("conflicts");
            for (String c : conflicts) {
                String[] parts = c.split(":", 2);
                ObjectNode conflict = arr.addObject();
                conflict.put("type", parts[0]);
                conflict.put("name", parts[1]);
            }
            throw new WebApplicationException(
                    jakarta.ws.rs.core.Response.status(409).entity(error).build());
        }

        int tools = importTools(pack.path("tools"));
        int toolsets = importToolsets(pack.path("toolsets"));
        int mcpServers = importMcpServers(pack.path("mcpServers"));
        int actionTypes = importActionTypes(pack.path("actionTypes"));
        int reportDefinitions = importReportDefinitions(pack.path("reportDefinitions"));
        int sessionTemplates = importSessionTemplates(pack.path("sessionTemplates"));
        int scheduledJobs = importScheduledJobs(pack.path("scheduledJobs"));

        String packName = pack.path("metadata").path("name").asText("unnamed");
        LOG.infof("Imported configuration pack '%s': %d tools, %d toolsets, %d MCP servers, "
                        + "%d action types, %d report definitions, %d session templates, "
                        + "%d scheduled jobs",
                packName, tools, toolsets, mcpServers, actionTypes, reportDefinitions,
                sessionTemplates, scheduledJobs);

        ImportResult result = new ImportResult();
        result.setTools(tools);
        result.setToolsets(toolsets);
        result.setMcpServers(mcpServers);
        result.setActionTypes(actionTypes);
        result.setReportDefinitions(reportDefinitions);
        result.setSessionTemplates(sessionTemplates);
        result.setScheduledJobs(scheduledJobs);
        return result;
    }

    /**
     * Result of an upsert import operation, tracking created vs updated counts.
     */
    public record UpsertResult(
            int toolsCreated, int toolsUpdated,
            int actionTypesCreated, int actionTypesUpdated,
            int reportDefinitionsCreated, int reportDefinitionsUpdated,
            int toolsetsCreated, int toolsetsUpdated,
            int sessionTemplatesCreated, int sessionTemplatesUpdated,
            int eventSourcesCreated, int eventSourcesUpdated,
            int scheduledJobsCreated, int scheduledJobsUpdated
    ) {}

    /**
     * Imports a configuration pack with upsert semantics: items whose name
     * matches an existing entity are updated in place; new names are created.
     * Used by the assistant apply flow.
     *
     * @param pack the pack JSON
     * @return summary with created and updated counts per category
     */
    @Transactional
    public UpsertResult importOrUpdatePack(JsonNode pack) {
        int[] tools = upsertTools(pack.path("tools"));
        int[] actionTypes = upsertActionTypes(pack.path("actionTypes"));
        int[] reportDefinitions = upsertReportDefinitions(pack.path("reportDefinitions"));
        int[] toolsets = upsertToolsets(pack.path("toolsets"));
        int[] sessionTemplates = upsertSessionTemplates(pack.path("sessionTemplates"));
        int[] eventSources = upsertEventSources(pack.path("eventSources"));
        int[] scheduledJobs = upsertScheduledJobs(pack.path("scheduledJobs"));

        String packName = pack.path("metadata").path("name").asText("unnamed");
        LOG.infof("Upserted configuration pack '%s': %d tools created, %d updated; "
                        + "%d action types created, %d updated; "
                        + "%d report definitions created, %d updated; "
                        + "%d toolsets created, %d updated; "
                        + "%d session templates created, %d updated; "
                        + "%d event sources created, %d updated; "
                        + "%d scheduled jobs created, %d updated",
                packName,
                tools[0], tools[1],
                actionTypes[0], actionTypes[1],
                reportDefinitions[0], reportDefinitions[1],
                toolsets[0], toolsets[1],
                sessionTemplates[0], sessionTemplates[1],
                eventSources[0], eventSources[1],
                scheduledJobs[0], scheduledJobs[1]);

        return new UpsertResult(
                tools[0], tools[1],
                actionTypes[0], actionTypes[1],
                reportDefinitions[0], reportDefinitions[1],
                toolsets[0], toolsets[1],
                sessionTemplates[0], sessionTemplates[1],
                eventSources[0], eventSources[1],
                scheduledJobs[0], scheduledJobs[1]
        );
    }

    // ── Conflict detection ───────────────────────────────────────────

    private void checkConflicts(JsonNode pack, String section, String nameField,
                                 String type, List<String> conflicts) {
        JsonNode items = pack.path(section);
        if (!items.isArray()) return;
        for (JsonNode item : items) {
            String name = item.path(nameField).asText(null);
            if (name == null) continue;
            boolean exists = switch (type) {
                case "tool" -> ToolDefinitionEntity.count("name", name) > 0;
                case "toolset" -> ToolsetEntity.count("name", name) > 0;
                case "mcpServer" -> McpServerEntity.count("name", name) > 0;
                case "actionType" -> ActionTypeEntity.count("name", name) > 0;
                case "reportDefinition" -> ReportDefinitionEntity.count("name", name) > 0;
                case "sessionTemplate" -> SessionTemplateEntity.count("templateId", name) > 0;
                case "scheduledJob" -> ScheduledJobEntity.count("name", name) > 0;
                default -> false;
            };
            if (exists) {
                conflicts.add(type + ":" + name);
            }
        }
    }

    // ── Import helpers ───────────────────────────────────────────────

    private int importTools(JsonNode items) {
        if (!items.isArray()) return 0;
        int count = 0;
        for (JsonNode item : items) {
            ToolDefinitionEntity entity = new ToolDefinitionEntity();
            entity.name = item.path("name").asText();
            entity.description = textOrNull(item, "description");
            entity.parameters = jsonOrNull(item, "parameters");
            entity.scriptTemplate = textOrNull(item, "scriptTemplate");
            JsonNode labelsNode = item.path("labels");
            if (labelsNode.isArray()) {
                for (JsonNode l : labelsNode) {
                    entity.labels.add(l.asText());
                }
            }
            entity.persist();
            count++;
        }
        return count;
    }

    private int importToolsets(JsonNode items) {
        if (!items.isArray()) return 0;
        int count = 0;
        for (JsonNode item : items) {
            ToolsetEntity entity = new ToolsetEntity();
            entity.name = item.path("name").asText();
            entity.description = textOrNull(item, "description");
            entity.tools = csvOrNull(item, "tools");
            entity.persist();
            count++;
        }
        return count;
    }

    private int importMcpServers(JsonNode items) {
        if (!items.isArray()) return 0;
        int count = 0;
        for (JsonNode item : items) {
            McpServerEntity entity = new McpServerEntity();
            entity.name = item.path("name").asText();
            entity.description = textOrNull(item, "description");
            entity.serverCommand = textOrNull(item, "serverCommand");
            entity.serverArgs = jsonOrNull(item, "serverArgs");
            entity.serverEnv = jsonOrNull(item, "serverEnv");
            entity.serverUrl = textOrNull(item, "serverUrl");
            entity.persist();
            count++;
        }
        return count;
    }

    private int importActionTypes(JsonNode items) {
        if (!items.isArray()) return 0;
        int count = 0;
        for (JsonNode item : items) {
            ActionTypeEntity entity = new ActionTypeEntity();
            entity.name = item.path("name").asText();
            entity.description = textOrNull(item, "description");
            entity.executionMode = item.path("executionMode").asText("actor");
            entity.userTriggerable = item.path("userTriggerable").asBoolean(false);
            entity.managerTriggerable = item.path("managerTriggerable").asBoolean(false);
            entity.emitsEvent = item.path("emitsEvent").asBoolean(false);
            entity.inputSchema = jsonOrNull(item, "inputSchema");
            entity.allowedTools = csvOrNull(item, "allowedTools");
            entity.promptTemplate = textOrNull(item, "promptTemplate");
            entity.scriptTemplate = textOrNull(item, "scriptTemplate");
            entity.model = textOrNull(item, "model");
            entity.engine = textOrNull(item, "engine");
            entity.maxSteps = item.has("maxSteps") ? item.path("maxSteps").asInt() : null;
            entity.maxBudgetUsd = item.has("maxBudgetUsd") ? item.path("maxBudgetUsd").asDouble() : null;
            entity.environment = jsonOrNull(item, "environment");
            JsonNode labelsNode = item.path("labels");
            if (labelsNode.isArray()) {
                for (JsonNode l : labelsNode) {
                    entity.labels.add(l.asText());
                }
            }
            entity.persist();
            count++;
        }
        return count;
    }

    private int importReportDefinitions(JsonNode items) {
        if (!items.isArray()) return 0;
        int count = 0;
        for (JsonNode item : items) {
            ReportDefinitionEntity entity = new ReportDefinitionEntity();
            entity.name = item.path("name").asText();
            entity.description = textOrNull(item, "description");
            entity.schedule = item.path("schedule").asText("none");
            entity.scheduleTime = textOrNull(item, "scheduleTime");
            entity.scheduleDayOfWeek = textOrNull(item, "scheduleDayOfWeek");
            entity.timeWindow = item.path("timeWindow").asText("last-7d");
            entity.promptTemplate = item.path("promptTemplate").asText("");
            entity.allowedTools = csvOrNull(item, "allowedTools");
            entity.environment = jsonOrNull(item, "environment");
            entity.timeoutSeconds = item.has("timeoutSeconds")
                    ? item.path("timeoutSeconds").asInt() : null;
            entity.enabled = false;
            entity.createdOn = Instant.now();
            entity.updatedOn = Instant.now();
            entity.persist();
            count++;
        }
        return count;
    }

    private int importSessionTemplates(JsonNode items) {
        if (!items.isArray()) return 0;
        int count = 0;
        for (JsonNode item : items) {
            String templateId = item.path("templateId").asText(null);
            if (templateId == null || templateId.isBlank()) {
                templateId = java.util.UUID.randomUUID().toString();
            }
            if (sessionTemplateService.isBuiltIn(templateId)) {
                LOG.warnf("Skipping built-in session template '%s' — cannot be imported", templateId);
                continue;
            }
            SessionTemplateEntity entity = new SessionTemplateEntity();
            entity.templateId = templateId;
            entity.name = item.path("name").asText("");
            entity.description = textOrNull(item, "description");
            entity.systemPrompt = textOrNull(item, "systemPrompt");
            entity.welcomeMessage = textOrNull(item, "welcomeMessage");
            entity.workingDirectory = textOrNull(item, "workingDirectory");
            entity.model = textOrNull(item, "model");
            entity.initScript = textOrNull(item, "initScript");
            entity.initScriptType = textOrNull(item, "initScriptType");
            entity.environment = jsonOrNull(item, "environment");
            entity.initialMessage = textOrNull(item, "initialMessage");
            JsonNode mcpNode = item.path("mcpServers");
            if (mcpNode.isArray()) {
                for (JsonNode s : mcpNode) {
                    entity.mcpServers.add(s.asText());
                }
            }
            JsonNode toolsNode = item.path("allowedTools");
            if (toolsNode.isArray()) {
                for (JsonNode t : toolsNode) {
                    entity.allowedTools.add(t.asText());
                }
            }
            entity.persist();
            count++;
        }
        return count;
    }

    // ── Upsert helpers ──────────────────────────────────────────────

    private int[] upsertTools(JsonNode items) {
        if (!items.isArray()) return new int[]{0, 0};
        int created = 0, updated = 0;
        for (JsonNode item : items) {
            String name = item.path("name").asText();
            ToolDefinitionEntity entity = ToolDefinitionEntity.find("name", name).firstResult();
            boolean isNew = (entity == null);
            if (isNew) {
                entity = new ToolDefinitionEntity();
                entity.name = name;
            } else {
                entity.labels.clear();
            }
            entity.description = textOrNull(item, "description");
            entity.parameters = jsonOrNull(item, "parameters");
            entity.scriptTemplate = textOrNull(item, "scriptTemplate");
            JsonNode labelsNode = item.path("labels");
            if (labelsNode.isArray()) {
                for (JsonNode l : labelsNode) {
                    entity.labels.add(l.asText());
                }
            }
            entity.persist();
            if (isNew) created++;
            else updated++;
        }
        return new int[]{created, updated};
    }

    private int[] upsertActionTypes(JsonNode items) {
        if (!items.isArray()) return new int[]{0, 0};
        int created = 0, updated = 0;
        for (JsonNode item : items) {
            String name = item.path("name").asText();
            ActionTypeEntity entity = ActionTypeEntity.find("name", name).firstResult();
            boolean isNew = (entity == null);
            if (isNew) {
                entity = new ActionTypeEntity();
                entity.name = name;
            }
            entity.description = textOrNull(item, "description");
            entity.executionMode = item.path("executionMode").asText("actor");
            entity.userTriggerable = item.path("userTriggerable").asBoolean(false);
            entity.managerTriggerable = item.path("managerTriggerable").asBoolean(false);
            entity.emitsEvent = item.path("emitsEvent").asBoolean(false);
            entity.inputSchema = jsonOrNull(item, "inputSchema");
            entity.allowedTools = csvOrNull(item, "allowedTools");
            entity.promptTemplate = textOrNull(item, "promptTemplate");
            entity.scriptTemplate = textOrNull(item, "scriptTemplate");
            entity.model = textOrNull(item, "model");
            entity.engine = textOrNull(item, "engine");
            entity.maxSteps = item.has("maxSteps") ? item.path("maxSteps").asInt() : null;
            entity.maxBudgetUsd = item.has("maxBudgetUsd") ? item.path("maxBudgetUsd").asDouble() : null;
            entity.environment = jsonOrNull(item, "environment");
            entity.labels.clear();
            JsonNode labelsNode = item.path("labels");
            if (labelsNode.isArray()) {
                for (JsonNode l : labelsNode) {
                    entity.labels.add(l.asText());
                }
            }
            entity.persist();
            if (isNew) created++;
            else updated++;
        }
        return new int[]{created, updated};
    }

    private int[] upsertReportDefinitions(JsonNode items) {
        if (!items.isArray()) return new int[]{0, 0};
        int created = 0, updated = 0;
        for (JsonNode item : items) {
            String name = item.path("name").asText();
            ReportDefinitionEntity entity = ReportDefinitionEntity.find("name", name).firstResult();
            boolean isNew = (entity == null);
            if (isNew) {
                entity = new ReportDefinitionEntity();
                entity.name = name;
                entity.enabled = false;
                entity.createdOn = Instant.now();
            }
            entity.description = textOrNull(item, "description");
            entity.schedule = item.path("schedule").asText("none");
            entity.scheduleTime = textOrNull(item, "scheduleTime");
            entity.scheduleDayOfWeek = textOrNull(item, "scheduleDayOfWeek");
            entity.timeWindow = item.path("timeWindow").asText("last-7d");
            entity.promptTemplate = item.path("promptTemplate").asText("");
            entity.allowedTools = csvOrNull(item, "allowedTools");
            entity.environment = jsonOrNull(item, "environment");
            entity.timeoutSeconds = item.has("timeoutSeconds")
                    ? item.path("timeoutSeconds").asInt() : null;
            entity.updatedOn = Instant.now();
            entity.persist();
            if (isNew) created++;
            else updated++;
        }
        return new int[]{created, updated};
    }

    private int[] upsertToolsets(JsonNode items) {
        if (!items.isArray()) return new int[]{0, 0};
        int created = 0, updated = 0;
        for (JsonNode item : items) {
            String name = item.path("name").asText();
            ToolsetEntity entity = ToolsetEntity.find("name", name).firstResult();
            boolean isNew = (entity == null);
            if (isNew) {
                entity = new ToolsetEntity();
                entity.name = name;
            }
            entity.description = textOrNull(item, "description");
            entity.tools = csvOrNull(item, "tools");
            entity.persist();
            if (isNew) created++;
            else updated++;
        }
        return new int[]{created, updated};
    }

    private int[] upsertSessionTemplates(JsonNode items) {
        if (!items.isArray()) return new int[]{0, 0};
        int created = 0, updated = 0;
        for (JsonNode item : items) {
            String templateId = item.path("templateId").asText(null);
            if (templateId == null || templateId.isBlank()) {
                templateId = java.util.UUID.randomUUID().toString();
            }

            // Guard against overwriting built-in templates
            if (sessionTemplateService.isBuiltIn(templateId)) {
                LOG.warnf("Skipping built-in session template '%s' — cannot be overwritten", templateId);
                continue;
            }

            SessionTemplateEntity entity = SessionTemplateEntity.find("templateId", templateId).firstResult();
            boolean isNew = (entity == null);
            if (isNew) {
                entity = new SessionTemplateEntity();
                entity.templateId = templateId;
            } else {
                entity.mcpServers.clear();
                entity.allowedTools.clear();
            }
            entity.name = item.path("name").asText("");
            entity.description = textOrNull(item, "description");
            entity.systemPrompt = textOrNull(item, "systemPrompt");
            entity.welcomeMessage = textOrNull(item, "welcomeMessage");
            entity.workingDirectory = textOrNull(item, "workingDirectory");
            entity.model = textOrNull(item, "model");
            entity.initScript = textOrNull(item, "initScript");
            entity.initScriptType = textOrNull(item, "initScriptType");
            entity.environment = jsonOrNull(item, "environment");
            entity.initialMessage = textOrNull(item, "initialMessage");
            JsonNode mcpNode = item.path("mcpServers");
            if (mcpNode.isArray()) {
                for (JsonNode s : mcpNode) {
                    entity.mcpServers.add(s.asText());
                }
            }
            JsonNode toolsNode = item.path("allowedTools");
            if (toolsNode.isArray()) {
                for (JsonNode t : toolsNode) {
                    entity.allowedTools.add(t.asText());
                }
            }
            entity.persist();
            if (isNew) created++;
            else updated++;
        }
        return new int[]{created, updated};
    }

    private int[] upsertEventSources(JsonNode items) {
        if (!items.isArray()) return new int[]{0, 0};
        int created = 0, updated = 0;
        for (JsonNode item : items) {
            String name = item.path("name").asText();
            EventSourceEntity entity = EventSourceEntity.find("name", name).firstResult();
            boolean isNew = (entity == null);
            if (isNew) {
                entity = new EventSourceEntity();
                entity.name = name;
            }
            entity.description = textOrNull(item, "description");
            entity.sourceType = item.path("sourceType").asText("github");
            entity.enabled = item.path("enabled").asBoolean(false);
            entity.pollInterval = item.has("pollInterval")
                    ? item.path("pollInterval").asInt() : null;
            entity.secretName = textOrNull(item, "secretName");
            entity.configuration = jsonOrNull(item, "configuration");
            if (entity.configuration == null) {
                entity.configuration = "{}";
            }
            entity.filters = jsonOrNull(item, "filters");
            entity.labels.clear();
            JsonNode labelsNode = item.path("labels");
            if (labelsNode.isArray()) {
                for (JsonNode l : labelsNode) {
                    entity.labels.add(l.asText());
                }
            }
            entity.persist();
            if (isNew) created++;
            else updated++;
        }
        return new int[]{created, updated};
    }

    // ── Serialization helpers ────────────────────────────────────────

    private ObjectNode serializeTool(ToolDefinitionEntity e) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("name", e.name);
        putIfNotNull(n, "description", e.description);
        putIfNotNull(n, "parameters", e.parameters);
        putIfNotNull(n, "scriptTemplate", e.scriptTemplate);
        if (e.labels != null && !e.labels.isEmpty()) {
            var labelsArr = n.putArray("labels");
            e.labels.forEach(labelsArr::add);
        }
        return n;
    }

    private ObjectNode serializeToolset(ToolsetEntity e) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("name", e.name);
        putIfNotNull(n, "description", e.description);
        putIfNotNull(n, "tools", e.tools);
        return n;
    }

    private ObjectNode serializeMcpServer(McpServerEntity e) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("name", e.name);
        putIfNotNull(n, "description", e.description);
        putIfNotNull(n, "serverCommand", e.serverCommand);
        putIfNotNull(n, "serverArgs", e.serverArgs);
        putIfNotNull(n, "serverEnv", e.serverEnv);
        putIfNotNull(n, "serverUrl", e.serverUrl);
        return n;
    }

    private ObjectNode serializeActionType(ActionTypeEntity e) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("name", e.name);
        putIfNotNull(n, "description", e.description);
        n.put("executionMode", e.executionMode);
        n.put("userTriggerable", e.userTriggerable);
        n.put("managerTriggerable", e.managerTriggerable);
        n.put("emitsEvent", e.emitsEvent);
        putIfNotNull(n, "inputSchema", e.inputSchema);
        putIfNotNull(n, "allowedTools", e.allowedTools);
        putIfNotNull(n, "promptTemplate", e.promptTemplate);
        putIfNotNull(n, "scriptTemplate", e.scriptTemplate);
        putIfNotNull(n, "model", e.model);
        putIfNotNull(n, "engine", e.engine);
        if (e.maxSteps != null) n.put("maxSteps", e.maxSteps);
        if (e.maxBudgetUsd != null) n.put("maxBudgetUsd", e.maxBudgetUsd);
        putIfNotNull(n, "environment", e.environment);
        if (e.labels != null && !e.labels.isEmpty()) {
            var labelsArr = n.putArray("labels");
            e.labels.forEach(labelsArr::add);
        }
        return n;
    }

    private ObjectNode serializeReportDefinition(ReportDefinitionEntity e) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("name", e.name);
        putIfNotNull(n, "description", e.description);
        n.put("schedule", e.schedule);
        putIfNotNull(n, "scheduleTime", e.scheduleTime);
        putIfNotNull(n, "scheduleDayOfWeek", e.scheduleDayOfWeek);
        n.put("timeWindow", e.timeWindow);
        putIfNotNull(n, "promptTemplate", e.promptTemplate);
        putIfNotNull(n, "allowedTools", e.allowedTools);
        putIfNotNull(n, "environment", e.environment);
        if (e.timeoutSeconds != null) n.put("timeoutSeconds", e.timeoutSeconds);
        return n;
    }

    private ObjectNode serializeSessionTemplate(SessionTemplateEntity e) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("templateId", e.templateId);
        n.put("name", e.name);
        putIfNotNull(n, "description", e.description);
        putIfNotNull(n, "systemPrompt", e.systemPrompt);
        putIfNotNull(n, "welcomeMessage", e.welcomeMessage);
        putIfNotNull(n, "workingDirectory", e.workingDirectory);
        putIfNotNull(n, "model", e.model);
        putIfNotNull(n, "initScript", e.initScript);
        putIfNotNull(n, "initScriptType", e.initScriptType);
        putIfNotNull(n, "environment", e.environment);
        putIfNotNull(n, "initialMessage", e.initialMessage);
        if (e.mcpServers != null && !e.mcpServers.isEmpty()) {
            ArrayNode arr = n.putArray("mcpServers");
            e.mcpServers.forEach(arr::add);
        }
        if (e.allowedTools != null && !e.allowedTools.isEmpty()) {
            ArrayNode arr = n.putArray("allowedTools");
            e.allowedTools.forEach(arr::add);
        }
        return n;
    }

    private int importScheduledJobs(JsonNode items) {
        if (!items.isArray()) return 0;
        int count = 0;
        for (JsonNode item : items) {
            ScheduledJobEntity entity = new ScheduledJobEntity();
            entity.name = item.path("name").asText();
            entity.description = textOrNull(item, "description");
            entity.schedule = item.path("schedule").asText("none");
            entity.scheduleTime = textOrNull(item, "scheduleTime");
            entity.scheduleDayOfWeek = textOrNull(item, "scheduleDayOfWeek");
            entity.executionMode = item.path("executionMode").asText("actor");
            entity.promptTemplate = textOrNull(item, "promptTemplate");
            entity.scriptTemplate = textOrNull(item, "scriptTemplate");
            entity.model = textOrNull(item, "model");
            entity.engine = textOrNull(item, "engine");
            entity.allowedTools = csvOrNull(item, "allowedTools");
            if (item.has("maxSteps")) entity.maxSteps = item.path("maxSteps").asInt();
            if (item.has("maxBudgetUsd")) entity.maxBudgetUsd = item.path("maxBudgetUsd").asDouble();
            entity.environment = jsonOrNull(item, "environment");
            entity.enabled = false;
            entity.createdOn = Instant.now();
            entity.updatedOn = Instant.now();
            JsonNode labelsNode = item.path("labels");
            if (labelsNode.isArray()) {
                for (JsonNode l : labelsNode) entity.labels.add(l.asText());
            }
            entity.persist();
            count++;
        }
        return count;
    }

    private int[] upsertScheduledJobs(JsonNode items) {
        if (!items.isArray()) return new int[]{0, 0};
        int created = 0, updated = 0;
        for (JsonNode item : items) {
            String name = item.path("name").asText();
            ScheduledJobEntity entity = ScheduledJobEntity.find("name", name).firstResult();
            boolean isNew = (entity == null);
            if (isNew) {
                entity = new ScheduledJobEntity();
                entity.name = name;
                entity.enabled = false;
                entity.createdOn = Instant.now();
            }
            entity.description = textOrNull(item, "description");
            entity.schedule = item.path("schedule").asText("none");
            entity.scheduleTime = textOrNull(item, "scheduleTime");
            entity.scheduleDayOfWeek = textOrNull(item, "scheduleDayOfWeek");
            entity.executionMode = item.path("executionMode").asText("actor");
            entity.promptTemplate = textOrNull(item, "promptTemplate");
            entity.scriptTemplate = textOrNull(item, "scriptTemplate");
            entity.model = textOrNull(item, "model");
            entity.engine = textOrNull(item, "engine");
            entity.allowedTools = csvOrNull(item, "allowedTools");
            if (item.has("maxSteps")) entity.maxSteps = item.path("maxSteps").asInt();
            if (item.has("maxBudgetUsd")) entity.maxBudgetUsd = item.path("maxBudgetUsd").asDouble();
            entity.environment = jsonOrNull(item, "environment");
            entity.labels.clear();
            JsonNode labelsNode = item.path("labels");
            if (labelsNode.isArray()) {
                for (JsonNode l : labelsNode) entity.labels.add(l.asText());
            }
            entity.updatedOn = Instant.now();
            entity.persist();
            if (isNew) created++;
            else updated++;
        }
        return new int[]{created, updated};
    }

    private ObjectNode serializeScheduledJob(ScheduledJobEntity e) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("name", e.name);
        putIfNotNull(n, "description", e.description);
        n.put("schedule", e.schedule);
        putIfNotNull(n, "scheduleTime", e.scheduleTime);
        putIfNotNull(n, "scheduleDayOfWeek", e.scheduleDayOfWeek);
        n.put("executionMode", e.executionMode);
        putIfNotNull(n, "promptTemplate", e.promptTemplate);
        putIfNotNull(n, "scriptTemplate", e.scriptTemplate);
        putIfNotNull(n, "model", e.model);
        putIfNotNull(n, "engine", e.engine);
        putIfNotNull(n, "allowedTools", e.allowedTools);
        if (e.maxSteps != null) n.put("maxSteps", e.maxSteps);
        if (e.maxBudgetUsd != null) n.put("maxBudgetUsd", e.maxBudgetUsd);
        putIfNotNull(n, "environment", e.environment);
        if (e.labels != null && !e.labels.isEmpty()) {
            ArrayNode arr = n.putArray("labels");
            e.labels.forEach(arr::add);
        }
        return n;
    }

    private void putIfNotNull(ObjectNode node, String field, String value) {
        if (value != null) node.put(field, value);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private String jsonOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.toString();
    }

    /**
     * Reads a field that is stored as a comma-separated string but may appear
     * in the JSON as either a string or an array of strings.
     */
    private String csvOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isArray()) {
            List<String> items = new ArrayList<>();
            for (JsonNode item : value) {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    items.add(text);
                }
            }
            return items.isEmpty() ? null : String.join(",", items);
        }
        return value.asText();
    }
}
