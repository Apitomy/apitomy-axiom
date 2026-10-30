import { describe, expect, it } from "vitest";
import type { McpCatalogEntry } from "../config/api";
import { buildCatalogServer, catalogInputs, defaultSecretName, newSecretsToCreate } from "./mcpCatalog";

const github: McpCatalogEntry = {
    id: "github", source: "curated", name: "github", title: "GitHub", transport: "http",
    url: "https://api.githubcopilot.com/mcp/", runtime: "remote",
    headers: [{ name: "Authorization", required: true, secret: true, template: "Bearer {value}" }],
};

const atlassian: McpCatalogEntry = {
    id: "atlassian", source: "curated", name: "atlassian", title: "Jira", transport: "stdio",
    command: "uvx", args: ["mcp-atlassian"], runtime: "uv",
    env: [
        { name: "JIRA_URL", required: true },
        { name: "JIRA_API_TOKEN", required: true, secret: true },
        { name: "CONFLUENCE_URL" },
    ],
};

const fs: McpCatalogEntry = {
    id: "filesystem", source: "curated", name: "filesystem", title: "FS", transport: "stdio", command: "npx",
    args: ["-y", "@modelcontextprotocol/server-filesystem", "{arg:directory}"], runtime: "node",
    arguments: [{ name: "directory", required: true }],
};

describe("mcpCatalog", () => {
    it("lists inputs with secret inputs defaulting to new secrets", () => {
        const inputs = catalogInputs(atlassian);
        expect(inputs.map((i) => i.key)).toEqual(["env:JIRA_URL", "env:JIRA_API_TOKEN", "env:CONFLUENCE_URL"]);
        expect(inputs[1].initial).toEqual({ mode: "newSecret", value: "", secretName: "JIRA_API_TOKEN" });
        expect(inputs[0].initial.mode).toBe("plain");
    });

    it("suggests secret names", () => {
        expect(defaultSecretName(github, "header", "Authorization")).toBe("GITHUB_TOKEN");
        expect(defaultSecretName(atlassian, "env", "JIRA_API_TOKEN")).toBe("JIRA_API_TOKEN");
    });

    it("builds a remote server with a templated secret header", () => {
        const server = buildCatalogServer(github, "github", {
            "header:Authorization": { mode: "newSecret", value: "ghp_x", secretName: "GITHUB_TOKEN" },
        });
        expect(server).toEqual({
            name: "github", description: undefined, transport: "http", serverUrl: "https://api.githubcopilot.com/mcp/",
            headers: { Authorization: "Bearer ${secret:GITHUB_TOKEN}" },
        });
    });

    it("uses plain values for unsaved secrets when testing", () => {
        const server = buildCatalogServer(github, "github", {
            "header:Authorization": { mode: "newSecret", value: "ghp_x", secretName: "GITHUB_TOKEN" },
        }, { forTest: true });
        expect(server.headers).toEqual({ Authorization: "Bearer ghp_x" });
    });

    it("builds a stdio server, omitting empty optional values and filling arguments", () => {
        expect(buildCatalogServer(atlassian, "jira", {
            "env:JIRA_URL": { mode: "plain", value: "https://x.atlassian.net" },
            "env:JIRA_API_TOKEN": { mode: "secret", value: "", secretName: "JIRA" },
            "env:CONFLUENCE_URL": { mode: "plain", value: "" },
        })).toEqual({
            name: "jira", description: undefined, transport: "stdio", serverCommand: "uvx", serverArgs: ["mcp-atlassian"],
            serverEnv: { JIRA_URL: "https://x.atlassian.net", JIRA_API_TOKEN: "${secret:JIRA}" },
        });
        expect(buildCatalogServer(fs, "fs", { "arg:directory": { mode: "plain", value: "/srv/data" } }).serverArgs)
            .toEqual(["-y", "@modelcontextprotocol/server-filesystem", "/srv/data"]);
    });

    it("lists the new secrets to create", () => {
        expect(newSecretsToCreate({
            a: { mode: "newSecret", value: "v", secretName: "S1" },
            b: { mode: "newSecret", value: "", secretName: "S2" },
            c: { mode: "plain", value: "p" },
        })).toEqual([{ name: "S1", value: "v" }]);
    });
});
