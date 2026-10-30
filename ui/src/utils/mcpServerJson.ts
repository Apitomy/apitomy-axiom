import type { NewMcpServer } from "../config/api";

/** Result of parsing an MCP server configuration snippet. */
export interface ParsedMcpServers {
    /** Servers found, in the order they appear. */
    servers: NewMcpServer[];
    /** Problems that prevented a server (or the whole snippet) from being read. */
    errors: string[];
    /** "server: KEY" for plain env/header values whose name suggests a credential. */
    secretWarnings: string[];
}

const SECRET_NAME = /(token|secret|password|passwd|api[_-]?key|apikey|authorization|credential|private[_-]?key)/i;

type Json = Record<string, unknown>;

function isObject(value: unknown): value is Json {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function stringMap(value: unknown): Record<string, string> | undefined {
    if (!isObject(value)) return undefined;
    const entries = Object.entries(value).map(([k, v]) => [k, String(v)] as const);
    return entries.length > 0 ? Object.fromEntries(entries) : undefined;
}

function stringList(value: unknown): string[] | undefined {
    return Array.isArray(value) && value.length > 0 ? value.map(String) : undefined;
}

/** Converts one server entry (Claude Code / Cursor / Windsurf / Cline / OpenCode style) to a NewMcpServer. */
function toServer(name: string, raw: Json): NewMcpServer | string {
    const type = typeof raw.type === "string" ? raw.type.toLowerCase() : undefined;
    const url = typeof raw.url === "string" ? raw.url : typeof raw.serverUrl === "string" ? raw.serverUrl : undefined;
    const server: NewMcpServer = { name };
    if (url) {
        server.transport = type === "sse" ? "sse" : "http";
        server.serverUrl = url;
        const headers = stringMap(raw.headers);
        if (headers) server.headers = headers;
    } else if (Array.isArray(raw.command) && raw.command.length > 0) {
        // OpenCode: command is the full argv.
        const [command, ...args] = raw.command.map(String);
        server.transport = "stdio";
        server.serverCommand = command;
        if (args.length > 0) server.serverArgs = args;
    } else if (typeof raw.command === "string" && raw.command.trim()) {
        server.transport = "stdio";
        server.serverCommand = raw.command;
        const args = stringList(raw.args);
        if (args) server.serverArgs = args;
    } else {
        return `${name}: needs a "command" (stdio) or a "url" (HTTP/SSE)`;
    }
    if (server.transport === "stdio") {
        const env = stringMap(raw.env) ?? stringMap(raw.environment);
        if (env) server.serverEnv = env;
    }
    if (typeof raw.timeout === "number" && raw.timeout > 0) server.timeout = raw.timeout;
    if (raw.enabled === false || raw.disabled === true) server.enabled = false;
    if (typeof raw.description === "string") server.description = raw.description;
    return server;
}

/**
 * Parses an MCP server configuration snippet as used by common coding agents:
 * {@code {"mcpServers": {...}}} (Claude Code, Cursor, Windsurf, Cline), {@code {"mcp": {...}}} (OpenCode),
 * or a bare map of server name to server.
 */
export function parseMcpServersJson(text: string): ParsedMcpServers {
    const result: ParsedMcpServers = { servers: [], errors: [], secretWarnings: [] };
    let root: unknown;
    try {
        root = JSON.parse(text);
    } catch (e) {
        result.errors.push(`Invalid JSON: ${(e as Error).message}`);
        return result;
    }
    if (!isObject(root)) {
        result.errors.push("Expected a JSON object");
        return result;
    }
    const map = isObject(root.mcpServers) ? root.mcpServers : isObject(root.mcp) ? root.mcp : root;
    for (const [name, raw] of Object.entries(map)) {
        if (!isObject(raw)) {
            result.errors.push(`${name}: expected an object`);
            continue;
        }
        const server = toServer(name, raw);
        if (typeof server === "string") {
            result.errors.push(server);
            continue;
        }
        result.servers.push(server);
        for (const [key, value] of Object.entries({ ...server.serverEnv, ...server.headers })) {
            if (SECRET_NAME.test(key) && value && !value.includes("${secret:")) {
                result.secretWarnings.push(`${name}: ${key}`);
            }
        }
    }
    if (result.servers.length === 0 && result.errors.length === 0) {
        result.errors.push("No MCP servers found");
    }
    return result;
}
