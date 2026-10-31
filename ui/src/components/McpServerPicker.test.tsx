import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { McpServerPicker, BUILT_IN_MCP_SERVERS } from "./McpServerPicker";

vi.mock("../config/api", async (orig) => ({
    ...(await orig<typeof import("../config/api")>()),
    fetchMcpServers: vi.fn().mockResolvedValue([{ id: 1, name: "github", transport: "stdio", enabled: true }]),
}));

describe("McpServerPicker", () => {
    it("lists built-in servers when given and does not flag them as missing", async () => {
        const onChange = vi.fn();
        render(<McpServerPicker builtIns={BUILT_IN_MCP_SERVERS} selected={["@axiom-tools", "gone"]} onChange={onChange} />);
        expect(await screen.findByRole("checkbox", { name: /@axiom-tools/ })).toBeChecked();
        expect(screen.getByRole("checkbox", { name: /@axiom-sdk/ })).not.toBeChecked();
        expect(screen.getAllByText("Not found")).toHaveLength(1);
        await userEvent.click(screen.getByRole("checkbox", { name: /github/ }));
        expect(onChange).toHaveBeenCalledWith(["@axiom-tools", "gone", "github"]);
    });
});
