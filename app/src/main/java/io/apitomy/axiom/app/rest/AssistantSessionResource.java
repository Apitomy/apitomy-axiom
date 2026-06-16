package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.api.beans.ImportResult;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.app.assistant.AssistantSessionManager;
import io.apitomy.axiom.app.assistant.AssistantSessionManager.AssistantItem;
import io.apitomy.axiom.app.assistant.AssistantSessionManager.SessionLimitReachedException;
import io.apitomy.axiom.app.assistant.AssistantSessionManager.ValidationException;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * JAX-RS resource for managing interactive AI assistant sessions.
 * All endpoints are under {@code /api/v1/assistant/sessions}.
 */
@Path("/api/v1/assistant/sessions")
@ApplicationScoped
@RunOnVirtualThread
public class AssistantSessionResource {

    private static final Logger LOG = Logger.getLogger(AssistantSessionResource.class);

    @Inject
    AssistantSessionManager sessionManager;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Creates a new assistant session.
     *
     * @param body optional JSON body with a "name" field
     * @return the created session info
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createSession(JsonNode body) {
        if (!sessionManager.isAvailable()) {
            return errorResponse(400,
                    "The AI Assistant requires Claude Code as the active AI engine.");
        }

        try {
            String name = body != null ? body.path("name").asText(null) : null;
            AssistantSession session = sessionManager.createSession(name);
            return Response.ok(toSessionInfo(session)).build();
        } catch (SessionLimitReachedException e) {
            return errorResponse(409, e.getMessage());
        } catch (IOException e) {
            LOG.errorf(e, "Failed to create assistant session");
            return errorResponse(500, "Failed to create session: " + e.getMessage());
        }
    }

    /**
     * Lists all active assistant sessions.
     *
     * @return array of session info objects
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response listSessions() {
        ArrayNode arr = objectMapper.createArrayNode();
        for (AssistantSession session : sessionManager.listSessions()) {
            arr.add(toSessionInfo(session));
        }
        return Response.ok(arr).build();
    }

    /**
     * Gets info for a specific session.
     *
     * @param id the session identifier
     * @return session info
     */
    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSession(@PathParam("id") String id) {
        AssistantSession session = sessionManager.getSession(id);
        if (session == null) {
            return errorResponse(404, "Session not found: " + id);
        }
        return Response.ok(toSessionInfo(session)).build();
    }

    /**
     * Renames a session.
     *
     * @param id the session identifier
     * @param body JSON body with a "name" field
     * @return updated session info
     */
    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response renameSession(@PathParam("id") String id, JsonNode body) {
        AssistantSession session = sessionManager.getSession(id);
        if (session == null) {
            return errorResponse(404, "Session not found: " + id);
        }
        // Session names are currently immutable after creation — this endpoint
        // is reserved for future use (the plan specifies PATCH for rename).
        return Response.ok(toSessionInfo(session)).build();
    }

