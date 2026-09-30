/** Canonical, engine-neutral tool kinds used by the chat UI. */
export type ToolKind = "bash" | "read" | "write" | "edit" | "glob" | "grep" | "list" | "agent" | "webfetch"
    | "websearch" | "todo" | "askUser" | "enterPlanMode" | "exitPlanMode" | "mcp" | "other";

const BUILTIN_KINDS: Readonly<Record<string, ToolKind>> = {
    Bash: "bash", bash: "bash",
    Read: "read", read: "read",
    Write: "write", write: "write",
    Edit: "edit", MultiEdit: "edit", edit: "edit",
    Glob: "glob", glob: "glob",
    Grep: "grep", grep: "grep",
    LS: "list", list: "list",
    Agent: "agent", Task: "agent", task: "agent",
    WebFetch: "webfetch", webfetch: "webfetch",
    WebSearch: "websearch", websearch: "websearch",
    TodoWrite: "todo", todowrite: "todo",
    AskUserQuestion: "askUser",
    EnterPlanMode: "enterPlanMode",
    ExitPlanMode: "exitPlanMode",
};

/** OpenCode permission/guard names that contain `_` but are not MCP tools. */
const NON_MCP_NAMES: ReadonlySet<string> = new Set(["external_directory", "doom_loop"]);

const DEFAULT_KNOWN_SERVERS: readonly string[] = ["axiom", "axiom-tools", "axiom-sdk"];

/**
 * Returns the MCP server name for Claude (`mcp__server__tool`) or OpenCode (`server_tool`) names, else null.
 *
 * @param toolName the raw tool name reported by the engine
 * @param knownServers MCP server names to match as OpenCode `<server>_` prefixes (longest match wins)
 * @returns the server name, or null when the tool is not an MCP tool
 */
export function mcpServer(toolName: string,
    knownServers: readonly string[] = DEFAULT_KNOWN_SERVERS): string | null {
    if (toolName.startsWith("mcp__")) {
        const rest = toolName.substring(5);
        const sep = rest.indexOf("__");
        return sep > 0 ? rest.substring(0, sep) : rest || null;
    }
    if (toolName in BUILTIN_KINDS || NON_MCP_NAMES.has(toolName)) return null;
    const known = [...knownServers]
        .sort((a, b) => b.length - a.length)
        .find((s) => toolName.startsWith(`${s}_`));
    if (known) return known;
    const sep = toolName.indexOf("_");
    return sep > 0 ? toolName.substring(0, sep) : null;
}

/**
 * Returns the canonical kind for a Claude Code or OpenCode tool name.
 *
 * @param toolName the raw tool name reported by the engine
 * @returns the engine-neutral tool kind
 */
export function toolKind(toolName: string): ToolKind {
    const builtin = BUILTIN_KINDS[toolName];
    if (builtin) return builtin;
    if (mcpServer(toolName) !== null) return "mcp";
    return "other";
}

/**
 * Returns the input key holding the file path (`file_path` for Claude, `filePath` for OpenCode), if present.
 *
 * @param input the tool input
 * @returns the key name, or undefined when neither key is present
 */
export function filePathField(input?: Record<string, unknown>): "file_path" | "filePath" | undefined {
    if (!input) return undefined;
    if ("file_path" in input) return "file_path";
    if ("filePath" in input) return "filePath";
    return undefined;
}
