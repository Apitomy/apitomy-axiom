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

// Handler implementations keyed by function name.
// Metadata (name, description, parameters) is fetched from the registry API.
const HANDLERS = {
    axiom_fire_event: async (args) => {
        return await axiomApi("POST", "/events", {
            source: args.source,
            eventType: args.eventType,
            issueRef: args.issueRef || null,
            repository: args.repository || null,
            payload: args.payload,
        });
    },
    axiom_list_projects: async (args) => {
        const params = new URLSearchParams();
        params.set("limit", "50");
        if (args.filterName) params.set("filterName", args.filterName);
        if (args.filterStatus) params.set("filterStatus", args.filterStatus);
        if (args.filterLabels) params.set("filterLabels", args.filterLabels);
        if (args.filterRef) params.set("filterRef", args.filterRef);
        return await axiomApi("GET", `/projects?${params}`);
    },
    axiom_get_project: async (args) => {
        return await axiomApi("GET", `/projects/${args.projectId}`);
    },
    axiom_create_task: async (args) => {
        return await axiomApi("POST", `/projects/${args.projectId}/tasks`, {
            actionType: args.actionType,
            input: args.input || null,
        });
    },
    axiom_get_task_status: async (args) => {
        const result = await axiomApi("GET", `/tasks?filterProjectId=${args.projectId}&limit=100`);
        const parsed = JSON.parse(result);
        const task = (parsed.items || []).find(t => t.id === Number(args.taskId));
        return task ? JSON.stringify(task, null, 2) : `Task #${args.taskId} not found in project #${args.projectId}`;
    },
    axiom_add_thread_entry: async (args) => {
        return await axiomApi("POST", `/projects/${args.projectId}/thread`, {
            content: args.content,
        });
    },
    axiom_close_project: async (args) => {
        return await axiomApi("POST", `/projects/${args.projectId}/close`);
    },
    axiom_reopen_project: async (args) => {
        return await axiomApi("POST", `/projects/${args.projectId}/reopen`);
    },
    axiom_add_project_label: async (args) => {
        const project = JSON.parse(await axiomApi("GET", `/projects/${args.projectId}`));
        const labels = project.labels || [];
        if (!labels.includes(args.label)) {
            labels.push(args.label);
            return await axiomApi("PUT", `/projects/${args.projectId}`, { labels });
        }
        return JSON.stringify(project);
    },
    axiom_remove_project_label: async (args) => {
        const project = JSON.parse(await axiomApi("GET", `/projects/${args.projectId}`));
        const labels = (project.labels || []).filter(l => l !== args.label);
        return await axiomApi("PUT", `/projects/${args.projectId}`, { labels });
    },
    axiom_add_report_label: async (args) => {
        const report = JSON.parse(await axiomApi("GET", `/reports/${args.reportId}`));
        const labels = report.labels || [];
        if (!labels.includes(args.label)) {
            labels.push(args.label);
            return await axiomApi("PUT", `/reports/${args.reportId}/labels`, labels);
        }
        return JSON.stringify(report);
    },
    axiom_remove_report_label: async (args) => {
        const report = JSON.parse(await axiomApi("GET", `/reports/${args.reportId}`));
        const labels = (report.labels || []).filter(l => l !== args.label);
        return await axiomApi("PUT", `/reports/${args.reportId}/labels`, labels);
    },
    axiom_list_tools: async (args) => {
        const params = new URLSearchParams();
        params.set("limit", "100");
        if (args.filterName) params.set("filterName", args.filterName);
        if (args.filterLabels) params.set("filterLabels", args.filterLabels);
        return await axiomApi("GET", `/tools?${params}`);
    },
    axiom_list_report_definitions: async () => {
        return await axiomApi("GET", "/reports/definitions");
    },
    axiom_list_reports: async (args) => {
        const params = new URLSearchParams();
        params.set("limit", "50");
        if (args.filterDefinitionId) params.set("filterDefinitionId", String(args.filterDefinitionId));
        if (args.filterStatus) params.set("filterStatus", args.filterStatus);
        if (args.filterTitle) params.set("filterTitle", args.filterTitle);
        if (args.filterLabels) params.set("filterLabels", args.filterLabels);
        return await axiomApi("GET", `/reports?${params}`);
    },
    axiom_get_project_thread: async (args) => {
        return await axiomApi("GET", `/projects/${args.projectId}/thread`);
    },
    axiom_list_action_types: async (args) => {
        const params = new URLSearchParams();
        params.set("limit", "100");
        if (args.filterName) params.set("filterName", args.filterName);
        return await axiomApi("GET", `/action-types?${params}`);
    },
    axiom_list_agents: async () => {
        return await axiomApi("GET", "/actors");
    },
    axiom_update_project: async (args) => {
        const payload = {};
        if (args.name) payload.name = args.name;
        if (args.body) payload.body = args.body;
        if (args.labels) payload.labels = args.labels.split(",").map(l => l.trim()).filter(Boolean);
        return await axiomApi("PUT", `/projects/${args.projectId}`, payload);
    },
    axiom_update_project_body: async (args) => {
        await axiomApi("PUT", `/projects/${args.projectId}/body`, args.body, {
            contentType: "text/markdown", rawBody: true,
        });
        return "OK";
    },
    axiom_list_events: async (args) => {
        return await axiomApi("GET", `/projects/${args.projectId}/events`);
    },
    axiom_respond_to_task: async (args) => {
        return await axiomApi("POST", `/projects/${args.projectId}/tasks/${args.taskId}/respond`, {
            response: args.response,
        });
    },
    axiom_create_project: async (args) => {
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
    axiom_get_activity_log: async (args) => {
        const params = new URLSearchParams();
        params.set("limit", String(args.limit || 50));
        if (args.projectId) params.set("filterProjectId", String(args.projectId));
        return await axiomApi("GET", `/activity?${params}`);
    },
};

async function loadRegistry() {
    try {
        const json = await axiomApi("GET", "/sdk/functions");
        return JSON.parse(json);
    } catch (e) {
        log("WARN", "Failed to load SDK registry from API, using handler names as fallback", { error: e.message });
        return Object.keys(HANDLERS).map(name => ({
            name,
            description: "",
            parameters: [],
        }));
    }
}

async function main() {
    const registry = await loadRegistry();
    const SDK_TOOLS = registry
        .filter(fn => HANDLERS[fn.name])
        .map(fn => ({ ...fn, handler: HANDLERS[fn.name] }));

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

    const transport = new StdioServerTransport();
    await server.connect(transport);
}
main().catch(console.error);
