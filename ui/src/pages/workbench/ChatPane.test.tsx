import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import * as c from "../../config/compositions";
import * as api from "../../config/api";
import { ChatPane } from "./ChatPane";

let chatProps: Record<string, any> = {};
vi.mock("../../components/assistant/AssistantChatPanel", () => ({
    AssistantChatPanel: (props: Record<string, any>) => { chatProps = props; return <div data-testid="chat" />; },
}));

const composition = { id: 1, name: "C", turnState: "idle", applyMode: "live", createdAt: "", updatedAt: "",
    assistantSessionId: "s1" } as c.Composition;
const props = { composition, sessionError: null, lastCompletedTurnId: "t1", prefill: undefined,
    beforeSend: vi.fn().mockResolvedValue({ nodeIds: ["a"], edgeIds: [] }), onRetrySession: vi.fn(),
    onReplaceWorkflow: vi.fn(), lastWorkflowSaveTurnId: "t1", onUndone: vi.fn() };

describe("ChatPane", () => {
    beforeEach(() => {
        vi.restoreAllMocks();
        vi.spyOn(api, "fetchAssistantSession").mockResolvedValue({ id: "s1", allowAll: false } as never);
    });

    it("sends through the composition with the selection after flushing", async () => {
        const send = vi.spyOn(c, "sendCompositionMessage").mockResolvedValue(composition);
        render(<ChatPane {...props} />);
        await chatProps.onSend("add a log step");
        expect(props.beforeSend).toHaveBeenCalled();
        expect(send).toHaveBeenCalledWith(1, { message: "add a log step", nodeIds: ["a"], edgeIds: [] });
    });

    it("undoes the last turn and replaces the workflow when allowed", async () => {
        const before = { id: "w", name: "wf", nodes: [], edges: [] };
        vi.spyOn(c, "undoTurn").mockResolvedValue({ restored: ["tool/t"], removed: [], skipped: [],
            workflowBefore: before, workflowRestorable: true } as never);
        render(<ChatPane {...props} />);
        fireEvent.click(screen.getByRole("button", { name: /undo ai turn/i }));
        await waitFor(() => expect(props.onReplaceWorkflow).toHaveBeenCalledWith(before));
        expect(await screen.findByText(/tool\/t/)).toBeTruthy();
    });

    it("mentions the restored workflow in the undo summary", async () => {
        const before = { id: "w", name: "wf", nodes: [], edges: [] };
        vi.spyOn(c, "undoTurn").mockResolvedValue({ restored: [], removed: [], skipped: [],
            workflowBefore: before, workflowRestorable: true } as never);
        render(<ChatPane {...props} />);
        fireEvent.click(screen.getByRole("button", { name: /undo ai turn/i }));
        expect(await screen.findByText(/Restored: workflow/)).toBeTruthy();
    });

    it("does not mention the workflow when it is not restored", async () => {
        vi.spyOn(c, "undoTurn").mockResolvedValue({ restored: ["tool/t"], removed: [], skipped: [],
            workflowRestorable: false } as never);
        render(<ChatPane {...props} />);
        fireEvent.click(screen.getByRole("button", { name: /undo ai turn/i }));
        const summary: HTMLElement = await screen.findByText(/Restored: tool\/t/);
        expect(summary.textContent).not.toMatch(/workflow/);
        expect(props.onReplaceWorkflow).not.toHaveBeenCalled();
    });

    it("disables the chat while the AI works and shows session errors", () => {
        const { rerender } = render(<ChatPane {...props} composition={{ ...composition, turnState: "ai-working" }} />);
        expect(chatProps.disabledReason).toBe("The AI is working…");
        rerender(<ChatPane {...props} sessionError="agent down" />);
        expect(screen.getByText(/agent down/)).toBeTruthy();
        expect(screen.getByRole("button", { name: /retry/i })).toBeTruthy();
    });

    it("flushes before undoing", async () => {
        const order: string[] = [];
        const beforeUndo = vi.fn(async () => { await Promise.resolve(); order.push("flush"); });
        vi.spyOn(c, "undoTurn").mockImplementation(async () => {
            order.push("undo");
            return { restored: [], removed: [], skipped: [], workflowRestorable: false };
        });
        render(<ChatPane {...props} beforeUndo={beforeUndo} />);
        fireEvent.click(screen.getByRole("button", { name: /undo ai turn/i }));
        await waitFor(() => expect(order).toEqual(["flush", "undo"]));
    });

    it("hides the undo bar after the turn is undone until a new turn completes", async () => {
        vi.spyOn(c, "undoTurn").mockResolvedValue({ restored: [], removed: [], skipped: [],
            workflowRestorable: false });
        const { rerender } = render(<ChatPane {...props} />);
        fireEvent.click(screen.getByRole("button", { name: /undo ai turn/i }));
        await waitFor(() => expect(screen.queryByRole("button", { name: /undo ai turn/i })).toBeNull());
        rerender(<ChatPane {...props} lastCompletedTurnId="t2" />);
        expect(screen.getByRole("button", { name: /undo ai turn/i })).toBeTruthy();
    });

    it("rejects /clear without sending a composition turn", async () => {
        const send = vi.spyOn(c, "sendCompositionMessage").mockResolvedValue(composition);
        render(<ChatPane {...props} />);
        await expect(chatProps.onSend("  /clear ")).rejects.toThrow("Clearing the conversation isn't supported in the workbench.");
        expect(send).not.toHaveBeenCalled();
    });

    it("renders workbench tool calls as cards when tool actions are given", () => {
        const toolActions = { openItem: vi.fn(), openWorkflow: vi.fn(), openSuggestion: vi.fn() };
        render(<ChatPane {...props} toolActions={toolActions} />);
        const out = chatProps.renderToolUseGroup([{ message: { id: "m", type: "tool_use",
            toolName: "mcp__workbench__remove_item", toolInput: { type: "tool", name: "x" }, toolResult: "ok" },
            genericBlock: null }]);
        render(<div data-testid="out">{out}</div>);
        expect(screen.getByTestId("out").textContent).toContain("Removed tool x");
    });

    it("keeps the generic tool blocks without tool actions", () => {
        render(<ChatPane {...props} />);
        expect(chatProps.renderToolUseGroup).toBeUndefined();
    });

    it("shows the session's Allow All state in the toolbar", async () => {
        vi.mocked(api.fetchAssistantSession).mockResolvedValue({ id: "s1", allowAll: true } as never);
        render(<ChatPane {...props} />);
        await waitFor(() => expect((screen.getByLabelText("Allow All") as HTMLInputElement).checked).toBe(true));
        expect(api.fetchAssistantSession).toHaveBeenCalledWith("s1");
    });

    it("follows Allow All changes reported by the chat", async () => {
        render(<ChatPane {...props} />);
        const toggle = screen.getByLabelText("Allow All") as HTMLInputElement;
        await waitFor(() => expect(api.fetchAssistantSession).toHaveBeenCalled());
        expect(toggle.checked).toBe(false);
        act(() => chatProps.onAllowAllChanged(true));
        expect(toggle.checked).toBe(true);
    });

    it("rereads Allow All when the composition gets a new session", async () => {
        const { rerender } = render(<ChatPane {...props} />);
        await waitFor(() => expect(api.fetchAssistantSession).toHaveBeenCalledWith("s1"));
        rerender(<ChatPane {...props} composition={{ ...composition, assistantSessionId: "s2" }} />);
        await waitFor(() => expect(api.fetchAssistantSession).toHaveBeenCalledWith("s2"));
    });

    it("has no Allow All switch until the session exists", () => {
        render(<ChatPane {...props} composition={{ ...composition, assistantSessionId: undefined }} />);
        expect(screen.queryByLabelText("Allow All")).toBeNull();
    });

    it("shows the model and session usage, updating after each turn", () => {
        render(<ChatPane {...props} />);
        expect(screen.queryByText(/^\$/)).toBeNull();
        act(() => { chatProps.onModelDetected("claude-test"); chatProps.onCostUpdate(0.01, 100, 20); });
        expect(screen.getByText("claude-test")).toBeTruthy();
        expect(screen.getByText("$0.0100")).toBeTruthy();
        act(() => { chatProps.onCostUpdate(0.025, 50, 10); });
        expect(screen.getByText("$0.0250")).toBeTruthy();
    });

    it("shows token totals when the engine reports no cost", () => {
        render(<ChatPane {...props} />);
        act(() => { chatProps.onCostUpdate(0, 100, 20); });
        expect(screen.getByText("120 tokens")).toBeTruthy();
        act(() => { chatProps.onCostUpdate(0, 50, 10); });
        expect(screen.getByText("180 tokens")).toBeTruthy();
    });

    it("resets usage when the session changes", () => {
        const { rerender } = render(<ChatPane {...props} />);
        act(() => { chatProps.onCostUpdate(0.01, 100, 20); });
        rerender(<ChatPane {...props} composition={{ ...composition, assistantSessionId: "s2" }} />);
        expect(screen.queryByText("$0.0100")).toBeNull();
    });
});
