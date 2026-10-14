/**
 * Writes a structured JSON log line to stderr.
 * @param {string} level log level
 * @param {string} message log message
 * @param {object} [data] extra fields
 */
function log(level, message, data) {
    const entry = {
        timestamp: new Date().toISOString(),
        level,
        source: "axiom-workbench-mcp",
        message,
        ...data
    };
    process.stderr.write(JSON.stringify(entry) + "\n");
}

const AXIOM_API_URL = process.env.AXIOM_API_URL || "http://localhost:9090/api/v1";
const AXIOM_COMPOSITION_ID = process.env.AXIOM_COMPOSITION_ID || "";
const AXIOM_SESSION_TOKEN = process.env.AXIOM_SESSION_TOKEN || "";

/**
 * Calls the Axiom REST API with the session token header.
 * @param {string} method HTTP method
 * @param {string} path path relative to AXIOM_API_URL
 * @param {*} [body] JSON body
 * @returns {Promise<string>} the response text
 */
async function axiomApi(method, path, body) {
    const url = `${AXIOM_API_URL}${path}`;
    const opts = {
        method,
        headers: { "Accept": "application/json" },
    };
    if (AXIOM_SESSION_TOKEN) {
        opts.headers["X-Axiom-Session-Token"] = AXIOM_SESSION_TOKEN;
    }
    if (body !== undefined) {
        opts.headers["Content-Type"] = "application/json";
        opts.body = JSON.stringify(body);
    }
    const resp = await fetch(url, opts);
    const text = await resp.text();
    if (!resp.ok) {
        throw new Error(`Axiom API ${method} ${path} returned ${resp.status}: ${text.substring(0, 500)}`);
    }
    return text;
}

/**
 * Returns the agent route prefix for the bound composition.
 * @returns {string} the path prefix
 */
function agentBase() {
    if (!AXIOM_COMPOSITION_ID) {
        throw new Error("This session is not bound to a composition.");
    }
    return `/compositions/${encodeURIComponent(AXIOM_COMPOSITION_ID)}/agent`;
}

/**
 * Parses a value that may be a JSON string into an object.
 * @param {*} value the value
 * @param {string} name parameter name for errors
 * @returns {*} the parsed value
 */
function asObject(value, name) {
    if (typeof value === "string") {
        try {
            return JSON.parse(value);
        } catch (e) {
            throw new Error(`${name} is not valid JSON: ${e.message}`);
        }
    }
    return value;
}

/**
 * Summarizes a stream event on one line.
 * @param {object} e the stream event
 * @returns {string} the summary line
 */
function summarizeEvent(e) {
    const actor = (e.actor && (e.actor.login || e.actor.name)) || "-";
    const payload = e.payload && typeof e.payload === "object" ? e.payload : {};
    return `${e.id} ${e.type} ${e.eventSourceId} ${e.ref} actor=${actor} at ${e.timestamp} `
        + `keys=[${Object.keys(payload).join(", ")}]`;
}

/**
 * Reads a nested value using dot segments and [n] indexes.
 * @param {*} obj the root value
 * @param {string} path e.g. "payload.issue.labels[0].name"
 * @returns {*} the value, or undefined when missing
 */
function pickPath(obj, path) {
    if (!path) {
        return obj;
    }
    const segments = [];
    for (const part of path.split(".")) {
        const re = /([^[\]]+)|\[(\d+)\]/g;
        let m;
        while ((m = re.exec(part)) !== null) {
            segments.push(m[1] !== undefined ? m[1] : Number(m[2]));
        }
    }
    let cur = obj;
    for (const seg of segments) {
        if (cur === null || cur === undefined || typeof cur !== "object") {
            return undefined;
        }
        cur = cur[seg];
    }
    return cur;
}

/**
 * Deep-copies a value, truncating long strings and arrays.
 * @param {*} value the value
 * @param {number} [maxString=2000] max string length
 * @param {number} [maxArray=50] max array length
 * @returns {*} the truncated copy
 */
