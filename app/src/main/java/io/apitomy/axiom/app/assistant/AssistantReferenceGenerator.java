package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.apitomy.axiom.app.validation.ConfigItemType;
import io.apitomy.axiom.app.validation.WorkflowContentRules;
import io.apitomy.axiom.core.SdkFunction;
import io.apitomy.axiom.core.SdkFunctionRegistry;
import io.apitomy.axiom.core.events.model.EventType;
import io.apitomy.axiom.core.events.model.RoutingRule;
import io.apitomy.axiom.core.services.ActionTypeValidator;
import io.apitomy.flow.model.NodeType;
import io.apitomy.flow.model.WorkflowEdge;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds the Configuration Assistant's reference text from code and the bundled examples. */
@ApplicationScoped
public class AssistantReferenceGenerator {

    /**
     * Per node type documentation of config fields; keys must cover every {@link NodeType}.
     * Kept next to the generator so a new node type fails {@code AssistantReferenceTest}.
     */
    static final Map<NodeType, String> NODE_CONFIG_DOCS = Map.of(
            NodeType.START, "Exactly one per workflow. `config.inputs`: optional array of "
                    + "`{name, type, required, description, defaultValue}` declaring the run's inputs "
                    + "(`type` is string|number|boolean|object). Built-in inputs are `subjectId`, `ref`, "
                    + "`event`, `subjectName`, `repository`.",
            NodeType.END, "Terminates the run (or the current branch). `config` is `{}`.",
            NodeType.ACTION, "Runs an action type as a task. `config.actionType` (required): an action type name "
                    + "or `sdk:<function>`. `config.inputs`: optional map of input name → EL expression "
                    + "(raw EL, e.g. `context.ref` or a quoted literal `'normal'`). The task's output fields "
                    + "are merged into the run context under their names, so a later condition can read "
                    + "`context.<outputName>`. `config.outputs`: optional array of "
                    + "`{name, type, required, contextKey}` to rename or require outputs.",
            NodeType.HUMAN_TASK, "Creates a task for a person and waits for completion. `config.description`, "
                    + "`config.inputs` (map of name → EL expression shown to the person), `config.outputs` "
                    + "(array of `{name, type, required, contextKey, label, description, widget, defaultValue, options}`; "
                    + "answers are merged into the context under `contextKey`, or the output's name if unset).",
            NodeType.RECEIVE_EVENT, "Parks the run until a matching event is delivered. `config.eventType` "
                    + "(required, an event type from the list below), `config.match`: optional array of EL "
                    + "conditions over `event` and `context`, `config.outputs`: optional array of "
                    + "`{contextKey, expression}` copying event values into the context.",
            NodeType.WAIT, "Pauses for a fixed time. `config.duration` (required): ISO-8601 duration, e.g. "
                    + "`PT1H` or `P1D`.");

    private static final String EXAMPLES_ROOT = "/templates/assistant-reference/examples/";

    private volatile String cachedReference;

    /** @return Markdown reference text (cached after first call) */
    public String generate() {
        String result = cachedReference;
        if (result == null) {
            result = buildReference();
            cachedReference = result;
        }
        return result;
    }

    /**
     * Lists bundled example files as (relative path, JSON text) pairs, in ConfigItemType order.
     *
     * @return example files
     */
    public List<Map.Entry<String, String>> examples() {
        return readResource(EXAMPLES_ROOT + "index.txt").lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .sorted(Comparator.comparingInt(path -> ConfigItemType.fromName(
                        path.substring(0, path.indexOf('/'))).ordinal()))
                .map(path -> (Map.Entry<String, String>) new AbstractMap.SimpleImmutableEntry<>(
                        path, readResource(EXAMPLES_ROOT + path)))
                .toList();
    }

