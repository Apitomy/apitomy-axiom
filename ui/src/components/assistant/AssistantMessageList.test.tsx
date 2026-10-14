import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { AssistantMessageList, type ChatMessage, type ToolUseGroupRenderer } from "./AssistantMessageList";

Element.prototype.scrollIntoView = vi.fn();

const messages: ChatMessage[] = [
    { id: "u1", type: "user", content: "hello" },
    { id: "t1", type: "tool_use", toolName: "tool_one", toolUseId: "tu1", toolResult: "ok" },
    { id: "t2", type: "tool_use", toolName: "tool_two", toolUseId: "tu2", toolResult: "ok" },
    { id: "a1", type: "assistant", content: "done" },
    { id: "t3", type: "tool_use", toolName: "tool_three", toolUseId: "tu3", toolResult: "ok" },
];

function renderList(msgs: ChatMessage[], renderer?: ToolUseGroupRenderer) {
    return render(<AssistantMessageList messages={msgs} onPermissionRespond={vi.fn()}
        renderToolUseGroup={renderer} />);
}

describe("AssistantMessageList tool use groups", () => {
    it("renders the generic blocks without a renderer", () => {
        const { container } = renderList(messages);
        expect(container.querySelectorAll("[data-tool-use-id]").length).toBe(3);
        expect(screen.getByText("tool_one")).toBeTruthy();
    });

    it("replaces groups of consecutive tool calls with the renderer output", () => {
        const renderer = vi.fn<ToolUseGroupRenderer>((group) =>
            <div data-testid="card">{group.map((g) => g.message.id).join(",")}</div>);
        const { container } = renderList(messages, renderer);
        const cards = screen.getAllByTestId("card").map((c) => c.textContent);
        expect(cards).toEqual(["t1,t2", "t3"]);
        expect(container.querySelectorAll("[data-tool-use-id]").length).toBe(0);
        expect(screen.getByText("hello")).toBeTruthy();
    });

    it("passes the generic block so a renderer can embed it", () => {
        const { container } = renderList(messages, (group) => <div>{group.map((g) => g.genericBlock)}</div>);
        expect(container.querySelector("[data-tool-use-id='tu1']")).toBeTruthy();
        expect(container.querySelectorAll("[data-tool-use-id]").length).toBe(3);
    });

    it("falls back to the default when the renderer returns null", () => {
        const { container } = renderList(messages, () => null);
        expect(container.querySelectorAll("[data-tool-use-id]").length).toBe(3);
    });

    it("never groups a call waiting for permission", () => {
        const msgs: ChatMessage[] = [
            { id: "t1", type: "tool_use", toolName: "tool_one", toolUseId: "tu1", toolResult: "ok" },
            { id: "t2", type: "tool_use", toolName: "tool_two", toolUseId: "tu2", permissionId: "p1" },
            { id: "t3", type: "tool_use", toolName: "tool_three", toolUseId: "tu3", toolResult: "ok" },
        ];
        const renderer = vi.fn<ToolUseGroupRenderer>((group) =>
            <div data-testid="card">{group.map((g) => g.message.id).join(",")}</div>);
        const { container } = renderList(msgs, renderer);
        expect(screen.getAllByTestId("card").map((c) => c.textContent)).toEqual(["t1", "t3"]);
        expect(container.querySelector("[data-tool-use-id='tu2']")).toBeTruthy();
        expect(renderer.mock.calls.flat(2).some((g) => g.message.id === "t2")).toBe(false);
    });
});
