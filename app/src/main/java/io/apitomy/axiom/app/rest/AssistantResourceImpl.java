package io.apitomy.axiom.app.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.axiom.api.beans.Environment;
import io.apitomy.axiom.api.AssistantResource;
import io.apitomy.axiom.api.beans.AssistantApplyResult;
import io.apitomy.axiom.api.beans.AssistantItem;
import io.apitomy.axiom.api.beans.AssistantPermissionResponse;
import io.apitomy.axiom.api.beans.AssistantSessionInfo;
import io.apitomy.axiom.api.beans.AutoApprovalRule;
import io.apitomy.axiom.api.beans.CreateAssistantSessionRequest;
import io.apitomy.axiom.api.beans.CreateAutoApprovalRequest;
import io.apitomy.axiom.api.beans.DismissCardRequest;
import io.apitomy.axiom.api.beans.NewSessionTemplate;
import io.apitomy.axiom.api.beans.RenameAssistantSessionRequest;
import io.apitomy.axiom.api.beans.SendAssistantMessageRequest;
import io.apitomy.axiom.api.beans.SessionTemplate;
import io.apitomy.axiom.api.beans.SetAllowAllRequest;
import io.apitomy.axiom.app.assistant.AssistantEventParser.SseEvent;
import io.apitomy.axiom.app.assistant.AssistantSession;
import io.apitomy.axiom.app.ImportExportService;
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
import jakarta.ws.rs.core.HttpHeaders;
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
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
                    data.getName(), data.getTemplateId(), data.getProjectId());
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
        if (data.getName() != null && !data.getName().isBlank()) {
            session.setName(data.getName());
        }
        return toSessionInfo(session);
    }

    /** {@inheritDoc} */
    @Override
    public void interruptAssistantSession(String sessionId) {
        AssistantSession session = requireSession(sessionId);
        session.interrupt();
    }

    /**
     * SSE event stream for an assistant session. This method overloads the
     * generated interface method to add {@code @Context} SSE parameters.
     * Supports {@code Last-Event-Id} for resuming after a reconnect.
     *
     * <p>Event delivery is decoupled from event dispatch via a per-connection
     * {@link LinkedBlockingQueue}. The listener registered with the session
     * performs a non-blocking {@code offer()} so that the session's
     * {@code eventLock} is never held while writing to the SSE sink. A
     * dedicated drainer virtual thread polls the queue and calls
     * {@code sink.send()}, ensuring only one thread ever writes to the sink.
     */
    @GET
    @Path("/sessions/{sessionId}/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamAssistantEvents(@PathParam("sessionId") String sessionId,
                                       @Context SseEventSink sink,
                                       @Context Sse sse,
                                       @Context HttpHeaders headers) {
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

        long lastEventId = parseLastEventId(headers);

        LinkedBlockingQueue<SseEvent> eventQueue = new LinkedBlockingQueue<>(4096);

        Consumer<SseEvent> listener = event -> {
            if (!eventQueue.offer(event)) {
                LOG.warnf("SSE event queue full for session %s, forcing reconnect",
                        sessionId);
                try { sink.close(); } catch (Exception ignored) { }
            }
        };

        List<SseEvent> history = session.addListenerWithHistory(listener, lastEventId);

        Thread.ofVirtual().name("sse-drainer-" + sessionId).start(() -> {
            try {
                long nextId = lastEventId + 1;

                for (SseEvent event : history) {
                    if (sink.isClosed()) return;
                    sink.send(buildSseEvent(sse, event, nextId++));
                }

                if (!sink.isClosed()) {
                    sink.send(sse.newEventBuilder().comment("connected").build());
                }

                int idleSeconds = 0;
                while (!sink.isClosed()
                        && (session.isAlive() || !eventQueue.isEmpty())) {
                    SseEvent event = eventQueue.poll(1, TimeUnit.SECONDS);
                    if (event != null) {
                        sink.send(buildSseEvent(sse, event, nextId++));
                        idleSeconds = 0;
                        List<SseEvent> batch = new ArrayList<>();
                        eventQueue.drainTo(batch);
                        for (SseEvent e : batch) {
                            if (sink.isClosed()) return;
                            sink.send(buildSseEvent(sse, e, nextId++));
                        }
                    } else {
                        idleSeconds++;
                        if (idleSeconds >= 15) {
                            idleSeconds = 0;
                            if (!sink.isClosed()) {
                                sink.send(sse.newEventBuilder()
                                        .comment("heartbeat").build());
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.debugf("SSE drainer ended for session %s: %s",
                        sessionId, e.getMessage());
            } finally {
                session.removeListener(listener);
                if (!sink.isClosed()) {
                    try { sink.close(); } catch (Exception ignored) { }
                }
            }
        });
    }

    /**
     * Parses the {@code Last-Event-Id} header, returning -1 if absent or invalid.
     */
    private long parseLastEventId(HttpHeaders headers) {
        String value = headers.getHeaderString("Last-Event-Id");
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Builds an outbound SSE event with a numeric ID, event name, and JSON data.
     */
    private OutboundSseEvent buildSseEvent(Sse sse, SseEvent event, long id) {
        return sse.newEventBuilder()
                .id(String.valueOf(id))
                .name(event.type())
                .data(event.toJson())
                .build();
    }

    /** {@inheritDoc} */
    @Override
    public void streamAssistantEvents(String sessionId) {
        // This method is called when the SSE-aware overload doesn't match
        // (e.g., non-SSE client). Check the session exists for a clean 404.
        requireSession(sessionId);
    }

    /** {@inheritDoc} */
    @Override
    public void getAssistantSessionHistory(String sessionId) {
        // Overridden below with @Context injection for Response building.
    }

    /**
     * Returns the full event history for an assistant session as a JSON array.
     * Each element contains eventType, eventData, and eventIndex.
     */
    @GET
    @Path("/sessions/{sessionId}/history")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAssistantSessionHistoryWithResponse(
            @PathParam("sessionId") String sessionId) {
        AssistantSession session = sessionManager.getSession(sessionId);
        if (session == null) {
            throw new WebApplicationException("Session not found: " + sessionId, 404);
        }

        List<SseEvent> history = session.getEventHistory();
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) json.append(",");
            SseEvent event = history.get(i);
            json.append("{\"eventType\":\"").append(event.type()).append("\",")
                    .append("\"eventData\":").append(event.toJson()).append(",")
                    .append("\"eventIndex\":").append(i).append("}");
        }
        json.append("]");

        return Response.ok(json.toString(), MediaType.APPLICATION_JSON).build();
    }

    /** {@inheritDoc} */
    @Override
    public String getAssistantSessionRawEvents(String sessionId) {
        AssistantSession session = requireSession(sessionId);

        java.nio.file.Path rawEventsFile = session.getRawEventsFile();
        if (!java.nio.file.Files.exists(rawEventsFile)) {
            throw new WebApplicationException(
                    "No raw events log available for session: " + sessionId, 404);
        }

        try {
            return java.nio.file.Files.readString(rawEventsFile, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new WebApplicationException(
                    "Failed to read raw events log: " + e.getMessage(), 500);
        }
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
    public void dismissAssistantCard(String sessionId, DismissCardRequest data) {
        AssistantSession session = requireSession(sessionId);
        if (data.getCardId() == null || data.getCardId().isBlank()) {
            throw new WebApplicationException("Missing 'cardId' field", 400);
        }
        ObjectNode eventData = objectMapper.createObjectNode();
        eventData.put("cardId", data.getCardId());
        session.addEvent(new SseEvent("card_dismissed", eventData));
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
    public Response getAssistantItem(String sessionId, String itemType,
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
            return Response.ok(content).build();
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
            ImportExportService.UpsertResult result =
                    sessionManager.applySession(sessionId);
            AssistantApplyResult applyResult = new AssistantApplyResult();
            applyResult.setTools(result.toolsCreated() + result.toolsUpdated());
            applyResult.setActionTypes(result.actionTypesCreated() + result.actionTypesUpdated());
            applyResult.setReportDefinitions(result.reportDefinitionsCreated() + result.reportDefinitionsUpdated());
            applyResult.setToolsCreated(result.toolsCreated());
            applyResult.setToolsUpdated(result.toolsUpdated());
            applyResult.setActionTypesCreated(result.actionTypesCreated());
            applyResult.setActionTypesUpdated(result.actionTypesUpdated());
            applyResult.setReportDefinitionsCreated(result.reportDefinitionsCreated());
            applyResult.setReportDefinitionsUpdated(result.reportDefinitionsUpdated());
            applyResult.setToolsets(result.toolsetsCreated() + result.toolsetsUpdated());
            applyResult.setToolsetsCreated(result.toolsetsCreated());
            applyResult.setToolsetsUpdated(result.toolsetsUpdated());
            applyResult.setSessionTemplates(result.sessionTemplatesCreated() + result.sessionTemplatesUpdated());
            applyResult.setSessionTemplatesCreated(result.sessionTemplatesCreated());
            applyResult.setSessionTemplatesUpdated(result.sessionTemplatesUpdated());
            applyResult.setScheduledJobs(result.scheduledJobsCreated() + result.scheduledJobsUpdated());
            applyResult.setScheduledJobsCreated(result.scheduledJobsCreated());
            applyResult.setScheduledJobsUpdated(result.scheduledJobsUpdated());
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

    // ── Auto-Approvals ────────────────────────────────────────────────

    /** {@inheritDoc} */
    @Override
    public List<AutoApprovalRule> listAutoApprovals(String sessionId) {
        AssistantSession session = requireSession(sessionId);
        return session.getAutoApprovalRules().stream()
                .map(this::toAutoApprovalBean).toList();
    }

    /** {@inheritDoc} */
    @Override
    public AutoApprovalRule createAutoApproval(String sessionId,
                                                CreateAutoApprovalRequest data) {
        AssistantSession session = requireSession(sessionId);
        try {
            AssistantSession.AutoApprovalRule rule = session.addAutoApprovalRule(
                    data.getToolName(), data.getFieldName(), data.getPattern());

            if (data.getPermissionId() != null && !data.getPermissionId().isBlank()) {
                try {
                    session.respondToPermission(data.getPermissionId(), true, null);
                } catch (IOException e) {
                    LOG.warnf(e, "Failed to auto-approve pending permission %s",
                            data.getPermissionId());
                }
            }

            return toAutoApprovalBean(rule);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new WebApplicationException("Invalid regex pattern: " + e.getMessage(), 400);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void deleteAutoApproval(String sessionId, String ruleId) {
        AssistantSession session = requireSession(sessionId);
        session.removeAutoApprovalRule(ruleId);
    }

    // ── Allow All ───────────────────────────────────────────────────

    /** {@inheritDoc} */
    @Override
    public void setAllowAll(String sessionId, SetAllowAllRequest data) {
        AssistantSession session = requireSession(sessionId);
        boolean enabled = data.getEnabled() != null && data.getEnabled();
        session.setAllowAll(enabled);
        ObjectNode eventData = objectMapper.createObjectNode();
        eventData.put("enabled", enabled);
        session.addEvent(new SseEvent("allow_all_changed", eventData));
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
        info.setTotalCostUsd(session.getTotalCostUsd());
        info.setTotalInputTokens(session.getTotalInputTokens());
        info.setTotalOutputTokens(session.getTotalOutputTokens());
        info.setTurnCount(session.getTurnCount());
        info.setProjectId(session.getProjectId());
        info.setProjectName(session.getProjectName());
        info.setAllowAll(session.isAllowAll());
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
        bean.setInitialMessage(template.initialMessage());
        bean.setWorkingDirectory(template.workingDirectory());
        bean.setModel(template.model());
        bean.setInitScript(template.initScript());
        bean.setInitScriptType(template.initScriptType());
        bean.setEnvironment(jsonToEnvironment(template.environment()));
        bean.setMcpServers(template.mcpServers());
        bean.setAllowedTools(template.allowedTools());
        return bean;
    }

    private AutoApprovalRule toAutoApprovalBean(AssistantSession.AutoApprovalRule rule) {
        AutoApprovalRule bean = new AutoApprovalRule();
        bean.setId(rule.id());
        bean.setToolName(rule.toolName());
        bean.setFieldName(rule.fieldName());
        bean.setPattern(rule.pattern());
        bean.setCreatedAt(java.util.Date.from(rule.createdAt()));
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
                data.getInitialMessage(),
                data.getWorkingDirectory(),
                data.getModel(),
                data.getInitScript(),
                data.getInitScriptType(),
                environmentToJson(data.getEnvironment()),
                data.getMcpServers() != null ? data.getMcpServers() : List.of(),
                data.getAllowedTools() != null ? data.getAllowedTools() : List.of(),
                false);
    }

    private String environmentToJson(Environment env) {
        if (env == null || env.getAdditionalProperties().isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(env.getAdditionalProperties());
        } catch (Exception e) {
            return null;
        }
    }

    private Environment jsonToEnvironment(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, String> map = objectMapper.readValue(json, new TypeReference<>() {});
            Environment env = new Environment();
            map.forEach(env::setAdditionalProperty);
            return env;
        } catch (Exception e) {
            return null;
        }
    }
}
