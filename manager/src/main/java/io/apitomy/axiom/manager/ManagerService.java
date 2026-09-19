package io.apitomy.axiom.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.AgentEntity;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
import io.apitomy.axiom.core.entities.EventSourceEntity;
import io.apitomy.axiom.core.entities.ManagerConfigEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.apitomy.axiom.agents.spi.AgentRequest;
import io.apitomy.axiom.agents.spi.AgentResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Service that invokes the AI Manager to evaluate events and produce decisions.
 * Uses the pluggable {@link AgentRegistry} to invoke the default AI agent
 * with a structured JSON schema for decision output.
 */
@ApplicationScoped
public class ManagerService {

    private static final Logger LOG = Logger.getLogger(ManagerService.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AgentRegistry agentRegistry;

    @Inject
    TraceService traceService;

    @ConfigProperty(name = "axiom.manager.confidence-threshold", defaultValue = "0.7")
    double confidenceThreshold;

    @ConfigProperty(name = "axiom.manager.timeout-seconds", defaultValue = "120")
    int timeoutSeconds;

    @ConfigProperty(name = "axiom.manager.max-turns", defaultValue = "5")
    int maxTurns;

    @ConfigProperty(name = "axiom.manager.engine")
    Optional<String> managerEngine;

    @ConfigProperty(name = "axiom.manager.model")
    Optional<String> model;

    /**
     * Evaluates an event and returns the Manager's decisions.
     *
     * <p>Context loading (action types, agents, project data, config) runs in a short
     * independent transaction so the subsequent AI engine call does not hold a database
     * connection.</p>
     *
     * @param event    the event to evaluate (may be detached)
     * @param traceCtx the current trace context (nullable — tracing is non-fatal)
     * @return a list of decisions (may be empty if the Manager fails)
     */
    public List<ManagerDecision> evaluate(EventEntity event, TraceContext traceCtx) {
        LOG.infof("Manager evaluating event %d: %s [%s]", event.id, event.eventType, event.issueRef);

        // Add manager-evaluation trace node and push onto stack so decisions are children
        Long evalNodeId = null;
        if (traceCtx != null) {
            try {
                evalNodeId = traceService.addNode(traceCtx, "manager-evaluation", "in-progress",
                        "Manager evaluation: " + event.eventType, null, null);
                traceCtx.push(evalNodeId);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to add manager-evaluation trace node for event %d", event.id);
            }
        }

        // Load context (short transaction — releases connection before AI call)
        EvalContext ctx = QuarkusTransaction.requiringNew().call(() -> {
            List<ActionTypeEntity> actionTypes = ActionTypeEntity.list("managerTriggerable", true);

            // Filter action types by label compatibility with the event source
            List<String> eventSourceLabels = Collections.emptyList();
            if (event.eventSourceId != null) {
                EventSourceEntity eventSource = EventSourceEntity.findById(event.eventSourceId);
                if (eventSource != null && eventSource.labels != null) {
                    eventSourceLabels = eventSource.labels;
                }
            }
            actionTypes = filterByLabels(actionTypes, eventSourceLabels);

            List<AgentEntity> agents = AgentEntity.listAll();

            ProjectEntity project = null;
            List<TaskEntity> recentTasks = Collections.emptyList();
            if (event.issueRef != null) {
                project = ProjectEntity.find("ref", event.issueRef).firstResult();
                if (project != null) {
                    recentTasks = TaskEntity.find(
                            "projectId = ?1 order by createdOn desc",
                            project.id).page(0, 10).list();
                }
            }

            ManagerConfigEntity config = ManagerConfigEntity.<ManagerConfigEntity>findAll()
                    .firstResult();
            return new EvalContext(actionTypes, agents, project, recentTasks, config);
        });

        return callManagerAI(ctx, event.source, event.eventType, event.issueRef,
                event.repository, event.payload, event.id, evalNodeId,
                String.valueOf(event.id));
    }

    /**
     * Evaluates a stream event using the AI Manager. This method accepts
     * the new normalized event format from the event stream pipeline.
     *
     * <p>This is a transitional method — in the future, the prompt builder
     * will use the typed payload fields directly. For now, it maps stream
     * event fields to the same template variables used by the legacy
     * {@link EventEntity} evaluation.</p>
     *
     * @param streamEvent the stream event entity to evaluate
     * @return list of Manager decisions (may be empty if the Manager fails)
     */
    public List<ManagerDecision> evaluateStreamEvent(StreamEventEntity streamEvent) {
        LOG.infof("Manager evaluating stream event %s: %s [%s]",
                streamEvent.id, streamEvent.type, streamEvent.ref);

        // Load context — stream events do not carry event source labels,
        // so all manager-triggerable action types are included (no label filtering).
        EvalContext ctx = QuarkusTransaction.requiringNew().call(() -> {
            List<ActionTypeEntity> actionTypes = ActionTypeEntity.list("managerTriggerable", true);

            List<AgentEntity> agents = AgentEntity.listAll();

            ProjectEntity project = null;
            List<TaskEntity> recentTasks = Collections.emptyList();
            if (streamEvent.ref != null) {
                project = ProjectEntity.find("ref", streamEvent.ref).firstResult();
                if (project != null) {
                    recentTasks = TaskEntity.find(
                            "projectId = ?1 order by createdOn desc",
                            project.id).page(0, 10).list();
                }
            }

            ManagerConfigEntity config = ManagerConfigEntity.<ManagerConfigEntity>findAll()
                    .firstResult();
            return new EvalContext(actionTypes, agents, project, recentTasks, config);
        });

        // Map stream event fields to template variables:
        //   streamEvent.source  → source
        //   streamEvent.type    → eventType
        //   streamEvent.ref     → issueRef and repository (full URL)
        //   streamEvent.payload → payload
        return callManagerAI(ctx, streamEvent.source, streamEvent.type, streamEvent.ref,
                streamEvent.ref, streamEvent.payload, null, null,
                String.valueOf(streamEvent.id));
    }

    /**
     * Core AI evaluation logic shared by both {@link #evaluate(EventEntity, TraceContext)}
     * and {@link #evaluateStreamEvent(StreamEventEntity)}.
     *
     * @param ctx            pre-loaded evaluation context (action types, agents, project, config)
     * @param source         event source identifier (e.g. "github")
     * @param eventType      event type (e.g. "issue-created" or "issue.created")
     * @param issueRef       issue reference or URL
     * @param repository     repository identifier or URL
     * @param payload        raw event payload JSON
     * @param eventId        legacy event ID for activity/usage logging (null for stream events)
     * @param evalNodeId     trace node ID (null if tracing is not active)
     * @param eventIdForLog  string representation of the event ID for log messages
     * @return list of Manager decisions
     */
    private List<ManagerDecision> callManagerAI(
            EvalContext ctx,
            String source, String eventType, String issueRef, String repository, String payload,
            Long eventId, Long evalNodeId, String eventIdForLog) {

        // Build prompts from detached context (no transaction needed)
        String systemPrompt = ManagerPromptBuilder.DEFAULT_SYSTEM_PROMPT;
        String promptTemplate = ManagerPromptBuilder.DEFAULT_PROMPT_TEMPLATE;
        if (ctx.config() != null) {
            if (ctx.config().systemPrompt != null && !ctx.config().systemPrompt.isBlank()) {
                systemPrompt = ctx.config().systemPrompt;
            }
            if (ctx.config().promptTemplate != null && !ctx.config().promptTemplate.isBlank()) {
                promptTemplate = ctx.config().promptTemplate;
            }
        }

        String userPrompt = ManagerPromptBuilder.buildUserPrompt(
                promptTemplate, source, eventType, issueRef, repository, payload,
                ctx.actionTypes(), ctx.agents(), ctx.project(), ctx.recentTasks());
        String jsonSchema = ManagerPromptBuilder.getResponseJsonSchema();

        // Build agent request (prompt is part of the request object)
        AgentRequest agentRequest = AgentRequest.builder()
                .prompt(userPrompt)
                .systemPrompt(systemPrompt)
                .allowedTools(List.of("StructuredOutput"))
                .timeoutSeconds(timeoutSeconds)
                .maxSteps(maxTurns)
                .model(model.orElse(null))
                .build();

        try {
            AgentResult result = agentRegistry.getAgent(managerEngine.orElse(null))
                    .executeWithSchema(agentRequest, jsonSchema).join();
            String executionLog = result.executionLog();

            // Record AI usage for this Manager evaluation
            if (eventId != null) {
                try {
                    recordAiUsage(eventId, ctx.project() != null ? ctx.project().id : null,
                            result.costUsd(), result.inputTokens(), result.outputTokens(),
                            result.engine(), result.model());
                } catch (Exception e) {
                    LOG.warnf(e, "Failed to record AI usage for event %s", eventIdForLog);
                }
            }

            if (!result.success()) {
                LOG.errorf("Manager AI engine failed for event %s: %s",
                        eventIdForLog, result.output());
                if (eventId != null) {
                    logManagerActivity(eventId, "manager-error",
                            "Manager failed to evaluate event: " + result.output(),
                            executionLog);
                }
                completeEvalNode(evalNodeId, "failed", null);
                return Collections.emptyList();
            }

            List<ManagerDecision> decisions = parseDecisions(result.output());

            // Build summary of decisions for the activity log
            StringBuilder summary = new StringBuilder();
            for (ManagerDecision decision : decisions) {
                LOG.infof("Manager decision for event %s: %s (action: %s, confidence: %.2f) — %s",
                        eventIdForLog, decision.decision(), decision.actionType(),
                        decision.confidence(), decision.reasoning());
                if (!summary.isEmpty()) summary.append("; ");
                summary.append(decision.decision());
                if (decision.actionType() != null) {
                    summary.append("(").append(decision.actionType()).append(")");
                }
            }

            String summaryText = decisions.isEmpty()
                    ? "Manager returned no decisions for event " + eventIdForLog
                    : "Manager decisions for event " + eventIdForLog + ": " + summary;
            Long activityLogId = null;
            if (eventId != null) {
                activityLogId = logManagerActivity(eventId, "manager-evaluated",
                        summaryText, executionLog);
            }
            completeEvalNode(evalNodeId, "completed", activityLogId);

            return decisions;

        } catch (Exception e) {
            LOG.errorf(e, "Manager evaluation failed for event %s", eventIdForLog);
            if (eventId != null) {
                logManagerActivity(eventId, "manager-error",
                        "Manager evaluation error: " + e.getMessage(), null);
            }
            completeEvalNode(evalNodeId, "failed", null);
            return Collections.emptyList();
        }
    }

    /**
     * Filters action types by label compatibility with the event's source labels.
     * Action types with no labels are always included (backwards-compatible).
     * Action types with labels are included only if their labels are a subset
     * of the event source labels.
     *
     * @param actionTypes the candidate action types
     * @param eventSourceLabels the labels from the event's source
     * @return the filtered list
     */
    static List<ActionTypeEntity> filterByLabels(List<ActionTypeEntity> actionTypes,
                                                  List<String> eventSourceLabels) {
        if (actionTypes == null || actionTypes.isEmpty()) {
            return actionTypes;
        }
        Set<String> eventLabels = eventSourceLabels != null
                ? new HashSet<>(eventSourceLabels) : Collections.emptySet();
        return actionTypes.stream()
                .filter(at -> at.labels == null || at.labels.isEmpty()
                        || eventLabels.containsAll(at.labels))
                .toList();
    }

    /**
     * Holds context data loaded in a short transaction before the AI engine call.
     */
    private record EvalContext(
            List<ActionTypeEntity> actionTypes,
            List<AgentEntity> agents,
            ProjectEntity project,
            List<TaskEntity> recentTasks,
            ManagerConfigEntity config
    ) {}

    /**
     * Completes the manager-evaluation trace node (non-fatal).
     */
    private void completeEvalNode(Long evalNodeId, String status, Long activityLogId) {
        if (evalNodeId == null) {
            return;
        }
        try {
            if (activityLogId != null) {
                traceService.completeNode(evalNodeId, status, "activity-log", activityLogId);
            } else {
                traceService.completeNode(evalNodeId, status);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to complete manager-evaluation trace node %d", evalNodeId);
        }
    }

    /**
     * Checks whether a decision meets the confidence threshold.
     *
     * @param decision the decision to check
     * @return true if the confidence is at or above the threshold
     */
    public boolean meetsConfidenceThreshold(ManagerDecision decision) {
        return decision.confidence() >= confidenceThreshold;
    }

    /**
     * Parses the Manager's JSON output into a list of decisions.
     */
    List<ManagerDecision> parseDecisions(String jsonOutput) {
        if (jsonOutput == null || jsonOutput.isBlank()) {
            return Collections.emptyList();
        }

        try {
            JsonNode root = objectMapper.readTree(jsonOutput);

            JsonNode decisionsNode = root.path("decisions");
            if (decisionsNode.isMissingNode() || !decisionsNode.isArray()) {
                if (root.has("result")) {
                    String resultText = root.get("result").asText();
                    return parseDecisions(resultText);
                }
                LOG.warnf("Manager output missing 'decisions' array: %s",
                        jsonOutput.substring(0, Math.min(jsonOutput.length(), 200)));
                return Collections.emptyList();
            }

            List<ManagerDecision> decisions = new ArrayList<>();
            for (JsonNode node : decisionsNode) {
                String humanContext = node.has("humanContext")
                        ? node.get("humanContext").toString() : null;
                String outputSchema = node.has("outputSchema")
                        ? node.get("outputSchema").toString() : null;
                ManagerDecision decision = new ManagerDecision(
                        node.path("decision").asText("ignore"),
                        node.path("actionType").asText(null),
                        node.path("agentHint").asText(null),
                        node.path("inputContext").asText(null),
                        node.path("confidence").asDouble(0.5),
                        node.path("reasoning").asText(""),
                        humanContext,
                        outputSchema
                );
                decisions.add(decision);
            }

            return decisions;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse Manager output: %s",
                    jsonOutput.substring(0, Math.min(jsonOutput.length(), 200)));
            return Collections.emptyList();
        }
    }

    /**
     * Logs a manager activity entry with optional execution log details.
     *
     * @param eventId   the event ID
     * @param entryType the activity log entry type
     * @param summary   a brief summary
     * @param details   the full execution log (may be null)
     * @return the persisted activity log entry ID
     */
    Long logManagerActivity(Long eventId, String entryType, String summary, String details) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ActivityLogEntity log = new ActivityLogEntity();
            log.eventId = eventId;
            log.entryType = entryType;
            log.summary = summary != null && summary.length() > 1024
                    ? summary.substring(0, 1021) + "..."
                    : summary;
            log.details = details;
            log.createdOn = Instant.now();
            log.persist();
            return log.id;
        });
    }

    void recordAiUsage(Long eventId, Long projectId,
                        Double costUsd, Long inputTokens, Long outputTokens,
                        String resultEngine, String resultModel) {
        String resolvedEngine = resultEngine != null && !resultEngine.isBlank()
                ? resultEngine : managerEngine.orElse(agentRegistry.getDefaultAgentType());
        String resolvedModel = resultModel != null && !resultModel.isBlank()
                ? resultModel : model.orElse(null);
        QuarkusTransaction.requiringNew().run(() -> {
            AiUsageEntity usage = new AiUsageEntity();
            usage.invocationType = "manager";
            usage.eventId = eventId;
            usage.projectId = projectId;
            usage.actionType = "manager-evaluate";
            usage.engine = resolvedEngine;
            usage.model = resolvedModel;
            usage.costUsd = costUsd;
            usage.inputTokens = inputTokens;
            usage.outputTokens = outputTokens;
            usage.createdOn = Instant.now();
            usage.persist();
        });
    }
}