function truncateDeep(value, maxString = 2000, maxArray = 50) {
    if (typeof value === "string") {
        return value.length > maxString
            ? value.substring(0, maxString) + `…[truncated ${value.length - maxString} chars]`
            : value;
    }
    if (Array.isArray(value)) {
        const out = value.slice(0, maxArray).map(v => truncateDeep(v, maxString, maxArray));
        if (value.length > maxArray) {
            out.push(`…[${value.length - maxArray} more items]`);
        }
        return out;
    }
    if (value !== null && typeof value === "object") {
        const out = {};
        for (const [k, v] of Object.entries(value)) {
            out[k] = truncateDeep(v, maxString, maxArray);
        }
        return out;
    }
    return value;
}

/**
 * Formats validation problems, one line each.
 * @param {Array<object>} problems the problems
 * @returns {string} the lines joined by newlines
 */
function formatProblems(problems) {
    return (problems || []).map(p =>
        `- [${String(p.severity || "").toUpperCase()}] ${p.path || "(root)"} ${p.code}: ${p.message}`).join("\n");
}

/**
 * Returns the validity status line for an item result.
 * @param {object} r object with valid and problems
 * @returns {string} VALID, VALID with warnings or INVALID
 */
function statusOf(r) {
    const problems = r.problems || [];
    if (!r.valid) return "INVALID";
    return problems.length === 0 ? "VALID" : "VALID with warnings";
}

/**
 * Builds the body of a test suggestion request.
 * @param {object} args the suggest_test arguments: type, name and optional inputs
 * @returns {{type: string, name: string, inputs: object}} the request body
 */
function suggestionBody(args) {
    if (args.type !== "action-type" && args.type !== "tool") {
        throw new Error("type must be action-type or tool");
    }
    return { type: args.type, name: args.name, inputs: asObject(args.inputs ?? {}, "inputs") };
}

/**
 * Renders a value as compact single-line JSON with long strings and arrays truncated.
 * @param {*} value the value
 * @returns {string} the compact JSON
 */
function compactJson(value) {
    return JSON.stringify(truncateDeep(value, 500, 20));
}

/**
 * Formats workflow runs, one line each.
 * @param {Array<object>} runs the run summaries (newest first)
 * @returns {string} the lines joined by newlines
 */
function formatRunList(runs) {
    if (!runs || runs.length === 0) return "No runs found.";
    return runs.map(r => `#${r.runId ?? r.id} ${r.status} v${r.definitionVersion} ${r.title || ""} `
        + `started ${r.startedOn}` + (r.currentNodeName ? ` at ${r.currentNodeName}` : "")).join("\n");
}

/**
 * Formats a workflow run: header, history and the details of its failed tasks.
 * @param {object} run the workflow instance
 * @param {Array<{taskId: number, nodeName: string, output: *, log: ?string}>} failedTasks failed task details
 * @returns {string} the formatted run
 */
function formatRun(run, failedTasks) {
    const lines = [`Run #${run.id}: ${run.title || ""}`, `status: ${run.status}`];
    if (run.definitionVersion !== undefined && run.definitionVersion !== null) {
        lines.push(`version: ${run.definitionVersion}`);
    }
    if (run.currentNodeName || run.currentNodeId) {
        lines.push(`current node: ${run.currentNodeName || "-"} (${run.currentNodeId || "-"})`);
    }
    if (run.failureReason) lines.push(`failure reason: ${run.failureReason}`);
    if (run.traceId) lines.push(`traceId: ${run.traceId}`);
    lines.push("", "History:");
    for (const h of run.history || []) {
        let line = `- ${h.nodeName || "-"} (${h.nodeId}) ${h.taskStatus || "-"}`;
        if (h.input !== undefined && h.input !== null) line += ` input=${compactJson(h.input)}`;
        if (h.output !== undefined && h.output !== null) line += ` output=${compactJson(h.output)}`;
        lines.push(line);
    }
    for (const t of failedTasks || []) {
        lines.push("", `Failed task ${t.taskId} (${t.nodeName || "-"})`,
            `output: ${t.output === undefined || t.output === null ? "none" : compactJson(t.output)}`);
        if (t.log) {
            lines.push("log (tail):", t.log);
        } else {
            lines.push("log: none");
        }
    }
    return lines.join("\n");
}

/**
 * Formats a trace and its nodes, failed nodes first.
 * @param {{trace: object, nodes: Array<object>}} detail the trace detail
 * @returns {string} the formatted trace
 */
