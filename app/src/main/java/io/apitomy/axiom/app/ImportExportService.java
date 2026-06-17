package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.api.beans.ImportResult;
import io.apitomy.axiom.api.beans.PackExportRequest;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.McpServerEntity;
import io.apitomy.axiom.core.entities.ReportDefinitionEntity;
import io.apitomy.axiom.core.entities.ToolDefinitionEntity;
import io.apitomy.axiom.core.entities.ToolsetEntity;
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

        String packName = pack.path("metadata").path("name").asText("unnamed");
        LOG.infof("Imported configuration pack '%s': %d tools, %d toolsets, %d MCP servers, "
                        + "%d action types, %d report definitions",
                packName, tools, toolsets, mcpServers, actionTypes, reportDefinitions);

        ImportResult result = new ImportResult();
        result.setTools(tools);
        result.setToolsets(toolsets);
        result.setMcpServers(mcpServers);
        result.setActionTypes(actionTypes);
        result.setReportDefinitions(reportDefinitions);
        return result;
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
            entity.tools = textOrNull(item, "tools");
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
            entity.allowedTools = textOrNull(item, "allowedTools");
            entity.promptTemplate = textOrNull(item, "promptTemplate");
            entity.scriptTemplate = textOrNull(item, "scriptTemplate");
            entity.model = textOrNull(item, "model");
            entity.engine = textOrNull(item, "engine");
            entity.environment = jsonOrNull(item, "environment");
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
            entity.allowedTools = textOrNull(item, "allowedTools");
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
        putIfNotNull(n, "environment", e.environment);
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
}
