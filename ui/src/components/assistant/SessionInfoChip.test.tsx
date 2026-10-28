import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { SessionInfoChip, sessionInfoChipText } from "./SessionInfoChip";

describe("SessionInfoChip", () => {
    it("summarises the cost, else the tokens, else nothing", () => {
        expect(sessionInfoChipText(0.12345, 1000, 200)).toBe("$0.1235");
        expect(sessionInfoChipText(0, 1000, 234)).toBe(`${(1234).toLocaleString()} tokens`);
        expect(sessionInfoChipText(0, 0, 0)).toBe("");
    });

    it("shows only one chip and reveals the full details on click", () => {
        render(<SessionInfoChip engine="claude-code" engineLabel="Claude Code" requestedModel="opus"
            actualModel="claude-opus-5" costUsd={0.5} inputTokens={1000} outputTokens={20} />);
        expect(screen.getByText("$0.5000")).toBeTruthy();
        expect(screen.queryByText("claude-opus-5")).toBeNull();
        fireEvent.click(screen.getByRole("button", { name: /AI session details/ }));
        expect(screen.getByText("Claude Code (claude-code)")).toBeTruthy();
        expect(screen.getByText("opus")).toBeTruthy();
        expect(screen.getByText("claude-opus-5")).toBeTruthy();
        expect(screen.getByText(`${(1000).toLocaleString()} in / 20 out`)).toBeTruthy();
    });

    it("reveals the details on hover", () => {
        render(<SessionInfoChip costUsd={0} inputTokens={0} outputTokens={0} />);
        fireEvent.mouseEnter(screen.getByRole("button", { name: "AI session details" }));
        expect(screen.getByText("Engine default")).toBeTruthy();
        expect(screen.getByText("Not reported yet")).toBeTruthy();
    });
});
