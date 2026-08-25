package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.NewToolDefinition;
import io.apitomy.axiom.api.beans.ToolAiEditRequest;
import io.apitomy.axiom.api.beans.ToolAiEditResponse;
import io.apitomy.axiom.api.beans.ToolParameter;
import io.apitomy.axiom.core.entities.AiUsageEntity;
import io.apitomy.axiom.agents.spi.AgentRegistry;
import io.apitomy.axiom.agents.spi.AgentRequest;
import io.apitomy.axiom.agents.spi.AgentResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service that invokes the AI engine to generate or update tool definitions
 * based on natural language instructions from the user.
 */
@ApplicationScoped
public class ToolAiService {

    private static final Logger LOG = Logger.getLogger(ToolAiService.class);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AgentRegistry agentRegistry;

    @ConfigProperty(name = "axiom.manager.model")
    Optional<String> model;

    @ConfigProperty(name = "axiom.ai-assistant.timeout-seconds", defaultValue = "300")
    int assistantTimeoutSeconds;

    private static final String SYSTEM_PROMPT = """
            You are a tool definition editor for Apicurio Axiom. Your job is to create \
            or update tool definitions based on the user's instructions.

            A tool definition has these fields:
            - **name**: A short snake_case identifier (e.g. "list_github_issues")
            - **description**: A clear description of what the tool does
            - **parameters**: An array of parameter objects, each with:
              - name: parameter name (snake_case)
              - type: "string", "number", or "boolean"
              - description: what the parameter is for
              - required: true or false
            - **scriptTemplate**: A bash script template that uses {{param_name}} for \
              parameter substitution. For string parameters that may contain special \
              characters, use {{param_name_file}} which writes the value to a temp file \
              and substitutes the file path.

            The script template supports multi-line bash scripts with variables, pipes, \
            conditionals, and loops. Common tools include gh CLI for GitHub operations, \
            curl for API calls, and standard Unix utilities.

            Return the complete tool definition as structured JSON output, plus a brief \
            explanation of what you created or changed.
            """;

    private static final String RESPONSE_SCHEMA = """
            {"type":"object","required":["name","description","parameters","scriptTemplate","explanation"],\
            "properties":{"name":{"type":"string"},"description":{"type":"string"},\
            "parameters":{"type":"array","items":{"type":"object","required":["name","type"],\
            "properties":{"name":{"type":"string"},"type":{"type":"string","enum":["string","number","boolean"]},\
            "description":{"type":"string"},"required":{"type":"boolean"}}}},\
            "scriptTemplate":{"type":"string"},"explanation":{"type":"string"}}}""";

