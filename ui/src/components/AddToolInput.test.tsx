import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AddToolInput } from "./AddToolInput";

vi.mock("../config/api", async (orig) => ({
    ...(await orig<typeof import("../config/api")>()),
    fetchToolsets: vi.fn().mockResolvedValue([]),
    fetchTools: vi.fn().mockResolvedValue({ items: [], total: 0 }),
    fetchMcpServers: vi.fn().mockResolvedValue([
        { id: 1, name: "gh", transport: "stdio", enabled: true, tools: [{ name: "list_issues" }] },
    ]),
}));
vi.mock("../config/sdkTools", () => ({ fetchSdkToolValues: vi.fn().mockResolvedValue([]) }));

describe("AddToolInput", () => {
    it("suggests tools discovered on MCP servers", async () => {
        render(<AddToolInput onAdd={() => {}} />);
        await userEvent.type(screen.getByLabelText("Add item"), "mcp__gh");
        expect(await screen.findByText("mcp__gh__list_issues")).toBeInTheDocument();
        expect(screen.getByText("mcp__gh__*")).toBeInTheDocument();
    });
});
