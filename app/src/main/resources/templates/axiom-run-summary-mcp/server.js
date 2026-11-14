// Read-only MCP server used by Axiom to let an agent investigate a finished workflow run while summarizing it.
// Stateless: every tool takes a runId. Only GET requests are made.
"use strict";

const API_URL = (process.env.AXIOM_API_URL || "http://localhost:9090/api/v1").replace(/\/$/, "");
const MAX_TEXT = 20000;
let fetchImpl = (...args) => fetch(...args);

/** Replaces fetch (tests only). */
function setFetch(fn) { fetchImpl = fn; }

async function get(path, accept = "application/json") {
    const resp = await fetchImpl(API_URL + path, { method: "GET", headers: { Accept: accept } });
    const text = await resp.text();
    if (!resp.ok) {
        throw new Error(`GET ${path} failed (${resp.status}): ${text.slice(0, 300)}`);
    }
    return text;
}

function clip(text) {
    return text.length <= MAX_TEXT ? text : text.slice(0, MAX_TEXT) + `\n…[truncated ${text.length - MAX_TEXT} chars]`;
}

/** Returns a window of text with clamped bounds. */
function pageText(text, offset, limit) {
    const total = text.length;
    const start = Math.min(Math.max(0, Number(offset) || 0), total);
    const end = Math.min(total, start + Math.max(1, Math.min(Number(limit) || 8000, MAX_TEXT)));
    return { text: text.slice(start, end), start, end, total };
}

/**
 * Validates that a value is a positive safe integer (number or digit-only string) and returns it as a number.
 * Prevents path traversal via ids such as "." or "..".
 */
function requireId(value, name) {
    const n = typeof value === "number" ? value
        : (typeof value === "string" && /^[0-9]+$/.test(value.trim()) ? Number(value.trim()) : NaN);
    if (!Number.isSafeInteger(n) || n <= 0) {
        throw new Error(`${name} must be a positive integer`);
    }
    return n;
}

async function loadRun(runId) {
    return JSON.parse(await get(`/workflow/runs/${requireId(runId, "runId")}`));
}

function runTask(run, taskId) {
    return (run.history || []).find(h => h.taskId !== undefined && h.taskId !== null
        && Number(h.taskId) === Number(taskId));
}

const TOOLS = [
    {
        name: "get_run",
        description: "Get a workflow run: status, inputs, timings, labels, failure reason, and its steps "
            + "(task id, node, status). Read-only.",
        parameters: [{ name: "runId", type: "number", description: "The run id", required: true }],
        handler: async (args) => clip(JSON.stringify(await loadRun(args.runId), null, 2)),
    },
    {
        name: "get_task",
        description: "Get the full input and output of one task (step) of a run. Read-only.",
        parameters: [
            { name: "runId", type: "number", description: "The run id", required: true },
            { name: "taskId", type: "number", description: "The task id from get_run", required: true },
        ],
        handler: async (args) => {
            const taskId = requireId(args.taskId, "taskId");
            const run = await loadRun(args.runId);
            if (!runTask(run, args.taskId)) {
                return `Task ${args.taskId} is not part of run ${args.runId}.`;
            }
            const task = JSON.parse(await get(`/subjects/${encodeURIComponent(run.subjectId)}/tasks/${taskId}`));
            return clip(JSON.stringify({ id: task.id, status: task.status, input: task.input, output: task.output }, null, 2));
        },
    },
    {
        name: "get_task_log",
        description: "Read part of a task's execution log. Use offset/limit (characters) to page. Read-only.",
        parameters: [
            { name: "runId", type: "number", description: "The run id", required: true },
            { name: "taskId", type: "number", description: "The task id from get_run", required: true },
            { name: "offset", type: "number", description: "Start character (default 0)", required: false },
            { name: "limit", type: "number", description: "Characters to read (default 8000)", required: false },
        ],
        handler: async (args) => {
            const taskId = requireId(args.taskId, "taskId");
            const run = await loadRun(args.runId);
            if (!runTask(run, args.taskId)) {
                return `Task ${args.taskId} is not part of run ${args.runId}.`;
            }
            let log;
            try {
                log = await get(`/subjects/${encodeURIComponent(run.subjectId)}/tasks/${taskId}/log`, "text/plain");
            } catch (e) {
                return `No execution log is available for task ${args.taskId}.`;
            }
            const page = pageText(log, args.offset, args.limit);
            return `[chars ${page.start}-${page.end} of ${page.total}]\n${page.text}`;
        },
    },
    {
        name: "get_trace",
        description: "Get the execution trace of a run (node timings and statuses, failed nodes first). Read-only.",
        parameters: [{ name: "runId", type: "number", description: "The run id", required: true }],
        handler: async (args) => {
            const run = await loadRun(args.runId);
            if (!run.traceId) {
                return `Run ${args.runId} has no trace.`;
            }
            const trace = JSON.parse(await get(`/traces/${encodeURIComponent(run.traceId)}`));
            const nodes = (trace.nodes || []).slice().sort((a, b) =>
                (/fail|error/i.test(String(b.status)) ? 1 : 0) - (/fail|error/i.test(String(a.status)) ? 1 : 0));
            return clip(JSON.stringify({ ...trace, nodes }, null, 2));
        },
    },
];

async function main() {
    const { Server } = require("@modelcontextprotocol/sdk/server/index.js");
    const { StdioServerTransport } = require("@modelcontextprotocol/sdk/server/stdio.js");
    const { ListToolsRequestSchema, CallToolRequestSchema } = require("@modelcontextprotocol/sdk/types.js");
    const server = new Server({ name: "axiom-run-summary", version: "3.0.6" }, { capabilities: { tools: {} } });
    server.setRequestHandler(ListToolsRequestSchema, async () => ({
        tools: TOOLS.map(t => ({
            name: t.name,
            description: t.description,
            inputSchema: {
                type: "object",
                properties: Object.fromEntries(t.parameters.map(p => [p.name, { type: p.type, description: p.description }])),
                required: t.parameters.filter(p => p.required).map(p => p.name),
            },
        })),
    }));
    server.setRequestHandler(CallToolRequestSchema, async (request) => {
        const tool = TOOLS.find(t => t.name === request.params.name);
        if (!tool) {
            return { content: [{ type: "text", text: "Unknown tool: " + request.params.name }], isError: true };
        }
        try {
            const text = await tool.handler(request.params.arguments || {});
            return { content: [{ type: "text", text: text || "OK" }] };
        } catch (error) {
            return { content: [{ type: "text", text: error.message }], isError: true };
        }
    });
    await server.connect(new StdioServerTransport());
}

module.exports = { TOOLS, setFetch, pageText, requireId };

if (require.main === module) {
    main().catch((e) => { console.error(e); process.exit(1); });
}