    /**
     * Invokes Claude Code to generate or update a tool definition.
     *
     * @param request the user's message and current tool state
     * @return the AI-generated tool definition and explanation
     */
    public ToolAiEditResponse editTool(ToolAiEditRequest request) {
        LOG.infof("AI tool edit request: %s",
                request.getMessage().substring(0, Math.min(request.getMessage().length(), 100)));

        // Build the user prompt with current tool context
        StringBuilder userPrompt = new StringBuilder();

        if (request.getCurrentTool() != null) {
            NewToolDefinition current = request.getCurrentTool();
            userPrompt.append("## Current Tool Definition\n\n");
            userPrompt.append("- **Name:** ").append(current.getName() != null ? current.getName() : "(not set)").append("\n");
            userPrompt.append("- **Description:** ").append(current.getDescription() != null ? current.getDescription() : "(not set)").append("\n");

            if (current.getParameters() != null && !current.getParameters().isEmpty()) {
                userPrompt.append("- **Parameters:**\n");
                for (ToolParameter p : current.getParameters()) {
                    userPrompt.append("  - ").append(p.getName()).append(" (").append(p.getType().value()).append(")");
                    if (p.getDescription() != null) userPrompt.append(": ").append(p.getDescription());
                    if (p.getRequired() != null && p.getRequired()) userPrompt.append(" [required]");
                    userPrompt.append("\n");
                }
            } else {
                userPrompt.append("- **Parameters:** (none)\n");
            }

            userPrompt.append("- **Script Template:**\n```bash\n");
            userPrompt.append(current.getScriptTemplate() != null ? current.getScriptTemplate() : "(not set)");
            userPrompt.append("\n```\n\n");
        } else {
            userPrompt.append("No existing tool definition — create a new one.\n\n");
        }

        userPrompt.append("## User Request\n\n");
        userPrompt.append(request.getMessage()).append("\n\n");
        userPrompt.append("Generate the complete tool definition as structured JSON output.");

        AgentRequest agentRequest = AgentRequest.builder()
                .systemPrompt(SYSTEM_PROMPT)
                .prompt(userPrompt.toString())
                .allowedTools(List.of("StructuredOutput"))
                .timeoutSeconds(assistantTimeoutSeconds)
                .maxSteps(3)
                .model(model.orElse(null))
                .build();

        try {
            AgentResult result = agentRegistry.getDefaultAgent().executeWithSchema(agentRequest,
                    RESPONSE_SCHEMA).join();

            // Record AI usage
            recordAiUsage(result.costUsd(), result.inputTokens(), result.outputTokens());

            if (!result.success()) {
                LOG.errorf("Tool AI edit failed: %s", result.output());
                ToolAiEditResponse response = new ToolAiEditResponse();
                response.setExplanation("Sorry, I encountered an error: " + result.output());
                return response;
            }

            return parseResponse(result.output());

        } catch (Exception e) {
            LOG.errorf(e, "Tool AI edit failed");
            ToolAiEditResponse response = new ToolAiEditResponse();
            response.setExplanation("Sorry, an error occurred: " + e.getMessage());
            return response;
        }
    }

    /**
     * Parses the Claude Code structured output into a ToolAiEditResponse.
     */
    private ToolAiEditResponse parseResponse(String jsonOutput) {
        try {
            JsonNode root = objectMapper.readTree(jsonOutput);

            NewToolDefinition tool = new NewToolDefinition();
            tool.setName(root.path("name").asText(null));
            tool.setDescription(root.path("description").asText(null));
            tool.setScriptTemplate(root.path("scriptTemplate").asText(null));

            // Parse parameters
            JsonNode paramsNode = root.path("parameters");
            if (paramsNode.isArray()) {
                List<ToolParameter> params = new ArrayList<>();
                for (JsonNode pNode : paramsNode) {
                    ToolParameter param = new ToolParameter();
                    param.setName(pNode.path("name").asText());
                    param.setType(ToolParameter.Type.fromValue(pNode.path("type").asText("string")));
                    param.setDescription(pNode.path("description").asText(null));
                    param.setRequired(pNode.path("required").asBoolean(false));
                    params.add(param);
                }
                tool.setParameters(params);
            }

            String explanation = root.path("explanation").asText("Tool definition generated.");

            ToolAiEditResponse response = new ToolAiEditResponse();
            response.setTool(tool);
            response.setExplanation(explanation);
            return response;

        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse AI tool edit response: %s",
                    jsonOutput.substring(0, Math.min(jsonOutput.length(), 200)));
            ToolAiEditResponse response = new ToolAiEditResponse();
            response.setExplanation("I generated a response but couldn't parse it properly. "
                    + "Please try again with a more specific description.");
            return response;
        }
    }

    @Transactional
    void recordAiUsage(Double costUsd, Long inputTokens, Long outputTokens) {
        AiUsageEntity usage = new AiUsageEntity();
        usage.invocationType = "tool-edit";
        usage.actionType = "tool-ai-edit";
        usage.costUsd = costUsd;
        usage.inputTokens = inputTokens;
        usage.outputTokens = outputTokens;
        usage.createdOn = Instant.now();
        usage.persist();
    }
}
