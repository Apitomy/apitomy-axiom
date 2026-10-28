const { Server } = require("@modelcontextprotocol/sdk/server/index.js");
const { StdioServerTransport } = require("@modelcontextprotocol/sdk/server/stdio.js");
const { ListToolsRequestSchema, CallToolRequestSchema } = require("@modelcontextprotocol/sdk/types.js");

function log(level, message, data) {
    const entry = {
        timestamp: new Date().toISOString(),
        level,
        source: "axiom-assistant-mcp",
        message,
        ...data
    };
    process.stderr.write(JSON.stringify(entry) + "\n");
}

const AXIOM_API_URL = process.env.AXIOM_API_URL || "http://localhost:9090/api/v1";
const AXIOM_SUBJECT_ID = process.env.AXIOM_SUBJECT_ID;
const AXIOM_ASSISTANT_SESSION_ID = process.env.AXIOM_ASSISTANT_SESSION_ID;
const AXIOM_SESSION_TOKEN = process.env.AXIOM_SESSION_TOKEN || "";

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

const TOOLS = [
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
        name: "axiom_list_mcp_servers",
        description: "List all configured MCP servers in Axiom. Returns names and descriptions.",
        parameters: [],
        handler: async () => {
            const items = JSON.parse(await axiomApi("GET", "/mcp-servers"));
            if (items.length === 0) return "No MCP servers configured.";
            return JSON.stringify(items.map(s => ({
                name: s.name,
                description: s.description || "",
            })), null, 2);
        },
    },
    {
        name: "axiom_list_toolsets",
        description: "List all configured toolsets in Axiom. Returns names, descriptions, and their tool lists.",
        parameters: [],
        handler: async () => {
            const items = JSON.parse(await axiomApi("GET", "/toolsets"));
            if (items.length === 0) return "No toolsets configured.";
            return JSON.stringify(items.map(ts => ({
                name: ts.name,
                description: ts.description || "",
                tools: ts.tools || [],
            })), null, 2);
        },
    },
    {
        name: "axiom_get_toolset",
        description: "Get full details of a specific toolset by name, including its tool list.",
        parameters: [
            { name: "name", type: "string", description: "The toolset name", required: true },
        ],
        handler: async (args) => {
            const items = JSON.parse(await axiomApi("GET", "/toolsets"));
            const ts = items.find(t => t.name === args.name);
            if (!ts) return `Toolset '${args.name}' not found.`;
            return JSON.stringify(ts, null, 2);
        },
    },
    {
        name: "axiom_list_session_templates",
        description: "List all AI Assistant session templates in Axiom. Returns template IDs, names, descriptions, and whether they are built-in.",
        parameters: [],
        handler: async () => {
            const items = JSON.parse(await axiomApi("GET", "/assistant/templates"));
            if (items.length === 0) return "No session templates configured.";
            return JSON.stringify(items.map(t => ({
                templateId: t.templateId,
                name: t.name,
                description: t.description || "",
                builtIn: t.builtIn || false,
            })), null, 2);
        },
    },
    {
        name: "axiom_get_session_template",
        description: "Get full details of a specific session template by template ID, including its system prompt, allowed tools, and MCP servers.",
        parameters: [
            { name: "templateId", type: "string", description: "The session template ID", required: true },
        ],
        handler: async (args) => {
            try {
                const result = await axiomApi("GET", "/assistant/templates/" + encodeURIComponent(args.templateId));
                return result;
            } catch (e) {
                return `Session template '${args.templateId}' not found.`;
            }
        },
    },
    {
        name: "axiom_list_event_sources",
        description: "List all configured event sources (GitHub, Jira) with their IDs, names, types, and enabled status.",
        parameters: [],
        handler: async () => {
            const result = JSON.parse(await axiomApi("GET", "/event-sources?limit=100"));
            const items = result.items || [];
            if (items.length === 0) return "No event sources configured.";
            return JSON.stringify(items.map(c => ({
                id: c.id,
                name: c.name,
                sourceType: c.sourceType,
                enabled: c.enabled,
                baseUrl: c.baseUrl || "",
            })), null, 2);
        },
    },
    {
        name: "axiom_get_event_source",
        description: "Get the full details of an event source by its slug ID, including configuration (repositories/subjects), base URL, and poll interval.",
        parameters: [
            { name: "eventSourceId", type: "string", description: "The event source slug ID (e.g., 'github-com')", required: true },
        ],
        handler: async (args) => {
            try {
                return await axiomApi("GET", "/event-sources/" + encodeURIComponent(args.eventSourceId));
            } catch (e) {
                return `Event source '${args.eventSourceId}' not found.`;
            }
        },
    },
    {
        name: "axiom_list_subscriptions",
        description: "List all event subscriptions with their names, enabled status, filter expressions, and routing rules.",
        parameters: [],
        handler: async () => {
            const result = JSON.parse(await axiomApi("GET", "/subscriptions?limit=100"));
            const items = result.items || [];
            if (items.length === 0) return "No subscriptions configured.";
            return JSON.stringify(items.map(s => ({
                id: s.id,
                name: s.name,
                enabled: s.enabled,
                filterExpression: s.filterExpression || "",
                routing: s.routing || [],
            })), null, 2);
        },
    },
    {
        name: "axiom_get_subscription",
        description: "Get the full details of an event subscription by ID, including its filter expression, routing rules, and labels.",
        parameters: [
            { name: "subscriptionId", type: "number", description: "The subscription ID", required: true },
        ],
        handler: async (args) => {
            try {
                return await axiomApi("GET", "/subscriptions/" + encodeURIComponent(args.subscriptionId));
            } catch (e) {
                return `Subscription '${args.subscriptionId}' not found.`;
            }
        },
    },
    {
        name: "axiom_list_workflow_definitions",
        description: "List all workflow definitions with their names, descriptions, and current published version.",
        parameters: [],
        handler: async () => {
            const result = JSON.parse(await axiomApi("GET", "/workflow/definitions?limit=100"));
            const items = result.items || [];
            if (items.length === 0) return "No workflow definitions configured.";
            return JSON.stringify(items.map(wd => ({
                id: wd.id,
                name: wd.name,
                description: wd.description || "",
                currentVersion: wd.currentVersion,
            })), null, 2);
        },
    },
    {
        name: "axiom_get_workflow_definition",
        description: "Get the full details of a workflow definition by ID, including the workflow content (nodes, edges, and their configuration).",
        parameters: [
            { name: "workflowDefinitionId", type: "number", description: "The workflow definition ID", required: true },
        ],
        handler: async (args) => {
            try {
                return await axiomApi("GET", "/workflow/definitions/" + encodeURIComponent(args.workflowDefinitionId));
            } catch (e) {
                return `Workflow definition '${args.workflowDefinitionId}' not found.`;
            }
        },
    },
    {
        name: "axiom_validate_item",
        description: "Validate a draft configuration item BEFORE writing it to a file. type is one of: tool, "
            + "action-type, toolset, session-template, workflow, event-source, subscription. item is the full "
            + "JSON object you intend to write. Items already written in this session count as existing.",
        parameters: [
            { name: "type", type: "string", description: "Item type", required: true },
            { name: "item", type: "object", description: "The complete item JSON", required: true },
        ],
        handler: async (args) => {
            if (!AXIOM_ASSISTANT_SESSION_ID) {
                return "Validation unavailable: no assistant session id.";
            }
            let item = args.item;
            if (typeof item === "string") {
                try {
                    item = JSON.parse(item);
                } catch (e) {
                    return "INVALID — item is not valid JSON: " + e.message;
                }
            }
            const result = JSON.parse(await axiomApi("POST",
                "/assistant/sessions/" + encodeURIComponent(AXIOM_ASSISTANT_SESSION_ID) + "/validate",
                { type: args.type, item }));
            if (result.valid && result.problems.length === 0) {
                return "VALID. You can write this item to its file.";
            }
            const lines = result.problems.map(p =>
                `- [${p.severity.toUpperCase()}] ${p.path || "(root)"} ${p.code}: ${p.message}`);
            return (result.valid ? "VALID with warnings:\n" : "INVALID — fix these and validate again:\n")
                + lines.join("\n");
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
    {
        name: "axiom_list_subjects",
        description: "List subjects in Axiom with optional filtering. Returns names, active flags, issue references, and labels.",
        parameters: [
            { name: "filterName", type: "string", description: "Substring filter on subject name or issue reference", required: false },
            { name: "limit", type: "string", description: "Max results to return (default 20)", required: false },
        ],
        handler: async (args) => {
            let path = "/subjects?limit=" + (args.limit || "20");
            if (args.filterName) path += "&filterName=" + encodeURIComponent(args.filterName);
            const result = JSON.parse(await axiomApi("GET", path));
            const items = result.items || [];
            if (items.length === 0) return "No subjects found.";
            return JSON.stringify(items.map(p => ({
                id: p.id,
                name: p.name,
                active: p.active,
                refSource: p.refSource || "",
                ref: p.ref || "",
                labels: p.labels || [],
            })), null, 2);
        },
    },
    {
        name: "axiom_list_agents",
        description: "List all configured agents in Axiom. Returns ids, names, agent types, enabled flags, capabilities, and descriptions.",
        parameters: [],
        handler: async () => {
            const items = JSON.parse(await axiomApi("GET", "/agents"));
            if (items.length === 0) return "No agents configured.";
            return JSON.stringify(items.map(a => ({
                id: a.id,
                name: a.name,
                description: a.description || "",
                agentType: a.agentType,
                enabled: a.enabled,
                capabilities: a.capabilities || [],
            })), null, 2);
        },
    },
];

if (AXIOM_SUBJECT_ID) {
    const subjectId = AXIOM_SUBJECT_ID;
    TOOLS.push(
        {
            name: "axiom_subject_get_details",
            description: "Get full details of the subject this session is scoped to, including name, description, type, active flag, issue reference, repository, and labels",
            parameters: [],
            handler: async () => {
                return await axiomApi("GET", `/subjects/${encodeURIComponent(subjectId)}`);
            },
        },
        {
            name: "axiom_subject_list_tasks",
            description: "List all tasks for this subject with their status, assigned actor, and action type",
            parameters: [],
            handler: async () => {
                return await axiomApi("GET", `/subjects/${encodeURIComponent(subjectId)}/tasks`);
            },
        },
        {
            name: "axiom_subject_get_task",
            description: "Get detailed information about a specific subject task including input, output, and execution log",
            parameters: [
                { name: "taskId", type: "string", description: "The task ID to retrieve", required: true },
            ],
            handler: async (args) => {
                return await axiomApi("GET", `/subjects/${encodeURIComponent(subjectId)}/tasks/${encodeURIComponent(args.taskId)}`);
            },
        },
        {
            name: "axiom_subject_get_thread",
            description: "Read the subject's discussion thread entries",
            parameters: [],
            handler: async () => {
                return await axiomApi("GET", `/subjects/${encodeURIComponent(subjectId)}/thread`);
            },
        },
        {
            name: "axiom_subject_add_thread_entry",
            description: "Post a new entry to the subject's discussion thread",
            parameters: [
                { name: "content", type: "string", description: "The thread entry content", required: true },
            ],
            handler: async (args) => {
                return await axiomApi("POST", `/subjects/${encodeURIComponent(subjectId)}/thread`, {
                    authorType: "assistant",
                    authorId: "axiom-assistant",
                    entryType: "comment",
                    content: args.content,
                });
            },
        },
        {
            name: "axiom_subject_list_events",
            description: "List events that triggered or relate to this subject",
            parameters: [],
            handler: async () => {
                return await axiomApi("GET", `/subjects/${encodeURIComponent(subjectId)}/events`);
            },
        },
        {
            name: "axiom_subject_list_traces",
            description: "List activity traces for this subject for debugging and review",
            parameters: [],
            handler: async () => {
                return await axiomApi("GET", `/subjects/${encodeURIComponent(subjectId)}/traces`);
            },
        },
    );
}

log("INFO", "Axiom Assistant MCP server started", {
    toolCount: TOOLS.length,
    axiomApiUrl: AXIOM_API_URL,
    subjectId: AXIOM_SUBJECT_ID || null,
});

const server = new Server({ name: "axiom-assistant", version: "3.0.1" }, {
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
        const startTime = Date.now();
        const result = await tool.handler(args);
        const durationMs = Date.now() - startTime;
        log("INFO", "Tool completed", { toolName, durationMs });
        return { content: [{ type: "text", text: result || "OK" }] };
    } catch (error) {
        log("ERROR", "Tool failed", { toolName, error: error.message });
        return { content: [{ type: "text", text: error.message }], isError: true };
    }
});

async function main() {
    const transport = new StdioServerTransport();
    await server.connect(transport);
}
main().catch(console.error);
