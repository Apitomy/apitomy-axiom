package io.apitomy.axiom.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.AgentEntity;
import io.apitomy.axiom.core.entities.StreamEventEntity;
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
import java.util.UUID;

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
     * Evaluates a stream event using the AI Manager without trace correlation.
     * Used by the debug endpoint ({@code POST /manager/evaluate/{eventId}}).
     *
     * @param streamEvent the stream event entity to evaluate
     * @return the evaluation result; {@link ManagerEvaluationResult#failed()} is true if the
     *         AI call failed or its output could not be parsed
     */
    public ManagerEvaluationResult evaluateStreamEvent(StreamEventEntity streamEvent) {
        return evaluateStreamEvent(streamEvent, null);
    }

    /**
     * Evaluates a stream event using the AI Manager. The {@code manager-evaluated} /
     * {@code manager-error} activity row and the {@code ai_usage} row are linked to the
     * event and to the given trace.
     *
     * <p>Stream event fields are mapped to the prompt template variables (source, event
     * type, ref as issue and repository, payload).</p>
     *
     * @param streamEvent the stream event entity to evaluate
     * @param traceId     the trace this evaluation belongs to (nullable)
     * @return the evaluation result; {@link ManagerEvaluationResult#failed()} is true if the
     *         AI call failed or its output could not be parsed
     */
    public ManagerEvaluationResult evaluateStreamEvent(StreamEventEntity streamEvent, UUID traceId) {
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
                streamEvent.ref, streamEvent.payload, streamEvent.id, traceId);
    }

    /**
     * Core AI evaluation logic used by {@link #evaluateStreamEvent(StreamEventEntity, UUID)}.
     *
     * @param ctx        pre-loaded evaluation context (action types, agents, project, config)
     * @param source     event source identifier (e.g. "github")
     * @param eventType  event type (e.g. "issue.created")
     * @param issueRef   issue reference or URL
     * @param repository repository identifier or URL
     * @param payload    raw event payload JSON
     * @param eventId    stream event ID for activity/usage logging (nullable)
     * @param traceId    trace ID for activity/usage correlation (nullable)
     * @return the evaluation result
     */
    private ManagerEvaluationResult callManagerAI(
            EvalContext ctx,
            String source, String eventType, String issueRef, String repository, String payload,
            UUID eventId, UUID traceId) {
        String eventIdForLog = String.valueOf(eventId);

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
            try {
                recordAiUsage(eventId, traceId, ctx.project() != null ? ctx.project().id : null,
                        result.costUsd(), result.inputTokens(), result.outputTokens(),
                        result.engine(), result.model());
            } catch (Exception e) {
                LOG.warnf(e, "Failed to record AI usage for event %s", eventIdForLog);
            }

            if (!result.success()) {
                String reason = result.errorMessage() != null ? result.errorMessage() : result.output();
                LOG.errorf("Manager AI engine failed for event %s: %s", eventIdForLog, reason);
                Long logId = logManagerActivity(eventId, traceId, "manager-error",
                        "Manager failed to evaluate event: " + reason, executionLog);
                return ManagerEvaluationResult.failure("Manager AI engine failed: " + reason, logId);
            }

            List<ManagerDecision> decisions;
            try {
                decisions = parseDecisionsStrict(result.output());
            } catch (ManagerOutputException e) {
                String error = "Manager output could not be parsed: " + e.getMessage();
                LOG.errorf("%s (event %s)", error, eventIdForLog);
                Long logId = logManagerActivity(eventId, traceId, "manager-error", error,
                        executionLog);
                return ManagerEvaluationResult.failure(error, logId);
            }

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
            Long activityLogId = logManagerActivity(eventId, traceId, "manager-evaluated",
                    summaryText, executionLog);
            return ManagerEvaluationResult.success(decisions, activityLogId);

        } catch (Exception e) {
            LOG.errorf(e, "Manager evaluation failed for event %s", eventIdForLog);
            String error = "Manager evaluation error: " + e.getMessage();
            Long logId = null;
            try {
                logId = logManagerActivity(eventId, traceId, "manager-error", error, null);
            } catch (Exception logError) {
                LOG.warnf(logError, "Failed to log Manager error for event %s", eventIdForLog);
            }
            return ManagerEvaluationResult.failure(error, logId);
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
     * Checks whether a decision meets the confidence threshold.
     *
     * @param decision the decision to check
     * @return true if the confidence is at or above the threshold
     */
    public boolean meetsConfidenceThreshold(ManagerDecision decision) {
        return decision.confidence() >= confidenceThreshold;
    }

    /**
     * Parses the Manager's JSON output into a list of decisions, returning an empty list
     * if the output is missing or malformed.
     *
     * @param jsonOutput the raw Manager output
     * @return the decisions (empty on malformed output)
     */
    List<ManagerDecision> parseDecisions(String jsonOutput) {
        try {
            return parseDecisionsStrict(jsonOutput);
        } catch (ManagerOutputException e) {
            LOG.warnf("Ignoring malformed Manager output: %s", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Parses the Manager's JSON output into a list of decisions. An explicit empty
     * {@code decisions} array is a valid "no decisions" result; anything that cannot be
     * read as a decision list is rejected.
     *
     * @param jsonOutput the raw Manager output
     * @return the decisions (possibly empty)
     * @throws ManagerOutputException if the output is blank, not JSON, or lacks a
     *                                {@code decisions} array
     */
    List<ManagerDecision> parseDecisionsStrict(String jsonOutput) {
        if (jsonOutput == null || jsonOutput.isBlank()) {
            throw new ManagerOutputException("Manager returned no output");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(jsonOutput);
        } catch (Exception e) {
            throw new ManagerOutputException("Manager output is not valid JSON: "
                    + abbreviate(jsonOutput), e);
        }

        JsonNode decisionsNode = root.path("decisions");
        if (decisionsNode.isMissingNode() || !decisionsNode.isArray()) {
            if (root.has("result")) {
                return parseDecisionsStrict(root.get("result").asText());
            }
            throw new ManagerOutputException("Manager output missing 'decisions' array: "
                    + abbreviate(jsonOutput));
        }

        List<ManagerDecision> decisions = new ArrayList<>();
        for (JsonNode node : decisionsNode) {
            String humanContext = node.has("humanContext")
                    ? node.get("humanContext").toString() : null;
            String outputSchema = node.has("outputSchema")
                    ? node.get("outputSchema").toString() : null;
            decisions.add(new ManagerDecision(
                    node.path("decision").asText("ignore"),
                    node.path("actionType").asText(null),
                    node.path("agentHint").asText(null),
                    node.path("inputContext").asText(null),
                    node.path("confidence").asDouble(0.5),
                    node.path("reasoning").asText(""),
                    humanContext,
                    outputSchema
            ));
        }
        return decisions;
    }

    private static String abbreviate(String text) {
        return text.substring(0, Math.min(text.length(), 200));
    }

    /**
     * Logs a manager activity entry with optional execution log details.
     *
     * @param eventId   the stream event ID (nullable)
     * @param traceId   the trace ID for correlation (nullable)
     * @param entryType the activity log entry type
     * @param summary   a brief summary
     * @param details   the full execution log (may be null)
     * @return the persisted activity log entry ID
     */
    Long logManagerActivity(UUID eventId, UUID traceId, String entryType, String summary,
                            String details) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ActivityLogEntity log = new ActivityLogEntity();
            log.eventId = eventId;
            log.traceId = traceId;
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

    void recordAiUsage(UUID eventId, UUID traceId, Long projectId,
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
            usage.traceId = traceId;
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
