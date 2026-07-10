package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.AssistantResource;
import io.apitomy.axiom.api.beans.AssistantApplyResult;
import io.apitomy.axiom.api.beans.AssistantItem;
import io.apitomy.axiom.api.beans.AssistantPermissionResponse;
import io.apitomy.axiom.api.beans.AssistantSessionInfo;
import io.apitomy.axiom.api.beans.CreateAssistantSessionRequest;
import io.apitomy.axiom.api.beans.NewSessionTemplate;
import io.apitomy.axiom.api.beans.RenameAssistantSessionRequest;
import io.apitomy.axiom.api.beans.SendAssistantMessageRequest;
import io.apitomy.axiom.api.beans.SessionTemplate;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.app.assistant.AssistantSessionManager;
import io.apitomy.axiom.app.assistant.AssistantSessionManager.SessionLimitReachedException;
import io.apitomy.axiom.app.assistant.AssistantSessionManager.ValidationException;
import io.apitomy.axiom.app.assistant.SessionTemplateService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * Implementation of the AI Assistant REST API, covering both session
 * management and template management endpoints.
 */
@ApplicationScoped
@RunOnVirtualThread
public class AssistantResourceImpl implements AssistantResource {

    private static final Logger LOG = Logger.getLogger(AssistantResourceImpl.class);

    @Inject
    AssistantSessionManager sessionManager;

    @Inject
    SessionTemplateService templateService;

    @Inject
    ObjectMapper objectMapper;

    // ── Sessions ─────────────────────────────────────────────────────

    /** {@inheritDoc} */
    @Override
    public List<AssistantSessionInfo> listAssistantSessions() {
        return sessionManager.listSessions().stream()
                .map(this::toSessionInfo).toList();
    }

