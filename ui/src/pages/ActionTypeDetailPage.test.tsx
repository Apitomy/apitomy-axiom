import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ActionTypeDetailPage } from "./ActionTypeDetailPage";

vi.mock("@patternfly/react-code-editor", () => ({
    CodeEditor: ({ code }: { code: string }) => <pre>{code}</pre>,
    Language: { json: "json", javascript: "javascript", shell: "shell", markdown: "markdown" },
}));
vi.mock("../config/api", async (orig) => ({
    ...(await orig<typeof import("../config/api")>()),
    fetchActionType: vi.fn().mockResolvedValue({
        id: 3, name: "at", executionMode: "agent", promptTemplate: "p", mcpServers: ["github"],
    }),
    updateActionType: vi.fn(),
    fetchEngines: vi.fn().mockResolvedValue([]),
    fetchModels: vi.fn().mockResolvedValue([]),
    validateActionType: vi.fn().mockResolvedValue({ valid: true, messages: [] }),
    fetchMcpServers: vi.fn().mockResolvedValue([]),
}));

describe("ActionTypeDetailPage", () => {
    it("keeps the action type's MCP server selection", async () => {
        render(<MemoryRouter initialEntries={["/action-types/3"]}><Routes>
            <Route path="/action-types/:actionTypeId" element={<ActionTypeDetailPage />} />
        </Routes></MemoryRouter>);
        expect(await screen.findByText("MCP Servers (1)")).toBeInTheDocument();
    });
});
