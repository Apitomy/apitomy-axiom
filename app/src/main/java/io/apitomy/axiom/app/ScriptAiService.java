package io.apitomy.axiom.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apitomy.axiom.api.beans.ScriptAiEditRequest;
import io.apitomy.axiom.api.beans.ScriptAiEditResponse;
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
import java.util.List;
import java.util.Optional;

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
    AgentRegistry agentRegistry;

    @ConfigProperty(name = "axiom.helper-services.engine")
    Optional<String> helperEngine;

    @ConfigProperty(name = "axiom.helper-services.model")
    Optional<String> model;

    @ConfigProperty(name = "axiom.ai-assistant.timeout-seconds", defaultValue = "300")
    int assistantTimeoutSeconds;

    private static final String SYSTEM_PROMPT = """
            You are a script editor for Apicurio Axiom. Your job is to create or update \
            bash script templates for script-mode action types.

            Scripts are bash scripts that run when a script action type is executed. The \
            only placeholder is {{inputs.<name>}}, which must refer to an input declared on \
            the action type. Declare an input for everything the script operates on (subject \
            id, issue reference, repository); workflow nodes map values into them.

            Input values are treated as literal data: each {{inputs.<name>}} is passed to the \
            script as an environment variable and expands to a quoted shell reference, so \
            values containing shell metacharacters are never executed. Use a placeholder \
            wherever you would use a shell variable — do NOT wrap it in extra quotes. An \
            optional input with no value renders empty.

            These environment variables are always available:
            - AXIOM_API_URL — the Axiom REST API base URL (e.g. "http://localhost:9090/api/v1")
            - AXIOM_TASK_ID — the task ID for this execution
            - AXIOM_WORK_DIR — the directory the script runs in (the subject workspace)

            Common Axiom REST API endpoints the script can call (declare a subjectId input):
            - GET $AXIOM_API_URL/subjects/{{inputs.subjectId}} — get subject details
            - PUT $AXIOM_API_URL/subjects/{{inputs.subjectId}} — update subject (name, description, labels, etc.)
            - GET $AXIOM_API_URL/subjects/{{inputs.subjectId}}/thread — get subject thread

            Common CLI tools available: curl, jq, gh (GitHub CLI), git, standard Unix utilities.

            Returning outputs: when the action type declares outputs, the script must print \
            exactly one JSON object with those keys to stdout as the last thing it prints, \
            e.g. jq -n --arg title "$TITLE" '{title: $title}'. Earlier stdout lines are \
            treated as logs; send diagnostics to stderr (>&2). Never write outputs to a \
            file; Axiom only reads stdout.

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

        AgentRequest agentRequest = AgentRequest.builder()
                .systemPrompt(SYSTEM_PROMPT)
                .prompt(userPrompt.toString())
                .allowedTools(List.of("StructuredOutput"))
                .timeoutSeconds(assistantTimeoutSeconds)
                .maxSteps(3)
                .model(model.orElse(null))
                .build();

        try {
            AgentResult result = agentRegistry.getAgent(helperEngine.orElse(null))
                    .executeWithSchema(agentRequest, RESPONSE_SCHEMA).join();

            recordAiUsage(result.costUsd(), result.inputTokens(), result.outputTokens(),
                    result.engine(), result.model());

            if (!result.success()) {
                LOG.errorf("Script AI edit failed: %s", result.output());
                ScriptAiEditResponse response = new ScriptAiEditResponse();
                response.setExplanation("Sorry, I encountered an error: " + result.output());
                return response;
            }

            return parseResponse(result.output());

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
    void recordAiUsage(Double costUsd, Long inputTokens, Long outputTokens,
                        String resultEngine, String resultModel) {
        AiUsageEntity usage = new AiUsageEntity();
        usage.invocationType = "script-edit";
        usage.actionType = "script-ai-edit";
        usage.engine = resultEngine != null && !resultEngine.isBlank()
                ? resultEngine : helperEngine.orElse(agentRegistry.getDefaultAgentType());
        usage.model = resultModel != null && !resultModel.isBlank()
                ? resultModel : model.orElse(null);
        usage.costUsd = costUsd;
        usage.inputTokens = inputTokens;
        usage.outputTokens = outputTokens;
        usage.createdOn = Instant.now();
        usage.persist();
    }
}
