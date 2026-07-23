package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.ScriptAiEditRequest;
import io.apitomy.axiom.api.beans.ScriptAiEditResponse;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.engine.spi.AiEngine;
import io.apitomy.axiom.engine.spi.AiEngineConfig;
import io.apitomy.axiom.engine.spi.AiEngineResult;
import io.apitomy.axiom.core.services.SystemSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * Service that invokes the AI engine to generate or update bash script
 * templates for script-mode action types.
 */
@ApplicationScoped
public class ScriptAiService {

    private static final Logger LOG = Logger.getLogger(ScriptAiService.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AiEngine aiEngine;

    @Inject
    SystemSettingsService settingsService;

    private static final String SYSTEM_PROMPT = """
            You are a script editor for Apicurio Axiom. Your job is to create or update \
            bash script templates for action types that are executed when triggered by the \
            Axiom Manager.

            Scripts are bash scripts that run when the Manager decides to execute a \
            script action type. They support these placeholders which are substituted \
            at runtime:
            - {{projectId}} — the Axiom project ID
            - {{eventId}} — the event ID that triggered this action
            - {{taskId}} — the task ID for this execution
            - {{issueRef}} — the issue reference (e.g. "owner/repo#42")
            - {{repository}} — the repository name (e.g. "owner/repo")
            - {{projectName}} — the project name
            - {{managerInput}} — context/instructions from the Manager
            - {{apiBaseUrl}} — the Axiom REST API base URL (e.g. "http://localhost:9090/api/v1")

            Environment variables are also available:
            - AXIOM_API_URL — same as {{apiBaseUrl}}
            - AXIOM_PROJECT_ID — same as {{projectId}}
            - AXIOM_TASK_ID — same as {{taskId}}

            Common Axiom REST API endpoints the script can call:
            - GET {{apiBaseUrl}}/projects/{{projectId}} — get project details
            - PUT {{apiBaseUrl}}/projects/{{projectId}} — update project (status, description, etc.)
            - POST {{apiBaseUrl}}/projects/{{projectId}}/close — close/complete a project
            - POST {{apiBaseUrl}}/projects/{{projectId}}/reopen — reopen a closed project
            - POST {{apiBaseUrl}}/projects/{{projectId}}/tasks — create a task
            - GET {{apiBaseUrl}}/projects/{{projectId}}/thread — get project thread

            Common CLI tools available: curl, jq, gh (GitHub CLI), git, standard Unix utilities.

            Return the script as structured JSON output, plus a brief explanation of what \
            you created or changed.
            """;

    private static final String RESPONSE_SCHEMA = """
            {"type":"object","required":["script","explanation"],\
            "properties":{"script":{"type":"string"},\
            "explanation":{"type":"string"}}}""";

    /**
     * Invokes Claude Code to generate or update a script template.
     *
     * @param request the user's message and current script context
     * @return the AI-generated script and explanation
     */
    public ScriptAiEditResponse editScript(ScriptAiEditRequest request) {
        LOG.infof("AI script edit request: %s",
                request.getMessage().substring(0, Math.min(request.getMessage().length(), 100)));

        StringBuilder userPrompt = new StringBuilder();

        if (request.getActionTypeName() != null) {
            userPrompt.append("## Action Type\n\n");
            userPrompt.append("- **Name:** ").append(request.getActionTypeName()).append("\n");
            if (request.getActionTypeDescription() != null) {
                userPrompt.append("- **Description:** ").append(request.getActionTypeDescription()).append("\n");
            }
            userPrompt.append("\n");
        }

        if (request.getCurrentScript() != null && !request.getCurrentScript().isBlank()) {
            userPrompt.append("## Current Script\n\n```bash\n");
            userPrompt.append(request.getCurrentScript());
            userPrompt.append("\n```\n\n");
        } else {
            userPrompt.append("No existing script — create a new one.\n\n");
        }

        userPrompt.append("## User Request\n\n");
        userPrompt.append(request.getMessage()).append("\n\n");
        userPrompt.append("Generate the script as structured JSON output.");

        AiEngineConfig engineConfig = AiEngineConfig.builder()
                .systemPrompt(SYSTEM_PROMPT)
                .allowedTools(List.of("StructuredOutput"))
                .timeoutSeconds(settingsService.getAssistantTimeoutSeconds())
                .maxSteps(3)
                .model(settingsService.getManagerModel())
                .build();

        try {
            AiEngineResult result = aiEngine.promptWithSchema(engineConfig, userPrompt.toString(),
                    RESPONSE_SCHEMA).join();

            recordAiUsage(result.costUsd(), result.inputTokens(), result.outputTokens());

            if (!result.success()) {
                LOG.errorf("Script AI edit failed: %s", result.result());
                ScriptAiEditResponse response = new ScriptAiEditResponse();
                response.setExplanation("Sorry, I encountered an error: " + result.result());
                return response;
            }

            return parseResponse(result.result());

        } catch (Exception e) {
            LOG.errorf(e, "Script AI edit failed");
            ScriptAiEditResponse response = new ScriptAiEditResponse();
            response.setExplanation("Sorry, an error occurred: " + e.getMessage());
            return response;
        }
    }

    private ScriptAiEditResponse parseResponse(String jsonOutput) {
        try {
            JsonNode root = objectMapper.readTree(jsonOutput);

            ScriptAiEditResponse response = new ScriptAiEditResponse();
            response.setScript(root.path("script").asText(null));
            response.setExplanation(root.path("explanation").asText("Script generated."));
            return response;

        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse AI script edit response: %s",
                    jsonOutput.substring(0, Math.min(jsonOutput.length(), 200)));
            ScriptAiEditResponse response = new ScriptAiEditResponse();
            response.setExplanation("I generated a response but couldn't parse it properly. "
                    + "Please try again with a more specific description.");
            return response;
        }
    }

    @Transactional
    void recordAiUsage(Double costUsd, Long inputTokens, Long outputTokens) {
        AiUsageEntity usage = new AiUsageEntity();
        usage.invocationType = "script-edit";
        usage.actionType = "script-ai-edit";
        usage.costUsd = costUsd;
        usage.inputTokens = inputTokens;
        usage.outputTokens = outputTokens;
        usage.createdOn = Instant.now();
        usage.persist();
    }
}
