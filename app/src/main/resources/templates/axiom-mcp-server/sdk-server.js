const { Server } = require("@modelcontextprotocol/sdk/server/index.js");
const { StdioServerTransport } = require("@modelcontextprotocol/sdk/server/stdio.js");
const { ListToolsRequestSchema, CallToolRequestSchema } = require("@modelcontextprotocol/sdk/types.js");
const { startToolTrace, completeToolTrace } = require(require("path").join(__dirname, "trace-helper.js"));

function log(level, message, data) {
    const entry = {
        timestamp: new Date().toISOString(),
        level,
        source: "axiom-sdk-server",
        message,
        ...data
    };
    process.stderr.write(JSON.stringify(entry) + "\n");
}

const AXIOM_API_URL = process.env.AXIOM_API_URL || "http://localhost:9090/api/v1";

async function axiomApi(method, path, body, { contentType = "application/json", rawBody = false } = {}) {
    const url = `${AXIOM_API_URL}${path}`;
    const opts = {
        method,
        headers: { "Content-Type": contentType, "Accept": "application/json" },
    };
    if (body) opts.body = rawBody ? body : JSON.stringify(body);
    const resp = await fetch(url, opts);
    const text = await resp.text();
    if (!resp.ok) {
        throw new Error(`Axiom API ${method} ${path} returned ${resp.status}: ${text.substring(0, 500)}`);
    }
    return text;
}