function formatTrace(detail) {
    const t = detail.trace || {};
    const isFailed = n => /fail|error/i.test(String(n.status || ""));
    const nodes = [...(detail.nodes || [])].sort((a, b) => Number(isFailed(b)) - Number(isFailed(a)));
    return [`Trace ${t.traceId}: ${t.status}` + (t.summary ? ` - ${t.summary}` : "")]
        .concat(nodes.map(n => `- ${n.nodeType} ${n.status} ${n.summary || ""}`
            + (n.durationMs !== undefined && n.durationMs !== null ? ` (${n.durationMs} ms)` : "")))
        .join("\n");
}

/**
 * Returns the published workflow definition id of the bound composition, or null when never published.
 * @returns {Promise<?number>} the definition id
 */
async function workflowDefinitionId() {
    const c = JSON.parse(await axiomApi("GET", `${agentBase()}/composition`));
    return c.workflowDefinitionId ?? null;
}

/**
 * Reads the tail of a task log, or null when the task has no log.
 * @param {string} subjectId the subject id
 * @param {number} taskId the task id
 * @returns {Promise<?string>} the last 4000 characters of the log
 */
async function taskLogTail(subjectId, taskId) {
    const path = `/subjects/${encodeURIComponent(subjectId)}/tasks/${encodeURIComponent(taskId)}/log`;
    const headers = { "Accept": "text/plain" };
    if (AXIOM_SESSION_TOKEN) headers["X-Axiom-Session-Token"] = AXIOM_SESSION_TOKEN;
    const resp = await fetch(`${AXIOM_API_URL}${path}`, { method: "GET", headers });
    if (resp.status === 404) return null;
    const text = await resp.text();
    if (!resp.ok) throw new Error(`Axiom API GET ${path} returned ${resp.status}: ${text.substring(0, 500)}`);
    return text.length > 4000 ? text.substring(text.length - 4000) : text;
}

