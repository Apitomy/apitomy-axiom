import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { AssistantChatPanel } from "./AssistantChatPanel";
import * as api from "../../config/api";

vi.mock("../../config/sse", () => ({
    sseClient: { connect: vi.fn(), subscribe: vi.fn(() => () => {}), subscribeSession: vi.fn(() => () => {}),
        onReconnect: vi.fn(() => () => {}) },
}));

Element.prototype.scrollIntoView = vi.fn();

describe("AssistantChatPanel overrides", () => {
    it("uses onSend instead of the generic endpoint", async () => {
        vi.spyOn(api, "fetchAssistantSessionHistory").mockResolvedValue([]);
        const generic = vi.spyOn(api, "sendAssistantMessage").mockResolvedValue();
        const onSend = vi.fn().mockResolvedValue(undefined);
        render(<AssistantChatPanel sessionId="s1" onSend={onSend} />);
        const input = await screen.findByRole("textbox");
        fireEvent.change(input, { target: { value: "hello" } });
        fireEvent.keyDown(input, { key: "Enter" });
        await waitFor(() => expect(onSend).toHaveBeenCalledWith("hello"));
        expect(generic).not.toHaveBeenCalled();
    });

    it("disables input with a reason", async () => {
        vi.spyOn(api, "fetchAssistantSessionHistory").mockResolvedValue([]);
        render(<AssistantChatPanel sessionId="s1" disabledReason="The AI is working…" />);
        const input = await screen.findByPlaceholderText("The AI is working…");
        expect((input as HTMLTextAreaElement).disabled).toBe(true);
    });

    it("puts a prefill into the input when its nonce changes", async () => {
        vi.spyOn(api, "fetchAssistantSessionHistory").mockResolvedValue([]);
        const { rerender } = render(<AssistantChatPanel sessionId="s1" prefill={{ text: "fix node a", nonce: 1 }} />);
        const input = await screen.findByRole("textbox") as HTMLTextAreaElement;
        await waitFor(() => expect(input.value).toBe("fix node a"));
        expect(document.activeElement).toBe(input);
        fireEvent.change(input, { target: { value: "edited" } });
        rerender(<AssistantChatPanel sessionId="s1" prefill={{ text: "fix node a", nonce: 1 }} />);
        expect(input.value).toBe("edited");
        rerender(<AssistantChatPanel sessionId="s1" prefill={{ text: "again", nonce: 2 }} />);
        await waitFor(() => expect(input.value).toBe("again"));
    });

    it("renders (Axiom) user_message notices as system messages", async () => {
        vi.spyOn(api, "fetchAssistantSessionHistory").mockResolvedValue([
            { eventIndex: 1, eventType: "user_message", eventData: { content: "(Axiom) Retrying with fresh state" } },
            { eventIndex: 2, eventType: "user_message", eventData: { content: "hi there" } },
        ] as never);
        const { container } = render(<AssistantChatPanel sessionId="s1" />);
        const notice: HTMLElement = await screen.findByText("(Axiom) Retrying with fresh state");
        expect(notice.closest(".axiom-message-list__system")).toBeTruthy();
        const user: HTMLElement = await screen.findByText("hi there");
        expect(user.closest(".axiom-message-list__system")).toBeNull();
        expect(container).toBeTruthy();
    });
});

describe("AssistantChatPanel compact side panel mode", () => {
    const todosEvent = { eventIndex: 1, eventType: "todos", eventData: { todos: [
        { content: "a", status: "completed" }, { content: "b", status: "in_progress" },
    ] } };
    const subagentEvent = { eventIndex: 2, eventType: "subagent_started", eventData: {
        toolUseId: "tu1", taskId: "t1", description: "Explore code", subagentType: "explore" } };
    const bgEvent = { eventIndex: 3, eventType: "background_task_started", eventData: {
        toolUseId: "bg1", taskId: "b1", description: "npm build" } };

    it("shows a summary strip instead of the side column", async () => {
        vi.spyOn(api, "fetchAssistantSessionHistory").mockResolvedValue([todosEvent, subagentEvent, bgEvent] as never);
        const { container } = render(<AssistantChatPanel sessionId="s1" sidePanelMode="compact" />);
        const strip = await screen.findByTestId("assistant-activity-strip");
        expect(strip.textContent).toContain("Todos 1/2 · 1 subagent running · 1 background task running");
        expect(container.querySelector(".axiom-subagent-panel")).toBeNull();
    });

    it("opens the popover when the strip is clicked", async () => {
        vi.spyOn(api, "fetchAssistantSessionHistory").mockResolvedValue([todosEvent] as never);
        render(<AssistantChatPanel sessionId="s1" sidePanelMode="compact" />);
        const strip = await screen.findByTestId("assistant-activity-strip");
        expect(screen.queryByRole("dialog")).toBeNull();
        fireEvent.click(strip);
        const dialog = await screen.findByRole("dialog");
        expect(dialog.querySelector(".axiom-subagent-panel--popover")).toBeTruthy();
        expect(dialog.textContent).toContain("Todos (1/2)");
        fireEvent.click(strip);
        await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    });

    it("opens the popover automatically on a pending subagent approval", async () => {
        vi.spyOn(api, "fetchAssistantSessionHistory").mockResolvedValue([subagentEvent,
            { eventIndex: 4, eventType: "permission_request", eventData: {
                requestId: "p1", toolName: "Bash", toolInput: { command: "ls" }, subagentToolUseId: "tu1" } },
        ] as never);
        render(<AssistantChatPanel sessionId="s1" sidePanelMode="compact" />);
        const dialog = await screen.findByRole("dialog");
        expect(dialog.querySelector('.axiom-subagent-card')).toBeTruthy();
        const strip = screen.getByTestId("assistant-activity-strip");
        expect(strip.className).toContain("axiom-activity-strip--attention");
        expect(strip.textContent).toContain("1 approval needed");
    });

    it("hides the strip when there is nothing to show", async () => {
        vi.spyOn(api, "fetchAssistantSessionHistory").mockResolvedValue([] as never);
        render(<AssistantChatPanel sessionId="s1" sidePanelMode="compact" />);
        await screen.findByRole("textbox");
        expect(screen.queryByTestId("assistant-activity-strip")).toBeNull();
    });

    it("keeps the side column in the default mode", async () => {
        vi.spyOn(api, "fetchAssistantSessionHistory").mockResolvedValue([todosEvent] as never);
        const { container } = render(<AssistantChatPanel sessionId="s1" />);
        await waitFor(() => expect(container.querySelector(".axiom-subagent-panel")).toBeTruthy());
        expect(screen.queryByTestId("assistant-activity-strip")).toBeNull();
    });
});
