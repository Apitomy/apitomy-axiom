package io.apitomy.axiom.app.assistant;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.apitomy.axiom.app.StandardInputs;
import io.apitomy.axiom.app.expressions.ExpressionService;
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
import java.util.Set;
import java.util.stream.Collectors;

/** Builds the Configuration Assistant's reference text from code and the bundled examples. */
@ApplicationScoped
public class AssistantReferenceGenerator {

    /** How a script-mode action type returns its declared outputs. */
    static final String SCRIPT_OUTPUT_RULE = "A script returns its outputs by printing one JSON object to "
            + "stdout as the last thing it prints (e.g. `jq -n --arg x \"$X\" '{x: $x}'`); it may span several "
            + "lines. Earlier stdout lines are treated as logs; send diagnostics to stderr. Outputs written to "
            + "files are ignored. To hand an input to a command, pipe it (`printf '%s' {{inputs.labels}} | jq -r "
            + "'join(\",\")'`) or use an unquoted heredoc (`<<EOF`); placeholders inside a quoted heredoc "
            + "(`<<'EOF'`) are never expanded and are rejected. Put temporary files in `$AXIOM_WORK_DIR` or create "
            + "them with `mktemp`, never at fixed `/tmp` paths.";

    /**
     * Per node type documentation of config fields; keys must cover every {@link NodeType}.
     * Kept next to the generator so a new node type fails {@code AssistantReferenceTest}.
     */
    static final Map<NodeType, String> NODE_CONFIG_DOCS = Map.of(
            NodeType.START, "Exactly one per workflow. `config.inputs`: array of `{name, type, required, description}` declaring "
                    + "which standard inputs the run receives (see Start inputs below).",
            NodeType.END, "Terminates the run (or the current branch). `config` is `{}`.",
            NodeType.ACTION, "Runs an action type as a task. `config.actionType` (required): an action type name "
                    + "or `sdk:<function>`. `config.inputs`: optional map of input name → EL expression "
                    + "(raw EL, e.g. `context.subject.ref` or a quoted literal `'normal'`). The task's output fields "
                    + "are merged into the run context under their names, so a later condition can read "
                    + "`context.<outputName>`. `config.outputs`: optional array of "
                    + "`{name, type, required, contextKey}` to rename or require outputs.",
            NodeType.HUMAN_TASK, "Creates a task for a person and waits for completion. `config.title`: optional "
                    + "EL expression used as the task's inbox subject (join text with `+=`, e.g. "
                    + "`'Review ' += context.subject.ref`; falls back to the node name). `config.description`, "
                    + "`config.inputs` (map of name → EL expression shown to the person), `config.outputs` "
                    + "(array of `{name, type, required, contextKey, label, description, widget, defaultValue, options}`; "
                    + "answers are merged into the context under `contextKey`, or the output's name if unset).",
            NodeType.RECEIVE_EVENT, "Parks the run until a matching event is delivered. `config.eventType` "
                    + "(required, an event type from the list below), `config.match`: optional array of EL "
                    + "conditions over `event` and `context`, `config.outputs`: optional array of "
                    + "`{contextKey, expression}` copying event values into the context. "
                    + "`config.correlationKey`: optional `{subscriptionKey, eventKey}` EL expressions "
                    + "(over `context` and `event` respectively); when set, only events whose key equals the "
                    + "run's key match. `config.lookback`: `run-start` (default: events since the run "
                    + "started can match, even ones that arrived before the node was reached), `none` (only "
                    + "events after the node is reached) or an ISO-8601 duration such as `PT10M`. "
                    + "`config.timeout`: optional ISO-8601 duration; requires exactly one outgoing edge with "
                    + "`isTimeout: true` (no condition), taken when no matching event arrives in time, plus "
                    + "a normal outgoing edge for the event path.",
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
     * Returns the reference used only by the workbench assistant: the change-set shape, ops, patch semantics,
     * error codes and one complete example, then how to check expressions with {@code evaluate_expression}.
     *
     * @return Markdown workbench reference
     */
    public String generateChangeSetReference() {
        return CHANGE_SET_REFERENCE + "\n" + expressionScopesReference();
    }

    /**
     * Documents the {@code evaluate_expression} scopes, generated from {@link ExpressionService.Scope}.
     *
     * @return Markdown section
     */
    private static String expressionScopesReference() {
        StringBuilder sb = new StringBuilder("# Checking expressions\n\n")
                .append("`evaluate_expression` takes the expression, a `scope` saying where it is used, and ")
                .append("optionally an `eventId` and a `context` object. Valid scopes:\n\n")
                .append("| Scope | Use it for |\n| --- | --- |\n");
        for (ExpressionService.Scope scope : ExpressionService.Scope.values()) {
            sb.append("| `").append(scope.jsonName()).append("` | ").append(scope.description()).append(" |\n");
        }
        sb.append("\nWith an `eventId`, scopes that see context get `context.subject` and `context.event` exactly ")
                .append("as a run started from that event would (the event's subject, or the one the run would ")
                .append("create). Pass `context` to add node outputs or to override them. Scopes that see the ")
                .append("event, including `subscription-filter`, need an `eventId`.\n");
        return sb.toString();
    }

    private static final String CHANGE_SET_REFERENCE = """
            # Workflow Change Sets

            `propose_workflow_changes` takes a change set:

            ```
            { "id": string, "baseRevision": string, "author": "agent:<name>", "summary": string, "ops": [ ... ] }
            ```

            | Op | Fields | Effect |
            | --- | --- | --- |
            | `addNode` | `node` | Adds a node. Omit `position`; the editor lays out the workflow. |
            | `updateNode` | `id`, `patch: { name?, config? }`, `unset?` | Updates the name and shallow-merges config. |
            | `renameNode` | `id`, `newId` | Renames a node and rewrites `source`/`target` of attached edges. |
            | `removeNode` | `id` | Removes a node and every edge attached to it. |
            | `addEdge` | `edge` | Adds an edge; both endpoints must exist. |
            | `updateEdge` | `id`, `patch`, `unset?` | Shallow-merges edge fields (any field except `id`). |
            | `removeEdge` | `id` | Removes an edge. |
            | `metadata` | `patch: { name?, description?, version? }` | Updates workflow metadata. |

            Ops apply in order and later ops see the effects of earlier ones. Application is atomic: any error
            leaves the workflow unchanged.

            ## Patch semantics

            - `updateNode.patch.config` is merged shallowly into the existing config. Nested values such as the
              `inputs` and `outputs` mappings are replaced whole; to drop one entry, resend the whole mapping.
            - `unset` lists top-level config keys (for `updateNode`) or edge fields (for `updateEdge`) to remove,
              for example `timeout` or `condition`. It is applied after `patch`; a key in both is `malformed`.
            - `null` in a patch is stored as a value. It does not remove the key; use `unset` for that.

            ## Errors

            | Code | Meaning |
            | --- | --- |
            | `target-missing` | The op references a node or edge that does not exist. |
            | `duplicate-id` | An added or renamed element's id already exists. |
            | `edge-endpoint-missing` | An edge's `source` or `target` does not exist. |
            | `malformed` | The op is structurally invalid, for example a key in both `patch` and `unset`. |
            | `stale` | `baseRevision` does not match the current workflow revision. Call `get_composition` and rebuild the change set. |

            ## Example

            Insert a one-hour wait between Start and End of a blank workflow (set `baseRevision` to the current
            `workflowRevision`):

            ```json
            {
              "id": "cs-1",
              "baseRevision": "<workflowRevision>",
              "author": "agent:axiom-workbench",
              "summary": "Wait one hour before ending",
              "ops": [
                { "op": "removeEdge", "id": "edge-1" },
                { "op": "addNode", "node": { "id": "wait-1", "type": "wait", "name": "Wait an hour",
                  "config": { "duration": "PT1H" } } },
                { "op": "addEdge", "edge": { "id": "edge-2", "source": "start-1", "target": "wait-1",
                  "priority": 0, "isDefault": false } },
                { "op": "addEdge", "edge": { "id": "edge-3", "source": "wait-1", "target": "end-1",
                  "priority": 0, "isDefault": false } }
              ]
            }
            ```
            """;

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
                .append("- Mark an edge as the default (`isDefault: true`) only when its node also has conditional ")
                .append("edges: the default is the fallback when no condition matches. A node with a single outgoing ")
                .append("edge, and every edge of a fork, uses an unconditional edge with `isDefault: false`.\n")
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
                .append("(the triggering or received event).\n\n");
        appendStartInputs(sb);
        appendSdkActionNodes(sb);
    }