    /** {@inheritDoc} */
    @Override
    public AssistantSessionInfo createAssistantSession(CreateAssistantSessionRequest data) {
        if (!sessionManager.isAvailable()) {
            throw new WebApplicationException(
                    "The AI Assistant requires Claude Code as the active AI engine.", 400);
        }
        if (data.getTemplateId() == null || data.getTemplateId().isBlank()) {
            throw new WebApplicationException("Missing required 'templateId' field", 400);
        }
        try {
            AssistantSession session = sessionManager.createSession(
                    data.getName(), data.getTemplateId());
            return toSessionInfo(session);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), 404);
        } catch (SessionLimitReachedException e) {
            throw new WebApplicationException(e.getMessage(), 409);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to create assistant session");
            throw new WebApplicationException(
                    "Failed to create session: " + e.getMessage(), 500);
        }
    }

    /** {@inheritDoc} */
    @Override
    public AssistantSessionInfo getAssistantSession(String sessionId) {
        AssistantSession session = requireSession(sessionId);
        return toSessionInfo(session);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteAssistantSession(String sessionId) {
        requireSession(sessionId);
        sessionManager.destroySession(sessionId);
    }

    /** {@inheritDoc} */
    @Override
    public AssistantSessionInfo renameAssistantSession(String sessionId,
                                                        RenameAssistantSessionRequest data) {
        AssistantSession session = requireSession(sessionId);
        return toSessionInfo(session);
    }

    /** {@inheritDoc} */
    @Override
    public void interruptAssistantSession(String sessionId) {
        AssistantSession session = requireSession(sessionId);
        session.interrupt();
    }

    /**
     * SSE event stream for an assistant session. Overrides the generated
     * interface method to inject the SSE sink and factory via {@code @Context}.
     *
     * @param sessionId the session identifier
     * @param sink the SSE event sink
     * @param sse the SSE factory
     */
    @GET
    @Path("/sessions/{sessionId}/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamAssistantEvents(@PathParam("sessionId") String sessionId,
                                       @Context SseEventSink sink,
                                       @Context Sse sse) {
        AssistantSession session = sessionManager.getSession(sessionId);
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

        sink.send(sse.newEventBuilder().comment("connected").build());

        Thread.ofVirtual().name("sse-cleanup-" + sessionId).start(() -> {
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

    /** {@inheritDoc} */
    @Override
    public void streamAssistantEvents(String sessionId) {
        throw new WebApplicationException(
                "Use the SSE endpoint with an EventSource client", 400);
    }

    /** {@inheritDoc} */
    @Override
    public void sendAssistantMessage(String sessionId, SendAssistantMessageRequest data) {
        AssistantSession session = requireSession(sessionId);
        if (data.getMessage() == null || data.getMessage().isBlank()) {
            throw new WebApplicationException("Missing 'message' field", 400);
        }
        try {
            session.sendMessage(data.getMessage());
        } catch (IOException e) {
            throw new WebApplicationException(
                    "Failed to send message: " + e.getMessage(), 500);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void respondToAssistantPermission(String sessionId,
                                              AssistantPermissionResponse data) {
        AssistantSession session = requireSession(sessionId);
        if (data.getPermissionId() == null || data.getPermissionId().isBlank()) {
            throw new WebApplicationException("Missing 'permissionId' field", 400);
        }
        try {
            JsonNode toolInput = data.getUpdatedInput() != null
                    ? objectMapper.valueToTree(data.getUpdatedInput())
                    : null;
            session.respondToPermission(
                    data.getPermissionId(), data.getAllow(), toolInput);
        } catch (IOException e) {
            throw new WebApplicationException(
                    "Failed to respond to permission: " + e.getMessage(), 500);
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<AssistantItem> listAssistantItems(String sessionId) {
        AssistantSession session = requireSession(sessionId);
        requireConfigAssistant(session);
        try {
            List<AssistantSessionManager.AssistantItem> items =
                    sessionManager.listItems(sessionId);
            return items.stream().map(this::toItemBean).toList();
        } catch (IOException e) {
            throw new WebApplicationException(
                    "Failed to list items: " + e.getMessage(), 500);
        }
    }

    /** {@inheritDoc} */
    @Override
    public AssistantItem getAssistantItem(String sessionId, String itemType,
                                           String itemName) {
        AssistantSession session = requireSession(sessionId);
        requireConfigAssistant(session);
        try {
            com.fasterxml.jackson.databind.JsonNode content =
                    sessionManager.getItemContent(sessionId, itemType, itemName);
            if (content == null) {
                throw new WebApplicationException(
                        "Item not found: " + itemType + "/" + itemName, 404);
            }
            // Return the raw content — the OpenAPI spec says AssistantItem but
            // the item content endpoint returns the full JSON file content.
            // This is a pragmatic deviation: the generated return type doesn't
            // match the actual response shape for this endpoint.
            throw new WebApplicationException(
                    Response.ok(content).build());
        } catch (WebApplicationException e) {
            throw e;
        } catch (IOException e) {
            throw new WebApplicationException(
                    "Failed to read item: " + e.getMessage(), 500);
        }
    }

    /** {@inheritDoc} */
    @Override
    public AssistantApplyResult applyAssistantSession(String sessionId) {
        AssistantSession session = requireSession(sessionId);
        requireConfigAssistant(session);
        try {
            io.apitomy.axiom.api.beans.ImportResult result =
                    sessionManager.applySession(sessionId);
            AssistantApplyResult applyResult = new AssistantApplyResult();
            applyResult.setTools(result.getTools());
            applyResult.setActionTypes(result.getActionTypes());
            applyResult.setReportDefinitions(result.getReportDefinitions());
            return applyResult;
        } catch (ValidationException e) {
            throw new WebApplicationException(e.getMessage(), 422);
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            throw new WebApplicationException(
                    "Failed to apply: " + e.getMessage(), 500);
        }
    }

    // ── Templates ────────────────────────────────────────────────────

    /** {@inheritDoc} */
    @Override
    public List<SessionTemplate> listSessionTemplates() {
        return templateService.listTemplates().stream()
                .map(this::toTemplateBean).toList();
    }

    /** {@inheritDoc} */
    @Override
    public SessionTemplate createSessionTemplate(NewSessionTemplate data) {
        try {
            SessionTemplateService.SessionTemplate created =
                    templateService.createTemplate(fromTemplateBean(data));
            return toTemplateBean(created);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to create template");
            throw new WebApplicationException(
                    "Failed to create template: " + e.getMessage(), 500);
        }
    }

    /** {@inheritDoc} */
    @Override
    public SessionTemplate getSessionTemplate(String templateId) {
        SessionTemplateService.SessionTemplate template =
                templateService.getTemplate(templateId);
        if (template == null) {
            throw new WebApplicationException("Template not found: " + templateId, 404);
        }
        return toTemplateBean(template);
    }

    /** {@inheritDoc} */
    @Override
    public SessionTemplate updateSessionTemplate(String templateId,
                                                   NewSessionTemplate data) {
        try {
            SessionTemplateService.SessionTemplate updated =
                    templateService.updateTemplate(templateId, fromTemplateBean(data));
            return toTemplateBean(updated);
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), 403);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), 404);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to update template %s", templateId);
            throw new WebApplicationException(
                    "Failed to update template: " + e.getMessage(), 500);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void deleteSessionTemplate(String templateId) {
        try {
            templateService.deleteTemplate(templateId);
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), 403);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private AssistantSession requireSession(String sessionId) {
        AssistantSession session = sessionManager.getSession(sessionId);
        if (session == null) {
            throw new WebApplicationException("Session not found: " + sessionId, 404);
        }
        return session;
    }

    private void requireConfigAssistant(AssistantSession session) {
        if (!"axiom-config-assistant".equals(session.getTemplateId())) {
            throw new WebApplicationException(
                    "Items are only available for Configuration Assistant sessions", 400);
        }
    }

    private AssistantSessionInfo toSessionInfo(AssistantSession session) {
        AssistantSessionInfo info = new AssistantSessionInfo();
        info.setId(session.getId());
        info.setName(session.getName());
        info.setTemplateId(session.getTemplateId());
        info.setStatus(AssistantSessionInfo.Status.fromValue(
                session.getStatus().name().toLowerCase()));
        info.setCreatedAt(Date.from(session.getCreatedAt()));
        info.setLastActivityAt(Date.from(session.getLastActivityAt()));
        info.setErrorMessage(session.getErrorMessage());
        return info;
    }

    private AssistantItem toItemBean(AssistantSessionManager.AssistantItem item) {
        AssistantItem bean = new AssistantItem();
        bean.setType(item.type());
        bean.setName(item.name());
        bean.setValid(item.isValid());
        bean.setValidationErrors(item.errors().isEmpty() ? null : item.errors());
        return bean;
    }

    private SessionTemplate toTemplateBean(
            SessionTemplateService.SessionTemplate template) {
        SessionTemplate bean = new SessionTemplate();
        bean.setTemplateId(template.templateId());
        bean.setName(template.name());
        bean.setDescription(template.description());
        bean.setBuiltIn(template.builtIn());
        bean.setSystemPrompt(template.systemPrompt());
        bean.setWelcomeMessage(template.welcomeMessage());
        bean.setWorkingDirectory(template.workingDirectory());
        bean.setMcpServers(template.mcpServers());
        bean.setAllowedTools(template.allowedTools());
        return bean;
    }

    private SessionTemplateService.SessionTemplate fromTemplateBean(
            NewSessionTemplate data) {
        return new SessionTemplateService.SessionTemplate(
                data.getTemplateId(),
                data.getName(),
                data.getDescription(),
                data.getSystemPrompt(),
                data.getWelcomeMessage(),
                data.getWorkingDirectory(),
                data.getMcpServers() != null ? data.getMcpServers() : List.of(),
                data.getAllowedTools() != null ? data.getAllowedTools() : List.of(),
                false);
    }
}