const SDK_TOOLS = [
    {
        name: "axiom_fire_event",
        description: "Fire a new event into Axiom for processing by the Manager.",
        parameters: [
            { name: "source", type: "string", description: "Event source type (e.g. 'github', 'jira', 'internal')", required: true },
            { name: "eventType", type: "string", description: "Event type (e.g. 'issue-created', 'custom')", required: true },
            { name: "issueRef", type: "string", description: "Issue reference (e.g. 'owner/repo#123')", required: false },
            { name: "repository", type: "string", description: "Repository identifier (e.g. 'owner/repo')", required: false },
            { name: "payload", type: "string", description: "JSON payload with event details", required: true },
        ],
        handler: async (args) => {
            return await axiomApi("POST", "/events", {
                source: args.source,
                eventType: args.eventType,
                issueRef: args.issueRef || null,
                repository: args.repository || null,
                payload: args.payload,
            });
        },
    },
    {
        name: "axiom_list_projects",
        description: "List existing Axiom projects with optional filtering by name, status, labels, and ref.",
        parameters: [
            { name: "filterName", type: "string", description: "Filter by project name or issue ref (substring match)", required: false },
            { name: "filterStatus", type: "string", description: "Filter by status: Created, InProgress, Idle, Completed (comma-separated for multiple)", required: false },
            { name: "filterLabels", type: "string", description: "Filter by labels (comma-separated, AND logic)", required: false },
            { name: "filterRef", type: "string", description: "Exact-match filter on the project issue reference (e.g. 'owner/repo#42')", required: false },
        ],
        handler: async (args) => {
            const params = new URLSearchParams();
            params.set("limit", "50");
            if (args.filterName) params.set("filterName", args.filterName);
            if (args.filterStatus) params.set("filterStatus", args.filterStatus);
            if (args.filterLabels) params.set("filterLabels", args.filterLabels);
            if (args.filterRef) params.set("filterRef", args.filterRef);
            return await axiomApi("GET", `/projects?${params}`);
        },
    },
    {
        name: "axiom_get_project",
        description: "Get details of a specific Axiom project including metadata, status, and issue reference.",
        parameters: [
            { name: "projectId", type: "number", description: "The project ID", required: true },
        ],
        handler: async (args) => {
            return await axiomApi("GET", `/projects/${args.projectId}`);
        },
    },
    {
        name: "axiom_create_task",
        description: "Create a new task on an Axiom project. The task will be queued and executed by an available actor.",
        parameters: [
            { name: "projectId", type: "number", description: "The project ID to create the task on", required: true },
            { name: "actionType", type: "string", description: "The action type name (e.g. 'analyze', 'implement')", required: true },
            { name: "input", type: "string", description: "Input context or instructions for the task", required: false },
        ],
        handler: async (args) => {
            return await axiomApi("POST", `/projects/${args.projectId}/tasks`, {
                actionType: args.actionType,
                input: args.input || null,
            });
        },
    },
    {
        name: "axiom_get_task_status",
        description: "Get the status and details of a specific task on a project.",
        parameters: [
            { name: "projectId", type: "number", description: "The project ID", required: true },
            { name: "taskId", type: "number", description: "The task ID", required: true },
        ],
        handler: async (args) => {
            const result = await axiomApi("GET", `/tasks?filterProjectId=${args.projectId}&limit=100`);
            const parsed = JSON.parse(result);
            const task = (parsed.items || []).find(t => t.id === Number(args.taskId));
            return task ? JSON.stringify(task, null, 2) : `Task #${args.taskId} not found in project #${args.projectId}`;
        },
    },
    {
        name: "axiom_add_thread_entry",
        description: "Post an update or message to a project's conversation thread.",
        parameters: [
            { name: "projectId", type: "number", description: "The project ID", required: true },
            { name: "content", type: "string", description: "The message content to post", required: true },
        ],
        handler: async (args) => {
            return await axiomApi("POST", `/projects/${args.projectId}/thread`, {
                content: args.content,
            });
        },
    },
    {
        name: "axiom_close_project",
        description: "Close (complete) an Axiom project.",
        parameters: [
            { name: "projectId", type: "number", description: "The project ID to close", required: true },
        ],
        handler: async (args) => {
            return await axiomApi("POST", `/projects/${args.projectId}/close`);
        },
    },
    {
        name: "axiom_reopen_project",
        description: "Reopen a previously closed Axiom project.",
        parameters: [
            { name: "projectId", type: "number", description: "The project ID to reopen", required: true },
        ],
        handler: async (args) => {
            return await axiomApi("POST", `/projects/${args.projectId}/reopen`);
        },
    },
    {
        name: "axiom_add_project_label",
        description: "Add a label to an Axiom project for categorization and filtering.",
        parameters: [
            { name: "projectId", type: "number", description: "The project ID", required: true },
            { name: "label", type: "string", description: "The label to add", required: true },
        ],
        handler: async (args) => {
            const project = JSON.parse(await axiomApi("GET", `/projects/${args.projectId}`));
            const labels = project.labels || [];
            if (!labels.includes(args.label)) {
                labels.push(args.label);
                return await axiomApi("PUT", `/projects/${args.projectId}`, { labels });
            }
            return JSON.stringify(project);
        },
    },
    {
        name: "axiom_remove_project_label",
        description: "Remove a label from an Axiom project.",
        parameters: [
            { name: "projectId", type: "number", description: "The project ID", required: true },
            { name: "label", type: "string", description: "The label to remove", required: true },
        ],
        handler: async (args) => {
            const project = JSON.parse(await axiomApi("GET", `/projects/${args.projectId}`));
            const labels = (project.labels || []).filter(l => l !== args.label);
            return await axiomApi("PUT", `/projects/${args.projectId}`, { labels });
        },
    },
    {
        name: "axiom_add_report_label",
        description: "Add a label to a generated Axiom report for categorization and filtering.",
        parameters: [
            { name: "reportId", type: "number", description: "The report ID", required: true },
            { name: "label", type: "string", description: "The label to add", required: true },
        ],
        handler: async (args) => {
            const report = JSON.parse(await axiomApi("GET", `/reports/${args.reportId}`));
            const labels = report.labels || [];
            if (!labels.includes(args.label)) {
                labels.push(args.label);
                return await axiomApi("PUT", `/reports/${args.reportId}/labels`, labels);
            }
            return JSON.stringify(report);
        },
    },
    {
        name: "axiom_remove_report_label",
        description: "Remove a label from a generated Axiom report.",
        parameters: [
            { name: "reportId", type: "number", description: "The report ID", required: true },
            { name: "label", type: "string", description: "The label to remove", required: true },
        ],
        handler: async (args) => {
            const report = JSON.parse(await axiomApi("GET", `/reports/${args.reportId}`));
            const labels = (report.labels || []).filter(l => l !== args.label);
            return await axiomApi("PUT", `/reports/${args.reportId}/labels`, labels);
        },
    },
    {
        name: "axiom_list_tools",
        description: "List all custom tool definitions configured in Axiom. Returns names, descriptions, and parameter info for each tool.",
        parameters: [
            { name: "filterName", type: "string", description: "Filter by tool name or description (substring match)", required: false },
            { name: "filterLabels", type: "string", description: "Filter by labels (comma-separated, AND logic)", required: false },
        ],
        handler: async (args) => {
            const params = new URLSearchParams();
            params.set("limit", "100");
            if (args.filterName) params.set("filterName", args.filterName);
            if (args.filterLabels) params.set("filterLabels", args.filterLabels);
            return await axiomApi("GET", `/tools?${params}`);
        },
    },
    {
        name: "axiom_list_report_definitions",
        description: "List all report definitions configured in Axiom. Returns names, descriptions, schedules, and enabled status.",
        parameters: [],
        handler: async () => {
            return await axiomApi("GET", "/reports/definitions");
        },
    },
    {
        name: "axiom_list_reports",
        description: "List generated reports with optional filtering by definition, status, title, and labels.",
        parameters: [
            { name: "filterDefinitionId", type: "number", description: "Filter by report definition ID", required: false },
            { name: "filterStatus", type: "string", description: "Filter by status (comma-separated)", required: false },
            { name: "filterTitle", type: "string", description: "Filter by title (substring match)", required: false },
            { name: "filterLabels", type: "string", description: "Filter by labels (comma-separated, AND logic)", required: false },
        ],
        handler: async (args) => {
            const params = new URLSearchParams();
            params.set("limit", "50");
            if (args.filterDefinitionId) params.set("filterDefinitionId", String(args.filterDefinitionId));
            if (args.filterStatus) params.set("filterStatus", args.filterStatus);
            if (args.filterTitle) params.set("filterTitle", args.filterTitle);
            if (args.filterLabels) params.set("filterLabels", args.filterLabels);
            return await axiomApi("GET", `/reports?${params}`);
        },
    },
    {
        name: "axiom_get_project_thread",
        description: "Read the conversation thread for a project. Returns all messages posted by actors, the manager, and humans.",
        parameters: [
            { name: "projectId", type: "number", description: "The project ID", required: true },
        ],
        handler: async (args) => {
            return await axiomApi("GET", `/projects/${args.projectId}/thread`);
        },
    },
    {
        name: "axiom_list_action_types",
        description: "List all action types configured in Axiom. Returns names, descriptions, execution modes, and trigger settings.",
        parameters: [
            { name: "filterName", type: "string", description: "Filter by action type name (substring match)", required: false },
        ],
        handler: async (args) => {
            const params = new URLSearchParams();
            params.set("limit", "100");
            if (args.filterName) params.set("filterName", args.filterName);
            return await axiomApi("GET", `/action-types?${params}`);
        },
    },
    {
        name: "axiom_list_actors",
        description: "List all actors (human and AI agent) configured in Axiom. Returns names, types, capabilities, and descriptions.",
        parameters: [],
        handler: async () => {
            return await axiomApi("GET", "/actors");
        },
    },
    {
        name: "axiom_update_project",
        description: "Update an Axiom project's metadata such as name, body, or labels.",
        parameters: [
            { name: "projectId", type: "number", description: "The project ID to update", required: true },
            { name: "name", type: "string", description: "New project name", required: false },
            { name: "body", type: "string", description: "New project body (markdown)", required: false },
            { name: "labels", type: "string", description: "Comma-separated list of labels to set on the project", required: false },
        ],
        handler: async (args) => {
            const payload = {};
            if (args.name) payload.name = args.name;
            if (args.body) payload.body = args.body;
            if (args.labels) payload.labels = args.labels.split(",").map(l => l.trim()).filter(Boolean);
            return await axiomApi("PUT", `/projects/${args.projectId}`, payload);
        },
    },
    {
        name: "axiom_update_project_body",
        description: "Update an Axiom project's body with markdown content.",
        parameters: [
            { name: "projectId", type: "number", description: "The project ID", required: true },
            { name: "body", type: "string", description: "The markdown body content", required: true },
        ],
        handler: async (args) => {
            await axiomApi("PUT", `/projects/${args.projectId}/body`, args.body, {
                contentType: "text/markdown", rawBody: true,
            });
            return "OK";
        },
    },
    {
        name: "axiom_list_events",
        description: "List events related to an Axiom project. Returns event source, type, payload, and timestamps.",
        parameters: [
            { name: "projectId", type: "number", description: "The project ID", required: true },
        ],
        handler: async (args) => {
            return await axiomApi("GET", `/projects/${args.projectId}/events`);
        },
    },
    {
        name: "axiom_respond_to_task",
        description: "Submit a response to a task that is waiting for human input.",
        parameters: [
            { name: "projectId", type: "number", description: "The project ID", required: true },
            { name: "taskId", type: "number", description: "The task ID awaiting a response", required: true },
            { name: "response", type: "string", description: "The response content to submit", required: true },
        ],
        handler: async (args) => {
            return await axiomApi("POST", `/projects/${args.projectId}/tasks/${args.taskId}/respond`, {
                response: args.response,
            });
        },
    },
    {
        name: "axiom_create_project",
        description: "Create a new Axiom project programmatically.",
        parameters: [
            { name: "name", type: "string", description: "The project name", required: true },
            { name: "type", type: "string", description: "The project type (e.g. 'bug-fix', 'feature', 'cve', 'issue', 'pull-request')", required: true },
            { name: "ref", type: "string", description: "Project reference (e.g. 'owner/repo#123' or 'CVE-2024-12345')", required: true },
            { name: "refSource", type: "string", description: "Reference source (e.g. 'github', 'jira')", required: false },
            { name: "repository", type: "string", description: "Repository identifier (e.g. 'owner/repo')", required: false },
            { name: "body", type: "string", description: "Optional markdown body content (e.g. issue body)", required: false },
            { name: "metadata", type: "string", description: "Optional JSON object of key-value metadata (e.g. '{\"priority\":\"high\"}')", required: false },
        ],
        handler: async (args) => {
            const body = {
                name: args.name,
                type: args.type,
                ref: args.ref,
                refSource: args.refSource || undefined,
                repository: args.repository || undefined,
            };
            if (args.body) body.body = args.body;
            if (args.metadata) {
                try {
                    body.metadata = JSON.parse(args.metadata);
                } catch (e) {
                    throw new Error(`Invalid JSON in metadata parameter: ${e.message}`);
                }
            }
            return await axiomApi("POST", "/projects", body);
        },
    },
    {
        name: "axiom_get_activity_log",
        description: "Get the global activity log, optionally filtered by project. Returns a timeline of events, tasks, and actions.",
        parameters: [
            { name: "projectId", type: "number", description: "Filter by project ID", required: false },
            { name: "limit", type: "number", description: "Maximum number of entries to return (default 50)", required: false },
        ],
        handler: async (args) => {
            const params = new URLSearchParams();
            params.set("limit", String(args.limit || 50));
            if (args.projectId) params.set("filterProjectId", String(args.projectId));
            return await axiomApi("GET", `/activity?${params}`);
        },
    },
];