    private String buildReference() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Configuration Reference\n\n");
        appendWorkflows(sb);
        appendActionTypes(sb);
        appendEvents(sb);
        appendSubscriptions(sb);
        appendExamples(sb);
        return sb.toString();
    }

    private static void appendWorkflows(StringBuilder sb) {
        sb.append("## Workflows\n\n")
                .append("File shape: `{name, description, content: {id, name, nodes, edges}}`.\n\n")
                .append("Node fields: `id`, `type`, `name`, `config`, `position {x, y}`.\n\n")
                .append("Node types (`type` values, lowercase):\n\n");
        for (NodeType type : NodeType.values()) {
            sb.append("- `").append(jsonName(type)).append("` — ").append(NODE_CONFIG_DOCS.get(type)).append('\n');
        }
        sb.append("\nEdge fields: ")
                .append(Arrays.stream(WorkflowEdge.class.getRecordComponents())
                        .map(c -> "`" + c.getName() + "`")
                        .collect(Collectors.joining(", ")))
                .append(".\n\n")
                .append("Graph rules:\n\n")
                .append("- At most one default edge (`isDefault: true`) per node.\n")
                .append("- A node with conditional edges needs exactly one default edge (no condition) as its ")
                .append("fallback; this includes a node with a single conditional edge.\n")
                .append("- A default edge must not have a `condition`.\n")
                .append("- A fork (parallel branches) uses only unconditional, non-default edges — do not mix ")
                .append("fork edges with conditional/default edges. Parallel branches must re-join at a single ")
                .append("node before reaching an `end` node.\n")
                .append("- Edges leaving the same node should have distinct `priority` values.\n")
                .append("- One `start` node (no incoming edges) and at least one `end` node (no outgoing edges).\n")
                .append("- Every node except `end` needs an outgoing edge; every node must be reachable from ")
                .append("`start` and have a path to an `end` node.\n")
                .append("- No cycle made only of `action` nodes (a loop must pass through a `human-task`, ")
                .append("`wait` or `receive-event` node).\n\n")
                .append("Conditions are raw EL without `${}`, e.g. `context.needsInfo == true`. Variables: ")
                .append("`context.<key>` (run context: inputs and merged node outputs) and `event.<field>` ")
                .append("(the triggering or received event).\n\n")
                .append("Start inputs: names must match `").append(WorkflowContentRules.INPUT_NAME_PATTERN.pattern())
                .append("`; these built-in inputs may not be marked required: ")
                .append(codeList(WorkflowContentRules.OPTIONAL_BUILT_IN_INPUTS)).append(".\n\n")
                .append("An `action` node's `actionType` may be `sdk:<function>` for: ")
                .append(codeList(SdkFunctionRegistry.sdkCallFunctions().stream().map(SdkFunction::name).toList()))
                .append(".\n\n");
    }

    private static void appendActionTypes(StringBuilder sb) {
        sb.append("## Action types\n\n")
                .append("Action types run only from workflow `action` nodes.\n\n")
                .append("The only template placeholder is `{{inputs.<name>}}` for a declared input. ")
                .append("Other former placeholders are rejected:\n\n");
        ActionTypeValidator.REMOVED_PLACEHOLDER_HINTS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("- `{{").append(e.getKey()).append("}}` — ")
                        .append(e.getValue()).append("\n"));
        sb.append("\nScripts and agents get runtime environment variables `AXIOM_API_URL`, ")
                .append("`AXIOM_TASK_ID`, `AXIOM_WORK_DIR`. The workflow context provides `subjectId`, ")
                .append("`subjectName`, `ref`, `repository` and `event` for mapping into inputs.\n\n")
                .append("Input and output field types: `string`, `number`, `boolean`, `object`.\n\n")
                .append("`allowedTools` entry formats: `mcp__axiom-tools__<tool>` (a tool), `@<toolset>` ")
                .append("(a toolset), or one of the Axiom SDK tools: ")
                .append(codeList(SdkFunctionRegistry.allToolNames())).append(".\n\n");
    }

    private static void appendEvents(StringBuilder sb) {
        sb.append("## Events\n\n")
                .append("Event types: ")
                .append(codeList(Arrays.stream(EventType.values()).map(EventType::value).toList()))
                .append(".\n\n")
                .append("Subscription filter variables: `event.id`, `event.type`, `event.source` ")
                .append("(`github`|`jira`), `event.eventSourceId`, `event.ref`, `event.timestamp`, ")
                .append("`event.actor`, `event.payload`.\n\n")
                .append("Event source fields: `id` (matches `[a-z0-9-]+`), `name`, `description`, `sourceType`, ")
                .append("`baseUrl`, `secretName`, `pollInterval` (seconds), `configuration`, `enabled`.\n\n")
                .append("- `github`: `configuration.repositories` — array of `owner/repo` strings.\n")
                .append("- `jira`: `configuration.projects` — array of project keys.\n\n");
    }

    private static void appendSubscriptions(StringBuilder sb) {
        sb.append("## Subscriptions\n\n")
                .append("Fields: `name`, `description`, `filterExpression` (EL over `event`), `routing`, ")
                .append("`enabled`.\n\n")
                .append("Routing rule types:\n\n")
                .append("- `").append(RoutingRule.TYPE_CREATE_WORKFLOW)
                .append("` — starts a workflow run; set `workflowDefinitionName` (preferred, required for ")
                .append("workflows created in the same session) or `workflowDefinitionId`, not both.\n")
                .append("- `").append(RoutingRule.TYPE_WORKFLOW_DISPATCH)
                .append("` — delivers events to runs waiting at a `receive-event` node.\n\n")
                .append("Subscriptions are created disabled (`enabled: false`).\n\n");
    }

    private void appendExamples(StringBuilder sb) {
        sb.append("## Examples\n\n")
                .append("A complete, valid scenario: when a GitHub issue opens, run a triage workflow.\n\n");
        for (Map.Entry<String, String> example : examples()) {
            sb.append("### `").append(example.getKey()).append("`\n\n```json\n")
                    .append(example.getValue().strip()).append("\n```\n\n");
        }
    }

    private static String jsonName(NodeType type) {
        try {
            JsonProperty property = NodeType.class.getField(type.name()).getAnnotation(JsonProperty.class);
            return property != null ? property.value() : type.name().toLowerCase().replace('_', '-');
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String codeList(Collection<String> values) {
        return values.stream().sorted().map(v -> "`" + v + "`").collect(Collectors.joining(", "));
    }

    private static String readResource(String path) {
        try (InputStream in = AssistantReferenceGenerator.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
