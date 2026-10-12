const { Server } = require("@modelcontextprotocol/sdk/server/index.js");
const { StdioServerTransport } = require("@modelcontextprotocol/sdk/server/stdio.js");
const { ListToolsRequestSchema, CallToolRequestSchema } = require("@modelcontextprotocol/sdk/types.js");

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
const AXIOM_COMPOSITION_ID = process.env.AXIOM_COMPOSITION_ID;
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
        name: "get_composition",
        description: "Get the current workbench composition: the workflow draft and its related action types and tools.",
        parameters: [],
        handler: async () => {
            if (!AXIOM_COMPOSITION_ID) {
                throw new Error("This session is not bound to a composition.");
            }
            return await axiomApi("GET", `/compositions/${encodeURIComponent(AXIOM_COMPOSITION_ID)}/agent/composition`);
        },
    },
];

log("INFO", "Axiom Workbench MCP server started", {
    toolCount: TOOLS.length,
    axiomApiUrl: AXIOM_API_URL,
    compositionId: AXIOM_COMPOSITION_ID || null,
});

const server = new Server({ name: "axiom-workbench", version: "1.0.0" }, {
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

async function main() {
    const transport = new StdioServerTransport();
    await server.connect(transport);
}
main().catch(console.error);
