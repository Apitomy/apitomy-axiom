import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { McpCatalogModal } from "./McpCatalogModal";
import * as api from "../config/api";

vi.mock("../config/api", async (orig) => ({
    ...(await orig<typeof import("../config/api")>()),
    searchMcpCatalog: vi.fn(),
    fetchMcpRuntimeStatus: vi.fn().mockResolvedValue({ node: true, uv: false, docker: true }),
    fetchSecrets: vi.fn().mockResolvedValue([{ id: 1, name: "EXISTING" }]),
    createSecret: vi.fn().mockResolvedValue({ id: 2, name: "GITHUB_TOKEN" }),
    createMcpServer: vi.fn().mockResolvedValue({ id: 9, name: "github" }),
    testNewMcpServer: vi.fn(),
}));

const github: api.McpCatalogEntry = {
    id: "github", source: "curated", name: "github", title: "GitHub", description: "GitHub's MCP server",
    transport: "http", url: "https://api.githubcopilot.com/mcp/", runtime: "remote",
    headers: [{ name: "Authorization", description: "GitHub personal access token", required: true, secret: true,
        template: "Bearer {value}" }],
    notes: "Create a fine-grained token.",
};
const jira: api.McpCatalogEntry = {
    id: "atlassian", source: "curated", name: "atlassian", title: "Jira & Confluence", transport: "stdio",
    command: "uvx", args: ["mcp-atlassian"], runtime: "uv", env: [{ name: "JIRA_URL", required: true }],
};

describe("McpCatalogModal", () => {
    beforeEach(() => {
        vi.mocked(api.searchMcpCatalog).mockReset().mockResolvedValue([github, jira]);
    });

    it("lists curated servers and warns about missing runtimes", async () => {
        render(<McpCatalogModal isOpen onClose={() => {}} onCreated={() => {}} />);
        expect(await screen.findByText("GitHub")).toBeInTheDocument();
        expect(screen.getByText("Jira & Confluence")).toBeInTheDocument();
        expect(screen.getByText(/uvx is not available/)).toBeInTheDocument();
        expect(api.searchMcpCatalog).toHaveBeenCalledWith("curated", "");
    });

    it("searches the MCP Registry", async () => {
        render(<McpCatalogModal isOpen onClose={() => {}} onCreated={() => {}} />);
        await userEvent.click(await screen.findByRole("tab", { name: "MCP Registry" }));
        await userEvent.type(screen.getByLabelText("Search the catalog"), "postgres{Enter}");
        expect(api.searchMcpCatalog).toHaveBeenLastCalledWith("registry", "postgres");
    });

    it("adds GitHub with its token stored as a new secret", async () => {
        const onCreated = vi.fn();
        render(<McpCatalogModal isOpen onClose={() => {}} onCreated={onCreated} />);
        await userEvent.click(await screen.findByRole("button", { name: "Select GitHub" }));
        const dialog = screen.getByRole("dialog");
        expect(within(dialog).getByText("Create a fine-grained token.")).toBeInTheDocument();
        const add = within(dialog).getByRole("button", { name: "Add server" });
        expect(add).toBeDisabled();
        await userEvent.type(within(dialog).getByLabelText("Value for Authorization"), "ghp_secret");
        expect(within(dialog).getByLabelText("Secret name for Authorization")).toHaveValue("GITHUB_TOKEN");
        await userEvent.click(add);
        expect(api.createSecret).toHaveBeenCalledWith(expect.objectContaining({ name: "GITHUB_TOKEN", value: "ghp_secret" }));
        expect(api.createMcpServer).toHaveBeenCalledWith(expect.objectContaining({
            name: "github", transport: "http", serverUrl: "https://api.githubcopilot.com/mcp/",
            headers: { Authorization: "Bearer ${secret:GITHUB_TOKEN}" },
        }));
        expect(onCreated).toHaveBeenCalledWith({ id: 9, name: "github" });
    });

    it("tests the configured server without creating secrets", async () => {
        vi.mocked(api.testNewMcpServer).mockResolvedValueOnce({ success: true, serverName: "github", tools: [], durationMs: 5 });
        render(<McpCatalogModal isOpen onClose={() => {}} onCreated={() => {}} />);
        await userEvent.click(await screen.findByRole("button", { name: "Select GitHub" }));
        await userEvent.type(screen.getByLabelText("Value for Authorization"), "ghp_secret");
        await userEvent.click(screen.getByRole("button", { name: "Test" }));
        expect(api.testNewMcpServer).toHaveBeenCalledWith(expect.objectContaining({
            headers: { Authorization: "Bearer ghp_secret" },
        }));
        expect(api.createSecret).not.toHaveBeenCalled();
        expect(await screen.findByText(/Connected to github/)).toBeInTheDocument();
    });
});