    /** Parameters the engine fills in from the node's own run when an {@code sdk:} node does not map them. */
    private static final Set<String> ENGINE_FILLED_SDK_PARAMS = Set.of("subjectId", "runId");

    /** Documents the standard Start inputs, generated from {@link StandardInputs}. */
    private static void appendStartInputs(StringBuilder sb) {
        sb.append("### Start inputs\n\n")
                .append("A run receives only the inputs its Start node declares. Declare only these inputs; no ")
                .append("other names and no `defaultValue`:\n\n")
                .append("| Input | Type | Available |\n|---|---|---|\n");
        for (StandardInputs.StandardInput input : StandardInputs.ALL) {
            sb.append("| `").append(input.name()).append("` | ").append(input.type()).append(" | ")
                    .append(input.availability()).append(" |\n");
        }
        sb.append("\n`subject` fields: ")
                .append(StandardInputs.SUBJECT_FIELDS.stream().map(f -> "`" + f + "`").collect(Collectors.joining(", ")))
                .append(" (missing values are null). Read them as `context.subject.<field>`, e.g. ")
                .append("`context.subject.ref`; read the event as `context.event.<field>`, e.g. ")
                .append("`context.event.payload`.\n\n")
                .append("- Mark `event` required only if the workflow must be started from an event; a ")
                .append("schedule-triggered workflow must not require it.\n")
                .append("- A required input that is not available stops the run from being created.\n")
                .append("- Do not give a node output the name of a declared input: it overwrites the input for ")
                .append("later nodes (validation warns with `SHADOWS_WORKFLOW_INPUT`).\n\n");
    }

