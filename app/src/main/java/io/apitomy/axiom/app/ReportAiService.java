package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.actors.claudecode.ClaudeCodeCommandBuilder;
import io.apitomy.axiom.actors.claudecode.ClaudeCodeResult;
import io.apitomy.axiom.actors.claudecode.ClaudeCodeSubprocess;
import io.apitomy.axiom.actors.claudecode.ExecutionLogBuilder;
import io.apitomy.axiom.actors.spi.ActorContext;
import io.apitomy.axiom.api.beans.ReportAiEditRequest;
import io.apitomy.axiom.api.beans.ReportAiEditResponse;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.core.services.SystemSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service that invokes Claude Code to generate or update report definition
 * prompt templates and allowed tools based on natural language instructions.
 */
@ApplicationScoped
public class ReportAiService {

    private static final Logger LOG = Logger.getLogger(ReportAiService.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SystemSettingsService settingsService;

    private static final String SYSTEM_PROMPT = """
            You are a report definition editor for Apicurio Axiom. Your job is to create \
            or update the prompt template and allowed tools for scheduled reports.

            A report definition controls how an AI agent generates periodic reports about \
            GitHub repository activity. The prompt template is sent to a Claude Code agent \
            that has access to configured tools.

            The prompt template supports these placeholders (substituted at runtime):
            - {{repositories}} — comma-separated list of repositories to scan
            - {{timeRangeStart}} — start of the report time window (ISO date)
            - {{timeRangeEnd}} — end of the report time window (ISO date)
            - {{timeWindow}} — human-readable time window (e.g. "last 24 hours")

            The prompt should instruct the AI agent on:
            - What data to gather (issues, PRs, commits, comments, etc.)
            - How to structure the report (sections, summaries, tables)
            - What format to use (markdown)
            - Any specific analysis to perform

            Allowed tools control what the AI agent can use when generating the report. \
            Common report tools include:
            - Read, Glob, Grep — for reading files
            - Bash(gh issue *), Bash(gh pr *), Bash(gh api *) — GitHub CLI commands
            - Bash(git log *), Bash(git diff *) — git commands
            - Bash(curl *), Bash(jq *) — API calls and JSON processing
            - @Report Tools — a toolset reference that includes standard report tools
            - mcp__axiom-tools__list_github_issues — MCP tool for listing issues
            - mcp__axiom-tools__list_github_prs — MCP tool for listing PRs

            Return the prompt template, recommended allowed tools, and a brief explanation \
            of what you created or changed.
            """;

    private static final String RESPONSE_SCHEMA = """
            {"type":"object","required":["promptTemplate","allowedTools","explanation"],\
            "properties":{"promptTemplate":{"type":"string"},\
            "allowedTools":{"type":"array","items":{"type":"string"}},\
            "explanation":{"type":"string"}}}""";

    /**
     * Invokes Claude Code to generate or update a report prompt template.
     *
     * @param request the user's message and current report context
     * @return the AI-generated prompt template, tools, and explanation
     */
    public ReportAiEditResponse editReportPrompt(ReportAiEditRequest request) {
        LOG.infof("AI report edit request: %s",
                request.getMessage().substring(0, Math.min(request.getMessage().length(), 100)));

        StringBuilder userPrompt = new StringBuilder();

        if (request.getReportName() != null) {
            userPrompt.append("## Report Definition\n\n");
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

        ExecutionLogBuilder logBuilder = new ExecutionLogBuilder();
        logBuilder.header(0, "report-ai-edit", Instant.now());
        logBuilder.systemPrompt(SYSTEM_PROMPT);
        logBuilder.prompt(userPrompt.toString());

        ActorContext context = ActorContext.builder()
                .systemPrompt(SYSTEM_PROMPT)
                .allowedTools(List.of("StructuredOutput"))
                .build();

        ClaudeCodeCommandBuilder cmdBuilder = ClaudeCodeCommandBuilder
                .fromContext(userPrompt.toString(), context)
                .streamJson(true)
                .maxTurns(3);

        String managerModel = settingsService.getManagerModel();
        if (managerModel != null) {
            cmdBuilder.model(managerModel);
        }

        List<String> command = cmdBuilder.build();
        command.add("--json-schema");
        command.add(RESPONSE_SCHEMA);

        ClaudeCodeSubprocess subprocess = new ClaudeCodeSubprocess(
                command, null, Map.of(),
                Duration.ofSeconds(60), null, logBuilder
        );

        try {
            ClaudeCodeResult result = subprocess.execute().join();

            recordAiUsage(result.totalCostUsd(), result.inputTokens(), result.outputTokens());

            if (!result.isSuccess()) {
                LOG.errorf("Report AI edit failed (exit %d): %s",
                        result.exitCode(), result.result());
                ReportAiEditResponse response = new ReportAiEditResponse();
                response.setExplanation("Sorry, I encountered an error: " + result.result());
                return response;
            }

            return parseResponse(result.result());

        } catch (Exception e) {
            LOG.errorf(e, "Report AI edit failed");
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
            response.setExplanation(root.path("explanation").asText("Report prompt generated."));

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
            LOG.warnf(e, "Failed to parse AI report edit response: %s",
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
        usage.invocationType = "report-edit";
        usage.actionType = "report-ai-edit";
        usage.costUsd = costUsd;
        usage.inputTokens = inputTokens;
        usage.outputTokens = outputTokens;
        usage.createdOn = Instant.now();
        usage.persist();
    }
}
