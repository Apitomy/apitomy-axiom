const { Server } = require("@modelcontextprotocol/sdk/server/index.js");
const { StdioServerTransport } = require("@modelcontextprotocol/sdk/server/stdio.js");
const { ListToolsRequestSchema, CallToolRequestSchema } = require("@modelcontextprotocol/sdk/types.js");
const { startToolTrace, completeToolTrace } = require(require("path").join(__dirname, "trace-helper.js"));
const { execSync } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");

function log(level, message, data) {
    const entry = {
        timestamp: new Date().toISOString(),
        level,
        source: "axiom-tools-server",
        message,
        ...data
    };
    process.stderr.write(JSON.stringify(entry) + "\n");
}

const toolsFile = process.argv[2];
if (!toolsFile) {
    log("ERROR", "Usage: node tools-server.js <tools.json>");
    process.exit(1);
}
const SCRIPT_TOOLS = JSON.parse(fs.readFileSync(toolsFile, "utf-8"));

log("INFO", "Axiom Tools MCP server started", {
    toolCount: SCRIPT_TOOLS.length,
    toolsFile,
});

const server = new Server({ name: "axiom-tools", version: "1.0.0" }, {
    capabilities: { tools: {} }
});

server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: SCRIPT_TOOLS.map(t => ({
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

    const tool = SCRIPT_TOOLS.find(t => t.name === toolName);
    if (!tool) {
        log("WARN", "Unknown tool called", { toolName });
        return { content: [{ type: "text", text: "Unknown tool: " + toolName }], isError: true };
    }

    log("INFO", "Script tool called", { toolName, args: Object.keys(args) });
    const toolInput = JSON.stringify(args);
    const traceNodeId = await startToolTrace(toolName, toolInput);

    try {
        let cmd = tool.scriptTemplate;
        for (const [key, value] of Object.entries(args)) {
            const fileKey = "{{" + key + "_file}}";
            if (cmd.includes(fileKey)) {
                const tmpFile = path.join(os.tmpdir(), `axiom-tool-${toolName}-${key}-${Date.now()}.txt`);
                fs.writeFileSync(tmpFile, String(value));
                cmd = cmd.replaceAll(fileKey, tmpFile);
            }
            cmd = cmd.replaceAll("{{" + key + "}}", String(value));
        }

        // Clear any remaining placeholders for optional parameters not provided
        const optionalParams = (tool.parameters || []).filter(p => !p.required && !(p.name in args));
        for (const p of optionalParams) {
            cmd = cmd.replaceAll("{{" + p.name + "_file}}", "");
            cmd = cmd.replaceAll("{{" + p.name + "}}", "");
        }

        const scriptFile = path.join(os.tmpdir(),
                `axiom-tool-${toolName}-${Date.now()}.sh`);
        fs.writeFileSync(scriptFile, cmd);
        log("DEBUG", "Executing script", { toolName, scriptFile });

        const startTime = Date.now();
        let result;
        try {
            result = execSync(`bash "${scriptFile}"`, {
                encoding: "utf-8",
                timeout: 30000,
                env: { ...process.env }
            });
        } finally {
            try { fs.unlinkSync(scriptFile); } catch (_) {}
        }
        const durationMs = Date.now() - startTime;

        log("INFO", "Script tool completed", { toolName, durationMs, outputLength: (result || "").length });
        await completeToolTrace(traceNodeId, result, "success", durationMs);
        return { content: [{ type: "text", text: result || "Command completed successfully" }] };
    } catch (error) {
        const msg = error.stderr || error.stdout || error.message || "Command failed";
        log("ERROR", "Script tool failed", { toolName, exitCode: error.status, error: msg.substring(0, 500) });
        await completeToolTrace(traceNodeId, msg, "failure", 0);
        return { content: [{ type: "text", text: msg }], isError: true };
    }
});

async function main() {
    const transport = new StdioServerTransport();
    await server.connect(transport);
}
main().catch(console.error);