    /**
     * Opens an SSE event stream for a session. Replays all buffered events
     * first, then streams new events in real time.
     *
     * @param id the session identifier
     * @param sink the SSE event sink
     * @param sse the SSE factory
     */
    @GET
    @Path("/{id}/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamEvents(@PathParam("id") String id,
                             @Context SseEventSink sink,
                             @Context Sse sse) {
        AssistantSession session = sessionManager.getSession(id);
        if (session == null) {
            try (sink) {
                sink.send(sse.newEventBuilder()
                        .name("error")
                        .data("{\"message\":\"Session not found\"}")
                        .build());
            }
            return;
        }

        // Replay history
        for (SseEvent event : session.getEventHistory()) {
            if (sink.isClosed()) return;
            OutboundSseEvent sseEvent = sse.newEventBuilder()
                    .name(event.type())
                    .data(event.toJson())
                    .build();
            sink.send(sseEvent);
        }

        // Stream live events
        Consumer<SseEvent> listener = event -> {
            if (sink.isClosed()) return;
            OutboundSseEvent sseEvent = sse.newEventBuilder()
                    .name(event.type())
                    .data(event.toJson())
                    .build();
            sink.send(sseEvent);
        };

        session.addListener(listener);

        // Clean up when the SSE connection closes
        sink.send(sse.newEventBuilder().comment("connected").build())
                .whenComplete((v, ex) -> {
                    // Keep listener until sink is actually closed
                });

        // Wait until the session ends or the sink closes
        Thread.ofVirtual().name("sse-cleanup-" + id).start(() -> {
            while (!sink.isClosed() && session.isAlive()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            session.removeListener(listener);
            if (!sink.isClosed()) {
                try {
                    sink.close();
                } catch (Exception e) {
                    // Ignore
                }
            }
        });
    }

    /**
     * Sends a user message to the Claude Code subprocess.
     *
     * @param id the session identifier
     * @param body JSON body with a "message" field
     * @return 204 No Content on success
     */
    @POST
    @Path("/{id}/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response sendMessage(@PathParam("id") String id, JsonNode body) {
        AssistantSession session = sessionManager.getSession(id);
        if (session == null) {
            return errorResponse(404, "Session not found: " + id);
        }

        String message = body.path("message").asText(null);
        if (message == null || message.isBlank()) {
            return errorResponse(400, "Missing 'message' field");
        }

        try {
            session.sendMessage(message);
            return Response.noContent().build();
        } catch (IOException e) {
            return errorResponse(500, "Failed to send message: " + e.getMessage());
        }
    }

    /**
     * Responds to a permission prompt from Claude Code.
     *
     * @param id the session identifier
     * @param body JSON body with "permissionId" and "allow" fields
     * @return 204 No Content on success
     */
    @POST
    @Path("/{id}/permissions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response respondToPermission(@PathParam("id") String id, JsonNode body) {
        AssistantSession session = sessionManager.getSession(id);
        if (session == null) {
            return errorResponse(404, "Session not found: " + id);
        }

        String permissionId = body.path("permissionId").asText(null);
        boolean allow = body.path("allow").asBoolean(false);
        JsonNode updatedInput = body.path("updatedInput");
        if (updatedInput.isMissingNode() || updatedInput.isNull()) {
            updatedInput = null;
        }

        if (permissionId == null || permissionId.isBlank()) {
            return errorResponse(400, "Missing 'permissionId' field");
        }

        try {
            session.respondToPermission(permissionId, allow, updatedInput);
            return Response.noContent().build();
        } catch (IOException e) {
            return errorResponse(500,
                    "Failed to respond to permission: " + e.getMessage());
        }
    }

    /**
     * Lists generated items from the session's working directory.
     *
     * @param id the session identifier
     * @return array of item descriptors with validation status
     */
    @GET
    @Path("/{id}/items")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listItems(@PathParam("id") String id) {
        try {
            List<AssistantItem> items = sessionManager.listItems(id);
            ArrayNode arr = objectMapper.createArrayNode();
            for (AssistantItem item : items) {
                ObjectNode node = arr.addObject();
                node.put("type", item.type());
                node.put("name", item.name());
                node.put("valid", item.isValid());
                if (!item.errors().isEmpty()) {
                    ArrayNode errArr = node.putArray("errors");
                    item.errors().forEach(errArr::add);
                }
                if (!item.warnings().isEmpty()) {
                    ArrayNode warnArr = node.putArray("warnings");
                    item.warnings().forEach(warnArr::add);
                }
            }
            return Response.ok(arr).build();
        } catch (IllegalArgumentException e) {
            return errorResponse(404, e.getMessage());
        } catch (IOException e) {
            return errorResponse(500, "Failed to list items: " + e.getMessage());
        }
    }

    /**
     * Gets the full content of a specific generated item.
     *
     * @param id the session identifier
     * @param type the item type (tools, action-types, report-definitions)
     * @param name the item name
     * @return the item's JSON content
     */
    @GET
    @Path("/{id}/items/{type}/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getItem(@PathParam("id") String id,
                            @PathParam("type") String type,
                            @PathParam("name") String name) {
        try {
            JsonNode content = sessionManager.getItemContent(id, type, name);
            if (content == null) {
                return errorResponse(404, "Item not found: " + type + "/" + name);
            }
            return Response.ok(content).build();
        } catch (IllegalArgumentException e) {
            return errorResponse(404, e.getMessage());
        } catch (IOException e) {
            return errorResponse(500, "Failed to read item: " + e.getMessage());
        }
    }

    /**
     * Validates all items and imports them as a Configuration Pack.
     *
     * @param id the session identifier
     * @return the import result with item counts
     */
    @POST
    @Path("/{id}/apply")
    @Produces(MediaType.APPLICATION_JSON)
    public Response apply(@PathParam("id") String id) {
        try {
            ImportResult result = sessionManager.applySession(id);
            return Response.ok(result).build();
        } catch (IllegalArgumentException e) {
            return errorResponse(404, e.getMessage());
        } catch (ValidationException e) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("message", e.getMessage());
            ArrayNode errArr = error.putArray("validationErrors");
            e.getErrors().forEach(errArr::add);
            return Response.status(422).entity(error).build();
        } catch (WebApplicationException e) {
            // Conflict from ImportExportService
            return e.getResponse();
        } catch (IOException e) {
            return errorResponse(500, "Failed to apply: " + e.getMessage());
        }
    }

    /**
     * Ends a session: kills the subprocess and deletes the working directory.
     *
     * @param id the session identifier
     * @return 204 No Content on success
     */
    @DELETE
    @Path("/{id}")
    public Response deleteSession(@PathParam("id") String id) {
        if (sessionManager.getSession(id) == null) {
            return errorResponse(404, "Session not found: " + id);
        }
        sessionManager.destroySession(id);
        return Response.noContent().build();
    }

    private ObjectNode toSessionInfo(AssistantSession session) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", session.getId());
        node.put("name", session.getName());
        node.put("status", session.getStatus().name().toLowerCase());
        node.put("createdAt", session.getCreatedAt().toString());
        node.put("lastActivityAt", session.getLastActivityAt().toString());
        if (session.getErrorMessage() != null) {
            node.put("errorMessage", session.getErrorMessage());
        }
        return node;
    }

    private Response errorResponse(int status, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("message", message);
        return Response.status(status).entity(error).build();
    }
}
