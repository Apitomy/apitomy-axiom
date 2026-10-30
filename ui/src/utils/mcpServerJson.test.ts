import { describe, expect, it } from "vitest";
import { parseMcpServersJson } from "./mcpServerJson";

describe("parseMcpServersJson", () => {
    it("parses Claude Code / Cursor mcpServers with stdio and http servers", () => {
        const result = parseMcpServersJson(JSON.stringify({
            mcpServers: {
                github: { command: "npx", args: ["-y", "@modelcontextprotocol/server-github"],
                    env: { GITHUB_PERSONAL_ACCESS_TOKEN: "${GITHUB_TOKEN}" } },
                linear: { type: "http", url: "https://mcp.linear.app/mcp", headers: { Authorization: "Bearer x" } },
                legacy: { type: "sse", url: "https://example.com/sse" },
            },
        }));
        expect(result.errors).toEqual([]);
        expect(result.servers).toEqual([
            { name: "github", transport: "stdio", serverCommand: "npx",
                serverArgs: ["-y", "@modelcontextprotocol/server-github"],
                serverEnv: { GITHUB_PERSONAL_ACCESS_TOKEN: "${GITHUB_TOKEN}" } },
            { name: "linear", transport: "http", serverUrl: "https://mcp.linear.app/mcp",
                headers: { Authorization: "Bearer x" } },
            { name: "legacy", transport: "sse", serverUrl: "https://example.com/sse" },
        ]);
    });

    it("parses OpenCode mcp config", () => {
        const result = parseMcpServersJson(JSON.stringify({
            mcp: {
                local1: { type: "local", command: ["npx", "-y", "pkg"], environment: { A: "b" }, timeout: 5000 },
                remote1: { type: "remote", url: "https://x.io/mcp", enabled: false },
            },
        }));
        expect(result.servers).toEqual([
            { name: "local1", transport: "stdio", serverCommand: "npx", serverArgs: ["-y", "pkg"],
                serverEnv: { A: "b" }, timeout: 5000 },
            { name: "remote1", transport: "http", serverUrl: "https://x.io/mcp", enabled: false },
        ]);
    });

    it("accepts a bare name-to-server map", () => {
        const result = parseMcpServersJson('{"fs": {"command": "npx", "args": ["-y", "fs"]}}');
        expect(result.servers.map((s) => s.name)).toEqual(["fs"]);
    });

    it("reports invalid JSON and servers without a command or URL", () => {
        expect(parseMcpServersJson("{nope").errors[0]).toMatch(/JSON/);
        const result = parseMcpServersJson('{"mcpServers": {"bad": {"foo": 1}}}');
        expect(result.servers).toEqual([]);
        expect(result.errors[0]).toMatch(/bad/);
    });

    it("flags plain values that look like credentials", () => {
        const result = parseMcpServersJson(JSON.stringify({ mcpServers: {
            gh: { command: "npx", env: { GITHUB_TOKEN: "ghp_abc", MODE: "x", REF: "${secret:GH}" } },
        } }));
        expect(result.secretWarnings).toEqual(["gh: GITHUB_TOKEN"]);
    });
});