const TOOLS = [
    {
        name: "get_composition",
        description: "Get the current workbench composition: goal, workflow draft and its items with validity.",
        parameters: [],
        handler: async () => {
            const result = JSON.parse(await axiomApi("GET", `${agentBase()}/composition`));
            return JSON.stringify(result, null, 2);
        },
    },
    {
        name: "get_item",
        description: "Get the content of a composition item by type and name.",
        parameters: [
            { name: "type", type: "string", description: "Item type", required: true },
            { name: "name", type: "string", description: "Item name", required: true },
        ],
        handler: async (args) => {
            return await axiomApi("GET",
                `${agentBase()}/items/${encodeURIComponent(args.type)}/${encodeURIComponent(args.name)}`);
        },
    },
    {
        name: "upsert_item",
        description: "Create or replace a composition item (e.g. action-type, tool). item is the full JSON object.",
        parameters: [
            { name: "type", type: "string", description: "Item type", required: true },
            { name: "item", type: "object", description: "The complete item JSON", required: true },
        ],
        handler: async (args) => {
            const content = asObject(args.item, "item");
            const r = JSON.parse(await axiomApi("PUT", `${agentBase()}/items`, { type: args.type, content }));
            const lines = formatProblems(r.problems);
            return `${statusOf(r)}: ${r.type}/${r.name} (${r.created ? "created" : "updated"}, revision ${r.contentRevision})`
                + (lines ? "\n" + lines : "");
        },
    },
    {
        name: "remove_item",
        description: "Remove a composition item by type and name.",
        parameters: [
            { name: "type", type: "string", description: "Item type", required: true },
            { name: "name", type: "string", description: "Item name", required: true },
        ],
        handler: async (args) => {
            await axiomApi("DELETE",
                `${agentBase()}/items/${encodeURIComponent(args.type)}/${encodeURIComponent(args.name)}`);
            return `Removed ${args.type}/${args.name}.`;
        },
    },
    {
        name: "propose_workflow_changes",
        description: "Apply a change set (list of operations) to the workflow draft.",
        parameters: [
            { name: "changeSet", type: "object", description: "The change set JSON", required: true },
        ],
        handler: async (args) => {
            const changeSet = asObject(args.changeSet, "changeSet");
            const r = JSON.parse(await axiomApi("POST", `${agentBase()}/workflow/changesets`, changeSet));
            const lines = formatProblems(r.problems);
            if (r.applied) {
                return `APPLIED. New revision: ${r.contentRevision}` + (lines ? "\n" + lines : "");
            }
            const err = r.error || {};
            return `REJECTED (${err.code} at op ${err.opIndex}): ${err.reason}` + (lines ? "\n" + lines : "");
        },
    },
    {
        name: "validate_composition",
        description: "Report the validity of every item in the composition.",
        parameters: [],
        handler: async () => {
            const c = JSON.parse(await axiomApi("GET", `${agentBase()}/composition`));
            const items = c.items || [];
            if (items.length === 0) return "No items in the composition.";
            return items.map(i => {
                const lines = formatProblems(i.problems);
                return `${i.type}/${i.name}: ${statusOf(i)}` + (lines ? "\n" + lines : "");
            }).join("\n");
        },
    },
    {
        name: "suggest_test",
        description: "Suggest a test of a draft node or script action type or a tool with sample inputs. You cannot "
            + "run it yourself: the suggestion appears in the workbench's Test output strip and the user runs it.",
        parameters: [
            { name: "type", type: "string", description: "action-type or tool", required: true },
            { name: "name", type: "string", description: "Draft name", required: true },
            { name: "inputs", type: "object", description: "Input or parameter values by name", required: false },
        ],
        handler: async (args) => {
            const body = suggestionBody(args);
            await axiomApi("POST", `${agentBase()}/test-suggestions`, body);
            return `Suggested a test of ${body.type} ${body.name} with inputs ${JSON.stringify(body.inputs)}. `
                + "The user can run it from the Test output strip; ask them to tell you the result.";
        },
    },
    {
        name: "list_stream_events",
        description: "List recent stream events, one summary line each. Filters are optional.",
        parameters: [
            { name: "type", type: "string", description: "Event type filter", required: false },
            { name: "eventSourceId", type: "string", description: "Event source filter", required: false },
            { name: "ref", type: "string", description: "Ref filter", required: false },
            { name: "limit", type: "number", description: "Max results (default 10, max 20)", required: false },
        ],
        handler: async (args) => {
            let limit = Number(args.limit) || 10;
            limit = Math.max(1, Math.min(20, limit));
            const params = new URLSearchParams();
            if (args.type) params.set("filterType", args.type);
            if (args.eventSourceId) params.set("filterEventSourceId", args.eventSourceId);
            if (args.ref) params.set("filterRef", args.ref);
            params.set("limit", String(limit));
            const result = JSON.parse(await axiomApi("GET", `/stream/events?${params.toString()}`));
            const items = result.items || [];
            if (items.length === 0) return "No stream events found.";
            return items.map(summarizeEvent).join("\n");
        },
    },
    {
        name: "get_stream_event",
        description: "Get a stream event by id, optionally narrowed to a path such as payload.issue.labels[0].name.",
        parameters: [
            { name: "eventId", type: "string", description: "The stream event id", required: true },
            { name: "path", type: "string", description: "Optional path into the event", required: false },
        ],
        handler: async (args) => {
            const e = JSON.parse(await axiomApi("GET", `/stream/events/${encodeURIComponent(args.eventId)}`));
            const normalized = {
                id: e.id, type: e.type, source: e.source, eventSourceId: e.eventSourceId, ref: e.ref,
                timestamp: e.timestamp, actor: e.actor, payload: e.payload,
            };
            const value = args.path ? pickPath(normalized, args.path) : normalized;
            if (value === undefined) return `Path '${args.path}' not found.`;
            return JSON.stringify(truncateDeep(value), null, 2);
        },
    },
    {
        name: "evaluate_expression",
        description: "Evaluate an expression in a scope, optionally against a stream event and extra context.",
        parameters: [
            { name: "expression", type: "string", description: "The expression", required: true },
            { name: "scope", type: "string", description: "The evaluation scope", required: true },
            { name: "eventId", type: "string", description: "Optional stream event id", required: false },
            { name: "context", type: "object", description: "Optional extra context", required: false },
        ],
        handler: async (args) => {
            const body = { expression: args.expression, scope: args.scope };
            if (args.eventId) body.eventId = args.eventId;
            if (args.context !== undefined) body.context = asObject(args.context, "context");
            const r = JSON.parse(await axiomApi("POST", "/expressions/evaluate", body));
            return r.ok ? `OK (${r.valueType}): ${r.valueJson}` : `ERROR: ${r.error}`;
        },
    },
    {
        name: "list_workflow_runs",
        description: "List runs of this composition's published workflow, newest first. Read-only.",
        parameters: [
            { name: "status", type: "string", description: "Optional status filter", required: false },
            { name: "limit", type: "number", description: "Max results (default 10, max 20)", required: false },
        ],
        handler: async (args) => {
            const definitionId = await workflowDefinitionId();
            if (definitionId === null) return "The workflow has not been published yet, so it has no runs.";
            const limit = Math.max(1, Math.min(20, Number(args.limit) || 10));
            const params = new URLSearchParams();
            params.set("definitionId", String(definitionId));
            params.set("limit", String(limit));
            if (args.status) params.set("status", args.status);
            const result = JSON.parse(await axiomApi("GET", `/workflow/runs?${params.toString()}`));
            return formatRunList(result.items || []);
        },
    },
    {
        name: "get_workflow_run",
        description: "Get a run of this composition's workflow: status, failure, history, and the output and log "
            + "tail of failed tasks. Read-only.",
        parameters: [
            { name: "runId", type: "number", description: "The run id", required: true },
        ],
        handler: async (args) => {
            const definitionId = await workflowDefinitionId();
            const run = JSON.parse(await axiomApi("GET", `/workflow/runs/${encodeURIComponent(args.runId)}`));
            if (definitionId === null || Number(run.definitionId) !== Number(definitionId)) {
                return `Run ${args.runId} is not a run of this composition's workflow.`;
            }
            const failed = (run.history || []).filter(h => h.taskId !== undefined && h.taskId !== null
                && /fail|error/i.test(String(h.taskStatus || "")));
            const details = [];
            for (const h of failed) {
                let output = null;
                try {
                    const task = JSON.parse(await axiomApi("GET",
                        `/subjects/${encodeURIComponent(run.subjectId)}/tasks/${encodeURIComponent(h.taskId)}`));
                    output = task.output ?? null;
                } catch (e) {
                    output = `(could not read task: ${e.message})`;
                }
                let logTail = null;
                try {
                    logTail = await taskLogTail(run.subjectId, h.taskId);
                } catch (e) {
                    logTail = `(could not read log: ${e.message})`;
                }
                details.push({ taskId: h.taskId, nodeName: h.nodeName, output, log: logTail });
            }
            return formatRun(run, details);
        },
    },
    {
        name: "get_trace",
        description: "Get a trace (e.g. a run's traceId): its status, summary and nodes, failed nodes first. Read-only.",
        parameters: [
            { name: "traceId", type: "string", description: "The trace id", required: true },
        ],
        handler: async (args) => {
            const detail = JSON.parse(await axiomApi("GET", `/traces/${encodeURIComponent(args.traceId)}`));
            return formatTrace(detail);
        },
    },
    {
        name: "axiom_list_tools",
        description: "List all existing tool definitions in Axiom. Returns names and descriptions of configured script tools.",
        parameters: [],
        handler: async () => {
            const result = JSON.parse(await axiomApi("GET", "/tools?limit=100"));
            const items = result.items || [];
            if (items.length === 0) return "No tools configured.";
            return JSON.stringify(items.map(t => ({
                name: t.name,
                description: t.description || "",
                labels: t.labels || [],
            })), null, 2);
        },
    },
    {
        name: "axiom_get_tool",
        description: "Get full details of a specific tool definition by name, including its parameters and script template.",
        parameters: [
            { name: "name", type: "string", description: "The tool name", required: true },
        ],
        handler: async (args) => {
            const result = JSON.parse(await axiomApi("GET", "/tools?limit=100&filterName=" + encodeURIComponent(args.name)));
            const items = result.items || [];
            const tool = items.find(t => t.name === args.name);
            if (!tool) return `Tool '${args.name}' not found.`;
            return JSON.stringify(tool, null, 2);
        },
    },
    {
        name: "axiom_list_action_types",
        description: "List all existing action types in Axiom. Returns names, descriptions, and execution modes. Action types run only from workflow action nodes.",
        parameters: [],
        handler: async () => {
            const response = JSON.parse(await axiomApi("GET", "/action-types"));
            const items = response.items || [];
            if (items.length === 0) return "No action types configured.";
            return JSON.stringify(items.map(at => ({
                name: at.name,
                description: at.description || "",
                executionMode: at.executionMode,
            })), null, 2);
        },
    },
    {
        name: "axiom_get_action_type",
        description: "Get full details of a specific action type by name, including its prompt template, allowed tools, and configuration.",
        parameters: [
            { name: "name", type: "string", description: "The action type name", required: true },
        ],
        handler: async (args) => {
            const response = JSON.parse(await axiomApi("GET", "/action-types"));
            const items = response.items || [];
            const at = items.find(a => a.name === args.name);
            if (!at) return `Action type '${args.name}' not found.`;
            return JSON.stringify(at, null, 2);
        },
    },
    {
        name: "axiom_list_secrets",
        description: "List all secret names in Axiom. Returns names and descriptions only (values are never exposed). Use these names with ${secret:NAME} syntax in environment variables and configuration.",
        parameters: [],
        handler: async () => {
            const items = JSON.parse(await axiomApi("GET", "/secrets"));
            if (items.length === 0) return "No secrets configured.";
            return JSON.stringify(items.map(s => ({
                name: s.name,
                description: s.description || "",
            })), null, 2);
        },
    },
];