    /**
     * Documents {@code sdk:} action nodes: each SDK function usable as a node, with its parameters and
     * description (from the SDK function registry), what the engine fills in, the node's outputs, and an
     * example.
     */
    private static void appendSdkActionNodes(StringBuilder sb) {
        sb.append("### SDK action nodes\n\n")
                .append("An `action` node can call an Axiom SDK function directly with `actionType` ")
                .append("`sdk:<function>`; no action type is needed. Map the parameters in `config.inputs`. ")
                .append("Parameters marked `?` may be left unmapped. `subjectId` and `runId` are filled in ")
                .append("from the node's own run; map them only to act on another subject or run.\n\n");
        for (SdkFunction fn : SdkFunctionRegistry.sdkCallFunctions()) {
            String params = fn.parameters().stream()
                    .map(p -> ENGINE_FILLED_SDK_PARAMS.contains(p.name()) || !p.required() ? p.name() + "?" : p.name())
                    .collect(Collectors.joining(", "));
            sb.append("- `sdk:").append(fn.name()).append("(").append(params).append(")` — ")
                    .append(fn.description()).append("\n");
        }
        sb.append("\nOutputs join the run context without being declared: `context.success`, `context.output` ")
                .append("(a message) and the function's result fields (for example `context.title` after ")
                .append("`axiom_set_workflow_run_title`). A failed call fails the node, and so the run.\n\n")
                .append("Example — title the run after its subject (the workflow's Start node declares `subject`):\n\n")
                .append("```json\n")
                .append("{\"id\": \"set-title\", \"type\": \"action\", \"name\": \"Set run title\",\n")
                .append(" \"config\": {\"actionType\": \"sdk:axiom_set_workflow_run_title\",\n")
                .append("            \"inputs\": {\"title\": \"'Checking ' += context.subject.name\"}}}\n")
                .append("```\n\n");
    }

