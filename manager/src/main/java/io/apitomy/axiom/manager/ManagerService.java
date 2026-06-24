package io.apitomy.axiom.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.core.tracing.TraceContext;
import io.apitomy.axiom.core.tracing.TraceService;
import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.ActivityLogEntity;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.entities.ActorEntity;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.ManagerConfigEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;
import io.apitomy.axiom.engine.spi.AiEngine;
import io.apitomy.axiom.engine.spi.AiEngineConfig;
import io.apitomy.axiom.engine.spi.AiEngineResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Service that invokes the AI Manager to evaluate events and produce decisions.
 * Uses the pluggable {@link AiEngine} SPI to invoke the configured AI engine
 * with a structured JSON schema for decision output.
 */
@ApplicationScoped
public class ManagerService {

    private static final Logger LOG = Logger.getLogger(ManagerService.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AiEngine aiEngine;

    @Inject
    TraceService traceService;

    @ConfigProperty(name = "axiom.manager.confidence-threshold", defaultValue = "0.7")
    double confidenceThreshold;

    @ConfigProperty(name = "axiom.manager.timeout-seconds", defaultValue = "120")
    int timeoutSeconds;

    @ConfigProperty(name = "axiom.manager.max-turns", defaultValue = "5")
    int maxTurns;

    @ConfigProperty(name = "axiom.manager.model")
    Optional<String> model;

    /**
     * Evaluates an event and returns the Manager's decisions.
     *
     * @param event    the event to evaluate
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

        // Load context
        List<ActionTypeEntity> actionTypes = ActionTypeEntity.list("managerTriggerable", true);
        List<ActorEntity> actors = ActorEntity.listAll();

        // Find existing project for this issue
        ProjectEntity project = null;
        List<TaskEntity> recentTasks = Collections.emptyList();
        if (event.issueRef != null) {
            project = ProjectEntity.find("issueRef", event.issueRef).firstResult();
            if (project != null) {
                recentTasks = TaskEntity.find(
                        "projectId = ?1 order by createdOn desc",
                        project.id).page(0, 10).list();
            }
        }

        // Load manager configuration (system prompt + prompt template)
        String systemPrompt = ManagerPromptBuilder.DEFAULT_SYSTEM_PROMPT;
        String promptTemplate = ManagerPromptBuilder.DEFAULT_PROMPT_TEMPLATE;
        ManagerConfigEntity config = ManagerConfigEntity.<ManagerConfigEntity>findAll()
                .firstResult();
        if (config != null) {
            if (config.systemPrompt != null && !config.systemPrompt.isBlank()) {
                systemPrompt = config.systemPrompt;
            }
            if (config.promptTemplate != null && !config.promptTemplate.isBlank()) {
                promptTemplate = config.promptTemplate;
            }
        }

        // Build user prompt from template with placeholder substitution
        String userPrompt = ManagerPromptBuilder.buildUserPrompt(
                promptTemplate, event, actionTypes, actors, project, recentTasks);
        String jsonSchema = ManagerPromptBuilder.getResponseJsonSchema();

        // Build engine-agnostic config
        AiEngineConfig engineConfig = AiEngineConfig.builder()
                .systemPrompt(systemPrompt)
                .allowedTools(List.of("StructuredOutput"))
                .timeoutSeconds(timeoutSeconds)
                .maxSteps(maxTurns)
                .model(model.orElse(null))
                .build();

        try {
            AiEngineResult result = aiEngine.promptWithSchema(engineConfig, userPrompt, jsonSchema).join();
            String executionLog = result.executionLog();

            // Record AI usage for this Manager evaluation
            try {
                recordAiUsage(event.id, project != null ? project.id : null,
                        result.costUsd(), result.inputTokens(), result.outputTokens());
            } catch (Exception e) {
                LOG.warnf(e, "Failed to record AI usage for event %d", event.id);
            }

            if (!result.success()) {
                LOG.errorf("Manager AI engine failed: %s", result.result());
                logManagerActivity(event.id, "manager-error",
                        "Manager failed to evaluate event: " + result.result(),
                        executionLog);
                completeEvalNode(evalNodeId, "failed", null);
                return Collections.emptyList();
            }

            List<ManagerDecision> decisions = parseDecisions(result.result());

            // Build summary of decisions for the activity log
            StringBuilder summary = new StringBuilder();
            for (ManagerDecision decision : decisions) {
                LOG.infof("Manager decision for event %d: %s (action: %s, confidence: %.2f) — %s",
                        event.id, decision.decision(), decision.actionType(),
                        decision.confidence(), decision.reasoning());
                if (!summary.isEmpty()) summary.append("; ");
                summary.append(decision.decision());
                if (decision.actionType() != null) {
                    summary.append("(").append(decision.actionType()).append(")");
                }
            }

            String summaryText = decisions.isEmpty()
                    ? "Manager returned no decisions for event " + event.id
                    : "Manager decisions for event " + event.id + ": " + summary;
            Long activityLogId = logManagerActivity(event.id, "manager-evaluated",
                    summaryText, executionLog);
            completeEvalNode(evalNodeId, "completed", activityLogId);

            return decisions;

        } catch (Exception e) {
            LOG.errorf(e, "Manager evaluation failed for event %d", event.id);
            logManagerActivity(event.id, "manager-error",
                    "Manager evaluation error: " + e.getMessage(), null);
            completeEvalNode(evalNodeId, "failed", null);
            return Collections.emptyList();
        }
    }

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
                ManagerDecision decision = new ManagerDecision(
                        node.path("decision").asText("ignore"),
                        node.path("actionType").asText(null),
                        node.path("actorHint").asText(null),
                        node.path("inputContext").asText(null),
                        node.path("confidence").asDouble(0.5),
                        node.path("reasoning").asText("")
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
                        Double costUsd, Long inputTokens, Long outputTokens) {
        QuarkusTransaction.requiringNew().run(() -> {
            AiUsageEntity usage = new AiUsageEntity();
            usage.invocationType = "manager";
            usage.eventId = eventId;
            usage.projectId = projectId;
            usage.actionType = "manager-evaluate";
            usage.costUsd = costUsd;
            usage.inputTokens = inputTokens;
            usage.outputTokens = outputTokens;
            usage.createdOn = Instant.now();
            usage.persist();
        });
    }
}
