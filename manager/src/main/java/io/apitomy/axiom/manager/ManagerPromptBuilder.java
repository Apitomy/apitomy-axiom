package io.apitomy.axiom.manager;

import io.apitomy.axiom.core.entities.ActionTypeEntity;
import io.apitomy.axiom.core.entities.AgentEntity;
import io.apitomy.axiom.core.entities.EventEntity;
import io.apitomy.axiom.core.entities.ProjectEntity;
import io.apitomy.axiom.core.entities.TaskEntity;

import java.util.List;

/**
 * Builds the system prompt and user prompt for the AI Manager.
 * The system prompt is user-configurable. The user prompt is built from a
 * configurable template with placeholder substitution for action types,
 * agents, event details, and project context.
 *
 * <p>Supported placeholders in the prompt template:</p>
 * <ul>
 *   <li>{@code {{actionTypes}}} — formatted list of available action types</li>
 *   <li>{@code {{agents}}} — formatted list of available agents</li>
 *   <li>{@code {{source}}} — event source (e.g. "github")</li>
 *   <li>{@code {{eventType}}} — event type (e.g. "issue-created")</li>
 *   <li>{@code {{issueRef}}} — issue reference (e.g. "owner/repo#42")</li>
 *   <li>{@code {{repository}}} — repository (e.g. "owner/repo")</li>
 *   <li>{@code {{payload}}} — raw event payload JSON</li>
 *   <li>{@code {{projectContext}}} — existing project and recent task details</li>
 * </ul>
 */
public final class ManagerPromptBuilder {

    private ManagerPromptBuilder() {
    }

