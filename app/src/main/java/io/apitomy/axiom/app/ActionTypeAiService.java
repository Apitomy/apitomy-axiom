package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.ReportAiEditRequest;
import io.apitomy.axiom.api.beans.ReportAiEditResponse;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Service that invokes the AI engine to generate or update prompt templates
 * and allowed tools for actor-mode action types.
 */
@ApplicationScoped
public class ActionTypeAiService {

    private static final Logger LOG = Logger.getLogger(ActionTypeAiService.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AiEngine aiEngine;

    @Inject
    SystemSettingsService settingsService;

    private static final String SYSTEM_PROMPT = """
            You are an action type editor for Apicurio Axiom. Your job is to create \
            or update the prompt template and allowed tools for action types that are \
            executed by AI agents.

            An action type defines a kind of work that Axiom can perform. When the \
            Manager AI decides to execute an action type, the prompt template is sent \
            to an AI agent (Claude Code) that performs the work in a git repository.

            The prompt template supports these placeholders (substituted at runtime):
            - {{managerInput}} — context and instructions from the Manager AI
            - {{issueRef}} — the issue reference (e.g. "owner/repo#42")
            - {{repository}} — the repository name (e.g. "owner/repo")
            - {{projectName}} — the project name
            - {{actionType}} — the action type name

            The prompt should instruct the AI agent on:
            - What to do (analyze code, fix a bug, review changes, etc.)
            - How to approach the task (read files first, check tests, etc.)
            - What output to produce (summary, code changes, comments, etc.)

            Allowed tools control what the AI agent can use. Common tools include:
            - Read, Glob, Grep — for reading files
            - Edit, Write — for modifying files
            - Bash(git *) — git commands
            - Bash(gh issue *), Bash(gh pr *) — GitHub CLI commands
            - @Read-Only Tools — a toolset reference for read-only access
            - @Read + MCP Tools — read-only plus MCP tools for comments/labels
            - @Write Tools — full read/write access plus git and MCP tools
            - mcp__axiom-tools__post_github_comment — post a comment on an issue
            - mcp__axiom-tools__apply_github_labels — apply labels to an issue
            - mcp__axiom-tools__create_github_pr — create a pull request

            Return the prompt template, recommended allowed tools, and a brief \
            explanation of what you created or changed.
            """;

    private static final String RESPONSE_SCHEMA = """
            {"type":"object","required":["promptTemplate","allowedTools","explanation"],\
            "properties":{"promptTemplate":{"type":"string"},\
            "allowedTools":{"type":"array","items":{"type":"string"}},\
            "explanation":{"type":"string"}}}""";

    /**
     * Invokes the AI engine to generate or update a prompt template and tools.
     *
     * @param request the user's message and current action type context
     * @return the AI-generated prompt template, tools, and explanation
     */
    public ReportAiEditResponse editActionPrompt(ReportAiEditRequest request) {
        LOG.infof("AI action type edit request: %s",
                request.getMessage().substring(0, Math.min(request.getMessage().length(), 100)));

        StringBuilder userPrompt = new StringBuilder();

        if (request.getReportName() != null) {
            userPrompt.append("## Action Type\n\n");
            userPrompt.append("- **Name:** ").append(request.getReportName()).append("\n");
            if (request.getReportDescription() != null) {
                userPrompt.append("- **Description:** ").append(request.getReportDescription()).append("\n");
            }
            userPrompt.append("\n");
        }

        if (request.getCurrentPromptTemplate() != null
                && !request.getCurrentPromptTemplate().isBlank()) {
            userPrompt.append("## Current Prompt Template\n\n```markdown\n");
            userPrompt.append(request.getCurrentPromptTemplate());
            userPrompt.append("\n```\n\n");
        } else {
            userPrompt.append("No existing prompt template — create a new one.\n\n");
        }

        if (request.getCurrentAllowedTools() != null
                && !request.getCurrentAllowedTools().isEmpty()) {
            userPrompt.append("## Current Allowed Tools\n\n");
            for (String tool : request.getCurrentAllowedTools()) {
                userPrompt.append("- ").append(tool).append("\n");
            }
            userPrompt.append("\n");
        }

        userPrompt.append("## User Request\n\n");
        userPrompt.append(request.getMessage()).append("\n\n");
        userPrompt.append("Generate the prompt template and allowed tools as structured JSON output.");

        AiEngineConfig config = AiEngineConfig.builder()
                .systemPrompt(SYSTEM_PROMPT)
                .allowedTools(List.of("StructuredOutput"))
                .maxSteps(3)
                .timeoutSeconds(settingsService.getAssistantTimeoutSeconds())
                .build();

        try {
            AiEngineResult result = aiEngine.promptWithSchema(config, userPrompt.toString(),
                    RESPONSE_SCHEMA).join();

            recordAiUsage(result.costUsd(), result.inputTokens(), result.outputTokens());

            if (!result.success()) {
                LOG.errorf("Action type AI edit failed: %s", result.result());
                ReportAiEditResponse response = new ReportAiEditResponse();
                response.setExplanation("Sorry, I encountered an error: " + result.result());
                return response;
            }

            return parseResponse(result.result());

        } catch (Exception e) {
            LOG.errorf(e, "Action type AI edit failed");
            ReportAiEditResponse response = new ReportAiEditResponse();
            response.setExplanation("Sorry, an error occurred: " + e.getMessage());
            return response;
        }
    }

    private ReportAiEditResponse parseResponse(String jsonOutput) {
        try {
            JsonNode root = objectMapper.readTree(jsonOutput);

            ReportAiEditResponse response = new ReportAiEditResponse();
            response.setPromptTemplate(root.path("promptTemplate").asText(null));
            response.setExplanation(root.path("explanation").asText("Action type prompt generated."));

            JsonNode toolsNode = root.path("allowedTools");
            if (toolsNode.isArray()) {
                List<String> tools = new ArrayList<>();
                for (JsonNode t : toolsNode) {
                    tools.add(t.asText());
                }
                response.setAllowedTools(tools);
            } else if (toolsNode.isTextual()) {
                response.setAllowedTools(
                        java.util.Arrays.stream(toolsNode.asText().split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList());
            }

            return response;

        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse AI action type edit response: %s",
                    jsonOutput.substring(0, Math.min(jsonOutput.length(), 200)));
            ReportAiEditResponse response = new ReportAiEditResponse();
            response.setExplanation("I generated a response but couldn't parse it properly. "
                    + "Please try again with a more specific description.");
            return response;
        }
    }

    @Transactional
    void recordAiUsage(Double costUsd, Long inputTokens, Long outputTokens) {
        AiUsageEntity usage = new AiUsageEntity();
        usage.invocationType = "action-type-edit";
        usage.actionType = "action-type-ai-edit";
        usage.costUsd = costUsd;
        usage.inputTokens = inputTokens;
        usage.outputTokens = outputTokens;
        usage.createdOn = Instant.now();
        usage.persist();
    }
}
