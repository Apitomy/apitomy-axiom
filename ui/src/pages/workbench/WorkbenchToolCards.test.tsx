import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type { ChatMessage, ToolUseRenderContext } from "../../components/assistant/AssistantMessageList";
import { renderWorkbenchToolGroup, type WorkbenchToolActions } from "./WorkbenchToolCards";

function ctx(id: string, toolName: string, toolInput: Record<string, unknown>, toolResult?: string,
             extra: Partial<ChatMessage> = {}): ToolUseRenderContext {
    const message: ChatMessage = { id, type: "tool_use", toolName, toolInput, toolResult, toolUseId: id, ...extra };
    return { message, genericBlock: <div key={id} data-testid={`generic-${id}`}>{toolName}</div> };
}

function actions(): WorkbenchToolActions {
    return { openItem: vi.fn(), openWorkflow: vi.fn(), openSuggestion: vi.fn() };
}

function show(group: ToolUseRenderContext[], a: WorkbenchToolActions = actions()) {
    return render(<div>{renderWorkbenchToolGroup(group, a)}</div>);
}

describe("renderWorkbenchToolGroup", () => {
    it("renders an updated item card with status label and opens the item", () => {
        const a = actions();
        const { container } = show([ctx("1", "mcp__workbench__upsert_item", { type: "tool", name: "my-tool" },
            "VALID: tool/my-tool (updated, revision r2)")], a);
        expect(container.textContent).toContain("Updated tool");
        expect(screen.getByText("my-tool").tagName).toBe("CODE");
        expect(screen.getByText("VALID")).toBeTruthy();
        fireEvent.click(screen.getByRole("button", { name: "Open" }));
        expect(a.openItem).toHaveBeenCalledWith("tool", "my-tool");
    });

    it("shows the generic block when a card is expanded", () => {
        show([ctx("1", "workbench_upsert_item", { type: "tool", name: "t" }, "INVALID: tool/t (created, x)")]);
        expect(screen.getByText("INVALID")).toBeTruthy();
        expect(screen.queryByTestId("generic-1")).toBeNull();
        fireEvent.click(screen.getByRole("button", { name: "Show details" }));
        expect(screen.getByTestId("generic-1")).toBeTruthy();
    });

    it("links workflow changes to the first touched node", () => {
        const a = actions();
        const { container } = show([ctx("1", "mcp__workbench__propose_workflow_changes",
            { changeSet: { ops: [{ op: "addNode", node: { id: "n1", name: "Log" } }] } }, "APPLIED")], a);
        expect(container.textContent).toContain("Proposed 1 workflow change added Log");
        fireEvent.click(screen.getByRole("button", { name: "Open workflow" }));
        expect(a.openWorkflow).toHaveBeenCalledWith("n1");
    });

    it("renders rejected workflow changes as a danger card", () => {
        const { container } = show([ctx("1", "mcp__workbench__propose_workflow_changes", {},
            "REJECTED (stale): base revision changed")]);
        expect(container.textContent).toContain("Workflow changes rejected: base revision changed");
        expect(container.querySelector(".axiom-workbench__tool-card--danger")).toBeTruthy();
    });

    it("opens the suggestion of a suggest_test card", () => {
        const a = actions();
        show([ctx("1", "mcp__workbench__suggest_test", { type: "action-type", name: "greet" }, "Suggested.")], a);
        fireEvent.click(screen.getByRole("button", { name: "Run…" }));
        expect(a.openSuggestion).toHaveBeenCalledWith("action-type", "greet");
    });

    it("renders a removal card without a link", () => {
        const { container } = show([ctx("1", "mcp__workbench__remove_item", { type: "workflow", name: "w" }, "Removed")]);
        expect(container.textContent).toContain("Removed workflow w");
        expect(screen.queryByRole("button", { name: /^Open/ })).toBeNull();
    });

    it("uses the card text for a single read-only call", () => {
        const { container } = show([ctx("1", "mcp__workbench__get_composition", {}, "{}")]);
        expect(container.textContent).toContain("Read the composition");
    });

    it("groups several read-only calls into one line listing them in the expansion", () => {
        show([ctx("1", "mcp__workbench__get_composition", {}, "{}"),
            ctx("2", "mcp__workbench__get_item", { type: "tool", name: "t" }, "{}"),
            ctx("3", "mcp__workbench__validate_composition", {}, "ok")]);
        expect(screen.getByText("Read 3 things")).toBeTruthy();
        expect(screen.queryByTestId("generic-1")).toBeNull();
        fireEvent.click(screen.getByRole("button", { name: "Show details" }));
        expect(screen.getByText("Read the composition")).toBeTruthy();
        expect(screen.getByText("Checked the composition")).toBeTruthy();
        ["1", "2", "3"].forEach((id) => expect(screen.getByTestId(`generic-${id}`)).toBeTruthy());
    });

    it("falls back to the generic block in place for unknown or running calls", () => {
        const { container } = show([ctx("1", "Bash", { command: "ls" }, "x"),
            ctx("2", "mcp__workbench__remove_item", { type: "tool", name: "a" }, "ok"),
            ctx("3", "mcp__workbench__upsert_item", { type: "tool", name: "b" }, undefined)]);
        expect(screen.getByTestId("generic-1")).toBeTruthy();
        expect(screen.getByTestId("generic-3")).toBeTruthy();
        const text: string = container.textContent ?? "";
        expect(text.indexOf("Bash")).toBeLessThan(text.indexOf("Removed tool"));
        expect(text.indexOf("Removed tool")).toBeLessThan(text.indexOf("mcp__workbench__upsert_item"));
    });

    it("never replaces a call waiting for permission", () => {
        show([ctx("1", "mcp__workbench__remove_item", { type: "tool", name: "a" }, "ok", { permissionId: "p" })]);
        expect(screen.getByTestId("generic-1")).toBeTruthy();
    });
});