log("INFO", "Axiom SDK MCP server started", {
    toolCount: SDK_TOOLS.length,
    axiomApiUrl: AXIOM_API_URL,
});

const server = new Server({ name: "axiom-sdk", version: "1.0.0" }, {
    capabilities: { tools: {} }
});

server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: SDK_TOOLS.map(t => ({
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

    const tool = SDK_TOOLS.find(t => t.name === toolName);
    if (!tool) {
        log("WARN", "Unknown tool called", { toolName });
        return { content: [{ type: "text", text: "Unknown tool: " + toolName }], isError: true };
    }

    log("INFO", "SDK tool called", { toolName, args: Object.keys(args) });
    const toolInput = JSON.stringify(args);
    const traceNodeId = await startToolTrace(toolName, toolInput);

    let result, status = "success";
    const startTime = Date.now();
    try {
        result = await tool.handler(args);
        const durationMs = Date.now() - startTime;
        log("INFO", "SDK tool completed", { toolName, durationMs });
        await completeToolTrace(traceNodeId, result, status, durationMs);
        return { content: [{ type: "text", text: result || "OK" }] };
    } catch (error) {
        status = "failure";
        const durationMs = Date.now() - startTime;
        log("ERROR", "SDK tool failed", { toolName, error: error.message });
        await completeToolTrace(traceNodeId, error.message, status, durationMs);
        return { content: [{ type: "text", text: error.message }], isError: true };
    }
});

async function main() {
    const transport = new StdioServerTransport();
    await server.connect(transport);
}
main().catch(console.error);