    /**
     * Formats the list of action types for inclusion in a prompt.
     *
     * @param actionTypes the registered action types
     * @return a formatted markdown list
     */
    public static String formatActionTypes(List<ActionTypeEntity> actionTypes) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Available Action Types\n\n");
        if (actionTypes.isEmpty()) {
            sb.append("No action types configured.\n");
        } else {
            for (ActionTypeEntity at : actionTypes) {
                sb.append("- **").append(at.name).append("** (").append(at.executionMode).append(")");
                if (at.description != null) {
                    sb.append(": ").append(at.description);
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Formats the list of agents for inclusion in a prompt.
     *
     * @param agents the configured agents
     * @return a formatted markdown list
     */
    public static String formatAgents(List<AgentEntity> agents) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Available Agents\n\n");
        if (agents.isEmpty()) {
            sb.append("No agents configured.\n");
        } else {
            for (AgentEntity agent : agents) {
                sb.append("- **").append(agent.name).append("** (").append(agent.agentType).append(")");
                if (agent.description != null) {
                    sb.append(": ").append(agent.description);
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Formats the project context section for inclusion in a prompt.
     *
     * @param project the existing project (may be null)
     * @param recentTasks recent tasks for the project (may be empty)
     * @return the project context section
     */
    public static String formatProjectContext(ProjectEntity project,
                                               List<TaskEntity> recentTasks) {
        StringBuilder sb = new StringBuilder();
        if (project != null) {
            sb.append("## Existing Project\n\n");
            sb.append("- **ID:** ").append(project.id).append("\n");
            sb.append("- **Name:** ").append(project.name).append("\n");
            sb.append("- **Status:** ").append(project.status).append("\n");
            sb.append("- **Type:** ").append(project.type).append("\n\n");

            if (!recentTasks.isEmpty()) {
                sb.append("### Recent Tasks\n\n");
                for (TaskEntity task : recentTasks) {
                    sb.append("- ").append(task.actionType)
                            .append(" (").append(task.status).append(")");
                    if (task.output != null && !task.output.isEmpty()) {
                        String preview = task.output.length() > 200
                                ? task.output.substring(0, 200) + "..."
                                : task.output;
                        sb.append(": ").append(preview);
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }
        } else {
            sb.append("There is no existing Axiom project for this issue (yet).\n");
        }
        return sb.toString();
    }

    /**
     * Builds the user prompt by substituting placeholders in the prompt template.
     *
     * @param promptTemplate the configurable prompt template with placeholders
     * @param event the event to evaluate
     * @param actionTypes the registered action types
     * @param agents the configured agents
     * @param project the existing project (may be null)
     * @param recentTasks recent tasks for the project
     * @return the resolved user prompt
     */
    public static String buildUserPrompt(String promptTemplate, EventEntity event,
                                          List<ActionTypeEntity> actionTypes,
                                          List<AgentEntity> agents,
                                          ProjectEntity project,
                                          List<TaskEntity> recentTasks) {
        String resolved = promptTemplate;
        resolved = resolved.replace("{{actionTypes}}", formatActionTypes(actionTypes));
        resolved = resolved.replace("{{agents}}", formatAgents(agents));
        resolved = resolved.replace("{{source}}", event.source != null ? event.source : "");
        resolved = resolved.replace("{{eventType}}", event.eventType != null ? event.eventType : "");
        resolved = resolved.replace("{{issueRef}}", event.issueRef != null ? event.issueRef : "");
        resolved = resolved.replace("{{repository}}", event.repository != null ? event.repository : "");
        resolved = resolved.replace("{{payload}}", event.payload != null ? event.payload : "{}");
        resolved = resolved.replace("{{projectContext}}", formatProjectContext(project, recentTasks));
        return resolved;
    }

    /**
     * Returns the JSON schema that enforces the Manager's response format.
     * Used with Claude Code's {@code --json-schema} flag.
     */
    public static String getResponseJsonSchema() {
        return """
                {"type":"object","required":["decisions"],"properties":{"decisions":\
                {"type":"array","items":{"type":"object","required":["decision",\
                "confidence","reasoning"],"properties":{"decision":{"type":"string",\
                "enum":["create_task","ignore","script_action","escalate"]},\
                "actionType":{"type":"string"},"agentHint":{"type":"string"},\
                "inputContext":{"type":"string"},"confidence":{"type":"number",\
                "minimum":0,"maximum":1},"reasoning":{"type":"string"},\
                "humanContext":{"type":"object","properties":{"title":{"type":"string"},\
                "description":{"type":"string"},"references":{"type":"array","items":\
                {"type":"object","properties":{"label":{"type":"string"},"url":\
                {"type":"string"}}}}}},\
                "outputSchema":{"type":"object","properties":{"fields":{"type":"array",\
                "items":{"type":"object","properties":{"name":{"type":"string"},\
                "type":{"type":"string","enum":["text","textarea","boolean","select","number"]},\
                "label":{"type":"string"},"description":{"type":"string"},\
                "required":{"type":"boolean"},"options":{"type":"array","items":\
                {"type":"object","properties":{"label":{"type":"string"},"value":\
                {"type":"string"}}}}}}}}}\
                }}}}}""";
    }

    /**
     * Default system prompt used when no custom system prompt is configured.
     */
    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are the Axiom Manager — an AI agent responsible for triaging incoming \
            events from GitHub and other sources. When an event arrives, you analyze \
            it and decide what actions (if any) should be taken.

            For each decision, specify:
            - **decision**: One of: create_task, ignore, script_action, escalate
            - **actionType**: The action to perform (required for create_task and script_action)
            - **agentHint**: (Optional) preferred agent name
            - **inputContext**: Instructions or context for the agent performing the task
            - **confidence**: 0.0 to 1.0 indicating your confidence
            - **reasoning**: Brief explanation of why you made this decision

            When creating a task for a human agent (agentHint is "human" or the action \
            type is designated for humans), you should also provide:
            - **humanContext**: An object with:
              - **title**: A short, clear title of what you need from the human
              - **description**: Detailed context about what is being asked and why
              - **references**: (Optional) array of {label, url} links to relevant resources
            - **outputSchema**: (Optional) an object with a "fields" array defining the \
              form fields the human must fill in. Each field has:
              - **name**: Field identifier (used as the response key)
              - **type**: One of: text, textarea, boolean, select, number
              - **label**: Human-readable label displayed in the form
              - **description**: (Optional) help text for the field
              - **required**: Whether the field must be filled in
              - **options**: (For select type only) array of {label, value} choices

            If no outputSchema is provided for a human task, the user will see a \
            freeform text input. Use outputSchema for structured responses like \
            approvals, selections, or multi-field forms.

            Guidelines:
            - You may return multiple decisions for a single event
            - Use "ignore" for events that don't require action (bot comments, trivial edits)
            - Use "escalate" when you're unsure what to do
            - Set a low confidence score if you're uncertain
            - Script actions run a predefined script (e.g. "close-project", "reopen-project")
            """;

    /**
     * Default prompt template used when no custom prompt template is configured.
     */
    public static final String DEFAULT_PROMPT_TEMPLATE = """
            {{actionTypes}}

            ## Event to Evaluate

            - **Source:** {{source}}
            - **Event type:** {{eventType}}
            - **Issue:** {{issueRef}}
            - **Repository:** {{repository}}

            ### Event Payload

            ```json
            {{payload}}
            ```

            {{projectContext}}

            Analyze this event and return ONLY a JSON object (no other text, no \
            markdown, no explanation) with a "decisions" array. Each element must \
            have: decision, actionType, agentHint, inputContext, confidence, reasoning. \
            Your entire response must be valid JSON and nothing else.
            """;
}
