package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.app.assistant.SessionTemplateService;
import io.apitomy.axiom.app.assistant.SessionTemplateService.SessionTemplate;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * REST resource for managing AI Assistant session templates.
 * All endpoints are under {@code /api/v1/assistant/templates}.
 */
@Path("/api/v1/assistant/templates")
@ApplicationScoped
@RunOnVirtualThread
public class SessionTemplateResource {

    private static final Logger LOG = Logger.getLogger(SessionTemplateResource.class);

    @Inject
    SessionTemplateService templateService;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Lists all session templates (built-in + user-defined).
     *
     * @return array of template objects
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response listTemplates() {
        List<SessionTemplate> templates = templateService.listTemplates();
        ArrayNode array = objectMapper.createArrayNode();
        for (SessionTemplate template : templates) {
            array.add(toJson(template));
        }
        return Response.ok(array).build();
    }

    /**
     * Gets a session template by its template ID.
     *
     * @param templateId the template identifier
     * @return the template details
     */
    @GET
    @Path("/{templateId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTemplate(@PathParam("templateId") String templateId) {
        SessionTemplate template = templateService.getTemplate(templateId);
        if (template == null) {
            return errorResponse(404, "Template not found: " + templateId);
        }
        return Response.ok(toJson(template)).build();
    }

    /**
     * Creates a new user-defined session template.
     *
     * @param body the template data as JSON
     * @return the created template
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createTemplate(JsonNode body) {
        try {
            SessionTemplate input = fromJson(body);
            SessionTemplate created = templateService.createTemplate(input);
            return Response.status(Response.Status.CREATED)
                    .entity(toJson(created)).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to create template");
            return errorResponse(500, "Failed to create template: " + e.getMessage());
        }
    }

    /**
     * Updates a user-defined session template. Returns 403 for built-ins.
     *
     * @param templateId the template identifier
     * @param body the updated template data as JSON
     * @return the updated template
     */
    @PUT
    @Path("/{templateId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateTemplate(@PathParam("templateId") String templateId,
                                    JsonNode body) {
        try {
            SessionTemplate input = fromJson(body);
            SessionTemplate updated = templateService.updateTemplate(templateId, input);
            return Response.ok(toJson(updated)).build();
        } catch (IllegalStateException e) {
            return errorResponse(403, e.getMessage());
        } catch (IllegalArgumentException e) {
            return errorResponse(404, e.getMessage());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to update template %s", templateId);
            return errorResponse(500, "Failed to update template: " + e.getMessage());
        }
    }

    /**
     * Deletes a user-defined session template. Returns 403 for built-ins.
     *
     * @param templateId the template identifier
     * @return 204 No Content on success
     */
    @DELETE
    @Path("/{templateId}")
    public Response deleteTemplate(@PathParam("templateId") String templateId) {
        try {
            templateService.deleteTemplate(templateId);
            return Response.noContent().build();
        } catch (IllegalStateException e) {
            return errorResponse(403, e.getMessage());
        }
    }

    private ObjectNode toJson(SessionTemplate template) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("templateId", template.templateId());
        node.put("name", template.name());
        node.put("description", template.description());
        node.put("builtIn", template.builtIn());
        node.put("systemPrompt", template.systemPrompt());
        if (template.welcomeMessage() != null) {
            node.put("welcomeMessage", template.welcomeMessage());
        }
        if (template.workingDirectory() != null) {
            node.put("workingDirectory", template.workingDirectory());
        }
        node.set("mcpServers", toJsonArray(template.mcpServers()));
        node.set("toolsets", toJsonArray(template.toolsets()));
        node.set("allowedTools", toJsonArray(template.allowedTools()));
        return node;
    }

    private SessionTemplate fromJson(JsonNode body) {
        return new SessionTemplate(
                body.path("templateId").asText(null),
                body.path("name").asText(""),
                body.path("description").asText(""),
                body.path("systemPrompt").asText(""),
                body.path("welcomeMessage").asText(null),
                body.path("workingDirectory").asText(null),
                jsonArrayToList(body.path("mcpServers")),
                jsonArrayToList(body.path("toolsets")),
                jsonArrayToList(body.path("allowedTools")),
                false);
    }

    private ArrayNode toJsonArray(List<String> items) {
        ArrayNode array = objectMapper.createArrayNode();
        if (items != null) {
            items.forEach(array::add);
        }
        return array;
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

    private Response errorResponse(int status, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("message", message);
        return Response.status(status).entity(error).build();
    }
}