/**
 * Starts the MCP server on stdio. The SDK is loaded lazily so the helpers can be required without it.
 */
async function main() {
    const { Server } = require("@modelcontextprotocol/sdk/server/index.js");
    const { StdioServerTransport } = require("@modelcontextprotocol/sdk/server/stdio.js");
    const { ListToolsRequestSchema, CallToolRequestSchema } = require("@modelcontextprotocol/sdk/types.js");

    if (!AXIOM_COMPOSITION_ID || !AXIOM_SESSION_TOKEN) {
        process.stderr.write("WARNING: AXIOM_COMPOSITION_ID or AXIOM_SESSION_TOKEN is empty; "
            + "composition tools will fail.\n");
    }

    log("INFO", "Axiom Workbench MCP server started", {
        toolCount: TOOLS.length,
        axiomApiUrl: AXIOM_API_URL,
        compositionId: AXIOM_COMPOSITION_ID || null,
    });

    const server = new Server({ name: "workbench", version: "1.0.0" }, {
        capabilities: { tools: {} }
    });

    server.setRequestHandler(ListToolsRequestSchema, async () => ({
        tools: TOOLS.map(t => ({
            name: t.name,
            description: t.description || "",
            inputSchema: {
                type: "object",
                properties: Object.fromEntries(
                    (t.parameters || []).map(p => [p.name, {
                        type: p.type || "string",
                        description: p.description || ""
                    }])
                ),
                required: (t.parameters || []).filter(p => p.required).map(p => p.name)
            }
        }))
    }));

    server.setRequestHandler(CallToolRequestSchema, async (request) => {
        const toolName = request.params.name;
        const args = request.params.arguments || {};
        const tool = TOOLS.find(t => t.name === toolName);
        if (!tool) {
            log("WARN", "Unknown tool called", { toolName });
            return { content: [{ type: "text", text: "Unknown tool: " + toolName }], isError: true };
        }
        log("INFO", "Tool called", { toolName, args: Object.keys(args) });
        try {
            const result = await tool.handler(args);
            return { content: [{ type: "text", text: result || "OK" }] };
        } catch (error) {
            log("ERROR", "Tool failed", { toolName, error: error.message });
            return { content: [{ type: "text", text: error.message }], isError: true };
        }
    });

    const transport = new StdioServerTransport();
    await server.connect(transport);
}

module.exports = {
    summarizeEvent, pickPath, truncateDeep, formatProblems, suggestionBody, formatRunList, formatRun, formatTrace,
};

if (require.main === module) {
    main().catch(console.error);
}
