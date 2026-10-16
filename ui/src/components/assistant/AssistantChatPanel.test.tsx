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
