import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import * as c from "../../config/compositions";
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

    it("disables the chat while the AI works and shows session errors", () => {
        const { rerender } = render(<ChatPane {...props} composition={{ ...composition, turnState: "ai-working" }} />);
        expect(chatProps.disabledReason).toBe("The AI is working…");
        rerender(<ChatPane {...props} sessionError="agent down" />);
        expect(screen.getByText(/agent down/)).toBeTruthy();
        expect(screen.getByRole("button", { name: /retry/i })).toBeTruthy();
    });
});
