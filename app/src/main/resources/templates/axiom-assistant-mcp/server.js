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

async function axiomApi(method, path) {
    const url = `${AXIOM_API_URL}${path}`;
    const opts = {
        method,
        headers: { "Accept": "application/json" },
    };
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
        description: "List all existing action types in Axiom. Returns names, descriptions, and execution modes.",
        parameters: [],
        handler: async () => {
            const items = JSON.parse(await axiomApi("GET", "/action-types"));
            if (items.length === 0) return "No action types configured.";
            return JSON.stringify(items.map(at => ({
                name: at.name,
                description: at.description || "",
                executionMode: at.executionMode,
                userTriggerable: at.userTriggerable,
                managerTriggerable: at.managerTriggerable,
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
            const items = JSON.parse(await axiomApi("GET", "/action-types"));
            const at = items.find(a => a.name === args.name);
            if (!at) return `Action type '${args.name}' not found.`;
            return JSON.stringify(at, null, 2);
        },
    },
    {
        name: "axiom_list_report_definitions",
        description: "List all existing report definitions in Axiom. Returns names, descriptions, and schedules.",
        parameters: [],
        handler: async () => {
            const items = JSON.parse(await axiomApi("GET", "/reports/definitions"));
            if (items.length === 0) return "No report definitions configured.";
            return JSON.stringify(items.map(rd => ({
                name: rd.name,
                description: rd.description || "",
                schedule: rd.schedule,
                enabled: rd.enabled,
            })), null, 2);
        },
    },
    {
        name: "axiom_get_report_definition",
        description: "Get full details of a specific report definition by name, including its prompt template, schedule, and allowed tools.",
        parameters: [
            { name: "name", type: "string", description: "The report definition name", required: true },
        ],
        handler: async (args) => {
            const items = JSON.parse(await axiomApi("GET", "/reports/definitions"));
            const rd = items.find(r => r.name === args.name);
            if (!rd) return `Report definition '${args.name}' not found.`;
            return JSON.stringify(rd, null, 2);
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
];

log("INFO", "Axiom Assistant MCP server started", {
    toolCount: TOOLS.length,
    axiomApiUrl: AXIOM_API_URL,
});

const server = new Server({ name: "axiom-assistant", version: "1.0.0" }, {
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