    private static void appendActionTypes(StringBuilder sb) {
        sb.append("## Action types\n\n")
                .append("Action types run only from workflow `action` nodes.\n\n")
                .append("Execution modes (`executionMode`):\n\n")
                .append("- `agent` — an AI agent follows `promptTemplate` using its `allowedTools`.\n")
                .append("- `node` — a Node.js ES module in `codeTemplate`.\n")
                .append("- `script` — a bash script in `scriptTemplate`.\n\n")
                .append("### Choosing an execution mode\n\n")
                .append("- Use `agent` only when the step needs AI judgment (reading, classifying, summarizing, ")
                .append("writing text).\n")
                .append("- Otherwise use `node` by default: fetching data, calling HTTP APIs, running CLIs such ")
                .append("as `gh`, and reshaping JSON. Inputs and outputs are real JSON values, so there is no ")
                .append("shell quoting, no `jq` and no stdout parsing to get wrong.\n")
                .append("- Use `script` only for a trivial one-line shell command, or when the user asks for ")
                .append("bash.\n\n")
                .append("### Node.js action types\n\n")
                .append("Contract: `codeTemplate` is an ES module that exports `async function run(inputs, ctx)` ")
                .append("(a synchronous `run` also works) and returns a plain object whose fields are the ")
                .append("declared outputs. Axiom serializes the returned object itself; do not print it. ")
                .append("Throw an `Error` to fail the task; its message is shown as `Action failed: <message>`.\n\n")
                .append("- `inputs` holds the declared inputs as typed JSON values (strings, numbers, booleans, ")
                .append("objects, arrays). Absent optional inputs are omitted, so check `inputs.name !== ")
                .append("undefined` before using one.\n")
                .append("- `ctx.workDir` — the subject workspace (also the process working directory); ")
                .append("`ctx.apiUrl` — Axiom's REST API base URL; `ctx.taskId` — the task id.\n")
                .append("- `ctx.log(...args)` — diagnostics, written to stderr and shown in the execution log.\n")
                .append("- `ctx.exec(cmd, args, {allowFailure, input})` — runs a command with no shell and ")
                .append("resolves to `{stdout, stderr, exitCode}`. It rejects on a non-zero exit unless ")
                .append("`allowFailure` is true; the rejected error has `error.result` with the same fields. ")
                .append("`input` is written to the command's stdin. Pass every argument as a separate array ")
                .append("element, e.g. `ctx.exec(\"gh\", [\"issue\", \"view\", inputs.ref, \"--json\", ")
                .append("\"title\"])`, then `JSON.parse(stdout)`.\n")
                .append("- HTTP: use the global `fetch`; check `response.ok` and throw on failure.\n")
                .append("- Secrets and environment: in `process.env`. If the action type sets `environment`, only ")
                .append("those variables are present (reference secrets as `${secret:NAME}`); otherwise every ")
                .append("secret is available by name, e.g. `process.env.GH_TOKEN`.\n\n")
                .append("Node.js pitfalls:\n\n")
                .append("- The module is ESM: use `import ... from \"node:fs\"`; `require` is not defined.\n")
                .append("- Imports are limited to Node built-in modules (`node:fs`, `node:path`, `node:crypto`, ")
                .append("...); there is no npm and no `node_modules`.\n")
                .append("- `ctx.exec` has no shell: no pipes, redirects, globs, `&&` or `$VAR` expansion. Do that ")
                .append("work in JavaScript instead (e.g. filter/map parsed JSON rather than piping to `jq`).\n")
                .append("- Use `ctx.log`, not `console.log`, for diagnostics.\n")
                .append("- Read inputs as `inputs.<name>`; `{{...}}` placeholders are rejected in node code.\n")
                .append("- Write temporary files under `ctx.workDir` (or `os.tmpdir()` with a unique name), ")
                .append("never at fixed `/tmp` paths.\n\n")
                .append("Validation for node mode: `codeTemplate` is required; the module must export `run`; ")
                .append("imports must be built-ins; syntax is checked with `node --check`; reading ")
                .append("`inputs.<name>` that is not a declared input produces a warning.\n\n")
                .append("In agent and script templates the only placeholder is `{{inputs.<name>}}` ")
                .append("for a declared input. ")
                .append("Other former placeholders are rejected:\n\n");
        ActionTypeValidator.REMOVED_PLACEHOLDER_HINTS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("- `{{").append(e.getKey()).append("}}` — ")
                        .append(e.getValue()).append("\n"));
        sb.append("\nScript outputs: " + SCRIPT_OUTPUT_RULE + "\n")
                .append("\nScripts get runtime environment variables `AXIOM_API_URL`, ")
                .append("`AXIOM_TASK_ID`, `AXIOM_WORK_DIR`; agents get data only through declared inputs. ")
                .append("The workflow context provides `subjectId`, ")
                .append("`subjectName`, `ref`, `repository` and `event` for mapping into inputs.\n\n")
                .append("Node input mappings (`config.inputs` on an `action` node): map every required input; "
                        + "map no undeclared input; each value is EL over the run context (use `context.event`, "
                        + "never a bare `event.`); a `context.<key>` reference must name a built-in key, a "
                        + "Start input, or an output some node writes to the context.\n\n")
                .append("Input and output field types: `string`, `number`, `boolean`, `object`. Use `object` for any JSON ")
                .append("structure, including arrays (e.g. a list of label names); there is no separate array type.\n\n")
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
                .append("- `github`: `configuration.repositories` — array of `owner/repo` strings. `baseUrl` is ")
                .append("the GitHub server: `https://github.com` for GitHub.com, or the Enterprise web URL such ")
                .append("as `https://github.example.com`. Axiom derives the REST API URL from it, so the API URL ")
                .append("(`https://api.github.com`, `https://github.example.com/api/v3`) or a repository page URL ")
                .append("also work; repositories always come from `configuration.repositories`.\n")
                .append("- `jira`: `configuration.projects` — array of project keys; `baseUrl` is the Jira site ")
                .append("URL.\n\n");
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
